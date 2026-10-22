package dev.pocketvm.runtime

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
    val vmName: String = "PocketVM",
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
        args += listOf("-cpu", "max")
        args += listOf("-smp", request.cpuCores.coerceIn(1, 8).toString())
        args += listOf("-m", request.memoryMb.coerceAtLeast(512).toString())
        // Multi-threaded TCG is the only accelerator available without KVM.
        args += listOf("-accel", "tcg,thread=multi,tb-size=512")
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

        args += listOf("-device", "virtio-gpu-pci")
        args += listOf("-device", "virtio-keyboard-pci")
        args += listOf("-device", "virtio-tablet-pci")

        val diskDrive =
            "if=none,id=disk0,format=${request.diskFormat},cache=writeback,discard=unmap,file=${escapeOption(request.diskPath)}"

        when (request.storageController) {
            StorageController.NVME -> {
                // stornvme is in the Windows installer; virtio-blk is not.
                args += listOf("-drive", diskDrive)
                args += listOf("-device", "nvme,drive=disk0,serial=PVMDISK0,bootindex=1")

                request.isoPath?.let { iso ->
                    // USB mass storage likewise needs no extra driver, in the
                    // installer *or* in edk2, so the USB CD can be booted from.
                    args += listOf("-device", "qemu-xhci,id=xhci")
                    args += listOf("-drive", "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=${escapeOption(iso)}")
                    args += listOf("-device", "usb-storage,drive=cd0,bus=xhci.0,bootindex=2")
                }
            }

            StorageController.VIRTIO -> {
                args += listOf("-device", "virtio-scsi-pci,id=scsi0")
                args += listOf("-drive", diskDrive)
                args += listOf("-device", "virtio-blk-pci,drive=disk0,bootindex=1")

                request.isoPath?.let { iso ->
                    args += listOf("-drive", "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=${escapeOption(iso)}")
                    args += listOf("-device", "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=2")
                }
            }
        }

        args += listOf("-netdev", "user,id=net0")
        args += listOf("-device", "virtio-net-pci,netdev=net0")

        // Windows blocks on entropy during first boot; virtio-rng avoids a 30s stall.
        args += listOf("-object", "rng-random,filename=/dev/urandom,id=rng0")
        args += listOf("-device", "virtio-rng-pci,rng=rng0")

        val vncSpec = buildString {
            append(request.vncBindAddress)
            append(':')
            append(displayIndex(request.vncPort))
            if (request.vncPasswordProtected) append(",password=on")
        }
        args += listOf("-vnc", vncSpec)
        args += listOf("-qmp", "unix:${escapeOption(request.qmpSocketPath)},server=on,wait=off")
        args += listOf(
            "-boot",
            if (request.bootFromCd && request.isoPath != null) "order=d" else "order=c",
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
