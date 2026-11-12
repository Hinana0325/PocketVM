package dev.pocketvm.gsi

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Android sparse image（`0xED26FF3A`）展开。
 *
 * SDK 的 system.img / vendor.img 常是 sparse 格式；GPT 拼装需要裸字节。
 * 只支持扩展（expand），不支持生成 sparse。CRC32 chunk 只跳过（我们不做校验，
 * 展开后的内容由内核/分区校验负责）。纯 JVM，便于单测。
 */
object SparseImage {

    const val MAGIC = 0xED26FF3A.toInt()
    private const val HEADER_BYTES = 28
    private const val CHUNK_HEADER_BYTES = 12
    private const val TYPE_RAW = 0xCAC1
    private const val TYPE_FILL = 0xCAC2
    private const val TYPE_DONT_CARE = 0xCAC3
    private const val TYPE_CRC32 = 0xCAC4

    data class Header(
        val blockSize: Int,
        val totalBlocks: Long,
        val totalChunks: Int,
    ) {
        val expandedBytes: Long get() = totalBlocks * blockSize
    }

    fun isSparse(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return file.inputStream().use { input ->
            val magic = readIntLe(readExact(input, 4))
            magic == MAGIC
        }
    }

    fun readHeader(file: File): Header? = file.inputStream().use { readHeader(it) }

    fun readHeader(input: InputStream): Header? = try {
        val bytes = readExact(input, HEADER_BYTES)
        if (readIntLe(bytes) != MAGIC) {
            null
        } else {
            Header(
                blockSize = readIntLe(bytes, 12),
                totalBlocks = readIntLe(bytes, 16).toLong() and 0xFFFFFFFFL,
                totalChunks = readIntLe(bytes, 20),
            )
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 把 sparse 流展开到 [sink]（顺序写）。返回写出的字节数。
     * [onProgress] 以「已展开字节」回调，便于 UI 显示。
     */
    fun expand(input: InputStream, sink: OutputStream, onProgress: ((Long) -> Unit)? = null): Long {
        val headerBytes = readExact(input, HEADER_BYTES)
        require(readIntLe(headerBytes) == MAGIC) { "不是 Android sparse 镜像" }
        val blockSize = readIntLe(headerBytes, 12)
        val totalChunks = readIntLe(headerBytes, 20)
        require(blockSize in 1..(1 shl 20)) { "sparse blockSize 非法：$blockSize" }

        var written = 0L
        repeat(totalChunks) {
            val chunk = readExact(input, CHUNK_HEADER_BYTES)
            val type = readShortLe(chunk, 0).toInt() and 0xFFFF
            val chunkBlocks = readIntLe(chunk, 4).toLong() and 0xFFFFFFFFL
            val bytes = chunkBlocks * blockSize
            when (type) {
                TYPE_RAW -> {
                    copyN(input, sink, bytes)
                    written += bytes
                }
                TYPE_FILL -> {
                    val pattern = readExact(input, 4)
                    writeRepeated(sink, pattern, bytes)
                    written += bytes
                }
                TYPE_DONT_CARE -> {
                    // 输出是顺序流，必须显式写零保持偏移正确。
                    writeZeros(sink, bytes)
                    written += bytes
                }
                TYPE_CRC32 -> readExact(input, 4) // 仅校验用，跳过
                else -> error("未知 sparse chunk 类型：0x${type.toString(16)}")
            }
            onProgress?.invoke(written)
        }
        return written
    }

    private fun copyN(input: InputStream, sink: OutputStream, count: Long) {
        val buffer = ByteArray(1 shl 20)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("sparse 数据提前结束")
            sink.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeRepeated(sink: OutputStream, pattern: ByteArray, count: Long) {
        val buffer = ByteArray(4 * 1024)
        for (i in buffer.indices) buffer[i] = pattern[i % 4]
        var remaining = count
        while (remaining > 0) {
            val n = minOf(buffer.size.toLong(), remaining).toInt()
            sink.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun writeZeros(sink: OutputStream, count: Long) {
        val buffer = ByteArray(4 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = minOf(buffer.size.toLong(), remaining).toInt()
            sink.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var off = 0
        while (off < count) {
            val read = input.read(out, off, count - off)
            if (read < 0) throw EOFException("sparse 文件提前结束（需要 $count 字节）")
            off += read
        }
        return out
    }

    private fun readIntLe(bytes: ByteArray, offset: Int = 0): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
}
