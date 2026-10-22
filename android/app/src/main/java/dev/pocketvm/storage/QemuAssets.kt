package dev.pocketvm.storage

import android.content.Context
import android.content.res.AssetManager
import dev.pocketvm.storage.VmStorageLayout
import java.io.File

/**
 * Ships QEMU's runtime data with the APK and unpacks it on first use.
 *
 * Right now that means the keymaps. They are not optional: VNC turns an RFB
 * keysym into a QEMU key code through `pc-bios/keymaps/en-us`, and if the file is
 * missing QEMU prints "Could not read keymap file" and typed characters are
 * dropped. That failure looks exactly like "VNC input is broken", so the data has
 * to travel with the binary.
 */
object QemuAssets {

    private const val ASSET_ROOT = "qemu"
    private const val FIRMWARE_ROOT = "firmware"
    // v2: 内置固件从 2 MiB（-bios 用）换成 64 MiB pflash，必须覆盖旧文件。
    private const val MARKER = ".extracted-v2"

    /** @return the directory to pass as `-L`, or null when nothing was packaged. */
    fun ensureSharedData(context: Context, layout: VmStorageLayout): File? {
        return try {
            val target = layout.qemuShareDir
            val marker = File(target, MARKER)
            if (marker.isFile) return target

            if (!hasAssets(context.assets, ASSET_ROOT)) return null
            target.mkdirs()
            copyTree(context.assets, ASSET_ROOT, target)
            marker.writeText("ok")
            target
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * edk2's 2 MiB pflash image ships inside the APK so the first launch can
     * actually boot instead of stopping at FIRMWARE_MISSING. A user-supplied
     * firmware in the same directory simply overwrites it.
     */
    fun ensureFirmware(context: Context, layout: VmStorageLayout): File? {
        return try {
            val target = layout.firmwareDir
            val marker = File(target, MARKER)
            if (marker.isFile) return target
            if (!hasAssets(context.assets, FIRMWARE_ROOT)) return null
            target.mkdirs()
            copyTree(context.assets, FIRMWARE_ROOT, target)
            marker.writeText("ok")
            target
        } catch (t: Throwable) {
            null
        }
    }

    private fun hasAssets(assets: AssetManager, path: String): Boolean =
        runCatching { assets.list(path)?.isNotEmpty() == true }.getOrDefault(false)

    private fun copyTree(assets: AssetManager, assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // Leaf: `list` returns an empty array for a file too.
            target.parentFile?.mkdirs()
            // The 64 MiB pflash image ships gzipped (~1.2 MB) to keep the repo
            // and APK sane; it is expanded to its real name on first run.
            val gzipped = target.name.endsWith(".gz")
            val outFile = if (gzipped) {
                File(target.parentFile, target.name.removeSuffix(".gz"))
            } else {
                target
            }
            assets.open(assetPath).use { raw ->
                val input = if (gzipped) java.util.zip.GZIPInputStream(raw) else raw
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        for (child in children) {
            copyTree(assets, "$assetPath/$child", File(target, child))
        }
    }
}
