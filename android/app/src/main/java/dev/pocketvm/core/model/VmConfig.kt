package dev.pocketvm.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Everything PocketVM needs to know about a virtual machine *before* it starts.
 *
 * Paths are stored absolute so that a config can be inspected without resolving
 * the storage layout; the layout still owns the default locations
 * (see [dev.pocketvm.storage.VmStorageLayout]).
 */
@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    val cpuCores: Int = 4,
    val memoryMb: Int = 3072,
    val diskSizeGb: Int = 64,
    /** Absolute path of the imported Windows ARM64 ISO, or null for a disk-only boot. */
    val isoPath: String? = null,
    /** Absolute path of the UEFI code flash. Null means "pick the first usable firmware". */
    val firmwareCodePath: String? = null,
    val bootFromCd: Boolean = true,
    /** VNC listen port. QEMU's display number is `vncPort - 5900`. */
    val vncPort: Int = 5900,
    /** Loopback by default; LAN exposure requires a password. */
    val vncAccess: VncAccess = VncAccess.LOOPBACK,
    /**
     * VNC password (max 8 chars, RFB truncates). Stored in plain text next to the
     * disk: this is a LAN convenience secret, not a security boundary.
     */
    val vncPassword: String? = null,
    /** See [StorageController]: NVMe works with the stock Windows installer. */
    val storageController: StorageController = StorageController.NVME,
    /**
     * Escape hatch for experimentation. Arguments are appended verbatim and are
     * *not* validated, because the whole point is to try unmodelled options.
     */
    val extraArgs: List<String> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun create(name: String): VmConfig = VmConfig(
            id = UUID.randomUUID().toString(),
            name = name,
        )
    }
}
