package dev.pocketvm.storage

import android.content.Context
import java.io.File

/**
 * Single source of truth for on-device paths.
 *
 * Layout (under `Android/data/dev.pocketvm/files`, i.e. app-specific external
 * storage so multi-gigabyte ISOs do not eat the internal partition):
 *
 * ```
 * files/                       ← getExternalFilesDir(), FUSE
 *   vms/<vmId>/disk.qcow2
 *   vms/<vmId>/vars.fd           UEFI variable store, one per VM
 *   vms/<vmId>/config.json
 *   vms/<vmId>/logs/run-<id>.log
 *   vms/<vmId>/logs/run-<id>.json
 *   images/<name>.iso            imported installation media
 *   firmware/                UEFI code flash (QEMU_EFI.fd)
 *   qemu/share                   optional -L data dir for a side-loaded QEMU
 *
 * filesDir/internal/            ← real ext4 (FUSE cannot host unix sockets)
 *   run/<vmId>.sock              QMP control channel
 *   tmp/                         TMPDIR for the child process
 * ```
 */
class VmStorageLayout(context: Context) {

    private val appContext = context.applicationContext

    val root: File = run {
        val external = appContext.getExternalFilesDir(null)
        (external ?: appContext.filesDir).also { it.mkdirs() }
    }

    val vmsDir: File get() = dir("vms")
    val imagesDir: File get() = dir("images")
    val firmwareDir: File get() = dir("firmware")

    /** `-L` target: QEMU's BIOS/keymap data. Absent for a self-contained binary. */
    val qemuShareDir: File get() = File(root, "qemu/share")

    /**
     * Private (internal) storage. Distinct from [root] on purpose:
     * `/storage/emulated/...` is FUSE, and FUSE does not support Unix domain
     * sockets. QMP is a unix socket, so it must live here. TMPDIR is here too,
     * for the same reason (glib/QEMU may create sockets under it).
     */
    private val internalRoot: File = File(appContext.filesDir, "internal").apply { mkdirs() }

    val tmpDir: File get() = File(internalRoot, "tmp").apply { mkdirs() }

    /** QEMU's control socket. Internal storage only - see [internalRoot]. */
    val runDir: File get() = File(internalRoot, "run").apply { mkdirs() }

    fun vmDir(vmId: String): File = dir("vms/${safeVmId(vmId)}")

    fun diskFile(vmId: String): File = File(vmDir(vmId), "disk.qcow2")

    fun varsFile(vmId: String): File = File(vmDir(vmId), "vars.fd")

    fun configFile(vmId: String): File = File(vmDir(vmId), "config.json")

    fun qmpSocket(vmId: String): File = File(runDir, "${safeVmId(vmId)}.sock")

    fun logDir(vmId: String): File = dir("vms/${safeVmId(vmId)}/logs")

    fun logFile(vmId: String, runId: String): File = File(logDir(vmId), "run-$runId.log")

    fun manifestFile(vmId: String, runId: String): File = File(logDir(vmId), "run-$runId.json")

    fun freeSpaceBytes(): Long = root.usableSpace

    fun deleteVm(vmId: String): Boolean {
        File(runDir, "${safeVmId(vmId)}.sock").delete()
        val dir = File(vmsDir, safeVmId(vmId))
        return dir.exists() && dir.deleteRecursively()
    }

    /**
     * vmId comes back from on-disk JSON; a tampered config containing `../`
     * must not escape the vms/ directory.
     */
    private fun safeVmId(vmId: String): String {
        require(vmId.matches(Regex("[A-Za-z0-9._-]+")) && vmId != "." && vmId != "..") {
            "非法的虚拟机 ID：$vmId"
        }
        return vmId
    }

    private fun dir(relative: String): File = File(root, relative).apply { mkdirs() }
}
