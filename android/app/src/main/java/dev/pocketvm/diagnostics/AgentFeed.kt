package dev.pocketvm.diagnostics

import android.util.Log
import java.io.File

/**
 * 给外部工具用的调试通道。
 *
 * 存在的理由：这个 App 原本一个字都不写 logcat，而 QEMU 的 `run-*.log` 在正常
 * 启动时也是空的 —— 于是一个卡死的 QEMU 和一个健康的 QEMU 在设备外部看起来
 * 完全一样。真机上确实撞到过：进程活着、5900 在 LISTEN、TCP 能完成握手，但
 * 主循环已经不再 accept，也不再响应 QMP。当时唯一能区分两者的办法是手工去读
 * `/proc/<pid>/stat`。这条通道就是把这个动作变成产品的一部分。
 *
 * 两路输出，各有用途：
 *
 *  - logcat，固定 tag [TAG]：`adb logcat -s PocketVM:*` 可以实时流式观察；
 *  - 磁盘上的 NDJSON（每行一个 JSON 对象）：结构化、能被 grep/jq 直接吃、
 *    App 崩溃后依然留在盘上，且位于外部存储的应用目录下，`adb shell`
 *    无需 root 就能读。
 *
 * 事件名用 `域.动作`（`session.start` / `attempt.failed` / `health`），字段用
 * 简短小写键。值只放标量：集合一律先展平成逗号分隔字符串，外部解析器不需要
 * 递归下降。
 */
object AgentFeed {

    const val TAG = "PocketVM"

    /** 单文件上限，超过就轮转一代；通道要有界，不能吃用户的存储。 */
    private const val MAX_BYTES = 1L * 1024 * 1024

    private val lock = Any()
    private var sink: File? = null

    /** 落盘位置由 [dev.pocketvm.storage.VmStorageLayout] 决定，本类只负责写。 */
    fun attach(target: File) {
        runCatching { target.parentFile?.mkdirs() }
        synchronized(lock) { sink = target }
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val text = line(name, fields.toList(), System.currentTimeMillis())
        // logcat 用同一个 NDJSON 文本：一套格式，两边都能被机器解析。
        Log.i(TAG, text)
        synchronized(lock) { append(text) }
    }

    private fun append(text: String) {
        val target = sink ?: return
        runCatching {
            if (target.length() > MAX_BYTES) {
                val rotated = File(target.absolutePath + ".1")
                rotated.delete()
                target.renameTo(rotated)
            }
            target.appendText(text + "\n")
        }
    }

    /**
     * 一行 NDJSON。纯函数、不碰 Android，便于单测。
     *
     * 时间戳是给工具用的：外部要对齐设备时钟和主机时钟，只能靠它。
     */
    internal fun line(name: String, fields: List<Pair<String, Any?>>, epochMs: Long): String {
        val sb = StringBuilder(128)
        sb.append('{')
        appendField(sb, "t", epochMs, first = true)
        appendField(sb, "event", name, first = false)
        for ((key, value) in fields) appendField(sb, key, value, first = false)
        sb.append('}')
        return sb.toString()
    }

    private fun appendField(sb: StringBuilder, key: String, value: Any?, first: Boolean) {
        if (!first) sb.append(',')
        sb.append('"').append(escape(key)).append("\":")
        when (value) {
            null -> sb.append("null")
            // 数字与布尔直接落字面量，外部不需要再做类型转换。
            is Number, is Boolean -> sb.append(value.toString())
            is Iterable<*> -> sb.append('"').append(escape(value.joinToString(","))).append('"')
            else -> sb.append('"').append(escape(value.toString())).append('"')
        }
    }

    private fun escape(raw: String): String {
        if (raw.none { it == '"' || it == '\\' || it < ' ' }) return raw
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        return sb.toString()
    }
}