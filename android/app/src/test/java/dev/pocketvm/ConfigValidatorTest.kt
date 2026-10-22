package dev.pocketvm

import dev.pocketvm.core.config.ConfigValidator
import dev.pocketvm.core.config.Severity
import dev.pocketvm.core.config.ValidationInput
import dev.pocketvm.core.model.VmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val validator = ConfigValidator()

    /** Reused across calls: a test may build several validation inputs. */
    private fun firmware(sizeBytes: Long = 64L * 1024 * 1024): File {
        val file = File(temp.root, "QEMU_EFI.fd")
        if (!file.exists()) setLength(file, sizeBytes)
        return file
    }

    private fun iso(): File = temp.newFile("win11-arm64.iso").apply { writeText("stub") }

    private fun config(
        memoryMb: Int = 4096,
        cpuCores: Int = 4,
        diskSizeGb: Int = 64,
        isoPath: String? = null,
        extraArgs: List<String> = emptyList(),
        vncPort: Int = 5900,
        vncAccess: dev.pocketvm.core.model.VncAccess = dev.pocketvm.core.model.VncAccess.LOOPBACK,
        vncPassword: String? = null,
    ) = VmConfig(
        id = "vm-1",
        name = "Windows 11 ARM64",
        cpuCores = cpuCores,
        memoryMb = memoryMb,
        diskSizeGb = diskSizeGb,
        isoPath = isoPath,
        extraArgs = extraArgs,
        vncPort = vncPort,
        vncAccess = vncAccess,
        vncPassword = vncPassword,
    )

    private fun input(
        config: VmConfig = config(),
        firmwareCode: File? = firmware(),
        firmwareVars: File? = null,
        isoFile: File? = null,
        writable: Boolean = true,
        deviceMemoryMb: Long = 12_288,
        freeSpaceBytes: Long = 64L * 1024 * 1024 * 1024,
        qemuAvailable: Boolean = true,
    ) = ValidationInput(
        config = config,
        firmwareCode = firmwareCode,
        firmwareVars = firmwareVars,
        isoFile = isoFile,
        diskParentWritable = writable,
        deviceMemoryMb = deviceMemoryMb,
        freeSpaceBytes = freeSpaceBytes,
        qemuAvailable = qemuAvailable,
    )

    private fun codes(input: ValidationInput) = validator.validate(input).issues.map { it.code }

    @Test
    fun `healthy configuration has no errors`() {
        val report = validator.validate(input())
        assertFalse(report.hasErrors)
        assertTrue(report.issues.isEmpty() || report.issues.all { it.severity == Severity.WARNING })
    }

    @Test
    fun `missing qemu blocks startup`() {
        val report = validator.validate(input(qemuAvailable = false))
        assertTrue(report.errors.any { it.code == "QEMU_MISSING" })
    }

    @Test
    fun `memory above three quarters of device ram is rejected`() {
        val report = validator.validate(input(config = config(memoryMb = 8192), deviceMemoryMb = 8192))
        assertTrue(report.errors.any { it.code == "MEM_EXCEEDS_DEVICE" })
    }

    @Test
    fun `memory below windows recommendation is only a warning`() {
        val report = validator.validate(input(config = config(memoryMb = 2048)))
        assertFalse(report.hasErrors)
        assertTrue(report.warnings.any { it.code == "MEM_BELOW_WINDOWS_RECOMMENDED" })
    }

    @Test
    fun `missing firmware is an error`() {
        val report = validator.validate(input(firmwareCode = null))
        assertTrue(report.errors.any { it.code == "FIRMWARE_MISSING" })
    }

    @Test
    fun `firmware smaller than a pflash image is rejected`() {
        val tiny = temp.newFile("tiny.fd").apply { writeText("not firmware") }
        val report = validator.validate(input(firmwareCode = tiny))
        assertTrue(report.errors.any { it.code == "FIRMWARE_IMPLAUSIBLE" })
    }

    @Test
    fun `vars size must match firmware size`() {
        val code = firmware(64L * 1024 * 1024)
        val vars = temp.newFile("vars.fd").apply { setLength(this, 2L * 1024 * 1024) }
        val report = validator.validate(input(firmwareCode = code, firmwareVars = vars))
        assertTrue(report.errors.any { it.code == "FIRMWARE_VARS_SIZE_MISMATCH" })
    }

    @Test
    fun `missing iso downgrades to a warning`() {
        val report = validator.validate(input())
        assertFalse(report.hasErrors)
        assertTrue(codes(input()).contains("ISO_MISSING"))
    }

    @Test
    fun `iso path that no longer exists is an error`() {
        val missing = File(temp.root, "gone.iso")
        val report = validator.validate(input(config = config(isoPath = missing.absolutePath), isoFile = missing))
        assertTrue(report.errors.any { it.code == "ISO_NOT_FOUND" })
    }

    @Test
    fun `unwritable vm directory blocks startup`() {
        val report = validator.validate(input(writable = false))
        assertTrue(report.errors.any { it.code == "DISK_NOT_WRITABLE" })
    }

    @Test
    fun `overriding the qmp channel is flagged`() {
        val report = validator.validate(input(config = config(extraArgs = listOf("-qmp", "tcp:0.0.0.0:4444"))))
        assertTrue(report.warnings.any { it.code == "RESERVED_ARG" })
    }

    @Test
    fun `eight vcpus are allowed but warned about`() {
        val report = validator.validate(input(config = config(cpuCores = 8)))
        assertFalse(report.hasErrors)
        assertTrue(report.warnings.any { it.code == "CPU_TCG_SCALING" })
    }

    @Test
    fun `zero cores is rejected`() {
        val report = validator.validate(input(config = config(cpuCores = 0)))
        assertEquals(true, report.errors.any { it.code == "CPU_RANGE" })
    }

    @Test
    fun `lan access without a password is an error`() {
        val report = validator.validate(
            input(config = config(vncAccess = dev.pocketvm.core.model.VncAccess.LAN, vncPassword = null)),
        )
        assertTrue(report.errors.any { it.code == "VNC_LAN_NO_PASSWORD" })
    }

    @Test
    fun `lan access with a password passes`() {
        val report = validator.validate(
            input(config = config(vncAccess = dev.pocketvm.core.model.VncAccess.LAN, vncPassword = "secret12")),
        )
        assertFalse(report.errors.any { it.code == "VNC_LAN_NO_PASSWORD" })
    }

    @Test
    fun `port below 5900 has no valid vnc display and is an error`() {
        val report = validator.validate(input(config = config(vncPort = 5800)))
        assertTrue(report.errors.any { it.code == "VNC_PORT_INVALID" })
    }

    @Test
    fun `sharing a vnc port with another vm is a warning`() {
        val other = config().copy(id = "vm-2", name = "另一台", vncPort = 5900)
        val report = validator.validate(input(config = config(vncPort = 5900)).copy(otherVms = listOf(other)))
        assertTrue(report.warnings.any { it.code == "VNC_PORT_SHARED" })
        assertFalse(report.hasErrors)
    }

    @Test
    fun `zero free space is reported instead of silently ignored`() {
        val report = validator.validate(input(freeSpaceBytes = 0))
        assertTrue(report.warnings.any { it.code == "DISK_FREE_UNKNOWN" })
    }

    private companion object {
        fun setLength(file: File, length: Long) {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(length) }
        }
    }
}
