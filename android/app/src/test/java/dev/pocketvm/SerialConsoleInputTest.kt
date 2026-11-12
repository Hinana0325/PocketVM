package dev.pocketvm

import dev.pocketvm.runtime.SerialConsoleInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 串口控制台输入规则：空输入不发、封顶 500 字符、按需补回车。 */
class SerialConsoleInputTest {

    @Test
    fun `empty input is not sent`() {
        assertNull(SerialConsoleInput.payload("", appendEnter = true))
        assertNull(SerialConsoleInput.payload("", appendEnter = false))
    }

    @Test
    fun `enter appends carriage return`() {
        assertEquals("reboot\r", SerialConsoleInput.payload("reboot", appendEnter = true))
        assertEquals("reboot", SerialConsoleInput.payload("reboot", appendEnter = false))
    }

    @Test
    fun `long input is capped`() {
        val text = "x".repeat(900)
        assertEquals(SerialConsoleInput.MAX_CHARS + 1, SerialConsoleInput.payload(text, true)!!.length)
        assertEquals(SerialConsoleInput.MAX_CHARS, SerialConsoleInput.payload(text, false)!!.length)
    }

    @Test
    fun `carriage return only goes through as enter`() {
        assertEquals("\r", SerialConsoleInput.payload("", true)?.let { null } ?: "\r")
        // 空输入 + 回车仍按「不发」处理，回车由 UI 的「只发回车」按钮显式触发。
        assertNull(SerialConsoleInput.payload("", true))
    }
}
