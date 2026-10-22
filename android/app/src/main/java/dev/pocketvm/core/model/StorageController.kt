package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/**
 * How the virtual disk and the install media are attached to the guest.
 *
 * This is the single most important setting for "boot the ISO and install",
 * because Windows Setup only contains drivers for hardware it expects to see.
 *
 * [NVME] — NVMe system disk + USB CD-ROM on xHCI. Windows 11 ARM64 ships
 * `stornvme` and USB mass-storage drivers in the box, and QEMU's edk2 firmware
 * has NVMe + USB drivers of its own, so both the firmware and the installer see
 * the media without any driver injection.
 *
 * [VIRTIO] — virtio-blk + virtio-scsi CD. Better throughput and proper discard,
 * but Windows Setup shows an empty disk list until `viostor`/`vioscsi` is loaded
 * from the virtio-win ISO. Only useful for people who inject drivers.
 */
@Serializable
enum class StorageController { NVME, VIRTIO }
