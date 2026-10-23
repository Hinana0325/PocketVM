package dev.pocketvm.storage

import android.content.Context
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.runtime.FirmwareFiles
import java.io.File
import java.io.RandomAccessFile

sealed interface FirmwareResult {
    data class Ready(
        val files: FirmwareFiles,
        val createdVars: Boolean,
        /** Something the user should know (e.g. the variable store was rebuilt). */
        val note: String? = null,
    ) : FirmwareResult

    data class Failure(val message: String) : FirmwareResult
}

/** virt's pflash device is fixed at 64 MiB; below this it cannot be a pflash image. */
private const val MIN_PFLASH_BYTES = 32L * 1024 * 1024

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

    /**
     * Sorted so the *default* pick is the newest plain (non-Secure-Boot) image:
     * Secure Boot builds are listed last because they will refuse to start an
     * unsigned bootloader, which looks exactly like "the ISO is broken".
     */
    private fun scan(): List<File> = layout.firmwareDir
        .listFiles()
        ?.filter { it.isFile && it.name.endsWith(".fd", ignoreCase = true) }
        ?.filterNot { it.name.contains("vars", ignoreCase = true) }
        ?.sortedWith(compareBy<File> { it.name.contains("secboot", ignoreCase = true) }.thenBy { it.name })
        ?: emptyList()

    fun resolve(config: VmConfig): File? {
        // A configured path is only trusted when the file can actually serve as
        // a pflash image. The old 2 MiB -bios file exists and is readable, so a
        // plain isFile() check kept returning it and the bundled 64 MiB image
        // was never extracted.
        config.firmwareCodePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.length() >= MIN_PFLASH_BYTES) return file
        }
        return available().firstOrNull()
    }

    fun prepare(config: VmConfig, vars: File): FirmwareResult {
        val code = resolve(config)
            ?: return FirmwareResult.Failure(
                "未找到 UEFI 固件。请把 QEMU_EFI.fd 放入 ${layout.firmwareDir.absolutePath}",
            )
        if (!code.isFile) return FirmwareResult.Failure("固件不存在：${code.absolutePath}")
        if (code.length() < MIN_PFLASH_BYTES) {
            return FirmwareResult.Failure(
                "${code.name} 只有 ${code.length() / (1024 * 1024)} MB，而 virt 的 pflash 需要 64 MiB。" +
                    "这多半是旧的 2 MiB -bios 镜像；在「编辑 → 固件」里重新导入 QEMU_EFI.fd 即可。",
            )
        }

        var createdVars = false
        var note: String? = null
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
            // The only way this happens is an older firmware generation (2 MiB
            // vars next to a 64 MiB code image, or vice versa). Those variables
            // are unusable either way, so rebuild instead of blocking the launch
            // and asking the user to delete a file by hand.
            note = "UEFI 变量存储大小 ${vars.length() / (1024 * 1024)} MB 与固件 " +
                "${code.length() / (1024 * 1024)} MB 不一致，已重建（启动项会重新枚举）"
            runCatching {
                vars.delete()
                RandomAccessFile(vars, "rw").use { it.setLength(code.length()) }
            }.onFailure {
                return FirmwareResult.Failure("无法重建 UEFI 变量存储：${it.message}")
            }
            createdVars = true
        }

        return FirmwareResult.Ready(
            files = FirmwareFiles(codePath = code.absolutePath, varsPath = vars.absolutePath),
            createdVars = createdVars,
            note = note,
        )
    }
}
