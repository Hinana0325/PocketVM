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
    /**
     * Resident set of the QEMU process, MiB (translation buffers included).
     * Null when neither `/proc/<pid>/statm` nor `/proc/<pid>/status` could be
     * read — Android denies those files on some builds.
     */
    val rssMb: Long?,
    /**
     * Block-layer throughput, MiB/s. Null when no usable I/O source exists or
     * this is the first sample from a new source. `/proc/<pid>/io` is the
     * primary source; QMP `query-blockstats` is the fallback (it reports the
     * devices' counters, which Android SELinux may still allow when procfs is
     * locked down).
     */
    val readMBs: Float?,
    val writeMBs: Float?,
    /** Wall time since the QEMU process started, ms. */
    val uptimeMs: Long,
    /** Recent [cpuPercent] history, oldest first, for the sparkline. */
    val cpuHistory: List<Float>,
)

/**
 * Pure parsers for the `/proc` files the sampler reads.
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

    /** `/proc/<pid>/status` `VmRSS:` in KiB — fallback when statm is unreadable. */
    fun rssKbFromStatus(status: String): Long? {
        for (line in status.lineSequence()) {
            if (line.startsWith("VmRSS:")) {
                return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
            }
        }
        return null
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

    /** `/proc/<pid>/stat` field 22 (starttime), in ticks since boot. */
    fun startTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        if (fields.size < 20) return null
        // 字段 22 = state(3) 之后的 index 19。
        return fields[19].toLongOrNull()
    }

    /**
     * Age of the process in milliseconds: `/proc/uptime` minus its start time.
     *
     * This is what the dashboard's「已运行」needs — anchoring on the QMP handshake
     * (or on the moment the UI started sampling) loses the whole firmware
     * bring-up and makes a 3-minute-old VM look 3 seconds old.
     */
    fun processAgeMs(stat: String, uptime: String, clockTicksPerSecond: Long = 100): Long? {
        val ticks = startTicks(stat) ?: return null
        val upSeconds = uptime.trim().split(' ').firstOrNull()?.toDoubleOrNull() ?: return null
        val ageMs = upSeconds * 1000.0 - ticks.toDouble() * 1000.0 / clockTicksPerSecond
        return ageMs.toLong().coerceAtLeast(0L)
    }
}

/**
 * Turns raw `/proc` counters into [VmMetrics] by differencing consecutive
 * samples. Absolute counters (RSS, uptime) are reported as-is; rates (CPU, I/O)
 * need two samples, so the first call returns null/zero rates rather than
 * nonsense.
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
    /**
     * Called only when `/proc/<pid>/io` is unreadable. Returns cumulative
     * read/write bytes from another source (today: QMP `query-blockstats`).
     */
    private val ioFallback: (() -> Pair<Long, Long>?)? = null,
) {

    private data class Snapshot(
        val epochMs: Long,
        val cpuTicks: Long,
        /** "proc" / "qmp"; switching source invalidates the byte delta. */
        val ioSource: String?,
        val readBytes: Long?,
        val writeBytes: Long?,
        /** tid -> ticks at sample time; null when that thread's stat is unreadable. */
        val vcpuTicks: Map<Int, Long?>,
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

        // Android locks down procfs on some builds: statm and status are two
        // different files, so try both before admitting we do not know.
        val rssMb = readFile("/proc/$pid/statm")?.let { ProcStats.rssPages(it) }
            ?.let { pages -> pages * pageSizeKb / 1024 }
            ?: readFile("/proc/$pid/status")?.let { ProcStats.rssKbFromStatus(it) }
                ?.let { kb -> kb / 1024 }

        var ioSource: String? = null
        var readBytes: Long? = null
        var writeBytes: Long? = null
        readFile("/proc/$pid/io")?.let { ProcStats.ioBytes(it) }?.let { bytes ->
            ioSource = "proc"
            readBytes = bytes.first
            writeBytes = bytes.second
        }
        if (ioSource == null) {
            ioFallback?.invoke()?.let { bytes ->
                ioSource = "qmp"
                readBytes = bytes.first
                writeBytes = bytes.second
            }
        }

        val vcpuTicks = vcpuTids.associateWith { tid ->
            readFile("/proc/$tid/stat")?.let { ProcStats.cpuTicks(it) }
        }

        val before = prev
        prev = Snapshot(nowMs, cpuTicks, ioSource, readBytes, writeBytes, vcpuTicks)

        val dtMs = before?.let { nowMs - it.epochMs } ?: 0L
        if (before == null || dtMs <= 0) {
            // First sample (or a clock that jumped backwards): absolute values
            // only. The next call has a delta to divide by.
            return VmMetrics(
                epochMs = nowMs,
                cpuPercent = 0f,
                vcpuPercents = vcpuTids.map { 0f },
                rssMb = rssMb,
                readMBs = null,
                writeMBs = null,
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
            val now = vcpuTicks[tid]
            val then = before.vcpuTicks[tid]
            // A missing read is "unknown", not zero: reporting 0 and then
            // differencing against it produced a fake 100% spike next sample.
            if (now == null || then == null) return@map 0f
            val d = (now - then).coerceAtLeast(0L)
            (d.toDouble() / clockTicksPerSecond / seconds * 100).coerceIn(0.0, 100.0).toFloat()
        }

        // Rates only make sense between two samples of the same source. Android
        // may hide /proc/<pid>/io for one second and hand it back the next; a
        // delta across that gap is either a huge fake burst (0 -> real counter)
        // or garbage (proc bytes vs QMP device bytes).
        var readMBs: Float? = null
        var writeMBs: Float? = null
        if (ioSource != null && ioSource == before.ioSource &&
            readBytes != null && writeBytes != null &&
            before.readBytes != null && before.writeBytes != null
        ) {
            readMBs = ((readBytes - before.readBytes).coerceAtLeast(0L) / 1048576.0 / seconds).toFloat()
            writeMBs = ((writeBytes - before.writeBytes).coerceAtLeast(0L) / 1048576.0 / seconds).toFloat()
        }

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
