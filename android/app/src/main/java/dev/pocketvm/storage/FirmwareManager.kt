package dev.pocketvm.storage

import android.content.Context
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.runtime.FirmwareFiles
import java.io.File
import java.io.RandomAccessFile

sealed interface FirmwareResult {
    data class Ready(val files: FirmwareFiles, val createdVars: Boolean) : FirmwareResult
    data class Failure(val message: String) : FirmwareResult
}

/**
 * UEFI flash management.
 *
 * AArch64 `virt` exposes UEFI through two pflash banks: code (read-only) and
 * variables (read-write). QEMU's pflash device for `-M virt` is fixed at 64 MiB,
 * so both files must be exactly 64 MiB -- a 2 MiB `QEMU_EFI.fd` is a `-bios`
 * image and gets rejected with "device requires 67108864 bytes".
 *
 * The variable bank may start as zeros: edk2 formats an unformatted store on
 * first boot. Verified by booting the shipped firmware pair under a real QEMU.
 */
class FirmwareManager(
    private val context: Context,
    private val layout: VmStorageLayout,
) {

    private var bundledExtracted = false

    fun available(): List<File> {
        if (!bundledExtracted) {
            QemuAssets.ensureFirmware(context.applicationContext, layout)
            bundledExtracted = true
        }
        return scan()
    }

    private fun scan(): List<File> = layout.firmwareDir
        .listFiles()
        ?.filter { it.isFile && it.name.endsWith(".fd", ignoreCase = true) }
        ?.filterNot { it.name.contains("vars", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun resolve(config: VmConfig): File? {
        config.firmwareCodePath?.let { path ->
            val file = File(path)
            if (file.isFile) return file
        }
        return available().firstOrNull()
    }

    fun prepare(config: VmConfig, vars: File): FirmwareResult {
        val code = resolve(config)
            ?: return FirmwareResult.Failure(
                "未找到 UEFI 固件。请把 QEMU_EFI.fd 放入 ${layout.firmwareDir.absolutePath}",
            )
        if (!code.isFile) return FirmwareResult.Failure("固件不存在：${code.absolutePath}")
        if (code.length() < 256L * 1024) {
            return FirmwareResult.Failure("${code.name} 只有 ${code.length()} 字节，不像 pflash 镜像")
        }

        var createdVars = false
        if (!vars.exists()) {
            vars.parentFile?.mkdirs()
            runCatching {
                RandomAccessFile(vars, "rw").use { it.setLength(code.length()) }
            }.onFailure {
                return FirmwareResult.Failure("无法创建 UEFI 变量存储：${it.message}")
            }
            createdVars = true
        }

        if (vars.length() != code.length()) {
            return FirmwareResult.Failure(
                "UEFI 变量存储大小 ${vars.length()} 与固件 ${code.length()} 不一致。" +
                    "删除 ${vars.name} 后重新启动可自动重建（会丢失启动项）。",
            )
        }

        return FirmwareResult.Ready(
            files = FirmwareFiles(codePath = code.absolutePath, varsPath = vars.absolutePath),
            createdVars = createdVars,
        )
    }
}
