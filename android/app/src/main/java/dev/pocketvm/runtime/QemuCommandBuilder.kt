package dev.pocketvm.runtime

import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.LaunchProfile
import dev.pocketvm.core.model.StorageController

/**
 * UEFI pflash pair. Both images must have exactly the same size; QEMU maps them
 * as two halves of one flash device, and a mismatch is a hard startup failure.
 */
data class FirmwareFiles(val codePath: String, val varsPath: String)

data class LaunchRequest(
    val executable: String,
    val firmware: FirmwareFiles,
    val diskPath: String,
    val diskFormat: String = "qcow2",
    val isoPath: String? = null,
    val cpuCores: Int = 4,
    val memoryMb: Int = 3072,
    val vncPort: Int = 5900,
    val vncBindAddress: String = "127.0.0.1",
    val vncPasswordProtected: Boolean = false,
    val qmpSocketPath: String,
    val bootFromCd: Boolean = true,
    val storageController: StorageController = StorageController.NVME,
    /** Bus for the install CD; see [CdTransport]. */
    val cdTransport: CdTransport = CdTransport.AUTO,
    val vmName: String = "PocketVM",
    /** Overrides [LaunchProfile.cpu] when set. */
    val cpuModel: String? = null,
    /** Overrides [LaunchProfile.rng] when set. */
    val rng: Boolean? = null,
    /** Acceleration/CPU profile; see [LaunchProfile]. */
    val profile: LaunchProfile = LaunchProfile.STANDARD,
    /**
     * El Torito EFI boot image extracted from the ISO. Attached as a small
     * read-only USB disk with bootindex 1 so the firmware does not have to
     * understand ISO9660/UDF.
     */
    val bootImagePath: String? = null,
    /**
     * Overrides [LaunchProfile.net] when the option ROM is missing: a NIC that
     * cannot be initialised must not block the whole VM from starting.
     */
    val enableNet: Boolean? = null,
    /**
     * Attach keyboard and tablet over USB instead of virtio.
     *
     * Which transport actually delivers keystrokes to a guest is device- and
     * firmware-dependent, so it is a variable rather than a constant; see
     * [QemuCommandBuilder.build] for the measurement that moved it to USB.
     */
    val usbInput: Boolean = true,
    /**
     * Unix socket for the firmware/guest serial console. Null disables it.
     * When present, VmSession watches it and auto-recovers from a UEFI Shell
     * drop by typing the EFI boot command — the "备用方案" for devices whose
     * firmware will not auto-boot the CD.
     */
    val serialSocketPath: String? = null,
    /** QEMU's `-L` data directory (BIOS, keymaps). Null for a self-contained build. */
    val dataDir: String? = null,
    val tmpDir: String? = null,
    val extraArgs: List<String> = emptyList(),
)

/**
 * Pure argument construction — no Android, no I/O, fully unit-testable.
 *
 * Design notes that are easy to get wrong:
 * * `-machine virt,highmem=on` is required for guest RAM above 1 GB on ARM.
 * * Storage defaults to NVMe + USB CD: Windows Setup has drivers for those, but
 *   not for virtio, so a virtio disk is invisible to a stock installer.
 * * IDE is not available on `virt`, so there is no AHCI fallback.
 * * VNC takes a *display number*, not a port: `127.0.0.1:0` means 5900.
 * * `password=on` only arms authentication; the password itself must be set over
 *   QMP (`set_password`) right after the handshake.
 * * `-qmp` is the control channel used for graceful shutdown and status polling;
 *   `-monitor none` keeps a stray human from stealing it.
 */
class QemuCommandBuilder {

    fun build(request: LaunchRequest): List<String> {
        val args = mutableListOf<String>()
        args += request.executable
        args += listOf("-name", sanitizeName(request.vmName))
        args += listOf("-machine", "virt,highmem=on")
        args += listOf("-cpu", request.cpuModel?.takeIf { it.isNotBlank() } ?: request.profile.cpu)
        args += listOf("-smp", request.cpuCores.coerceIn(1, 8).toString())
        args += listOf("-m", request.memoryMb.coerceAtLeast(512).toString())
        // Multi-threaded TCG is the only accelerator available without KVM, but
        // it needs a large translation buffer; the fallback profiles shrink it.
        args += listOf("-accel", request.profile.accel)
        args += listOf("-monitor", "none")
        args += listOf("-rtc", "base=localtime")

        request.dataDir?.let { args += listOf("-L", it) }

        // UEFI: code flash read-only, variable store writable.
        args += listOf(
            "-drive",
            "if=pflash,format=raw,unit=0,readonly=on,file=${escapeOption(request.firmware.codePath)}",
        )
        args += listOf(
            "-drive",
            "if=pflash,format=raw,unit=1,file=${escapeOption(request.firmware.varsPath)}",
        )

        val mediaPath = request.isoPath?.takeUnless { request.profile.dropMedia }

        // Resolve AUTO to USB: it is the only bus both edk2 and WinPE understand
        // (see CdTransport). The launcher downgrades to VIRTIO_SCSI if QEMU dies
        // on the USB stack, so AUTO never reaches here as AUTO.
        val transport = when (request.cdTransport) {
            CdTransport.AUTO, CdTransport.USB -> CdTransport.USB
            CdTransport.VIRTIO_SCSI -> CdTransport.VIRTIO_SCSI
        }

        // virtio-scsi + AHCI only exist for the VIRTIO_SCSI transport: edk2 boots
        // the scsi-cd copy, and the ich9-ahci copy is the one stock WinPE can
        // read (storahci) — but edk2 has no AHCI driver, so it cannot boot it.
        // That split is exactly why USB is preferred whenever it does not crash.
        if (mediaPath != null && transport == CdTransport.VIRTIO_SCSI) {
            args += listOf("-device", "virtio-scsi-pci,id=scsi0")
            args += listOf("-device", "ich9-ahci,id=ahci")
        }

        args += listOf("-device", "virtio-gpu-pci")

        // Input goes over USB, not virtio. edk2 and a stock Windows ARM64 install
        // both carry xHCI/USB-HID drivers; virtio input needs drivers the installer
        // does not ship. Measured on the Xiaomi 14 Pro: with virtio-keyboard-pci
        // attached, keystrokes sent over both RFB KeyEvent and QMP send-key never
        // reached the guest's UEFI Shell.
        //
        // The controller must be declared before the devices that sit on it, or
        // QEMU answers "Bus 'xhci.0' not found".
        val needsUsb = (mediaPath != null && transport == CdTransport.USB) || request.usbInput
        if (needsUsb) args += listOf("-device", "qemu-xhci,id=xhci")
        if (request.usbInput) {
            args += listOf("-device", "usb-kbd,bus=xhci.0")
            args += listOf("-device", "usb-tablet,bus=xhci.0")
        } else {
            args += listOf("-device", "virtio-keyboard-pci")
            args += listOf("-device", "virtio-tablet-pci")
        }

        val diskDrive =
            "if=none,id=disk0,format=${request.diskFormat},cache=writeback,discard=unmap,file=${escapeOption(request.diskPath)}"

        // Boot order: the installation CD first even when an extracted boot image
        // is attached (that image holds only the loader — see IsoBootImage),
        // then the boot image, then the disk.
        val diskBootIndex = if (request.bootImagePath != null) 3 else 2
        // The boot image goes on virtio-blk rather than usb-storage: edk2 drives
        // virtio-blk directly, whereas the XHCI -> hub -> mass-storage chain ended
        // in SIGILL (exit=132) on the device we tested, before QEMU wrote a line.

        when (request.storageController) {
            StorageController.NVME -> {
                // stornvme is in the Windows installer; virtio-blk is not.
                args += listOf("-drive", diskDrive)
                args += listOf("-device", "nvme,drive=disk0,serial=PVMDISK0,bootindex=$diskBootIndex")
            }

            StorageController.VIRTIO -> {
                args += listOf("-drive", diskDrive)
                args += listOf("-device", "virtio-blk-pci,drive=disk0,bootindex=$diskBootIndex")
            }
        }

        request.bootImagePath?.let { image ->
            // A partition-less FAT image: edk2 mounts it and boots
            // \EFI\BOOT\BOOTAA64.EFI from it without needing ISO9660/UDF. It is
            // only the loader though — no \boot\bcd, no boot.wim — so it sits
            // behind the CD in the boot order.
            args += listOf("-drive", "if=none,id=boot0,media=disk,readonly=on,format=raw,file=${escapeOption(image)}")
            args += listOf("-device", "virtio-blk-pci,drive=boot0,bootindex=2")
        }

        mediaPath?.let { iso ->
            if (transport == CdTransport.USB) {
                // One drive, one bus — and both stacks can use it: edk2 boots
                // \EFI\BOOT\BOOTAA64.EFI off the USB CD, and WinPE keeps reading
                // install.wim from the very same device.
                args += listOf("-drive", "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=${escapeOption(iso)}")
                args += listOf("-device", "usb-storage,drive=cd0,bus=xhci.0,bootindex=1")
            } else {
                // Two backend drives over one file; QEMU is fine with a readonly
                // medium attached on both buses at once.
                args += listOf("-drive", "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=${escapeOption(iso)}")
                args += listOf("-device", "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1")
                args += listOf("-drive", "if=none,id=cd1,media=cdrom,readonly=on,format=raw,file=${escapeOption(iso)}")
                args += listOf("-device", "ide-cd,drive=cd1,bus=ahci.0")
            }
        }

        if (request.enableNet ?: request.profile.net) {
            args += listOf("-netdev", "user,id=net0")
            args += listOf("-device", "virtio-net-pci,netdev=net0")
        }

        // Windows blocks on entropy during first boot; virtio-rng avoids a 30s stall.
        if (request.rng ?: request.profile.rng) {
            args += listOf("-object", "rng-random,filename=/dev/urandom,id=rng0")
            args += listOf("-device", "virtio-rng-pci,rng=rng0")
        }

        val vncSpec = buildString {
            append(request.vncBindAddress)
            append(':')
            append(displayIndex(request.vncPort))
            if (request.vncPasswordProtected) append(",password=on")
        }
        args += listOf("-vnc", vncSpec)
        args += listOf("-qmp", "unix:${escapeOption(request.qmpSocketPath)},server=on,wait=off")

        // Serial console: lets PocketVM see (and recover from) a UEFI Shell drop.
        // The chardev is a Unix socket on internal ext4 storage, like QMP.
        request.serialSocketPath?.let {
            args += listOf("-serial", "unix:${escapeOption(it)},server=on,wait=off")
        }
        args += listOf(
            "-boot",
            if (request.bootFromCd && mediaPath != null) "order=d" else "order=c",
        )

        request.extraArgs.filter { it.isNotBlank() }.forEach { args += it }
        return args
    }

    /**
     * QEMU VNC syntax is `host:display`; 5900 + display == TCP port. Ports below
     * 5900 are rejected by the validator, so pass the difference through instead
     * of silently clamping (a clamped listener would contradict the manifest).
     */
    fun displayIndex(vncPort: Int): Int = vncPort - 5900

    /** QEMU's option parser splits on commas; a literal comma is written `,,`. */
    private fun escapeOption(value: String): String = value.replace(",", ",,")

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace('\n', ' ').replace('\r', ' ').trim()
        return if (cleaned.isEmpty()) "PocketVM" else cleaned.take(60)
    }
}
