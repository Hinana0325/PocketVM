package dev.pocketvm

import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.LaunchProfile
import dev.pocketvm.core.model.StorageController
import dev.pocketvm.runtime.FirmwareFiles
import dev.pocketvm.runtime.LaunchRequest
import dev.pocketvm.runtime.QemuCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuCommandBuilderTest {

    private val builder = QemuCommandBuilder()

    private fun request(
        iso: String? = "/images/win11-arm64.iso",
        vncPort: Int = 5900,
        cpuCores: Int = 4,
        memoryMb: Int = 3072,
        extraArgs: List<String> = emptyList(),
        name: String = "Windows 11 ARM64",
    ) = LaunchRequest(
        executable = "/data/app/lib/arm64/libqemu-system-aarch64.so",
        firmware = FirmwareFiles("/firmware/QEMU_EFI.fd", "/vms/1/vars.fd"),
        diskPath = "/vms/1/disk.qcow2",
        isoPath = iso,
        cpuCores = cpuCores,
        memoryMb = memoryMb,
        vncPort = vncPort,
        qmpSocketPath = "/vms/1/qmp.sock",
        vmName = name,
        extraArgs = extraArgs,
    )

    private fun valueOf(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    @Test
    fun `first argument is the executable`() {
        val args = builder.build(request())
        assertEquals("/data/app/lib/arm64/libqemu-system-aarch64.so", args.first())
    }

    @Test
    fun `uefi uses two pflash drives with read-only code`() {
        val args = builder.build(request())
        val drives = args.filterIndexed { index, value -> index > 0 && args[index - 1] == "-drive" }
        assertTrue(drives.any { it.contains("if=pflash") && it.contains("unit=0") && it.contains("readonly=on") })
        assertTrue(drives.any { it.contains("if=pflash") && it.contains("unit=1") && !it.contains("readonly=on") })
    }

    @Test
    fun `highmem is enabled for guests above one gigabyte`() {
        assertEquals("virt,highmem=on", valueOf(builder.build(request(memoryMb = 4096)), "-machine"))
    }

    @Test
    fun `vnc argument is a display index not a port`() {
        assertEquals("127.0.0.1:0", valueOf(builder.build(request(vncPort = 5900)), "-vnc"))
        assertEquals("127.0.0.1:1", valueOf(builder.build(request(vncPort = 5901)), "-vnc"))
    }

    @Test
    fun `lan mode binds all interfaces and keeps password flag`() {
        val args = builder.build(
            request().copy(vncBindAddress = "0.0.0.0", vncPasswordProtected = true),
        )
        assertEquals("0.0.0.0:0,password=on", valueOf(args, "-vnc"))
    }

    @Test
    fun `loopback mode never exposes a passwordless lan socket`() {
        val args = builder.build(request())
        assertEquals("127.0.0.1:0", valueOf(args, "-vnc"))
    }

    @Test
    fun `qmp control channel points at the vm socket`() {
        val args = builder.build(request())
        assertEquals("unix:/vms/1/qmp.sock,server=on,wait=off", valueOf(args, "-qmp"))
        assertEquals("none", valueOf(args, "-monitor"))
    }

    @Test
    fun `default storage is nvme plus a usb cdrom`() {
        val args = builder.build(request())
        assertTrue("磁盘必须是 NVMe，安装程序才有原生驱动", args.any { it.startsWith("nvme,drive=disk0") })
        assertTrue("光盘必须走 USB，安装程序才有原生驱动", args.any { it.startsWith("usb-storage,drive=cd0") })
        assertFalse("NVMe 模式下不应出现 virtio-blk", args.any { it.startsWith("virtio-blk-pci") })
        assertEquals("order=d", valueOf(args, "-boot"))
    }

    @Test
    fun `virtio mode keeps virtio-blk and scsi cdrom`() {
        val args = builder.build(request().copy(storageController = StorageController.VIRTIO))
        assertTrue(args.any { it.startsWith("virtio-blk-pci,drive=disk0") })
        assertTrue(args.any { it.startsWith("scsi-cd,drive=cd0") })
        assertFalse(args.any { it.startsWith("nvme,") })
    }

    @Test
    fun `without iso the disk boots and no cdrom is attached`() {
        val args = builder.build(request(iso = null))
        assertFalse(args.any { it.startsWith("scsi-cd") || it.startsWith("usb-storage") })
        assertEquals("order=c", valueOf(args, "-boot"))
    }

    @Test
    fun `disk format follows the provisioner`() {
        val args = builder.build(request().copy(diskFormat = "raw"))
        assertTrue(args.any { it.contains("format=raw") && it.contains("id=disk0") })
    }

    @Test
    fun `cpu and memory are clamped to a sane range`() {
        val args = builder.build(request(cpuCores = 16, memoryMb = 128))
        assertEquals("8", valueOf(args, "-smp"))
        assertEquals("512", valueOf(args, "-m"))
    }

    @Test
    fun `newlines in the vm name cannot break the argument list`() {
        val args = builder.build(request(name = "line1\nline2"))
        assertEquals("line1 line2", valueOf(args, "-name"))
    }

    @Test
    fun `extra args are appended verbatim at the end`() {
        val args = builder.build(request(extraArgs = listOf("-device", "usb-kbd")))
        assertEquals(listOf("-device", "usb-kbd"), args.takeLast(2))
    }

    @Test
    fun `data dir is only passed when available`() {
        assertFalse(builder.build(request()).contains("-L"))
        assertTrue(builder.build(request().copy(dataDir = "/files/qemu/share")).contains("-L"))
    }

    @Test
    fun `commas in file paths are escaped for the qemu option parser`() {
        val args = builder.build(
            request(iso = "/images/win11, arm64.iso").copy(diskPath = "/vms/1/disk,v2.qcow2"),
        )
        assertTrue(args.any { it.contains("file=/images/win11,, arm64.iso") })
        assertTrue(args.any { it.contains("file=/vms/1/disk,,v2.qcow2") })
    }

    @Test
    fun `display index is the exact port offset with no silent clamping`() {
        assertEquals(0, builder.displayIndex(5900))
        assertEquals(99, builder.displayIndex(5999))
        // Below 5900 is rejected by the validator; the builder must not clamp
        // it back to 5900 or the manifest would describe a different listener.
        assertEquals(-100, builder.displayIndex(5800))
    }

    @Test
    fun `standard profile uses multithreaded tcg and max cpu`() {
        val args = builder.build(request().copy(profile = LaunchProfile.STANDARD))
        assertEquals("tcg,thread=multi,tb-size=512", valueOf(args, "-accel"))
        assertEquals("max", valueOf(args, "-cpu"))
        assertTrue(args.contains("-object"))
        assertTrue(args.any { it.startsWith("virtio-net-pci") })
    }

    @Test
    fun `compat profile shrinks the code cache and drops rng`() {
        val args = builder.build(request().copy(profile = LaunchProfile.COMPAT))
        assertEquals("tcg,thread=single,tb-size=128", valueOf(args, "-accel"))
        assertEquals("cortex-a76", valueOf(args, "-cpu"))
        assertFalse("兼容模式不挂 rng", args.contains("rng-random,filename=/dev/urandom,id=rng0"))
        assertTrue("网络保留", args.any { it.startsWith("virtio-net-pci") })
    }

    @Test
    fun `minimal profile drops networking too`() {
        val args = builder.build(request().copy(profile = LaunchProfile.MINIMAL))
        assertEquals("tcg,thread=single,tb-size=64", valueOf(args, "-accel"))
        assertEquals("cortex-a76", valueOf(args, "-cpu"))
        assertFalse(args.contains("-netdev"))
        assertFalse(args.any { it.startsWith("virtio-net-pci") })
    }

    @Test
    fun `profiles never change the disk controller`() {
        // Switching the controller would make an installed Windows unbootable,
        // so a fallback must only touch acceleration/CPU/optional devices.
        LaunchProfile.entries.forEach { profile ->
            val args = builder.build(request().copy(profile = profile))
            assertTrue("$profile 丢了 NVMe", args.any { it.startsWith("nvme,drive=disk0") })
        }
    }

    @Test
    fun `auto mode ladder escalates from standard to a media-less last resort`() {
        assertEquals(
            listOf(
                LaunchProfile.STANDARD,
                LaunchProfile.COMPAT,
                LaunchProfile.MINIMAL,
                LaunchProfile.MINIMAL_NO_MEDIA,
            ),
            LaunchProfile.ladder(LaunchMode.AUTO),
        )
        // Pinning 最小 still allows dropping the CD as a last step.
        assertEquals(
            listOf(LaunchProfile.MINIMAL, LaunchProfile.MINIMAL_NO_MEDIA),
            LaunchProfile.ladder(LaunchMode.MINIMAL),
        )
    }

    @Test
    fun `media-less profile attaches no cdrom at all`() {
        val args = builder.build(request().copy(profile = LaunchProfile.MINIMAL_NO_MEDIA))
        assertFalse("不应再有光驱", args.any { it.startsWith("usb-storage,drive=cd0") })
        assertFalse(args.any { it.contains("id=cd0") })
        assertEquals("order=c", valueOf(args, "-boot"))
        assertTrue("磁盘仍在", args.any { it.startsWith("nvme,drive=disk0") })
    }

    @Test
    fun `boot image goes on virtio-blk, never on the usb stack`() {
        // The USB mass-storage path is what produced SIGILL (exit=132) on the
        // test device; edk2 talks to virtio-blk directly, so the boot image must
        // not drag in xhci -> usb-storage again.
        val args = builder.build(request().copy(bootImagePath = "/images/win.boot.img"))
        assertTrue(args.any { it.startsWith("virtio-blk-pci,drive=boot0,bootindex=1") })
        assertFalse("引导镜像不能走 USB", args.any { it.contains("usb-storage,drive=boot0") })
        assertEquals("磁盘让位到第三位", "nvme,drive=disk0,serial=PVMDISK0,bootindex=3", args.first { it.startsWith("nvme,") })
    }

    @Test
    fun `without a boot image the disk boots first again`() {
        val args = builder.build(request().copy(bootImagePath = null))
        assertFalse(args.any { it.contains("boot0") })
        assertEquals("nvme,drive=disk0,serial=PVMDISK0,bootindex=1", args.first { it.startsWith("nvme,") })
    }

    @Test
    fun `usb is only present when a cdrom is attached`() {
        val noMedia = builder.build(request(iso = null).copy(profile = LaunchProfile.MINIMAL_NO_MEDIA))
        assertFalse("没有介质就不需要 xhci", noMedia.any { it == "qemu-xhci,id=xhci" })
        val withCd = builder.build(request())
        assertTrue(withCd.any { it == "qemu-xhci,id=xhci" })
    }
}
