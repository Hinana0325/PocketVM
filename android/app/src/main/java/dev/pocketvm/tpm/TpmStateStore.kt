package dev.pocketvm.tpm

import java.io.File

/**
 * 每台 VM 的 TPM 状态与运行时文件位置（阶段 2，swtpm 进程管理）。
 *
 * 两条路径、两种文件系统，绝不能混：
 * - TPM 状态是普通文件，落在外部存储 `vms/<vmId>/tpm/state`，与磁盘同目录，
 *   备份时一起带走；绝不在 cacheDir；绝不每次启动重建；删 VM 时随整目录删。
 * - TPM 控制 socket 是 unix socket，落在内部存储 `filesDir/internal/run/`，
 *   与 QMP socket 同一目录、同一规则——外部存储走 FUSE，建不了 unix socket。
 *
 * 本类只拼路径（纯 `java.io.File`，无 Android），构造时传进来的两个根目录
 * 由 [dev.pocketvm.storage.VmStorageLayout] 提供；单测直接给临时目录即可。
 */
class TpmStateStore(
    /** 外部存储的 `vms/` 目录。 */
    private val vmsDir: File,
    /** 内部存储的 `filesDir/internal/run/` 目录。 */
    private val runDir: File,
) {

    companion object {
        /**
         * Unix socket 路径（含结尾 NUL）上限 108 字节；路径本身必须短于 108，
         * 超长在 [socketFile] 里直接抛错，不等 swtpm/QEMU 去报看不懂的错。
         */
        const val MAX_SOCKET_PATH_LENGTH = 108

        private val VM_ID_PATTERN = Regex("[A-Za-z0-9._-]+")
    }

    /**
     * 与 [dev.pocketvm.storage.VmStorageLayout] 同一条规则：vmId 来自磁盘上的
     * JSON，含 `../` 的篡改值绝不能逃出 `vms/` 目录。
     */
    fun safeVmId(vmId: String): String {
        require(vmId.matches(VM_ID_PATTERN) && vmId != "." && vmId != "..") {
            "非法的虚拟机 ID：$vmId"
        }
        return vmId
    }

    /** TPM 状态目录：`vms/<vmId>/tpm/state`。不存在时创建（首次启动建身份）。 */
    fun stateDir(vmId: String): File = stateDirFile(vmId).apply { mkdirs() }

    /** TPM 状态目录，不创建。`recover()` 用它判断「有没有 TPM 身份可恢复」。 */
    fun stateDirFile(vmId: String): File =
        File(File(File(vmsDir, safeVmId(vmId)), "tpm"), "state")

    /**
     * swtpm 的运行日志：`vms/<vmId>/tpm/swtpm.log`。
     * 放在 `state/` 之外——启动失败时把日志尾巴拼进报错里，但日志文件本身
     * 绝不能污染 TPM 状态目录。
     */
    fun logFile(vmId: String): File =
        File(File(File(vmsDir, safeVmId(vmId)), "tpm"), "swtpm.log")
            .apply { parentFile?.mkdirs() }

    /**
     * TPM 控制 socket：`filesDir/internal/run/<vmId>.tpm.sock`。
     * 不创建文件本身（swtpm 启动时创建）；只做路径长度守卫。
     */
    fun socketFile(vmId: String): File {
        val file = File(runDir, "${safeVmId(vmId)}.tpm.sock")
        require(file.absolutePath.length < MAX_SOCKET_PATH_LENGTH) {
            "TPM 控制套接字路径过长（${file.absolutePath.length}≥$MAX_SOCKET_PATH_LENGTH）：" +
                file.absolutePath
        }
        return file
    }

    /** 删残留 socket 文件。只删 socket，状态文件永远不动。 */
    fun deleteSocketFile(vmId: String): Boolean = runCatching {
        File(runDir, "${safeVmId(vmId)}.tpm.sock").delete()
    }.getOrDefault(false)
}
