package dev.pocketvm.runtime

import java.io.File

/**
 * big.LITTLE 拓扑：把 QEMU 的 TCG 线程限制在性能核上。
 *
 * TCG 的 vCPU 线程是普通的 CPU 密集型线程。放到 A5xx 小核上时，同样的翻译
 * 工作量要跑更久，还会拖慢整个客户机的锁/时钟；把进程限制在最高频的那一簇，
 * 小核留给 Android UI，通常比让调度器随便放更稳、也更快。
 *
 * 读取 `/sys/devices/system/cpu/cpu N/cpufreq/cpuinfo_max_freq`，以最高频率的
 * 80% 为阈值挑出性能核；同频（没有大小核）或读不到时返回 null，表示不需要绑核。
 */
object CpuTopology {

    data class Topology(
        val bigCores: List<Int>,
        /** toybox taskset 接受的十六进制掩码（不带 0x），如 "fc"；null 表示不绑核。 */
        val maskHex: String?,
    )

    fun fromMaxFreqs(maxFreqs: Map<Int, Long>): Topology {
        if (maxFreqs.size < 2) return Topology(emptyList(), null)
        val max = maxFreqs.values.maxOrNull() ?: return Topology(emptyList(), null)
        if (max <= 0) return Topology(emptyList(), null)
        val threshold = (max * 0.8).toLong()
        val big = maxFreqs.filterValues { it >= threshold }.keys.sorted()
        // 全是同一档频率：没有「大小核」可挑，绑核只会限制调度。
        if (big.size < 2 || big.size == maxFreqs.size) return Topology(big, null)
        val mask = big.fold(0L) { acc, cpu -> acc or (1L shl cpu) }
        return Topology(big, mask.toString(16))
    }

    fun read(root: File = File("/sys/devices/system/cpu")): Topology {
        val freqs = mutableMapOf<Int, Long>()
        val cpuPattern = Regex("cpu(\\d+)")
        root.listFiles()?.forEach { dir ->
            val cpu = cpuPattern.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val freq = runCatching {
                File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull() ?: return@forEach
            freqs[cpu] = freq
        }
        return fromMaxFreqs(freqs)
    }

    fun tasksetPath(): String? {
        val path = File("/system/bin/taskset")
        return path.absolutePath.takeIf { path.canExecute() }
    }
}
