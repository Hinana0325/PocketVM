package dev.pocketvm

import dev.pocketvm.runtime.FailureHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureHintsTest {

    @Test
    fun `permission denied mentions both exec and socket paths`() {
        val hint = FailureHints.fromLog("/data/user/0/dev.pocketvm/files/qemu: Permission denied")
        assertTrue(hint!!.contains("执行层"))
        assertTrue(hint.contains("Unix socket"))
    }

    @Test
    fun `socket bind failure is called out as a fuse problem`() {
        val hint = FailureHints.fromLog(
            "qemu-system-aarch64: -qmp unix:/storage/emulated/0/x.sock,server=on,wait=off: " +
                "Failed to bind socket: Permission denied",
        )
        assertTrue(hint!!.contains("FUSE"))
    }

    @Test
    fun `killed process is reported as memory pressure`() {
        assertEquals(
            "进程被系统杀死（SIGKILL），通常是内存压力或省电策略。",
            FailureHints.fromLog("", 137),
        )
    }

    @Test
    fun `pflash size mismatch is detected`() {
        val hint = FailureHints.fromLog("qemu-system-aarch64: pflash invalid size 4194304")
        assertTrue(hint!!.contains("等长"))
    }

    @Test
    fun `pflash size mismatch names the stale 2 MiB image`() {
        val hint = FailureHints.fromLog(
            "libqemu-system-aarch64.so: cfi.pflash01 device '/machine/virt.flash0' " +
                "requires 67108864 bytes, pflash0 block backend provides 2097152 bytes",
        )
        assertTrue(hint!!.contains("64 MiB"))
    }

    @Test
    fun `clean exit has no hint`() {
        assertNull(FailureHints.fromLog("", 0))
    }

    @Test
    fun `first matching rule wins over a later generic one`() {
        val hint = FailureHints.fromLog(
            "Could not open '/images/win.iso': Permission denied\nNo such file or directory",
        )
        assertTrue(hint!!.contains("ISO"))
    }
}
