package dev.pocketvm.runtime

/**
 * 串口控制台输入的纯规则：空输入不发送、长度封顶、按需附加回车。
 * 与设备无关，便于单测。
 */
object SerialConsoleInput {

    const val MAX_CHARS = 500

    /** 返回要写入串口的字节串；空输入返回 null（不发送）。 */
    fun payload(text: String, appendEnter: Boolean): String? {
        if (text.isEmpty()) return null
        val capped = text.take(MAX_CHARS)
        return if (appendEnter) "$capped\r" else capped
    }
}
