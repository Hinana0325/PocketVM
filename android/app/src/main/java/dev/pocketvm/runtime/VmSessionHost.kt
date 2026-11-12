package dev.pocketvm.runtime

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.diagnostics.DiagEvent
import dev.pocketvm.diagnostics.DiagSnapshot
import dev.pocketvm.diagnostics.VmMetrics
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 多机同跑的核心：每个 VM 一个 [VmSession]（各自一个 QEMU 进程），这里统一
 * 持有并把各自的状态流合并成按 vmId 索引的 StateFlow，供 UI / 调试接口消费。
 *
 * 约束仍然存在：
 * - 内存预算（[SessionBudget]）会拦下明显装不下的启动；
 * - 前台保活服务按运行台数引用计数（[VmKeepAliveService]）；
 * - VNC 端口由各 session 启动时自动挑空闲端口。
 */
@Singleton
class VmSessionHost @Inject constructor(
    @ApplicationContext context: Context,
    private val sessionProvider: Provider<VmSession>,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, VmSession>()
    private val requestedMemoryMb = ConcurrentHashMap<String, Int>()

    private val _states = MutableStateFlow<Map<String, VmRuntimeState>>(emptyMap())
    val states: StateFlow<Map<String, VmRuntimeState>> = _states.asStateFlow()

    private val _metrics = MutableStateFlow<Map<String, VmMetrics?>>(emptyMap())
    val metrics: StateFlow<Map<String, VmMetrics?>> = _metrics.asStateFlow()

    private val _diag = MutableStateFlow<Map<String, DiagSnapshot>>(emptyMap())
    val diag: StateFlow<Map<String, DiagSnapshot>> = _diag.asStateFlow()

    private val _diagEvents = MutableStateFlow<Map<String, List<DiagEvent>>>(emptyMap())
    val diagEvents: StateFlow<Map<String, List<DiagEvent>>> = _diagEvents.asStateFlow()

    /** 取（或创建）这台 VM 的会话；同一 VM 复用同一个实例。 */
    fun session(vmId: String): VmSession =
        sessions.getOrPut(vmId) {
            sessionProvider.get().also { created ->
                scope.launch {
                    created.state.collect { state ->
                        _states.update { it + (vmId to state) }
                        if (state !is VmRuntimeState.Running && state !is VmRuntimeState.Preparing) {
                            // 进程结束后不再占内存预算；会话对象保留（日志/诊断还要读）。
                            if (state is VmRuntimeState.Exited || state is VmRuntimeState.Failed) {
                                requestedMemoryMb.remove(vmId)
                            }
                        }
                    }
                }
                scope.launch {
                    created.metrics.collect { sample -> _metrics.update { it + (vmId to sample) } }
                }
                scope.launch {
                    created.diag.collect { snapshot ->
                        snapshot?.let { s -> _diag.update { it + (vmId to s) } }
                    }
                }
                scope.launch {
                    created.diagEvents.collect { events -> _diagEvents.update { it + (vmId to events) } }
                }
            }
        }

    fun isRunning(vmId: String): Boolean = sessionOrNull(vmId)?.isRunning() == true

    fun stateOf(vmId: String): VmRuntimeState = _states.value[vmId] ?: VmRuntimeState.Idle

    fun runningVmId(): String? =
        _states.value.entries.firstOrNull { it.value is VmRuntimeState.Running }?.key

    fun runningVmIds(): List<String> =
        _states.value.entries.filter { it.value is VmRuntimeState.Running }.map { it.key }

    fun currentRun(vmId: String) = sessionOrNull(vmId)?.currentRun(vmId)

    fun readLog(vmId: String, maxLines: Int = 400): String =
        sessionOrNull(vmId)?.readLog(vmId, maxLines) ?: ""

    fun readSerialTail(vmId: String, maxLines: Int = 120): String =
        sessionOrNull(vmId)?.readSerialTail(vmId, maxLines) ?: ""

    fun serialSend(vmId: String, text: String): Boolean =
        sessionOrNull(vmId)?.serialSend(text) == true

    suspend fun start(config: VmConfig): LaunchOutcome {
        if (isRunning(config.id)) {
            return LaunchOutcome.Failed("这台虚拟机已经在运行。")
        }
        val runningMemory = requestedMemoryMb
            .filterKeys { key -> isRunning(key) }
            .values.sum().toLong()
        SessionBudget.blockReason(deviceMemoryMb(), runningMemory, config.memoryMb)?.let {
            return LaunchOutcome.Failed(it)
        }
        requestedMemoryMb[config.id] = config.memoryMb
        val outcome = session(config.id).start(config)
        if (outcome !is LaunchOutcome.Started) requestedMemoryMb.remove(config.id)
        return outcome
    }

    suspend fun stop(vmId: String) {
        sessionOrNull(vmId)?.stop(vmId)
    }

    fun exportDiagnostics(vmId: String, config: VmConfig?): Result<java.io.File> =
        session(vmId).exportDiagnostics(vmId, config)

    private fun sessionOrNull(vmId: String): VmSession? = sessions[vmId]

    private fun deviceMemoryMb(): Long = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    }.getOrDefault(0L)
}
