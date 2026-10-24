package dev.pocketvm.diagnostics

import java.io.File

/**
 * One sample of what the running VM costs the phone.
 *
 * Everything here is measured on the *host* side: the QEMU process is our own
 * child, so its /proc entries are readable, and QMP enumerates the vCPU threads.
 * No guest agent is involved — nothing has to be installed inside Windows for
 * these numbers to exist.
 */
data class VmMetrics(
    val epochMs: Long,
    /**
     * Whole QEMU process, in percent of ONE core. Multi-threaded TCG keeps
     * several vCPU threads busy at once, so this routinely exceeds 100 —
     * that is the point of showing it raw instead of dividing by core count.
     */
    val cpuPercent: Float,
    /** Per-vCPU usage, each 0..100. Empty when QMP could not enumerate threads. */
    val vcpuPercents: List<Float>,
    /** Resident set of the QEMU process, MiB (translation buffers included). */
    val rssMb: Long,
    /** Block-layer throughput of the process, MiB/s. */
    val readMBs: Float,
    val writeMBs: Float,
    /** Wall time since launch, ms. */
    val uptimeMs: Long,
    /** Recent [cpuPercent] history, oldest first, for the sparkline. */
    val cpuHistory: List<Float>,
)

/**
 * Pure parsers for the three `/proc` files the sampler reads.
 *
 * Split out from the sampler so the awkward formats (comm in parens, key/value
 * lines) can be unit-tested with synthetic strings instead of a live process.
 */
object ProcStats {

    /**
     * `/proc/<pid>/statm` field 2 (resident) in pages.
     * Field 1 is total VM size; resident is what actually occupies RAM.
     */
    fun rssPages(statm: String): Long? {
        val fields = statm.trim().split(' ')
        if (fields.size < 2) return null
        return fields[1].toLongOrNull()
    }

    /**
     * `/proc/<pid>/io` `read_bytes` / `write_bytes` — bytes that actually reached
     * the block layer. Note this counts the qcow2 file's I/O, which is what the
     * user perceives as disk activity, not guest-visible logical writes.
     */
    fun ioBytes(io: String): Pair<Long, Long>? {
        var read: Long? = null
        var write: Long? = null
        for (line in io.lineSequence()) {
            when {
                line.startsWith("read_bytes:") -> read = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("write_bytes:") -> write = line.substringAfter(':').trim().toLongOrNull()
            }
        }
        return if (read != null && write != null) read to write else null
    }

    /**
     * `/proc/<pid|tid>/stat` utime+stime in kernel ticks.
     *
     * comm is parenthesised and may itself contain spaces or parens, so the
     * split starts after the LAST ')': state is field 3 after it, which puts
     * utime at index 11 and stime at 12. Same convention as [ProcessHealth].
     */
    fun cpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return utime + stime
    }
}

/**
 * Turns raw `/proc` counters into [VmMetrics] by differencing consecutive
 * samples. Absolute counters (RSS, uptime) are reported as-is; rates (CPU, I/O)
 * need two samples, so the first call returns zero rates rather than nonsense.
 *
 * Not thread-safe: the caller samples from one coroutine.
 */
class VmMetricsSampler(
    private val pid: Int,
    private val pageSizeKb: Long = 4,
    private val clockTicksPerSecond: Long = 100,
    private val historySize: Int = 60,
    private val readFile: (String) -> String? = { path ->
        runCatching { File(path).readText() }.getOrNull()
    },
) {

    private data class Snapshot(
        val epochMs: Long,
        val cpuTicks: Long,
        val readBytes: Long,
        val writeBytes: Long,
        /** tid -> ticks at sample time. */
        val vcpuTicks: Map<Int, Long>,
    )

    private var prev: Snapshot? = null
    private val history = ArrayDeque<Float>()

    /** Forget the previous sample (after a gap, a restart, or a failed read). */
    fun reset() {
        prev = null
        history.clear()
    }

    /**
     * @param vcpuTids thread id per vCPU, from QMP `query-cpus-fast`. Stable for
     *   the lifetime of a run, so the caller fetches it once.
     * @return null when `/proc/<pid>` is gone or unreadable — the caller treats
     *   that as "process died" and stops sampling.
     */
    fun sample(nowMs: Long, vcpuTids: List<Int>, uptimeMs: Long): VmMetrics? {
        val cpuTicks = readFile("/proc/$pid/stat")?.let { ProcStats.cpuTicks(it) }
            ?: run { prev = null; return null }

        val rssMb = readFile("/proc/$pid/statm")
            ?.let { ProcStats.rssPages(it) }
            ?.let { pages -> pages * pageSizeKb / 1024 }
            ?: 0L

        val io = readFile("/proc/$pid/io")?.let { ProcStats.ioBytes(it) }
        val readBytes = io?.first ?: 0L
        val writeBytes = io?.second ?: 0L

        val vcpuTicks = vcpuTids.associateWith { tid ->
            readFile("/proc/$tid/stat")?.let { ProcStats.cpuTicks(it) } ?: 0L
        }

        val before = prev
        prev = Snapshot(nowMs, cpuTicks, readBytes, writeBytes, vcpuTicks)

        val dtMs = before?.let { nowMs - it.epochMs } ?: 0L
        if (before == null || dtMs <= 0) {
            // First sample (or a clock that jumped backwards): absolute values
            // only. The next call has a delta to divide by.
            return VmMetrics(
                epochMs = nowMs,
                cpuPercent = 0f,
                vcpuPercents = vcpuTids.map { 0f },
                rssMb = rssMb,
                readMBs = 0f,
                writeMBs = 0f,
                uptimeMs = uptimeMs,
                cpuHistory = history.toList(),
            )
        }

        val seconds = dtMs / 1000.0
        val dCpu = (cpuTicks - before.cpuTicks).coerceAtLeast(0L)
        // toDouble() first: Long/Long would truncate, and anything under one
        // full core-second of ticks (100 at 100 Hz) would round to 0%.
        val cpuPercent = (dCpu.toDouble() / clockTicksPerSecond / seconds * 100).toFloat()

        val vcpuPercents = vcpuTids.map { tid ->
            val beforeTicks = before.vcpuTicks[tid] ?: return@map 0f
            val d = (vcpuTicks[tid]!! - beforeTicks).coerceAtLeast(0L)
            (d.toDouble() / clockTicksPerSecond / seconds * 100).coerceIn(0.0, 100.0).toFloat()
        }

        val readMBs = ((readBytes - before.readBytes).coerceAtLeast(0L) / 1048576.0 / seconds).toFloat()
        val writeMBs = ((writeBytes - before.writeBytes).coerceAtLeast(0L) / 1048576.0 / seconds).toFloat()

        history.addLast(cpuPercent)
        while (history.size > historySize) history.removeFirst()

        return VmMetrics(
            epochMs = nowMs,
            cpuPercent = cpuPercent,
            vcpuPercents = vcpuPercents,
            rssMb = rssMb,
            readMBs = readMBs,
            writeMBs = writeMBs,
            uptimeMs = uptimeMs,
            cpuHistory = history.toList(),
        )
    }
}
