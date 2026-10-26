package dev.pocketvm.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Minimal QMP client over a Unix socket.
 *
 * QMP is how PocketVM stops a VM *gracefully* (ACPI power button) instead of
 * killing the process and corrupting the qcow2. It is also the only reliable way
 * to tell "guest is running" from "QEMU is still starting".
 */
class QmpClient(private val socketPath: String) : Closeable {

    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /** Serializes [execute]: concurrent callers would interleave writes on the wire. */
    private val commandLock = Any()

    /**
     * @param alive polled between attempts; when the child has already died there
     *   is no point waiting out [totalWaitMs] — the launch has failed and the
     *   fallback ladder should move on immediately.
     * @return true when the QMP greeting was received and capabilities negotiated.
     */
    fun connect(
        timeoutMs: Int = 3000,
        totalWaitMs: Long = 15_000,
        alive: () -> Boolean = { true },
    ): Boolean {
        val deadline = System.currentTimeMillis() + totalWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (tryConnect(timeoutMs)) {
                // A refused `qmp_capabilities` means every later command fails;
                // report that as a failed connect instead of a successful one.
                if (negotiate()) return true
                close()
            }
            if (!alive()) return false
            Thread.sleep(200)
        }
        return false
    }

    private fun tryConnect(timeoutMs: Int): Boolean = try {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        s.soTimeout = timeoutMs
        socket = s
        reader = BufferedReader(InputStreamReader(s.inputStream))
        writer = BufferedWriter(OutputStreamWriter(s.outputStream))
        // Greeting line: {"QMP": {"version": ..., "capabilities": []}}
        reader?.readLine() != null
    } catch (t: Throwable) {
        close()
        false
    }

    private fun negotiate(): Boolean {
        val response = execute("qmp_capabilities") ?: return false
        return !response.has("error")
    }

    fun execute(command: String, arguments: Map<String, Any?>? = null, timeoutMs: Int = 3000): JSONObject? =
        synchronized(commandLock) { executeLocked(command, arguments, timeoutMs) }

    private fun executeLocked(command: String, arguments: Map<String, Any?>?, timeoutMs: Int): JSONObject? {
        val payload = JSONObject().put("execute", command)
        if (!arguments.isNullOrEmpty()) {
            payload.put("arguments", JSONObject(arguments))
        }
        val out = writer ?: return null
        val input = reader ?: return null

        return try {
            socket?.soTimeout = timeoutMs
            out.write(payload.toString())
            out.write("\n")
            out.flush()

            var reply: JSONObject? = null
            while (reply == null) {
                val line = input.readLine() ?: break
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                // Asynchronous events (RESET, STOP, ...) may arrive before the reply.
                if (json.has("event")) continue
                if (json.has("return") || json.has("error")) reply = json
            }
            reply
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Arms VNC authentication after the handshake.
     *
     * RFB's VNC auth is DES with an 8-byte key, so anything past 8 characters is
     * silently dropped — truncate here instead of letting the user believe a
     * longer password protects them.
     */
    fun setVncPassword(password: String): Boolean {
        val key = password.take(8)
        if (key.isEmpty()) return false
        val modern = execute("set_password", mapOf("protocol" to "vnc", "password" to key))
        if (modern?.has("return") == true) return true
        // QEMU < 2.3 fallback: the legacy `change` command.
        val legacy = execute(
            "change",
            mapOf("device" to "vnc", "target" to "password", "arg" to key),
        )
        return legacy?.has("return") == true
    }

    /** ACPI shutdown request; the guest decides when to actually power off. */
    fun systemPowerdown(): Boolean = execute("system_powerdown")?.has("return") == true

    /**
     * Injects a keystroke, e.g. "ret" for Enter. Used to answer the Windows
     * "Press any key to boot from CD" prompt, which otherwise times out while
     * the user is still switching to their VNC client.
     */
    fun sendKey(qcode: String): Boolean = sendKeys(listOf(qcode))

    /**
     * 一次按下多个键（组合键，如 Ctrl+Alt+Del）。QMP 的 `send-key` 原生接受
     * qcode 列表，同一个请求里按下比逐键发送更接近真实按键。
     */
    fun sendKeys(qcodes: List<String>): Boolean {
        if (qcodes.isEmpty()) return false
        return execute(
            "send-key",
            mapOf(
                "keys" to qcodes.map { mapOf("type" to "qcode", "data" to it) },
                "hold-time" to 60,
            ),
            timeoutMs = 2000,
        )?.has("return") == true
    }

    /**
     * Types [text] one character at a time, via QMP `send-key`.
     *
     * Used for the UEFI Shell case: a phone keyboard in a VNC client is
     * miserable, so the app can type `fs0:\EFI\BOOT\BOOTAA64.EFI` for the user.
     * Only the US layout subset that appears in EFI paths is mapped; anything
     * else returns false rather than typing the wrong command.
     */
    fun typeText(text: String, holdMs: Int = 40, gapMs: Long = 55): Boolean {
        for (ch in text) {
            val mapping = qcodeOf(ch) ?: return false
            val keys = buildList {
                if (mapping.second) add(mapOf("type" to "qcode", "data" to "shift"))
                add(mapOf("type" to "qcode", "data" to mapping.first))
            }
            val ok = execute(
                "send-key",
                mapOf("keys" to keys, "hold-time" to holdMs),
                timeoutMs = 2000,
            )?.has("return") == true
            if (!ok) return false
            Thread.sleep(gapMs)
        }
        return true
    }

    /** qcode plus whether shift must be held; null when unmapped. */
    private fun qcodeOf(ch: Char): Pair<String, Boolean>? = when {
        ch in 'a'..'z' -> ch.toString() to false
        ch in 'A'..'Z' -> ch.lowercaseChar().toString() to true
        ch in '0'..'9' -> ch.toString() to false
        ch == ' ' -> "spc" to false
        ch == ':' -> "semicolon" to true
        ch == ';' -> "semicolon" to false
        ch == '\\' -> "backslash" to false
        ch == '/' -> "slash" to false
        ch == '.' -> "dot" to false
        ch == '-' -> "minus" to false
        ch == '_' -> "minus" to true
        ch == '\n' || ch == '\r' -> "ret" to false
        else -> null
    }

    /** Immediate exit. Safe only after the guest is down or unresponsive. */
    fun quit(): Boolean = execute("quit")?.has("return") == true

    fun queryStatus(): String? =
        execute("query-status")?.optJSONObject("return")?.optString("status")

    /**
     * Thread id per vCPU (QMP `query-cpus-fast`), ordered by cpu-index.
     *
     * The performance dashboard samples `/proc/<tid>/stat` for each vCPU thread,
     * and the mapping is stable for the lifetime of a run — so it is fetched
     * once, not on every sample. Empty when the command is unavailable; the
     * dashboard then shows the whole-process number only.
     */
    fun cpuThreadIds(): List<Int> {
        val reply = execute("query-cpus-fast") ?: return emptyList()
        val cpus = reply.optJSONArray("return") ?: return emptyList()
        return (0 until cpus.length()).mapNotNull { i ->
            cpus.optJSONObject(i)?.optInt("thread-id")?.takeIf { it > 0 }
        }
    }

    /**
     * Cumulative device I/O from QMP `query-blockstats`, summed over all nodes.
     *
     * Fallback for the dashboard's disk card: Android may deny `/proc/<pid>/io`
     * even to the app that owns the process. QMP is already open, and these are
     * the counters the guest's block layer actually moved — a different (and
     * arguably more honest) view than procfs block bytes.
     */
    fun blockIoBytes(): Pair<Long, Long>? {
        val reply = execute("query-blockstats") ?: return null
        val stats = reply.optJSONArray("return") ?: return null
        var read = 0L
        var write = 0L
        var seen = false
        for (i in 0 until stats.length()) {
            val node = stats.optJSONObject(i)?.optJSONObject("stats") ?: continue
            read += node.optLong("rd_bytes", 0L)
            write += node.optLong("wr_bytes", 0L)
            seen = true
        }
        return if (seen) read to write else null
    }

    override fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null
    }
}
