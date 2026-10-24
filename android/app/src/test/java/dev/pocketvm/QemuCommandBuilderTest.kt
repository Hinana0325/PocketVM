package dev.pocketvm

import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.LaunchProfile
import dev.pocketvm.core.model.PortForward
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
    fun `default cd goes on usb, the only bus both stacks can use`() {
        val args = builder.build(request())
        assertTrue("磁盘必须是 NVMe，安装程序才有原生驱动", args.any { it.startsWith("nvme,drive=disk0") })
        // USB is the one transport both sides understand: edk2 boots
        // \EFI\BOOT\BOOTAA64.EFI off it, and WinPE keeps reading install.wim
        // from that very same device. See CdTransport.
        assertTrue(
            "光驱挂 USB：固件与 WinPE 都能用的唯一总线",
            args.any { it == "usb-storage,drive=cd0,bus=xhci.0,bootindex=1" },
        )
        assertFalse("默认不再挂 virtio-scsi/AHCI", args.any { it.startsWith("scsi-cd") || it.startsWith("ide-cd") })
        assertFalse("NVMe 模式下不应出现 virtio-blk", args.any { it.startsWith("virtio-blk-pci") })
        assertTrue(
            "控制器必须先于挂在上面的设备出现",
            args.indexOf("qemu-xhci,id=xhci") < args.indexOf("usb-storage,drive=cd0,bus=xhci.0,bootindex=1"),
        )
        assertEquals("order=d", valueOf(args, "-boot"))
    }

    @Test
    fun `virtio-scsi transport keeps the cd on both buses`() {
        // Fallback used when the USB stack kills QEMU: edk2 boots the scsi-cd
        // copy, and the AHCI copy is the one WinPE can read (storahci) — though
        // edk2 has no AHCI driver, so it cannot boot from that copy.
        val args = builder.build(request().copy(cdTransport = CdTransport.VIRTIO_SCSI))
        assertTrue(args.any { it == "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1" })
        assertTrue(args.any { it == "ide-cd,drive=cd1,bus=ahci.0" })
        assertFalse(args.any { it.startsWith("usb-storage") })
        assertTrue(
            "控制器必须先于挂在上面的设备出现",
            args.indexOf("virtio-scsi-pci,id=scsi0") < args.indexOf("scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1"),
        )
    }

    @Test
    fun `driver iso attaches on the AHCI bus in virtio-scsi mode`() {
        // Without a driver disk WinPE cannot read install.wim from virtio-scsi,
        // so the Fedora virtio-win ISO must appear as a second AHCI cdrom.
        val args = builder.build(
            request().copy(cdTransport = CdTransport.VIRTIO_SCSI, driverIsoPath = "/x/virtio-win.iso"),
        )
        assertTrue(args.any { it == "ide-cd,drive=cd2,bus=ahci.0" })
        assertTrue("驱动盘后端 drive 应指向传入的 driverIsoPath", args.any { it.contains("id=cd2,") && it.contains("virtio-win.iso") })
        assertTrue(
            "AHCI 控制器必须先于驱动盘出现",
            args.indexOf("ich9-ahci,id=ahci") < args.indexOf("ide-cd,drive=cd2,bus=ahci.0"),
        )
        // Windows Setup cannot configure two ISO devices at once and freezes when
        // it tries (richliu.com). With a driver disk the AHCI copy of the install
        // ISO is dropped: vioscsi loaded from the driver disk makes the scsi-cd
        // readable, so the duplicate is pure risk.
        assertFalse(
            "挂驱动盘时安装 ISO 不再有 AHCI 副本（两个 ISO 设备同屏会冻结）",
            args.any { it == "ide-cd,drive=cd1,bus=ahci.0" },
        )
        assertTrue("安装 ISO 仍在 virtio-scsi 上供固件引导", args.any { it == "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1" })
    }

    @Test
    fun `no driver iso means no second AHCI cdrom`() {
        val args = builder.build(request().copy(cdTransport = CdTransport.VIRTIO_SCSI, driverIsoPath = null))
        assertFalse(args.any { it == "ide-cd,drive=cd2,bus=ahci.0" })
    }

    @Test
    fun `virtio mode keeps virtio-blk for the disk`() {
        val args = builder.build(request().copy(storageController = StorageController.VIRTIO))
        assertTrue(args.any { it.startsWith("virtio-blk-pci,drive=disk0") })
        assertTrue(args.any { it.startsWith("usb-storage,drive=cd0") })
        assertFalse(args.any { it.startsWith("nvme,") })
    }

    @Test
    fun `without iso the disk boots and no cdrom is attached`() {
        val args = builder.build(request(iso = null))
        assertFalse("不应挂光驱", args.any { it.startsWith("ide-cd") || it.startsWith("scsi-cd") || it.startsWith("usb-storage") })
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
    fun `port forwards become hostfwd rules bound to loopback by default`() {
        // hostfwd is how a PC reaches a service inside the VM: RDP for Windows,
        // SSH for Linux. Loopback keeps the exposure identical to 仅本机 VNC.
        val args = builder.build(
            request().copy(portForwards = listOf(PortForward(5389, 3389), PortForward(2022, 22))),
        )
        val netdev = valueOf(args, "-netdev")
        assertTrue(netdev!!.startsWith("user,id=net0,"))
        assertTrue(netdev.contains("hostfwd=tcp:127.0.0.1:5389-:3389"))
        assertTrue(netdev.contains("hostfwd=tcp:127.0.0.1:2022-:22"))
    }

    @Test
    fun `lan mode binds forwards to all interfaces like vnc`() {
        val args = builder.build(
            request().copy(
                vncBindAddress = "0.0.0.0",
                portForwards = listOf(PortForward(5389, 3389)),
            ),
        )
        assertTrue(valueOf(args, "-netdev")!!.contains("hostfwd=tcp:0.0.0.0:5389-:3389"))
    }

    @Test
    fun `invalid and duplicate forwards never reach qemu`() {
        // 80 is below the 1024 floor an Android app UID can bind, 70000 is out of
        // range, and a repeated host port would make QEMU reject the whole
        // -netdev. First occurrence wins.
        val args = builder.build(
            request().copy(
                portForwards = listOf(
                    PortForward(80, 3389),
                    PortForward(5389, 70000),
                    PortForward(5389, 22),
                    PortForward(2022, 22),
                    PortForward(2022, 80),
                ),
            ),
        )
        val netdev = valueOf(args, "-netdev")!!
        assertFalse("特权主机端口必须被丢弃", netdev.contains(":80-"))
        assertFalse("越界的客户机端口必须被丢弃", netdev.contains("70000"))
        assertTrue("同主机端口保留第一条", netdev.contains("hostfwd=tcp:127.0.0.1:5389-:22"))
        assertTrue(netdev.contains("hostfwd=tcp:127.0.0.1:2022-:22"))
        assertFalse("重复主机端口必须被丢弃", netdev.contains("2022-:80"))
    }

    @Test
    fun `disabled nic carries no hostfwd`() {
        val args = builder.build(
            request().copy(enableNet = false, portForwards = listOf(PortForward(5389, 3389))),
        )
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
        // not drag in xhci -> usb-storage again. It still sits behind the CD:
        // the image holds only the loader, with no \boot\bcd and no boot.wim.
        val args = builder.build(request().copy(bootImagePath = "/images/win.boot.img"))
        assertTrue(args.any { it.startsWith("virtio-blk-pci,drive=boot0,bootindex=2") })
        assertFalse("引导镜像不能走 USB", args.any { it.contains("usb-storage,drive=boot0") })
        assertEquals(
            "CD 仍是第一引导项",
            "usb-storage,drive=cd0,bus=xhci.0,bootindex=1",
            args.first { it.startsWith("usb-storage,drive=cd0") },
        )
        assertEquals("磁盘让位到第三位", "nvme,drive=disk0,serial=PVMDISK0,bootindex=3", args.first { it.startsWith("nvme,") })
    }

    @Test
    fun `without a boot image the disk boots second, behind the cd`() {
        val args = builder.build(request().copy(bootImagePath = null))
        assertFalse(args.any { it.contains("boot0") })
        assertEquals("nvme,drive=disk0,serial=PVMDISK0,bootindex=2", args.first { it.startsWith("nvme,") })
    }

    @Test
    fun `input goes over usb, on a controller declared before it`() {
        val args = builder.build(request())
        assertTrue(args.any { it == "usb-kbd,bus=xhci.0" })
        assertTrue(args.any { it == "usb-tablet,bus=xhci.0" })
        assertFalse(
            "不再依赖 virtio 输入",
            args.any { it == "virtio-keyboard-pci" || it == "virtio-tablet-pci" },
        )
        assertTrue(
            "控制器必须先于挂在上面的设备出现，否则 QEMU 报 Bus 'xhci.0' not found",
            args.indexOf("qemu-xhci,id=xhci") < args.indexOf("usb-kbd,bus=xhci.0"),
        )
    }

    @Test
    fun `xhci is present for usb input even with no cdrom`() {
        // The old rule was "xhci only when a CD is attached"; USB input needs the
        // controller whether or not there is media.
        val usb = builder.build(request(iso = null).copy(profile = LaunchProfile.MINIMAL_NO_MEDIA))
        assertTrue(usb.any { it == "qemu-xhci,id=xhci" })

        val virtio = builder.build(
            request(iso = null).copy(profile = LaunchProfile.MINIMAL_NO_MEDIA, usbInput = false),
        )
        assertFalse("virtio 输入且无介质时不该有 xhci", virtio.any { it == "qemu-xhci,id=xhci" })
        assertTrue(virtio.any { it == "virtio-keyboard-pci" })
    }

    @Test
    fun `display device defaults to virtio-gpu`() {
        // Measured on the device: the firmware picture comes up at 1280x800.
        val args = builder.build(request())
        assertTrue(args.any { it == "virtio-gpu-pci" })
        assertFalse(args.any { it == "ramfb" })
    }

    @Test
    fun `ramfb is selectable for the windows install path`() {
        // Every community-verified Windows ARM64 install on virt uses ramfb:
        // virtio-gpu's driver handover resets the device and QEMU drops the
        // scanout mid-Setup ("Display output is not active.").
        val args = builder.build(request().copy(displayDevice = DisplayDevice.RAMFB))
        assertTrue(args.any { it == "ramfb" })
        assertFalse("ramfb 与 virtio-gpu 不并存", args.any { it == "virtio-gpu-pci" })
    }
}
