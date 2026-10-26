package dev.pocketvm.runtime

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.pocketvm.core.model.PortForward
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream

/** SSH 控制台的连接状态。密码只活在 [SshConsole] 的私有字段里，从不进状态流。 */
sealed interface SshState {
    data object Disconnected : SshState
    data object Connecting : SshState
    data class Connected(val host: String, val port: Int, val user: String) : SshState
}

object SshDefaults {
    /** 客户机里 sshd 的标准端口。 */
    const val GUEST_SSH_PORT = 22

    /**
     * 从端口转发里找「主机端口 → 客户机 22」这一条。
     * QEMU 用户网络下 App 只能连云主机侧，所以必须靠 hostfwd。
     */
    fun hostPort(portForwards: List<PortForward>): Int? =
        portForwards.firstOrNull { it.guestPort == GUEST_SSH_PORT }?.hostPort

    /** @return 中文错误；null 表示可以发起连接。 */
    fun validate(host: String, port: Int, user: String): String? = when {
        host.isBlank() -> "主机不能为空"
        port !in 1..65535 -> "端口要在 1–65535 之间"
        user.isBlank() -> "用户名不能为空"
        else -> null
    }
}

/**
 * 一条 SSH 会话上的命令执行器。
 *
 * 只做「连接 + 逐条执行命令」，不模拟终端：客户机里的 sshd 负责 PTY，这里
 * 用 exec 通道拿输出，UI 侧当只读控制台。密码不落盘、不进 UiState；
 * 主机密钥不校验（目标是本机 QEMU 用户网络经 hostfwd 暴露的 127.0.0.1，
 * 没有可维护的 known_hosts）。
 */
class SshConsole {

    private val _state = MutableStateFlow<SshState>(SshState.Disconnected)
    val state: StateFlow<SshState> = _state.asStateFlow()

    private var session: Session? = null

    private var shell: ChannelShell? = null
    private var shellInput: OutputStream? = null

    /** 阻塞调用，放在 IO 线程上。@return 中文错误；null 表示已连上。 */
    fun connect(host: String, port: Int, user: String, password: String): String? {
        disconnect()
        _state.value = SshState.Connecting
        return try {
            val jsch = JSch()
            val s = jsch.getSession(user, host, port)
            s.setPassword(password)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setServerAliveInterval(10_000)
            s.setServerAliveCountMax(2)
            s.connect(10_000)
            session = s
            _state.value = SshState.Connected(host, port, user)
            null
        } catch (t: Throwable) {
            session = null
            _state.value = SshState.Disconnected
            describe(t)
        }
    }

    /**
     * 执行一条命令，输出按块回调（回调发生在调用线程）。
     *
     * @return 远端退出码；连接已断或通道建立失败时为 null（错误已写进输出流）。
     */
    fun run(command: String, onChunk: (String) -> Unit): Int? {
        val s = session ?: return null
        var channel: ChannelExec? = null
        return try {
            channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setErrStream(
                object : OutputStream() {
                    override fun write(b: Int) = onChunk(String(byteArrayOf(b.toByte()), Charsets.UTF_8))
                    override fun write(b: ByteArray, off: Int, len: Int) =
                        onChunk(String(b, off, len, Charsets.UTF_8))
                },
                true,
            )
            val input = channel.inputStream
            channel.connect(10_000)
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) onChunk(String(buf, 0, n, Charsets.UTF_8))
            }
            channel.exitStatus
        } catch (t: Throwable) {
            onChunk("\n[SSH] " + describe(t) + "\n")
            null
        } finally {
            runCatching { channel?.disconnect() }
        }
    }

    fun disconnect() {
        closeShell()
        runCatching { session?.disconnect() }
        session = null
        if (_state.value !is SshState.Disconnected) _state.value = SshState.Disconnected
    }

    /**
     * 打开一个 PTY 交互 shell（xterm-256color）。
     *
     * 输出在后台线程里逐块回调（调用方自行剥 ANSI、追加到控制台）；远端或本地
     * 任何一方关闭通道时回调 [onClosed]。sudo 的密码提示、vim 之外的普通交互
     * 命令都能用，但不做完整终端仿真。
     */
    fun openShell(onOutput: (String) -> Unit, onClosed: () -> Unit): String? {
        val s = session ?: return "先连接 SSH"
        closeShell()
        return try {
            val channel = s.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color")
            channel.setPtySize(160, 40, 0, 0)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(10_000)
            shell = channel
            shellInput = output
            val reader = Thread({
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) onOutput(String(buf, 0, n, Charsets.UTF_8))
                    }
                } catch (_: Throwable) {
                    // 本地关通道会让 read 抛异常，属于正常退出路径。
                } finally {
                    // 只有「当前这条 shell」关闭才回调；旧通道在重连时被换掉，
                    // 它的收尾不能把新 shell 的状态改成已关闭。
                    if (shell === channel) {
                        shell = null
                        shellInput = null
                        onClosed()
                    }
                }
            }, "pvm-ssh-shell")
            reader.isDaemon = true
            reader.start()
            null
        } catch (t: Throwable) {
            closeShell()
            describe(t)
        }
    }

    fun sendShellLine(line: String): Boolean {
        val out = shellInput ?: return false
        return runCatching {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            true
        }.getOrDefault(false)
    }

    fun sendShellCtrlC(): Boolean {
        val out = shellInput ?: return false
        return runCatching {
            out.write(3)
            out.flush()
            true
        }.getOrDefault(false)
    }

    fun closeShell() {
        runCatching { shell?.disconnect() }
        shell = null
        shellInput = null
    }

    fun isShellOpen(): Boolean = shell != null

    private fun describe(t: Throwable): String {
        val msg = t.message ?: t.toString()
        return when {
            msg.contains("Auth fail", true) || msg.contains("auth cancel", true) ->
                "认证失败：用户名/密码不对，或客户机的 sshd 禁止密码登录。"
            msg.contains("refused", true) ->
                "连接被拒绝：客户机里 sshd 没启动，或端口转发没有指向客户机 22。"
            msg.contains("timeout", true) || msg.contains("timed out", true) ->
                "连接超时：确认端口转发已生效、客户机 sshd 在监听。"
            msg.contains("UnknownHost", true) -> "主机名无法解析。"
            msg.contains("Connection reset", true) -> "连接被重置：客户机可能刚关机或 sshd 崩溃。"
            else -> "SSH 错误：$msg"
        }
    }
}
