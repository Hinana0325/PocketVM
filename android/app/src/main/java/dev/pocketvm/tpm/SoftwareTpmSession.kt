package dev.pocketvm.tpm

import java.io.File

/**
 * 一次 swtpm 前台进程的会话：VmSession 启动 QEMU 之前持有它，QEMU 退出之后
 * 停掉它。TPM 身份（状态目录里的文件）属于 VM，不属于这次会话——会话结束
 * 只删 socket，状态文件保留。
 */
data class SoftwareTpmSession(
    /** swtpm 子进程（前台模式，绝不用 `--daemon`，句柄必须在我们手里）。 */
    val process: Process,
    val vmId: String,
    /** TPM 状态目录：`vms/<vmId>/tpm/state`。 */
    val stateDir: File,
    /** 控制 socket 的绝对路径：`filesDir/internal/run/<vmId>.tpm.sock`。 */
    val socketPath: String,
    val startedAtEpochMs: Long,
) {
    val isAlive: Boolean get() = process.isAlive
}
