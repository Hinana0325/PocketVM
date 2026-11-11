package dev.pocketvm.runtime

import android.os.PowerManager
import android.os.Process
import dev.pocketvm.diagnostics.AgentFeed
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 调度器当前状态，供诊断看板展示；null 表示未启用（VM 未运行）。 */
data class PerfState(
    val thermalStatus: Int,
    val mode: AffinityMode,
    val vcpuTids: List<Int>,
    /** 实际生效的 taskset 掩码（十六进制，不带 0x）；OFF 时为 null。 */
    val appliedMaskHex: String?,
    /** 本次成功绑核的 vCPU 线程号。 */
    val appliedTids: List<Int>,
    val topology: CpuTopology.Topology?,
)

/** 温控来源：生产用 PowerManager，单测用假实现。 */
interface ThermalSource {
    fun currentStatus(): Int
    /** 注册监听，返回取消注册的回调；注册失败返回 null。 */
    fun register(onStatus: (Int) -> Unit): (() -> Unit)?
}

class PowerManagerThermalSource(
    private val powerManager: PowerManager,
    private val executor: Executor,
) : ThermalSource {

    override fun currentStatus(): Int =
        runCatching { powerManager.currentThermalStatus }
            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)

    override fun register(onStatus: (Int) -> Unit): (() -> Unit)? {
        val listener = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
        val registered = runCatching {
            powerManager.addThermalStatusListener(executor, listener)
        }.isSuccess
        if (!registered) return null
        return { runCatching { powerManager.removeThermalStatusListener(listener) } }
    }
}

/**
 * 运行期性能调度器：启动后把 QEMU 的 vCPU 线程绑到性能核，并随温控自动降档。
 *
 * 与启动时 taskset 的关系：启动时 [CpuTopology] + taskset 把**整个 QEMU
 * 进程**（主线程、IO 线程、vCPU 线程）一次性限制在性能核上；这里是启动后
 * 的细粒度补充——只动 QMP `query-cpus-fast` 给出的 vCPU 线程，主线程留在
 * 启动掩码上不动。它同时补两个启动绑核覆盖不到的缺口：
 *  1. 启动时 taskset 不可用（ROM 没有 /system/bin/taskset，或被 SELinux
 *     拒绝）导致 affinity=off：这里逐线程重绑一次；
 *  2. 温控降档：发热时把 vCPU 切到能效核，降温后恢复，启动掩码做不到。
 *
 * 权限边界（未 Root 可行，已核实）：
 *  - taskset -p：sched_setaffinity 只要求调用方与目标同 UID（man 2
 *    sched_setaffinity 的 EPERM 条款），QEMU 是 App fork 的子进程，同
 *    UID；cpuset 会把掩码截到进程组允许的核上，交集为空时返回 EINVAL，
 *    这里按失败记事件，不影响 VM 运行。
 *  - 提优先级：Process.setThreadPriority(tid, -4)；Android 给 App 设
 *    RLIMIT_NICE=40，无 Root 也能把自己 UID 的线程 nice 降到 -20。
 *    实时调度（SCHED_FIFO/RR）要 CAP_SYS_NICE，不可用，不尝试。
 *  - 温控监听：PowerManager.addThermalStatusListener，API 29+，无需权限。
 *
 * 只在 [arm] 传入的 [CoroutineScope]（VmSession 的 IO 域）里执行阻塞调用；
 * [applyAffinity]/[applyPriority] 的默认实现是同步的，调用方必须保证不在
 * 主线程。失败一律只记事件，绝不把已跑起来的 VM 判死。
 */
class VmPerformanceScheduler(
    private val scope: CoroutineScope,
    private val thermalSource: ThermalSource,
    private val readTopology: () -> CpuTopology.Topology = { CpuTopology.read() },
    private val applyAffinity: (tid: Int, maskHex: String) -> Boolean = ::applyTasksetAffinity,
    private val applyPriority: (tid: Int, priority: Int) -> Boolean = ::applyThreadPriority,
) {

    private val _state = MutableStateFlow<PerfState?>(null)
    val state: StateFlow<PerfState?> = _state.asStateFlow()

    private var armJob: Job? = null
    private var unregister: (() -> Unit)? = null
    private var currentMode: AffinityMode? = null
    private var warnedStatus: Int = PowerManager.THERMAL_STATUS_NONE
    /** disarm 后已在途的温控回调不再写状态，避免停机后快照又出现调度状态。 */
    private var armed: Boolean = false

    /**
     * 在 QMP 握手 + 稳定窗口之后调用：vCPU 线程在机器初始化完成前不存在，
     * 太早绑会扑空。vCPU 线程号一次运行内不变，取一次即可。
     */
    fun arm(vcpuTids: List<Int>) {
        disarm()
        armed = true
        armJob = scope.launch {
            val topology = runCatching { readTopology() }.getOrNull()
            val status = thermalSource.currentStatus()
            applyPolicy(status, vcpuTids, topology)
            unregister = thermalSource.register { newStatus ->
                scope.launch { applyPolicy(newStatus, vcpuTids, topology) }
            }
            if (unregister == null) {
                AgentFeed.event(
                    "perf.thermal_unavailable",
                    "reason" to "温控监听注册失败，只按启动时状态绑核，不再跟随温度变化",
                )
            }
        }
    }

    /**
     * 停止监听并清状态。**不恢复**亲和性：调用时进程正在停止或已死，
     * 恢复没有意义；下次启动会重新按当前温控绑核。
     */
    fun disarm() {
        armed = false
        armJob?.cancel()
        armJob = null
        runCatching { unregister?.invoke() }
        unregister = null
        currentMode = null
        warnedStatus = PowerManager.THERMAL_STATUS_NONE
        _state.value = null
    }

    private fun effectiveMode(want: AffinityMode, topology: CpuTopology.Topology?): AffinityMode {
        if (topology?.maskHex == null) return AffinityMode.OFF
        return when (want) {
            // 没有能区分的能效核时降无可降，维持性能核，只发警告。
            AffinityMode.EFFICIENCY ->
                if (topology.efficiencyMaskHex != null) AffinityMode.EFFICIENCY else AffinityMode.PERFORMANCE
            else -> AffinityMode.PERFORMANCE
        }
    }

    private fun applyPolicy(status: Int, vcpuTids: List<Int>, topology: CpuTopology.Topology?) {
        if (!armed) return
        val want = ThermalPolicy.modeFor(status)
        val effective = effectiveMode(want, topology)
        val mask = when (effective) {
            AffinityMode.PERFORMANCE -> topology?.maskHex
            AffinityMode.EFFICIENCY -> topology?.efficiencyMaskHex
            AffinityMode.OFF -> null
        }
        // 档位没变时不重复执行 taskset：纯温控数字变化（正常→轻微）不值得
        // 再绑一次核；档位变化（性能↔能效）才真正动手。
        val applied = if (mask != null && effective != currentMode) {
            val priority = if (effective == AffinityMode.PERFORMANCE) {
                Process.THREAD_PRIORITY_DISPLAY // -4：比 UI 主线程略高，不抢渲染线程
            } else {
                // 发热时把优先级还给系统：系统已在降频，再抢时间片只会更热。
                Process.THREAD_PRIORITY_DEFAULT
            }
            vcpuTids.mapNotNull { tid ->
                val affinityOk = applyAffinity(tid, mask)
                val priorityOk = applyPriority(tid, priority)
                if (!affinityOk || !priorityOk) {
                    AgentFeed.event(
                        "perf.apply_failed",
                        "tid" to tid,
                        "mode" to effective.name,
                        "affinityOk" to affinityOk,
                        "priorityOk" to priorityOk,
                    )
                }
                if (affinityOk) tid else null
            }
        } else {
            _state.value?.appliedTids ?: emptyList()
        }
        val previousMode = currentMode
        currentMode = effective
        _state.value = PerfState(
            thermalStatus = status,
            mode = effective,
            vcpuTids = vcpuTids,
            appliedMaskHex = if (effective == AffinityMode.OFF) null else mask,
            appliedTids = applied,
            topology = topology,
        )
        if (previousMode == null) {
            AgentFeed.event(
                "perf.armed",
                "mode" to effective.name,
                "thermal" to ThermalPolicy.label(status),
                "mask" to (mask ?: "off"),
                "vcpuTids" to vcpuTids.joinToString(","),
                "appliedTids" to applied.joinToString(","),
            )
        } else if (previousMode != effective) {
            AgentFeed.event(
                "perf.mode_changed",
                "from" to previousMode.name,
                "to" to effective.name,
                "thermal" to ThermalPolicy.label(status),
                "mask" to (mask ?: "off"),
            )
        }
        // SEVERE 及以上每升一级警告一次；降温回落后再升温会重新警告。
        if (ThermalPolicy.shouldWarn(status) && status > warnedStatus) {
            warnedStatus = status
            AgentFeed.event(
                "perf.thermal_warning",
                "thermal" to ThermalPolicy.label(status),
                "mode" to effective.name,
                "advice" to if (effective == AffinityMode.EFFICIENCY) {
                    "已把 vCPU 切到能效核降温；过热持续请在控制台停止虚拟机"
                } else {
                    "无能效核可降，请在控制台停止虚拟机降温"
                },
            )
        } else if (!ThermalPolicy.shouldWarn(status)) {
            warnedStatus = PowerManager.THERMAL_STATUS_NONE
        }
    }

    companion object {
        /**
         * toybox taskset -p：给已存在的线程设亲和性。返回 false 的情况：
         * taskset 不存在、SELinux 拒绝 exec、掩码与 cpuset 无交集（EINVAL）。
         * 2 秒超时后直接杀掉 taskset，避免卡住调度协程。
         */
        fun applyTasksetAffinity(tid: Int, maskHex: String): Boolean {
            val taskset = CpuTopology.tasksetPath() ?: return false
            return runCatching {
                val proc = ProcessBuilder(taskset, "-p", maskHex, tid.toString())
                    // Redirect.DISCARD 在 Android SDK 的 ProcessBuilder 里不存在；
                    // 按仓库惯例（DiskProvisioner）把 stderr 并进 stdout 再并发排空，
                    // taskset 最多输出一行，2 秒超时内一定能读完。
                    .redirectErrorStream(true)
                    .start()
                val drain = java.util.concurrent.CompletableFuture.supplyAsync {
                    runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
                        .getOrDefault("")
                }
                val finished = proc.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    runCatching { proc.destroyForcibly() }
                    return false
                }
                runCatching { drain.get(1, TimeUnit.SECONDS) }
                proc.exitValue() == 0
            }.getOrDefault(false)
        }

        /** Process.setThreadPriority(tid, …)：同 UID 线程可提权到 -20。 */
        fun applyThreadPriority(tid: Int, priority: Int): Boolean = runCatching {
            Process.setThreadPriority(tid, priority)
            true
        }.getOrDefault(false)
    }
}
