package dev.pocketvm.tpm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 盯着 swtpm 进程：它意外退出时记一条事件。
 *
 * 通道照抄 VmSession 里现有的看门狗/串口/健康探针——[dev.pocketvm.diagnostics.AgentFeed]，
 * 不发明新上报通道。意外退出的定义是「不是我们主动停的」：[stop] 先把标志位
 * 置上，再取消等待任务，所以正常停止不会误报。
 *
 * 监控只记录、不处理：QEMU 正在跑时 swtpm 死了，QEMU 的 TPM 后端下次发命令
 * 才会失败，杀不杀 QEMU、报不报错是 VmSession 的决定，不是监控器的。
 */
class TpmProcessMonitor @Inject constructor() {

    private val stopped = AtomicBoolean(true)
    private var job: Job? = null

    /**
     * 在 [scope] 里等 [session] 的进程退出。非主动退出时记 `tpm.exited_unexpectedly`
     * 事件，并调 [onUnexpectedExit]（exitCode 为 null = 等待本身失败）。
     */
    fun start(
        scope: CoroutineScope,
        session: SoftwareTpmSession,
        onUnexpectedExit: (SoftwareTpmSession, Int?) -> Unit = { _, _ -> },
    ) {
        stop()
        stopped.set(false)
        job = scope.launch(Dispatchers.IO) {
            val code = runCatching { session.process.waitFor() }.getOrNull()
            if (!stopped.get()) {
                dev.pocketvm.diagnostics.AgentFeed.event(
                    "tpm.exited_unexpectedly",
                    "vm" to session.vmId,
                    "exitCode" to code,
                )
                runCatching { onUnexpectedExit(session, code) }
            }
        }
    }

    /** 主动停止：先置标志位再取消等待任务，[start] 里的回调不会再触发。 */
    fun stop() {
        stopped.set(true)
        job?.cancel()
        job = null
    }
}
