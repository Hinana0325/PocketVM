package dev.pocketvm.diagnostics

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.Locale
import kotlin.math.round
import kotlinx.coroutines.delay

/**
 * 运行期看门狗：把「主循环停摆」从一个无法复述的现象变成可判读的结构化证据。
 *
 * 真机上出现过这样的状态：QEMU 进程活着、5900 仍在 LISTEN、TCP 握手能完成，
 * 但主循环不再 accept，VNC/QMP 一个字节都不回，TCG 的 vCPU 线程继续满核转。
 * App 这时显示「运行中」，外部工具也看不出异常。现有 [ProcessHealth] 只看
 * 整个进程的 CPU tick，分不清「vCPU 在跑」和「主循环还活着」。
 *
 * 每 [RuntimeWatchdogSampler.DEFAULT_INTERVAL_MS] 采一拍，逐拍写
 * `run-<runId>.watchdog.jsonl`（字段契约见 [WatchdogJson]，合并门禁以 T4
 * §6.2 为准），并同步更新 [DiagContract] 的 UI 快照。观测面是四条独立信号：
 *
 *  1. QMP `query-status` 是否在短超时内返回、RTT 与显式错误 —— 控制面证据；
 *  2. VNC 端口 TCP 是否连上、是否收到 RFB banner —— 显示面证据；
 *  3. 串口日志字节数增量 —— 客户机固件是否仍在输出（没有串口时为 null）；
 *  4. `/proc/<pid>/task/<tid>/stat` 的 utime+stime 增量（dticks）与
 *     `/proc/<tid>/schedstat` 的每线程 CPU 占比 —— 主线程/vCPU 分布。
 *
 * 判据里 `main idle + vcpu busy` 单独不是异常：健康的 QEMU 主线程本来就常驻
 * `poll()` 睡眠，guest 计算时 vCPU 满核是常态。它只在 QMP/VNC 已经失联时
 * 作为归因佐证。本类只观测，不重启、不改状态机、不碰 QEMU argv。
 */

/** 一次 QMP query-status 探测结果。RTT 为 null 表示请求没有在超时内返回。 */
data class QmpProbe(
    val responded: Boolean,
    val rttMs: Long?,
    val status: String?,
    /** 显式失败原因；采样失败不得静默成 0。 */
    val error: String? = null,
)

/**
 * 一次 VNC 端口探测。`banner` 是去掉换行后的 `RFB 003.008` 之类字符串；
 * 连上但没等到 banner 时 `connected=true`、`banner=null`——这正是主循环
 * 停摆时真正发生的形态（监听还在，accept 后不再说话）。
 */
data class VncProbe(
    val connected: Boolean,
    val banner: String?,
    val rttMs: Long?,
    val error: String?,
) {
    val bannerSeen: Boolean get() = banner != null
}

/** 串口日志的绝对字节数与相邻两拍的增量；增量 null 表示首拍或文件被重建。 */
data class SerialProbe(
    val bytes: Long,
    val deltaBytes: Long?,
)

enum class ThreadRole { MAIN, VCPU }

/**
 * 单个线程的 CPU 数据。
 *
 * `statTicksDelta` 是 `/proc/<pid>/task/<tid>/stat` utime+stime 的窗口增量，
 * 即 QA 契约里的 dticks；`cpuPercent` 优先由 `/proc/<tid>/schedstat` 求差——
 * Android 16 上 task stat 可能返回线程组聚合值，schedstat 才是每线程独立的。
 */
data class ThreadCpu(
    val tid: Int,
    val role: ThreadRole,
    val cpuPercent: Float?,
    val statTicks: Long?,
    val statTicksDelta: Long?,
    val schedRuntimeNs: Long?,
    /** stat 第 3 字段的状态字符（R/S/D/…）；读不到为 null。停摆归因判别量。 */
    val state: String? = null,
    /**
     * `/proc/<pid>/task/<tid>/syscall` 原始内容（syscall 号 + 参数 + sp + pc，
     * 或 "running"）；SELinux 拒绝或不可读为 null，不是空串。
     */
    val syscall: String? = null,
)

/**
 * 主线程与 vCPU 的 CPU 分布。百分比未知时是 null（不是 0）；一个都读不到时
 * [vcpuSumPercent] 为 null。
 */
data class ThreadDistribution(
    val mainCpuPercent: Float?,
    val vcpuCpuPercents: List<Float?>,
    val vcpuSumPercent: Float?,
    val threads: List<ThreadCpu>,
    /** 主线程状态字符（D/S/R…），便于直接判读阻塞类型；未知为 null。 */
    val mainState: String? = null,
    /** 主线程当前 syscall 原始行；未知为 null。 */
    val mainSyscall: String? = null,
)

/** 看门狗的一拍。 */
data class WatchdogSample(
    val epochMs: Long,
    /** 与上一拍的实际间隔；首拍为 null。探测本身耗时也计入这里。 */
    val dtMs: Long?,
    val intervalMs: Long,
    val qmp: QmpProbe,
    val vnc: VncProbe?,
    val serial: SerialProbe?,
    val threads: ThreadDistribution?,
    /** 主线程的 /proc 视图是否可读（stat 与 schedstat 至少一个可读）。 */
    val procReadable: Boolean,
)

/**
 * 5 秒一拍的采样器，可注入探测与文件读取，便于用假 /proc 夹具做单测。
 *
 * 线程数选择：主线程（tid == pid）+ QMP `query-cpus-fast` 给出的 vCPU 线程。
 * 不枚举 QEMU 的全部辅助线程——判读只需要「主线程 vs vCPU」这一组对比，
 * 固定 1 + 8 次小文件读取，代价可忽略。
 */
class RuntimeWatchdogSampler(
    private val pid: Int,
    vcpuTids: List<Int>,
    private val serialLog: File?,
    private val qmpStatus: () -> QmpProbe,
    private val vncProbe: (() -> VncProbe)?,
    private val readFile: (String) -> String? = { path ->
        runCatching { File(path).readText() }.getOrNull()
    },
    private val clockTicksPerSecond: Long = 100,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    /** 去重并排除主线程，避免 QMP 意外把 pid 也报进来时重复计数。 */
    private val cpuTids: List<Int> = vcpuTids.filter { it > 0 && it != pid }.distinct()

    private data class ThreadSnapshot(
        val statTicks: Long?,
        val schedRuntimeNs: Long?,
        val state: String?,
        val syscall: String?,
    )

    private var prevEpochMs: Long? = null
    private var prevThreads: Map<Int, ThreadSnapshot> = emptyMap()
    private var prevSerialBytes: Long? = null

    fun sample(nowMs: Long): WatchdogSample {
        val qmp = runCatching { qmpStatus() }.getOrElse {
            QmpProbe(responded = false, rttMs = null, status = null, error = it.toString())
        }
        val vnc = vncProbe?.let { probe ->
            runCatching { probe() }.getOrElse {
                VncProbe(connected = false, banner = null, rttMs = null, error = it.toString())
            }
        }
        val serial = sampleSerial()

        val mainSnapshot = readThread(pid)
        val vcpuSnapshots = cpuTids.map { it to readThread(it) }
        val dtMs = prevEpochMs?.let { nowMs - it }
        val usableDt = dtMs?.takeIf { it > 0 }

        val mainPercent = percentOf(prevThreads[pid], mainSnapshot, usableDt)
        val mainDelta = deltaOf(prevThreads[pid], mainSnapshot)
        val vcpuPercents = vcpuSnapshots.map { (tid, snapshot) ->
            percentOf(prevThreads[tid], snapshot, usableDt)
        }
        val vcpuDeltas = vcpuSnapshots.map { (tid, snapshot) -> deltaOf(prevThreads[tid], snapshot) }
        val distribution = ThreadDistribution(
            mainCpuPercent = mainPercent,
            vcpuCpuPercents = vcpuPercents,
            vcpuSumPercent = vcpuPercents.filterNotNull().takeIf { it.isNotEmpty() }?.sum(),
            threads = buildList {
                add(ThreadCpu(pid, ThreadRole.MAIN, mainPercent, mainSnapshot.statTicks, mainDelta, mainSnapshot.schedRuntimeNs, mainSnapshot.state, mainSnapshot.syscall))
                vcpuSnapshots.forEachIndexed { index, (tid, snapshot) ->
                    add(ThreadCpu(tid, ThreadRole.VCPU, vcpuPercents[index], snapshot.statTicks, vcpuDeltas[index], snapshot.schedRuntimeNs, snapshot.state, snapshot.syscall))
                }
            },
            mainState = mainSnapshot.state,
            mainSyscall = mainSnapshot.syscall,
        )

        prevEpochMs = nowMs
        prevThreads = buildMap {
            put(pid, mainSnapshot)
            vcpuSnapshots.forEach { (tid, snapshot) -> put(tid, snapshot) }
        }

        return WatchdogSample(
            epochMs = nowMs,
            dtMs = usableDt,
            intervalMs = intervalMs,
            qmp = qmp,
            vnc = vnc,
            serial = serial,
            threads = distribution,
            procReadable = mainSnapshot.statTicks != null || mainSnapshot.schedRuntimeNs != null,
        )
    }

    private fun readThread(tid: Int): ThreadSnapshot {
        val stat = readFile("/proc/$pid/task/$tid/stat")
        val sched = readFile("/proc/$pid/task/$tid/schedstat")
        val syscall = readFile("/proc/$pid/task/$tid/syscall")
        return ThreadSnapshot(
            statTicks = stat?.let { ProcStats.cpuTicks(it) },
            schedRuntimeNs = sched?.let { ProcStats.schedRuntimeNs(it) },
            state = stat?.let { ProcStats.stateChar(it) },
            syscall = syscall?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** dticks：utime+stime 的窗口增量；缺上一拍或计数回退时为 null，不是 0。 */
    private fun deltaOf(prev: ThreadSnapshot?, now: ThreadSnapshot?): Long? {
        val prevTicks = prev?.statTicks ?: return null
        val nowTicks = now?.statTicks ?: return null
        val delta = nowTicks - prevTicks
        return delta.takeIf { it >= 0 }
    }

    /**
     * 每线程百分比：schedstat 优先（每线程独立），stat tick 回退（可能拿到
     * 线程组聚合值，但总比 null 多一个可判读信号）。缺上一拍或计数回退时
     * 返回 null，绝不把未知当 0——0 会被误读成「主线程空闲」。
     */
    private fun percentOf(prev: ThreadSnapshot?, now: ThreadSnapshot?, dtMs: Long?): Float? {
        if (prev == null || now == null || dtMs == null || dtMs <= 0) return null
        val seconds = dtMs / 1000.0
        val schedNow = now.schedRuntimeNs
        val schedPrev = prev.schedRuntimeNs
        if (schedNow != null && schedPrev != null) {
            val delta = schedNow - schedPrev
            if (delta >= 0) {
                return ((delta / 1_000_000_000.0) / seconds * 100.0).coerceIn(0.0, 100.0).toFloat()
            }
        }
        val statNow = now.statTicks
        val statPrev = prev.statTicks
        if (statNow != null && statPrev != null) {
            val delta = statNow - statPrev
            if (delta >= 0) {
                return ((delta.toDouble() / clockTicksPerSecond) / seconds * 100.0)
                    .coerceIn(0.0, 100.0).toFloat()
            }
        }
        return null
    }

    private fun sampleSerial(): SerialProbe? {
        val file = serialLog ?: return null
        val bytes = runCatching { if (file.isFile) file.length() else null }.getOrNull() ?: return null
        val previous = prevSerialBytes
        val delta = if (previous == null || bytes < previous) null else bytes - previous
        prevSerialBytes = bytes
        return SerialProbe(bytes, delta)
    }

    companion object {
        /** 5 秒一拍：停摆窗口的分辨率，又不会让看门狗自己成为负载。 */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}

/** 面向前端的判定结果。NOT_RUNNING 由 UI 在无快照时自行使用。 */
enum class DiagVerdict { NOT_RUNNING, SAMPLING, HEALTHY, WATCH, SUSPECTED_STALL, STALLED, UNKNOWN }

/** VNC 探测结论。TIMEOUT/REFUSED 区分连接层失败，NO_RESPONSE 是「连上但不说话」。 */
enum class DiagVncBanner { OK, TIMEOUT, REFUSED, NO_RESPONSE }

/**
 * UI 内存契约（T6 §5）。字段不可变、整体替换；`processCpuPercent` /
 * `processUptimeMs` 由现有性能仪表（VmSession.metrics）提供，看门狗不重复采样。
 */
data class DiagSnapshot(
    val vmId: String,
    val runId: String,
    val windowMs: Int,
    val sampledAtEpochMs: Long,
    val verdict: DiagVerdict,
    /** 本次运行累计打开的异常窗口数（即 windowId）。 */
    val badWindows: Int,
    val qmpRttMs: Int?,
    val qmpTimedOut: Boolean?,
    val vncBanner: DiagVncBanner?,
    val vncConnectMs: Int?,
    val serialBytesDelta: Long?,
    val serialBytesTotal: Long?,
    val processAlive: Boolean?,
    val processUptimeMs: Long?,
    val processCpuPercent: Float?,
    val mainThreadCpuPercent: Float?,
    val vcpuPercents: List<Float>?,
    /** 主线程 stat 状态字符（R/S/D…）；停摆归因判别量，读不到为 null。 */
    val mainState: String? = null,
    /** 主线程 /proc syscall 原始行；归因用，读不到为 null。 */
    val mainSyscall: String? = null,
    /** 各 vCPU 的状态字符，与 [vcpuSyscalls] 同序；缺测为 null。 */
    val vcpuStates: List<String?> = emptyList(),
    val vcpuSyscalls: List<String?> = emptyList(),
    /**
     * 采样间隔超过 3×窗口时记录实际间隔（ms），作为进程被系统整体挂起（冻结）
     * 的间接证据；正常节拍为 null。App 的 SELinux 域读不到 cgroup.freeze，
     * 只能靠采样间隙推断；shell 侧确认命令见 docs/troubleshooting.md。
     */
    val processSuspendGapMs: Long? = null,
    val procReadable: Boolean,
    val unavailableSignals: List<String>,
    /** PowerManager 温控状态码（THERMAL_STATUS_*）；调度器未启用时为 null。 */
    val thermalStatusCode: Int? = null,
    /** vCPU 亲和性档位中文（性能核 / 能效核 / 未绑核）；未启用时为 null。 */
    val affinityMode: String? = null,
    /** 实际生效的 taskset 掩码（十六进制，不带 0x）；未绑核时为 null。 */
    val affinityMaskHex: String? = null,
)

/** UI 事件（T6 §5）：新→旧最多 20 条，由 VmSession 的 StateFlow 提供。 */
data class DiagEvent(
    val epochMs: Long,
    val kind: String,
    val windowMs: Int,
    val consecutive: Int,
    val evidence: String,
)

/**
 * 把连续异常样本合并成「异常窗口」的纯判据。
 *
 * 硬信号各自连续出现 [WINDOW_THRESHOLD] 拍才开窗：
 *  - `qmp_timeout`：query-status 没有在超时内返回；
 *  - `qmp_status_<s>`：QMP 回了，但状态不是 running（paused / guest-panicked …）；
 *  - `vnc_no_banner`：TCP 连上了，但没等到 RFB banner（停摆的典型形态）；
 *  - `proc_unreadable`：进程还活着但主线程 /proc 读不到。
 *
 * 单拍抖动不开窗；窗口在第一条无硬信号的样本上关闭。阈值是合并门禁的一部分，
 * 改动必须同步 docs/troubleshooting.md。
 */
class WatchdogDetector(
    private val windowThreshold: Int = WINDOW_THRESHOLD,
    private val idleMainPercent: Float = IDLE_MAIN_PERCENT,
    private val busyVcpuPercent: Float = BUSY_VCPU_PERCENT,
) {

    data class Verdict(
        /** 本拍的硬信号（不论是否已连续够开窗）。 */
        val signals: List<String>,
        val inWindow: Boolean,
        /** 最近一次窗口编号；未开过窗时为 0。 */
        val windowId: Int,
        /** 当前窗口内已累计的样本数；不在窗口内为 0。 */
        val windowSamples: Int,
        val windowOpened: Boolean,
        val windowClosed: Boolean,
        val closedWindowId: Int?,
        val closedWindowSamples: Int?,
        /** 主线程空闲 + vCPU 繁忙的归因标签；不构成单独异常。 */
        val stallProfile: String?,
        /** 面向前端的判定（见 [DiagVerdict]）。 */
        val diagnosis: DiagVerdict,
    )

    private val streaks = mutableMapOf<String, Int>()
    private var windowId = 0
    private var inWindow = false
    private var samplesInWindow = 0

    fun evaluate(sample: WatchdogSample): Verdict {
        val hard = hardSignals(sample)
        for (key in hard) streaks[key] = (streaks[key] ?: 0) + 1
        streaks.keys.retainAll(hard.toSet())

        val active = hard.filter { (streaks[it] ?: 0) >= windowThreshold }
        var opened = false
        var closedId: Int? = null
        var closedSamples: Int? = null
        if (active.isNotEmpty()) {
            if (!inWindow) {
                windowId += 1
                inWindow = true
                // 窗口从「连续第 N 拍」开始：开窗这一拍已经攒够了阈值拍数，
                // 不是从 1 重新数，否则证据里会写成「连续 1 拍无响应」。
                samplesInWindow = active.maxOfOrNull { streaks[it] ?: 1 } ?: windowThreshold
                opened = true
            } else {
                samplesInWindow += 1
            }
        } else if (inWindow) {
            inWindow = false
            closedId = windowId
            closedSamples = samplesInWindow
        }

        val stall = stallProfile(sample)
        return Verdict(
            signals = hard,
            inWindow = inWindow,
            windowId = windowId,
            windowSamples = if (inWindow) samplesInWindow else 0,
            windowOpened = opened,
            windowClosed = closedId != null,
            closedWindowId = closedId,
            closedWindowSamples = closedSamples,
            stallProfile = stall,
            diagnosis = diagnosis(sample, inWindow, hard, stall),
        )
    }

    private fun hardSignals(sample: WatchdogSample): List<String> = buildList {
        if (!sample.qmp.responded) {
            add("qmp_timeout")
        } else {
            sample.qmp.status?.takeIf { it != "running" }?.let { add("qmp_status_$it") }
        }
        sample.vnc?.let { if (!it.bannerSeen) add("vnc_no_banner") }
        if (!sample.procReadable) add("proc_unreadable")
    }

    private fun stallProfile(sample: WatchdogSample): String? {
        val threads = sample.threads ?: return null
        val main = threads.mainCpuPercent ?: return null
        val vcpu = threads.vcpuSumPercent ?: return null
        return if (main < idleMainPercent && vcpu >= busyVcpuPercent) "main_idle_vcpu_busy" else null
    }

    private fun diagnosis(
        sample: WatchdogSample,
        inWindow: Boolean,
        hard: List<String>,
        stallProfile: String?,
    ): DiagVerdict {
        val controlPlaneDown = !sample.qmp.responded || sample.vnc?.bannerSeen == false
        return when {
            // 首拍没有上一拍可比，先给 SAMPLING；下一拍才有速率与窗口判断。
            sample.dtMs == null -> DiagVerdict.SAMPLING
            inWindow && controlPlaneDown && stallProfile != null -> DiagVerdict.STALLED
            inWindow && controlPlaneDown -> DiagVerdict.SUSPECTED_STALL
            inWindow || hard.isNotEmpty() -> DiagVerdict.WATCH
            sample.threads == null -> DiagVerdict.UNKNOWN
            else -> DiagVerdict.HEALTHY
        }
    }

    companion object {
        /** 连续多少拍硬信号才开窗：5 秒一拍 ⇒ 约 10 秒，滤掉单拍抖动。 */
        const val WINDOW_THRESHOLD = 2

        /** 主线程低于该百分比视为空闲（正常 guest 计算时主线程也在 poll 睡眠）。 */
        const val IDLE_MAIN_PERCENT = 5f

        /** vCPU 合计高于该百分比视为仍在繁忙。 */
        const val BUSY_VCPU_PERCENT = 25f
    }
}

/**
 * VNC 端口探测：连 127.0.0.1:<port>，读 12 字节的 RFB greeting。
 *
 * 必须显式用 IPv4 回环：QEMU 的 VNC 只绑 127.0.0.1，`getLoopbackAddress()`
 * 可能给出 ::1，在只开 IPv4 的监听上会被拒绝，把「服务正常」误报成「失联」。
 */
object VncBannerProbe {

    private const val DEFAULT_TIMEOUT_MS = 500
    private const val BANNER_BYTES = 12

    fun probe(port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): VncProbe {
        val started = System.nanoTime()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
            socket.soTimeout = timeoutMs
            val banner = readBanner(socket)
            val rttMs = (System.nanoTime() - started) / 1_000_000
            VncProbe(
                connected = true,
                banner = banner,
                rttMs = rttMs,
                error = if (banner == null) "no RFB banner within $timeoutMs ms" else null,
            )
        } catch (t: Throwable) {
            VncProbe(
                connected = false,
                banner = null,
                rttMs = null,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun readBanner(socket: Socket): String? {
        val input = socket.getInputStream()
        val buffer = ByteArray(BANNER_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = try {
                input.read(buffer, read, buffer.size - read)
            } catch (t: Throwable) {
                break
            }
            if (n <= 0) break
            read += n
        }
        if (read == 0) return null
        return String(buffer, 0, read, Charsets.US_ASCII).trim().takeIf { it.startsWith("RFB ") }
    }
}

/**
 * 把一拍样本与判据写成一行 JSON。
 *
 * 字段契约以 T4 §6.2 为准（至少含 ts_utc / vm_id / run_id / qemu_pid /
 * qmp_rtt_ms / qmp_ok / vnc_connect_ok / vnc_banner / serial_total_bytes /
 * main_thread_dticks / vcpu_dticks[] / window_anomaly）；其余字段是补充证据。
 * `main_state` / `vcpu_states[]`（stat 状态字符）与 `main_syscall` /
 * `vcpu_syscalls[]`（/proc syscall 原始行）是停摆归因的第一手判别量：
 * 主线程 D ⇒ 同步 I/O 阻塞，S+futex ⇒ 锁阻塞。采样失败一律写显式错误字段
 * （qmp_error / vnc_error / proc_error），缺测的数值是 null 而不是 0。
 *
 * 这里手写序列化而不用 [AgentFeed.line]：契约要求 `vcpu_dticks` 是真正的 JSON
 * 数组，而 AgentFeed 的通用字段只接受标量或逗号分隔字符串。
 */
object WatchdogJson {

    fun line(
        sample: WatchdogSample,
        verdict: WatchdogDetector.Verdict,
        seq: Int,
        vmId: String,
        runId: String,
        pid: Int,
    ): String {
        val threads = sample.threads
        val mainDticks = threads?.threads?.firstOrNull { it.role == ThreadRole.MAIN }?.statTicksDelta
        val vcpuDticks = threads?.threads
            ?.filter { it.role == ThreadRole.VCPU }
            ?.map { it.statTicksDelta }
            ?: emptyList()
        val vcpuCpu = threads?.vcpuCpuPercents ?: emptyList()
        val vcpuThreads = threads?.threads?.filter { it.role == ThreadRole.VCPU } ?: emptyList()
        val vcpuStates = vcpuThreads.map { it.state }
        val vcpuSyscalls = vcpuThreads.map { it.syscall }

        val fields = listOf(
            "ts_utc" to quote(Instant.ofEpochMilli(sample.epochMs).toString()),
            "vm_id" to quote(vmId),
            "run_id" to quote(runId),
            "qemu_pid" to pid.toString(),
            "qmp_rtt_ms" to number(sample.qmp.rttMs),
            "qmp_ok" to sample.qmp.responded.toString(),
            "qmp_status" to quote(sample.qmp.status),
            "qmp_error" to quote(sample.qmp.error),
            "vnc_connect_ok" to number(sample.vnc?.connected),
            "vnc_banner" to quote(sample.vnc?.banner),
            "vnc_rtt_ms" to number(sample.vnc?.rttMs),
            "vnc_error" to quote(sample.vnc?.error),
            "serial_total_bytes" to number(sample.serial?.bytes),
            "serial_delta_bytes" to number(sample.serial?.deltaBytes),
            "main_thread_dticks" to number(mainDticks),
            "vcpu_dticks" to array(vcpuDticks.map { number(it) }),
            "main_cpu" to number(threads?.mainCpuPercent?.let { round1(it) }),
            "vcpu_cpu" to array(vcpuCpu.map { number(it?.let { v -> round1(v) }) }),
            "vcpu_sum" to number(threads?.vcpuSumPercent?.let { round1(it) }),
            "main_state" to quote(threads?.mainState),
            "vcpu_states" to array(vcpuStates.map { quote(it) }),
            "main_syscall" to quote(threads?.mainSyscall),
            "vcpu_syscalls" to array(vcpuSyscalls.map { quote(it) }),
            "proc_readable" to sample.procReadable.toString(),
            "proc_error" to quote(if (sample.procReadable) null else "main thread /proc unreadable"),
            "dt_ms" to number(sample.dtMs),
            "interval_ms" to sample.intervalMs.toString(),
            "process_suspended" to (DiagContract.suspendGapMs(sample) != null).toString(),
            "seq" to seq.toString(),
            "window_anomaly" to verdict.inWindow.toString(),
            "window_id" to number(if (verdict.inWindow) verdict.windowId else null),
            "window_samples" to verdict.windowSamples.toString(),
            "window_closed" to number(verdict.closedWindowId),
            "signals" to array(verdict.signals.map { quote(it) }),
            "stall_profile" to quote(verdict.stallProfile),
            "verdict" to quote(verdict.diagnosis.name),
        )
        return fields.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":$value"
        }
    }

    fun round1(value: Float): Float = round(value * 10f) / 10f

    private fun number(value: Long?): String = value?.toString() ?: "null"

    private fun number(value: Int?): String = value?.toString() ?: "null"

    private fun number(value: Boolean?): String = value?.toString() ?: "null"

    private fun number(value: Float?): String = value?.toString() ?: "null"

    private fun array(values: List<String>): String = values.joinToString(",", "[", "]")

    private fun quote(raw: String?): String {
        if (raw == null) return "null"
        val sb = StringBuilder(raw.length + 8)
        sb.append('"')
        for (c in raw) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/**
 * 纯映射：看门狗样本 → 前端消费者（T6 §5）需要的快照与事件。
 *
 * 判定完全由 [WatchdogDetector] 负责，这里只做字段翻译；事件只在窗口打开、
 * 采样间隔异常时产生，避免每 5 秒刷屏。
 */
object DiagContract {

    private const val CPU_SPIN_THRESHOLD = 100f
    private const val MAIN_STALL_THRESHOLD = 1f
    private const val SAMPLE_GAP_FACTOR = 3L

    /**
     * 采样间隔超过 3×窗口 ⇒ 进程曾被整体挂起（系统冻结的在 App 内唯一可观测
     * 证据）；正常节拍返回 null。注意它**不是** QEMU 异常硬信号——冻结期间
     * App 与 QEMU 同组被冻，解冻即恢复，不该据此开停摆窗口。
     */
    fun suspendGapMs(sample: WatchdogSample): Long? =
        sample.dtMs?.takeIf { it > sample.intervalMs * SAMPLE_GAP_FACTOR }

    fun snapshot(
        vmId: String,
        runId: String,
        sample: WatchdogSample,
        verdict: WatchdogDetector.Verdict,
        processAlive: Boolean = true,
        processUptimeMs: Long? = null,
        processCpuPercent: Float? = null,
        thermalStatusCode: Int? = null,
        affinityMode: String? = null,
        affinityMaskHex: String? = null,
    ): DiagSnapshot {
        val vnc = sample.vnc
        return DiagSnapshot(
            vmId = vmId,
            runId = runId,
            windowMs = sample.intervalMs.toInt(),
            sampledAtEpochMs = sample.epochMs,
            verdict = verdict.diagnosis,
            badWindows = verdict.windowId,
            qmpRttMs = sample.qmp.rttMs?.toInt(),
            qmpTimedOut = !sample.qmp.responded,
            vncBanner = when {
                vnc == null -> null
                vnc.banner != null -> DiagVncBanner.OK
                vnc.connected -> DiagVncBanner.NO_RESPONSE
                vnc.error?.contains("timeout", ignoreCase = true) == true -> DiagVncBanner.TIMEOUT
                else -> DiagVncBanner.REFUSED
            },
            vncConnectMs = vnc?.rttMs?.toInt(),
            serialBytesDelta = sample.serial?.deltaBytes,
            serialBytesTotal = sample.serial?.bytes,
            processAlive = processAlive,
            processUptimeMs = processUptimeMs,
            processCpuPercent = processCpuPercent,
            mainThreadCpuPercent = sample.threads?.mainCpuPercent,
            vcpuPercents = sample.threads?.vcpuCpuPercents?.filterNotNull()?.takeIf { it.isNotEmpty() },
            mainState = sample.threads?.mainState,
            mainSyscall = sample.threads?.mainSyscall,
            vcpuStates = sample.threads?.threads
                ?.filter { it.role == ThreadRole.VCPU }?.map { it.state } ?: emptyList(),
            vcpuSyscalls = sample.threads?.threads
                ?.filter { it.role == ThreadRole.VCPU }?.map { it.syscall } ?: emptyList(),
            processSuspendGapMs = suspendGapMs(sample),
            procReadable = sample.procReadable,
            thermalStatusCode = thermalStatusCode,
            affinityMode = affinityMode,
            affinityMaskHex = affinityMaskHex,
            unavailableSignals = buildList {
                if (!sample.procReadable) add("proc-main-thread")
                if (!sample.qmp.responded) add("qmp")
                if (vnc == null) add("vnc")
                else if (!vnc.bannerSeen) add("vnc-banner")
                if (sample.serial == null) add("serial")
                if (sample.threads?.vcpuCpuPercents.isNullOrEmpty()) add("vcpu-threads")
                if (processCpuPercent == null && processUptimeMs == null) add("process-metrics")
            },
        )
    }

    fun eventsOnSample(sample: WatchdogSample, verdict: WatchdogDetector.Verdict): List<DiagEvent> {
        val events = mutableListOf<DiagEvent>()
        if (verdict.windowOpened) {
            for (signal in verdict.signals) {
                val (kind, evidence) = when {
                    signal == "qmp_timeout" ->
                        "QMP_TIMEOUT" to "QMP query-status 连续 ${verdict.windowSamples} 拍无响应"
                    signal == "vnc_no_banner" ->
                        "VNC_BANNER_TIMEOUT" to "VNC 端口可连接但未收到 RFB banner"
                    signal == "proc_unreadable" ->
                        "PROC_UNREADABLE" to "进程存活但主线程 /proc 读取失败"
                    signal.startsWith("qmp_status_") ->
                        "QMP_STATUS" to "QMP 状态为 ${signal.removePrefix("qmp_status_")}（预期 running）"
                    else -> signal to signal
                }
                events += DiagEvent(
                    epochMs = sample.epochMs,
                    kind = kind,
                    windowMs = sample.intervalMs.toInt(),
                    consecutive = verdict.windowId,
                    evidence = evidence,
                )
            }
            val threads = sample.threads
            if (verdict.stallProfile == "main_idle_vcpu_busy") {
                val vcpu = threads?.vcpuSumPercent
                if (vcpu != null && vcpu >= CPU_SPIN_THRESHOLD) {
                    events += DiagEvent(
                        epochMs = sample.epochMs,
                        kind = "CPU_SPIN",
                        windowMs = sample.intervalMs.toInt(),
                        consecutive = verdict.windowId,
                        evidence = "vCPU 合计 ${format1(vcpu)}%，主循环无响应",
                    )
                }
                val main = threads?.mainCpuPercent
                if (main != null && main < MAIN_STALL_THRESHOLD) {
                    events += DiagEvent(
                        epochMs = sample.epochMs,
                        kind = "MAIN_THREAD_STALL",
                        windowMs = sample.intervalMs.toInt(),
                        consecutive = verdict.windowId,
                        evidence = "主线程占用 ${format1(main)}%，疑似阻塞在系统调用",
                    )
                }
            }
        }
        val gap = suspendGapMs(sample)
        if (gap != null) {
            events += DiagEvent(
                epochMs = sample.epochMs,
                kind = "SAMPLE_GAP",
                windowMs = sample.intervalMs.toInt(),
                consecutive = 0,
                evidence = "采样间隔 ${gap} ms（期望 ${sample.intervalMs} ms），进程曾被整体挂起，疑似被系统冻结",
            )
        }
        return events
    }

    private fun format1(value: Float): String = String.format(Locale.US, "%.1f", value)
}

/**
 * 看门狗时间线的落盘：`run-<runId>.watchdog.jsonl`，超过 1 MiB 轮转一代。
 *
 * 与 [AgentFeed] 同样的有界策略：诊断通道不能吃用户的存储。逐行 append
 * 而不是常开 BufferedWriter —— 进程被系统杀死时已经写下的行必须完整。
 */
class WatchdogLog(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
) {

    fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val addition = line.toByteArray(Charsets.UTF_8).size + 1
            if (file.isFile && file.length() + addition > maxBytes) {
                val rotated = File(file.absolutePath + ".1")
                rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(line + "\n")
        }
    }

    companion object {
        /** 5 秒一拍约 700 行/小时；1 MiB 足够覆盖一次真机复现（≥30 分钟）。 */
        const val MAX_BYTES = 1L * 1024 * 1024
    }
}

/**
 * 看门狗主循环。只做三件事：采样 → 判据 → 落盘/回调；不碰状态机与 UI。
 *
 * [sleep] 可注入，单测里用空实现连续跑多拍。循环在进程退出、用户停止
 * （调用方把这两件事合成 [isAlive] 的判据）或协程取消时结束。
 */
class RuntimeWatchdog(
    private val sampler: RuntimeWatchdogSampler,
    private val detector: WatchdogDetector,
    private val log: WatchdogLog,
    private val vmId: String,
    private val runId: String,
    private val pid: Int,
) {

    suspend fun run(
        isAlive: () -> Boolean,
        onSample: (WatchdogSample, WatchdogDetector.Verdict) -> Unit = { _, _ -> },
        onWindowOpen: (WatchdogDetector.Verdict, WatchdogSample) -> Unit = { _, _ -> },
        onWindowClose: (WatchdogDetector.Verdict, WatchdogSample) -> Unit = { _, _ -> },
        sleep: suspend (Long) -> Unit = { delay(it) },
    ) {
        var seq = 0
        while (isAlive()) {
            val sample = sampler.sample(System.currentTimeMillis())
            seq += 1
            val verdict = detector.evaluate(sample)
            log.append(WatchdogJson.line(sample, verdict, seq, vmId, runId, pid))
            onSample(sample, verdict)
            if (verdict.windowOpened) onWindowOpen(verdict, sample)
            if (verdict.windowClosed) onWindowClose(verdict, sample)
            sleep(sample.intervalMs)
        }
    }
}
