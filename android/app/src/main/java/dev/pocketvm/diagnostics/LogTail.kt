package dev.pocketvm.diagnostics

import java.io.File
import java.io.RandomAccessFile

object LogTail {

    /**
     * Reads the tail of a file that is still being written by another process.
     * `RandomAccessFile` is used instead of `File.readText()` so a 200 MB QEMU
     * log does not have to be loaded to show the last screen.
     */
    fun read(file: File, maxBytes: Int = 64 * 1024, maxLines: Int = 800): String {
        if (!file.isFile || file.length() == 0L) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = (length - maxBytes).coerceAtLeast(0)
                val buffer = ByteArray((length - start).toInt())
                raf.seek(start)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
                    .lines()
                    .takeLast(maxLines)
                    .joinToString("\n")
            }
        } catch (t: Throwable) {
            "读取日志失败：${t.message}"
        }
    }
}
