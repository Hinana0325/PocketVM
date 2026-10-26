package dev.pocketvm.runtime

/**
 * 把 SSH PTY 输出里的 ANSI 控制序列去掉，只留可读文本。
 *
 * 交互式 shell 会发颜色、光标移动、窗口标题（OSC）和 \r 进度刷新；不做完整
 * 终端仿真，但把转义序列剥掉后，命令回显、sudo 提示、错误信息都能正常阅读。
 */
object AnsiText {

    /** CSI：ESC [ 参数 中间字节 终止字节（颜色、光标、清屏等）。 */
    private val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** OSC：ESC ] ... BEL 或 ESC \（窗口标题、超链接）。 */
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    fun strip(text: String): String =
        text.replace(osc, "")
            .replace(csi, "")
            .replace("\r\n", "\n")
            .replace("\r", "")
}
