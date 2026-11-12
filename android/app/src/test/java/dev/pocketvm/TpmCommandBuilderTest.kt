package dev.pocketvm

import dev.pocketvm.runtime.FirmwareFiles
import dev.pocketvm.runtime.LaunchRequest
import dev.pocketvm.runtime.QemuCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QEMU 命令里的 TPM 参数（阶段 2）。纯 JVM，不启动任何进程。
 */
class TpmCommandBuilderTest {

    private val builder = QemuCommandBuilder()

    private fun request(
        enableTpm: Boolean = false,
        tpmSocketPath: String? = null,
    ) = LaunchRequest(
        executable = "/data/app/lib/arm64/libqemu-system-aarch64.so",
        firmware = FirmwareFiles("/firmware/QEMU_EFI.fd", "/vms/1/vars.fd"),
        diskPath = "/vms/1/disk.qcow2",
        qmpSocketPath = "/vms/1/qmp.sock",
        enableTpm = enableTpm,
        tpmSocketPath = tpmSocketPath,
    )

    private fun valueOf(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    @Test
    fun `tpm args appear only when enabled and socket present`() {
        val args = builder.build(request(enableTpm = true, tpmSocketPath = "/run/win11.tpm.sock"))
        assertEquals("socket,id=chrtpm,path=/run/win11.tpm.sock", valueOf(args, "-chardev"))
        assertEquals("emulator,id=tpm0,chardev=chrtpm", valueOf(args, "-tpmdev"))
        // -device 出现多次（ramfb 在前），不能用 valueOf 只取第一个。
        assertTrue(args.any { it == "tpm-tis-device,tpmdev=tpm0,ppi=on" })
    }

    @Test
    fun `enabled without socket adds no tpm args`() {
        val args = builder.build(request(enableTpm = true, tpmSocketPath = null))
        assertFalse(args.any { it.contains("chrtpm") || it.contains("tpm0") })
    }

    @Test
    fun `socket without enable adds no tpm args`() {
        val args = builder.build(request(enableTpm = false, tpmSocketPath = "/run/win11.tpm.sock"))
        assertFalse(args.any { it.contains("chrtpm") || it.contains("tpm0") })
    }

    @Test
    fun `tpm is off by default`() {
        val args = builder.build(request())
        assertFalse(args.any { it.contains("chrtpm") || it.contains("tpm0") })
    }

    @Test
    fun `machine is virt with acpi on and no acpi-off`() {
        val args = builder.build(request(enableTpm = true, tpmSocketPath = "/run/win11.tpm.sock"))
        assertEquals("virt,highmem=on,gic-version=3", valueOf(args, "-machine"))
        assertFalse(args.any { it.contains("acpi=off") })
    }
}
