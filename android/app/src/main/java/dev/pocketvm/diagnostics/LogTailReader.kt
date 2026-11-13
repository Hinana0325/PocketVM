package dev.pocketvm.diagnostics

import java.io.File
import java.io.RandomAccessFile

/**
 * 增量式日志尾部读取器。
 *
 * UI 每秒轮询日志（`VmViewModel` 的 1s tick），而 [LogTail.read] 每次都从文件尾部读满
 * maxBytes 再整段解码。QEMU 的 `run-*.log` 与串口日志在装机和长时间运行时会涨到几十
 * MB，这个固定开销会一直存在，即使日志早已停止输出。
 *
 * 这个类按文件偏移做增量：
 * - 内容没变（`length == offset`）→ 直接返回上次的文本，**不碰磁盘**；
 * - 有新增 → 只读 `[offset, length)` 这一段，缓冲仍然是 maxBytes 的尾部窗口；
 * - 换文件（新 run）或文件变短（截断/轮转）→ 自动重置。
 *
 * 缓冲存的是**字节**而不是字符串，所以追加后整段重新解码时，上一次落在字符中间的
 * 那半个 UTF-8 序列会被后面的字节自然补齐（只有日志永久停止时才会留下一个替换符）。
 *
 * 一个实例对应一份文件；[read] 是线程安全的（UI 与调试接口可能并发调用）。
 */
class LogTailReader(private val maxBytes: Int = 64 * 1024) {

    private var path: String? = null
    private var offset = 0L
    private var tail = ByteArray(0)
    private var cachedText = ""
    private var cachedMaxLines = -1

    /** 累计从磁盘实际读取的字节数。测试用它证明「无变化时不读盘」。 */
    internal var totalBytesRead = 0L
        private set

    /**
     * 返回 [file] 的尾部文本，最多 [maxLines] 行。
     *
     * 文件不存在或读失败时返回空串（与 [LogTail.read] 的行为一致）。
     */
    @Synchronized
    fun read(file: File, maxLines: Int = 800): String {
        if (!file.isFile) {
            reset()
            return ""
        }

        val length = file.length()
        val currentPath = file.absolutePath
        if (currentPath != path || length < offset) {
            reset()
            path = currentPath
        }

        if (length != offset) {
            val read = readFrom(file, length)
            if (read > 0) {
                totalBytesRead += read
                offset = length
                // 尾部变了，之前缓存的文本作废（maxLines 相同也要重新解码）。
                cachedMaxLines = -1
            }
        }

        if (cachedMaxLines == maxLines) return cachedText

        // dropLastWhile：文件以换行结尾时 lines() 会多出一个空串，takeLast 会把它
        // 算作一行，导致「要 5 行只给 4 行」。先去空串再截断。
        cachedText = String(tail, Charsets.UTF_8)
            .lines()
            .dropLastWhile { it.isEmpty() }
            .takeLast(maxLines)
            .joinToString("\n")
        cachedMaxLines = maxLines
        return cachedText
    }

    /** 丢掉所有状态。下次 [read] 会重新取尾部窗口。 */
    @Synchronized
    fun reset() {
        path = null
        offset = 0L
        tail = ByteArray(0)
        cachedText = ""
        cachedMaxLines = -1
    }

    /**
     * 只读新增部分，返回读到的字节数；失败返回 -1（保持旧状态，下次再试）。
     *
     * 如果一秒钟内写了超过 maxBytes，说明中间的旧内容已经没有展示价值，直接以
     * 尾部窗口覆盖缓冲，避免缓冲无限增长。
     */
    private fun readFrom(file: File, length: Long): Long {
        val from = maxOf(offset, length - maxBytes)
        val count = (length - from).toInt()
        if (count <= 0) return 0

        val buffer = ByteArray(count)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                raf.readFully(buffer)
            }
        } catch (_: Throwable) {
            return -1
        }

        // from > offset 说明中间有一段被跳过了，旧缓冲不再连续，直接替换。
        // 首次读或跳过时，起点可能落在某一行中间，先丢到下一个换行，避免 UI 上
        // 第一行显示成半截。
        tail = if (from <= offset) {
            tail + buffer
        } else {
            skipPartialFirstLine(buffer)
        }
        if (tail.size > maxBytes) {
            tail = trimFromLineBoundary(tail, maxBytes)
        }
        return count.toLong()
    }

    /** 丢掉开头那段不完整的行（从任意字节处切入时用）。全无换行则原样返回。 */
    private fun skipPartialFirstLine(bytes: ByteArray): ByteArray {
        var index = 0
        while (index < bytes.size && bytes[index] != NEWLINE) index++
        if (index >= bytes.size) return bytes
        return bytes.copyOfRange(index + 1, bytes.size)
    }

    /**
     * 把缓冲裁到最多 [limit] 字节，并尽量从行首开始——否则第一行会是半截，
     * UI 上看着像日志损坏。整份都是同一行（没有换行）时只能从字节处截断。
     */
    private fun trimFromLineBoundary(bytes: ByteArray, limit: Int): ByteArray {
        val cut = bytes.size - limit
        if (cut <= 0) return bytes
        var from = cut
        while (from < bytes.size && bytes[from] != NEWLINE) from++
        if (from >= bytes.size) return bytes.copyOfRange(cut, bytes.size)
        return bytes.copyOfRange(from + 1, bytes.size)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
