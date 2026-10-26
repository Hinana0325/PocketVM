package dev.pocketvm

import dev.pocketvm.runtime.FailureHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `SIGILL names both the usb stack and the old PAC bug`() {
        val hint = FailureHints.fromLog("", 132)
        assertTrue(hint!!.contains("禁用 USB"))
        assertTrue(hint.contains("PAC"))
        assertTrue(hint.contains("patch-qemu-pac.py"))
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

    /**
     * QEMU builds the VNC cipher while parsing `password=on`, and RFB 3.8
     * authenticates with DES. A build without a DES-capable backend therefore
     * never reaches machine init — the failure observed on the Xiaomi 14 Pro,
     * where the required fix is a different *access path*, not another profile.
     */
    @Test
    fun `vnc password without a DES backend points at loopback plus forwarding`() {
        val hint = FailureHints.fromLog(
            "libqemu-system-aarch64.so: -vnc 0.0.0.0:1,password=on: " +
                "Cipher backend does not support DES algorithm",
        )
        assertTrue(hint!!.contains("DES"))
        assertTrue(hint.contains("仅本机"))
        assertTrue(hint.contains("adb forward"))
    }

    @Test
    fun `rejected vnc password is config-fatal so the ladder stops early`() {
        assertTrue(
            FailureHints.isConfigFatal(
                "libqemu-system-aarch64.so: -vnc 0.0.0.0:1,password=on: " +
                    "Cipher backend does not support DES algorithm",
            ),
        )
    }

    /**
     * These are exactly what 兼容/最小 exist to work around, so they must keep the
     * ladder walking instead of aborting it.
     */
    @Test
    fun `profile-dependent failures are not config-fatal`() {
        assertFalse(FailureHints.isConfigFatal("qemu: unable to find CPU model 'cortex-a76'"))
        assertFalse(FailureHints.isConfigFatal("Could not allocate dynamic translator buffer"))
        assertFalse(FailureHints.isConfigFatal("Cannot allocate memory"))
        assertFalse(FailureHints.isConfigFatal("qemu: Could not initialize SDL"))
    }

    @Test
    fun `missing files and unusable sockets are config-fatal`() {
        assertTrue(
            FailureHints.isConfigFatal("qemu: -drive file=/x/disk.qcow2: No such file or directory"),
        )
        assertTrue(FailureHints.isConfigFatal("Failed to bind socket: Permission denied"))
        assertTrue(FailureHints.isConfigFatal("qemu: -L /x: Could not read keymap file"))
    }

    @Test
    fun `an empty log is never config-fatal`() {
        assertFalse(FailureHints.isConfigFatal(""))
    }
}
