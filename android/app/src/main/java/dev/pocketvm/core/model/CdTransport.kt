package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/**
 * Which bus the installation CD sits on.
 *
 * The boot chain has to be readable by **two** very different stacks, and their
 * capabilities barely overlap:
 *
 *  * **edk2 (ArmVirtQemu)** ships VirtioScsiDxe and the xHCI/USB mass storage
 *    stack, but carries **no AHCI driver** — an `ide-cd` never appears in the
 *    firmware's `map` output, so edk2 cannot boot from it at all.
 *  * **Stock WinPE** ships `storahci` and USB storage, but **no `vioscsi`** — so
 *    it cannot read `install.wim` from a virtio-scsi CD, even though that is the
 *    bus edk2 can boot from.
 *
 * That leaves exactly one transport both sides understand: **USB**. Attaching the
 * CD on virtio-scsi gets the firmware as far as "Loading files..." and then fails,
 * because the boot manager keeps reading from the device it was booted from.
 *
 * `usb-storage` crashes with SIGILL (exit=132) on this device (seen on QEMU 9.2.3
 * and still on 11.1.2), so [AUTO] tries [USB] first and falls back to [VIRTIO_SCSI]
 * when the process is killed by a signal — same ladder idea as dropping an unusable
 * boot image. Once that fallback fires, the app persists VIRTIO_SCSI so it is never
 * retried.
 */
@Serializable
enum class CdTransport(val label: String) {
    /** Try USB, fall back to virtio-scsi if QEMU dies on it. */
    AUTO("自动"),

    /** Boot-and-read via USB mass storage: the only bus both stacks support. */
    USB("USB"),

    /** Boot via virtio-scsi (plus an AHCI copy WinPE can see but edk2 cannot boot). */
    VIRTIO_SCSI("virtio-scsi+AHCI"),
    ;
}
