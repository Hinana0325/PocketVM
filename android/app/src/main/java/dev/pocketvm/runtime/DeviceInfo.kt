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

    /**
     * 静态探针（`libpvm-probe.so`）是 4 KB 对齐的 ET_EXEC，16 KB 页设备上
     * 内核根本无法 exec 它；这时跑探针只会把「对齐不兼容」误报成「静态链接不可用」。
     * 这只影响自检文案，执行层本身（QEMU 11.1.2）已按 16 KB 对齐。
     */
    fun shouldProbeStaticExec(pageSizeKb: Long): Boolean = pageSizeKb in 1..4

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /** Coarse vendor bucket, used only for diagnostics text. */
    fun vendor(): String = Build.MANUFACTURER
}
