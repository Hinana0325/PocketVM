package dev.pocketvm.runtime

import android.os.Build
import android.system.Os
import android.system.OsConstants

/**
 * Physical facts about the phone that actually change how QEMU behaves.
 *
 * Recorded in diagnostics and shown in the environment card because "it works on
 * one Snapdragon and not another" is usually one of these: SoC generation, page
 * size (16 KB devices need 16 KB-aligned ELF segments), or available RAM.
 */
object DeviceInfo {

    /** e.g. "Qualcomm SM8650" / "QCOM SM8650"; falls back to Build.HARDWARE. */
    fun socLabel(): String {
        val maker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
        val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        val parts = listOfNotNull(maker?.takeIf { it.isNotBlank() && it != "unknown" },
            model?.takeIf { it.isNotBlank() && it != "unknown" })
        return if (parts.isEmpty()) Build.HARDWARE else parts.joinToString(" ")
    }

    /** Page size in KiB. 4 on most phones, 16 on newer kernels. */
    fun pageSizeKb(): Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
    }.getOrDefault(0L)

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /** Coarse vendor bucket, used only for diagnostics text. */
    fun vendor(): String = Build.MANUFACTURER
}
