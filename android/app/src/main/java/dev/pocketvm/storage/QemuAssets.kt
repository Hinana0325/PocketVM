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
    // v3: previous builds could leave a 2 MiB -bios image behind with a valid
    // marker, which made QEMU fail with "pflash0 block backend provides 2097152
    // bytes". The marker is now only trusted together with a size check.
    private const val MARKER = ".extracted-v3"

    /**
     * Independent marker for the `-L` data dir. v3 puts `efi-virtio.rom` at the
     * **root** of the data dir: QEMU's qemu_find_file() uses subdir "" for
     * QEMU_FILE_TYPE_BIOS, so `<data_dir>/pc-bios/...` is never searched.
     */
    private const val SHARE_MARKER = ".extracted-qemu-v3"

    /** virt's pflash device is 64 MiB; anything far below that is a -bios image. */
    private const val MIN_PFLASH_BYTES = 32L * 1024 * 1024

    /** @return the directory to pass as `-L`, or null when nothing was packaged. */
    fun ensureSharedData(context: Context, layout: VmStorageLayout): File? {
        return try {
            val target = layout.qemuShareDir
            val marker = File(target, SHARE_MARKER)
            // The marker alone is not proof: a previous copy could have missed a
            // subtree (AssetManager.list() is not guaranteed to enumerate it) yet
            // still written the marker, which then short-circuits every retry.
            if (marker.isFile && File(target, ROM_SENTINEL).isFile) return target

            if (!hasAssets(context.assets, ASSET_ROOT)) return null
            target.mkdirs()
            copyTree(context.assets, ASSET_ROOT, target)
            // Copy the ROM by exact path too: this file decides whether the VM
            // can start with a NIC, so it must not depend on directory listing.
            if (!File(target, ROM_SENTINEL).isFile) {
                copyFileAsset(context.assets, "$ASSET_ROOT/$ROM_SENTINEL", File(target, ROM_SENTINEL))
            }
            if (File(target, ROM_SENTINEL).isFile) marker.writeText("ok")
            target
        } catch (t: Throwable) {
            null
        }
    }

    /** ROM that virtio-net-pci hardcodes; also the sentinel for a complete -L tree. */
    private const val ROM_SENTINEL = "efi-virtio.rom"

    private fun copyFileAsset(assets: AssetManager, assetPath: String, out: File) {
        out.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
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
            val plausible = hasPlausibleFirmware(target)
            if (marker.isFile && plausible) return target

            // A user-supplied 64 MiB image is kept; only stale small ones (the
            // old 2 MiB -bios file) are removed, because they shadow the bundle.
            if (!plausible) {
                target.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".fd", ignoreCase = true) }
                    ?.filter { !it.name.contains("vars", ignoreCase = true) }
                    ?.filter { it.length() < MIN_PFLASH_BYTES }
                    ?.forEach { it.delete() }
            }
            if (hasPlausibleFirmware(target)) {
                marker.parentFile?.mkdirs()
                marker.writeText("ok")
                return target
            }

            if (!hasAssets(context.assets, FIRMWARE_ROOT)) return null
            target.mkdirs()
            copyTree(context.assets, FIRMWARE_ROOT, target)
            marker.writeText("ok")
            target
        } catch (t: Throwable) {
            null
        }
    }

    private fun hasPlausibleFirmware(dir: File): Boolean = dir.listFiles()?.any {
        it.isFile && it.name.endsWith(".fd", ignoreCase = true) &&
            !it.name.contains("vars", ignoreCase = true) &&
            it.length() >= MIN_PFLASH_BYTES
    } == true

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
