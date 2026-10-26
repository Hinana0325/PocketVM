package dev.pocketvm

import dev.pocketvm.runtime.AnsiText
import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiTextTest {

    @Test
    fun `strips color and cursor sequences`() {
        val raw = "\u001B[1;32mubuntu@vm\u001B[0m:~$ \u001B[Kls\r\n"
        assertEquals("ubuntu@vm:~$ ls\n", AnsiText.strip(raw))
    }

    @Test
    fun `strips window title written as OSC`() {
        assertEquals("ready", AnsiText.strip("\u001B]0;root@vm: ~\u0007ready"))
        assertEquals("ready", AnsiText.strip("\u001B]2;title\u001B\\ready"))
    }

    @Test
    fun `keeps chinese text and normalizes line endings`() {
        assertEquals("密码：\n失败\n", AnsiText.strip("\u001B[31m密码：\u001B[0m\r\n失败\r\n"))
    }
}
