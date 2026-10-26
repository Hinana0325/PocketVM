package dev.pocketvm.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Android gives us `content://` URIs, QEMU wants a path. There is no way to pass
 * an open file descriptor across a fork from the JVM, so the media has to be
 * copied into app storage. This is also why the wizard shows a progress bar and
 * tells the user it may take a few minutes.
 *
 * 写入先落到 `*.part`，成功后才 rename：进程被杀或用户取消时不会留下一个
 * 看起来能选、实际是半个文件的 ISO/固件。
 */
class IsoImporter(
    context: Context,
    private val layout: VmStorageLayout,
) {

    private val appContext = context.applicationContext

    suspend fun import(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<File> {
        val name = queryName(uri) ?: "install-${System.currentTimeMillis()}.iso"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val target = uniqueTarget(File(layout.imagesDir, safeName.ifEmpty { "install.iso" }))
        val part = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")

        val total = querySize(uri)
        // Fail before the multi-minute copy, not 5 GB into it.
        if (total != null && total > layout.imagesDir.usableSpace) {
            return Result.failure(
                IllegalStateException(
                    "空间不足：镜像需要 ${total / (1024 * 1024)} MB，" +
                        "可用只有 ${layout.imagesDir.usableSpace / (1024 * 1024)} MB",
                ),
            )
        }

        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        // 每块检查一次取消：ContentResolver 的读流不会响应协程取消。
                        currentCoroutineContext().ensureActive()
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
                    if (total != null && total > 0 && copied != total) {
                        error("复制不完整：预期 $total 字节，实际 $copied 字节")
                    }
                    onProgress(copied, total ?: copied)
                }
            } ?: error("无法读取所选文件")
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success(target)
        } catch (c: CancellationException) {
            part.delete()
            throw c
        } catch (t: Throwable) {
            part.delete()
            Result.failure(t)
        }
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
    suspend fun importFirmware(uri: Uri): Result<File> {
        val raw = queryName(uri) ?: "QEMU_EFI.fd"
        val named = if (raw.endsWith(".fd", ignoreCase = true)) raw else "$raw.fd"
        val safe = named.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val target = File(layout.firmwareDir, safe)
        val part = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().buffered().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val buffer = ByteArray(1 shl 20)
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: error("无法读取所选固件")
            val size = part.length()
            if (size < 256 * 1024) {
                part.delete()
                error("文件只有 $size 字节，不像是 pflash 固件")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success(target)
        } catch (c: CancellationException) {
            part.delete()
            throw c
        } catch (t: Throwable) {
            part.delete()
            Result.failure(t)
        }
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
