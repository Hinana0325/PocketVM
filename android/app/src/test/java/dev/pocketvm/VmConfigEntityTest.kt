package dev.pocketvm

import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.PortForward
import dev.pocketvm.core.model.StorageController
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.data.VmConfigConverters
import dev.pocketvm.data.VmConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2 数据层：Entity ↔ VmConfig 映射的纯 JVM 测试。
 *
 * 不碰 Room 数据库本身（建库要 Android Context，纯 JVM 测试跑不起来，
 * 由 CI 的构建 + 真机验证覆盖）；只测映射逻辑：字段全量往返、枚举回退、
 * JSON 列表解析失败回退。
 */
class VmConfigEntityTest {

    private fun fullConfig() = VmConfig(
        id = "vm-full",
        name = "Win11 ARM64",
        platform = VmPlatform.APPLE_T8030,
        cpuCores = 4,
        memoryMb = 3072,
        diskSizeGb = 64,
        isoPath = "/storage/iso/win11.iso",
        driverIsoPath = "/storage/iso/virtio-win.iso",
        firmwareCodePath = "/storage/fw/QEMU_EFI.fd",
        bootFromCd = false,
        vncPort = 5901,
        vncAccess = VncAccess.LAN,
        vncPassword = "secret12",
        storageController = StorageController.VIRTIO,
        cdTransport = CdTransport.VIRTIO_SCSI,
        usbInput = false,
        usbDisabled = true,
        displayDevice = DisplayDevice.VIRTIO_GPU,
        serialRecovery = false,
        enableNet = false,
        portForwards = listOf(PortForward(3389, 3389), PortForward(2222, 22)),
        rng = true,
        useBootImage = true,
        cpuModel = "cortex-a76",
        launchMode = LaunchMode.COMPAT,
        extraArgs = listOf("-rtc", "base=localtime"),
        createdAtEpochMs = 1_791_234_567_890L,
    )

    @Test
    fun `full config round-trips through the entity`() {
        val config = fullConfig()
        assertEquals(config, VmConfigEntity.fromVmConfig(config).toVmConfig())
    }

    @Test
    fun `default config round-trips through the entity`() {
        val config = VmConfig.create("Windows 11 ARM64")
        assertEquals(config, VmConfigEntity.fromVmConfig(config).toVmConfig())
    }

    @Test
    fun `nullable fields survive the round-trip as null`() {
        val config = VmConfig.create("x").copy(
            isoPath = null,
            driverIsoPath = null,
            firmwareCodePath = null,
            vncPassword = null,
            enableNet = null,
            rng = null,
            cpuModel = null,
        )
        val restored = VmConfigEntity.fromVmConfig(config).toVmConfig()
        assertEquals(config, restored)
        assertNull(restored.isoPath)
        assertNull(restored.enableNet)
        assertNull(restored.rng)
    }

    @Test
    fun `unknown enum names fall back to defaults instead of throwing`() {
        assertEquals(VmPlatform.UEFI_ARM64, VmConfigConverters.platformFromString("FUTURE_PLATFORM"))
        assertEquals(VncAccess.LOOPBACK, VmConfigConverters.vncAccessFromString("FUTURE_ACCESS"))
        assertEquals(StorageController.NVME, VmConfigConverters.storageControllerFromString("FUTURE_CTRL"))
        assertEquals(CdTransport.AUTO, VmConfigConverters.cdTransportFromString("FUTURE_BUS"))
        assertEquals(DisplayDevice.RAMFB, VmConfigConverters.displayDeviceFromString("FUTURE_DISPLAY"))
        assertEquals(LaunchMode.AUTO, VmConfigConverters.launchModeFromString("FUTURE_MODE"))
    }

    @Test
    fun `malformed json lists fall back to empty instead of throwing`() {
        assertEquals(emptyList<PortForward>(), VmConfigConverters.portForwardsFromJson("not json"))
        assertEquals(emptyList<PortForward>(), VmConfigConverters.portForwardsFromJson(""))
        assertEquals(emptyList<String>(), VmConfigConverters.stringListFromJson("{bad"))
        assertEquals(emptyList<String>(), VmConfigConverters.stringListFromJson(""))
    }

    @Test
    fun `port forwards and extra args round-trip as json`() {
        val forwards = listOf(PortForward(8080, 80), PortForward(2222, 22))
        assertEquals(
            forwards,
            VmConfigConverters.portForwardsFromJson(VmConfigConverters.portForwardsToJson(forwards)),
        )
        val args = listOf("-cpu", "max", "-smp", "4")
        assertEquals(
            args,
            VmConfigConverters.stringListFromJson(VmConfigConverters.stringListToJson(args)),
        )
    }
}
