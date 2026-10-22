package dev.pocketvm.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Android gives us `content://` URIs, QEMU wants a path. There is no way to pass
 * an open file descriptor across a fork from the JVM, so the media has to be
 * copied into app storage. This is also why the wizard shows a progress bar and
 * tells the user it may take a few minutes.
 */
class IsoImporter(
    context: Context,
    private val layout: VmStorageLayout,
) {

    private val appContext = context.applicationContext

    suspend fun import(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<File> = runCatching {
        val name = queryName(uri) ?: "install-${System.currentTimeMillis()}.iso"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val target = uniqueTarget(File(layout.imagesDir, safeName.ifEmpty { "install.iso" }))

        val total = querySize(uri)
        // Fail before the multi-minute copy, not 5 GB into it.
        if (total != null && total > layout.imagesDir.usableSpace) {
            error("空间不足：镜像需要 ${total / (1024 * 1024)} MB，可用只有 ${layout.imagesDir.usableSpace / (1024 * 1024)} MB")
        }

        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport > 64L * 1024 * 1024) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                    output.flush()
                    onProgress(copied, total ?: copied)
                }
            } ?: error("无法读取所选文件")
        } catch (t: Throwable) {
            // Never leave a truncated ISO behind: it would look importable.
            target.delete()
            throw t
        }
        target
    }

    /** Same-name imports from different sources must not overwrite each other. */
    private fun uniqueTarget(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val candidate = File(file.parentFile, "$base-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    /** UEFI code flash has no content:// path problem, but it still must be a real file. */
    suspend fun importFirmware(uri: Uri): Result<File> = runCatching {
        val raw = queryName(uri) ?: "QEMU_EFI.fd"
        val named = if (raw.endsWith(".fd", ignoreCase = true)) raw else "$raw.fd"
        val safe = named.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val target = File(layout.firmwareDir, safe)
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选固件")
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        val size = target.length()
        if (size < 256 * 1024) {
            target.delete()
            error("文件只有 $size 字节，不像是 pflash 固件")
        }
        target
    }

    private fun queryName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()
}
