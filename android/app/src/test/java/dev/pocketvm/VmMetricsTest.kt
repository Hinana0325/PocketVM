package dev.pocketvm

import dev.pocketvm.diagnostics.ProcStats
import dev.pocketvm.diagnostics.VmMetricsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmMetricsTest {

    /** /proc/<pid>/stat with the given utime/stime; comm deliberately contains ") ". */
    private fun stat(utime: Long, stime: Long): String =
        "42 (qemu ) aarch64) S 1 1 1 0 -1 4194560 100 0 0 0 $utime $stime 0 0 20 0 1"

    private fun samplerWith(files: MutableMap<String, String>, historySize: Int = 60) =
        VmMetricsSampler(
            pid = 42,
            pageSizeKb = 4,
            clockTicksPerSecond = 100,
            historySize = historySize,
            readFile = { path -> files[path] },
        )

    @Test
    fun `cpu ticks survive a comm full of parens and spaces`() {
        // The split starts after the LAST ')'; utime/stime are fields 11/12 after it.
        assertEquals(10L, ProcStats.cpuTicks(stat(utime = 7, stime = 3)))
        assertNull(ProcStats.cpuTicks("42 (no close paren here"))
    }

    @Test
    fun `statm resident is the second field`() {
        assertEquals(25000L, ProcStats.rssPages("90000 25000 20000 1 0 5000 0\n"))
        assertNull(ProcStats.rssPages("90000\n"))
    }

    @Test
    fun `proc io parses read and write bytes among the noise`() {
        val io = """
            rchar: 123
            wchar: 456
            syscr: 7
            syscw: 8
            read_bytes: 1048576
            write_bytes: 2097152
            cancelled_write_bytes: 0
        """.trimIndent()
        assertEquals(1048576L to 2097152L, ProcStats.ioBytes(io))
        assertNull(ProcStats.ioBytes("rchar: 1\n"))
    }

    @Test
    fun `first sample reports absolute values and zero rates`() {
        val files = mutableMapOf(
            "/proc/42/stat" to stat(utime = 100, stime = 50),
            // 25600 resident pages x 4 KiB = exactly 100 MiB.
            "/proc/42/statm" to "90000 25600 20000 1 0 5000 0",
            "/proc/42/io" to "read_bytes: 1048576\nwrite_bytes: 2097152\n",
        )
        val sampler = samplerWith(files)

        val first = sampler.sample(nowMs = 1_000, vcpuTids = listOf(700, 701), uptimeMs = 1_000)

        requireNotNull(first)
        // 25000 pages x 4 KiB = 100 MiB of RSS, straight from statm.
        assertEquals(100L, first.rssMb)
        assertEquals(0f, first.cpuPercent, 0.001f)
        assertEquals(0f, first.readMBs, 0.001f)
        assertTrue("还没有第二个样本，历史应为空", first.cpuHistory.isEmpty())
    }

    @Test
    fun `second sample turns tick and byte deltas into rates`() {
        val files = mutableMapOf(
            "/proc/42/stat" to stat(utime = 100, stime = 50),
            "/proc/42/statm" to "90000 25000 20000 1 0 5000 0",
            "/proc/42/io" to "read_bytes: 1048576\nwrite_bytes: 2097152\n",
            "/proc/700/stat" to stat(utime = 0, stime = 0),
            "/proc/701/stat" to stat(utime = 0, stime = 0),
        )
        val sampler = samplerWith(files)
        sampler.sample(nowMs = 1_000, vcpuTids = listOf(700, 701), uptimeMs = 1_000)

        // One second later: +50 process ticks (0.5 core at 100 Hz = 50%),
        // vCPU0 +30 ticks, vCPU1 +20 ticks, +2 MiB read, no new writes.
        files["/proc/42/stat"] = stat(utime = 150, stime = 50)
        files["/proc/700/stat"] = stat(utime = 30, stime = 0)
        files["/proc/701/stat"] = stat(utime = 0, stime = 20)
        files["/proc/42/io"] = "read_bytes: 3145728\nwrite_bytes: 2097152\n"

        val second = sampler.sample(nowMs = 2_000, vcpuTids = listOf(700, 701), uptimeMs = 2_000)

        requireNotNull(second)
        assertEquals(50f, second.cpuPercent, 0.001f)
        assertEquals(30f, second.vcpuPercents[0], 0.001f)
        assertEquals(20f, second.vcpuPercents[1], 0.001f)
        assertEquals(2f, second.readMBs, 0.001f)
        assertEquals(0f, second.writeMBs, 0.001f)
        assertEquals(listOf(50f), second.cpuHistory)
    }

    @Test
    fun `cpu history is capped and oldest first`() {
        val files = mutableMapOf(
            "/proc/42/stat" to stat(utime = 0, stime = 0),
            "/proc/42/statm" to "90000 25000 20000 1 0 5000 0",
            "/proc/42/io" to "read_bytes: 0\nwrite_bytes: 0\n",
        )
        val sampler = samplerWith(files, historySize = 3)

        var now = 0L
        for (step in 1..5) {
            now += 1_000
            files["/proc/42/stat"] = stat(utime = step * 10L, stime = 0) // +10 ticks = 10%
            sampler.sample(nowMs = now, vcpuTids = emptyList(), uptimeMs = now)
        }

        // One more tick of progress, so the final sample is a real delta too.
        files["/proc/42/stat"] = stat(utime = 60, stime = 0)
        val last = sampler.sample(nowMs = now + 1_000, vcpuTids = emptyList(), uptimeMs = now + 1_000)
        requireNotNull(last)
        assertEquals(3, last.cpuHistory.size)
        // Steps 3,4,5,6 all 10%; the first two fell off the front of the ring.
        assertEquals(listOf(10f, 10f, 10f), last.cpuHistory)
    }

    @Test
    fun `a vanished process samples as null`() {
        val files = mutableMapOf(
            "/proc/42/stat" to stat(utime = 0, stime = 0),
            "/proc/42/statm" to "90000 25000 20000 1 0 5000 0",
            "/proc/42/io" to "read_bytes: 0\nwrite_bytes: 0\n",
        )
        val sampler = samplerWith(files)
        sampler.sample(nowMs = 1_000, vcpuTids = emptyList(), uptimeMs = 1_000)

        files.remove("/proc/42/stat")
        assertNull(sampler.sample(nowMs = 2_000, vcpuTids = emptyList(), uptimeMs = 2_000))
    }
}
