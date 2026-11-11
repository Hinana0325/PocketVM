package dev.pocketvm.runtime

import java.io.File

/**
 * big.LITTLE 拓扑：把 QEMU 的 TCG 线程限制在性能核上。
 *
 * TCG 的 vCPU 线程是普通的 CPU 密集型线程。放到 A5xx 小核上时，同样的翻译
 * 工作量要跑更久，还会拖慢整个客户机的锁/时钟；把进程限制在最高频的那一簇，
 * 小核留给 Android UI，通常比让调度器随便放更稳、也更快。
 *
 * 信号来源（按优先级）：
 *  1. `/sys/devices/system/cpu/cpu N/cpu_capacity`：内核能量模型给出的
 *     容量值（最大核归一化为 1024），是调度器自己用来放线程的依据，
 *     最能区分大/中/小核；读不到任一核时整体回退到频率。
 *  2. `/sys/devices/system/cpu/cpu N/cpufreq/cpuinfo_max_freq`：最高频率，
 *     80% 阈值挑性能核（旧路径，保持不变）。
 * 两个来源绝不混用：容量与频率的量纲不同，混在一起分层会错。
 */
object CpuTopology {

    data class Topology(
        val bigCores: List<Int>,
        /** toybox taskset 接受的十六进制掩码（不带 0x），如 "fc"；null 表示不绑核。 */
        val maskHex: String?,
        /** 能效核（容量最低的那一档）；温控降档时 vCPU 切到这里。 */
        val littleCores: List<Int> = emptyList(),
        /** 能效核的 taskset 掩码；没有可区分的能效核时为 null。 */
        val efficiencyMaskHex: String? = null,
    )

    fun fromMaxFreqs(maxFreqs: Map<Int, Long>): Topology {
        if (maxFreqs.size < 2) return Topology(emptyList(), null)
        val max = maxFreqs.values.maxOrNull() ?: return Topology(emptyList(), null)
        if (max <= 0) return Topology(emptyList(), null)
        val threshold = (max * 0.8).toLong()
        val big = maxFreqs.filterValues { it >= threshold }.keys.sorted()
        // 全是同一档频率：没有「大小核」可挑，绑核只会限制调度。
        if (big.size < 2 || big.size == maxFreqs.size) return Topology(big, null)
        return Topology(big, toMaskHex(big))
    }

    /**
     * 按容量值分层：容量是调度器归一化的相对算力（最大核 1024），比频率
     * 拉得开——骁龙 8 Gen 3 的 X4 与 A720 频率只差 5%，容量差约 15%~30%。
     *
     * 阈值取 60%/40% 而不用频率的 80%：容量本身已经是相对值，中核容量
     * 通常在最大的 60%~90% 之间，80% 会把中核误判成小核，只剩大核一颗
     * 扛全部 vCPU；小核容量一般不到最大的 40%。中间档（40%~60%）两边
     * 都不进，不参与绑核。
     */
    fun fromCapacities(capacities: Map<Int, Long>): Topology {
        if (capacities.size < 2) return Topology(emptyList(), null)
        val max = capacities.values.maxOrNull() ?: return Topology(emptyList(), null)
        if (max <= 0) return Topology(emptyList(), null)
        val big = capacities.filterValues { it * 5 >= max * 3 }.keys.sorted()
        val little = capacities.filterValues { it * 5 <= max * 2 }.keys.sorted()
        // 顶档只有一颗核（或根本没有大小核）：绑核会让全部 vCPU 挤在一颗核上，
        // 不如不绑；与 fromMaxFreqs 的 big.size < 2 规则一致。
        if (big.size < 2 || big.size == capacities.size) return Topology(big, null)
        val efficiencyMask = little.takeIf { it.isNotEmpty() }?.let { toMaskHex(it) }
        return Topology(big, toMaskHex(big), little, efficiencyMask)
    }

    private fun toMaskHex(cores: List<Int>): String =
        cores.fold(0L) { acc, cpu -> acc or (1L shl cpu) }.toString(16)

    fun read(root: File = File("/sys/devices/system/cpu")): Topology {
        // 容量优先：任一核读不到就整体回退到频率，不混用两个来源。
        readCapacities(root)?.let { return fromCapacities(it) }
        return fromMaxFreqs(readMaxFreqs(root))
    }

    private fun readCapacities(root: File): Map<Int, Long>? {
        val capacities = mutableMapOf<Int, Long>()
        var cpuCount = 0
        root.listFiles()?.forEach { dir ->
            val cpu = cpuPattern.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            cpuCount++
            val capacity = runCatching {
                File(dir, "cpu_capacity").readText().trim().toLong()
            }.getOrNull() ?: return null
            capacities[cpu] = capacity
        }
        if (capacities.isEmpty() || capacities.size != cpuCount) return null
        return capacities
    }

    private fun readMaxFreqs(root: File): Map<Int, Long> {
        val freqs = mutableMapOf<Int, Long>()
        root.listFiles()?.forEach { dir ->
            val cpu = cpuPattern.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            val freq = runCatching {
                File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull() ?: return@forEach
            freqs[cpu] = freq
        }
        return freqs
    }

    private val cpuPattern = Regex("cpu(\\d+)")

    fun tasksetPath(): String? {
        val path = File("/system/bin/taskset")
        return path.absolutePath.takeIf { path.canExecute() }
    }
}
