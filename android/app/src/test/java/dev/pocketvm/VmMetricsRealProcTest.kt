package dev.pocketvm

import dev.pocketvm.diagnostics.ChildProcess
import dev.pocketvm.diagnostics.ProcStats
import dev.pocketvm.diagnostics.VmMetricsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real-kernel counterparts to [VmMetricsTest] and [ChildProcessTest].
 *
 * The synthetic fixtures prove the arithmetic against strings we made up; these
 * prove the parsers against what the machine actually running the tests puts in
 * /proc — which is also the only coverage of the samplers' default real-file
 * readFile path. If a kernel changes a field layout, this is what notices.
 *
 * Linux-only: /proc is the only place these parsers ever run.
 */
class VmMetricsRealProcTest {

    private val isLinux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)

    private fun proc(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    /** This JVM's own pid, parsed from /proc/self/stat field 1 — no API-level
     *  assumptions (android.jar has no ProcessHandle on every level we test). */
    private fun myPid(): Int =
        proc("/proc/self/stat")!!.substringBefore('(').trim().toInt()

    /**
     * A child whose pid we know without reflection: the shell prints its own
     * $$ and then busy-loops *in that same shell*, so the printed pid is the
     * pid burning CPU. POSIX-only (no exec tricks).
     */
    private fun startBusyLoop(): Pair<Process, Int> {
        val process = ProcessBuilder("/bin/sh", "-c", "echo \$\$; while :; do :; done").start()
        val pid = process.inputStream.bufferedReader().readLine().trim().toInt()
        return process to pid
    }

    @Test
    fun `parses this JVM's own proc entries`() {
        assumeTrue(isLinux)

        val stat = proc("/proc/self/stat")
        assertNotNull("this JVM must have /proc/self/stat", stat)
        // A JVM that has already run a test class has burned CPU, so utime+stime
        // is strictly positive. comm here is plain "java" — the point is that
        // the parser copes with whatever it finds, parens or not.
        val ticks = ProcStats.cpuTicks(stat!!)
        assertNotNull(ticks)
        assertTrue("JVM utime+stime should be > 0, was $ticks", ticks!! > 0L)

        val statm = proc("/proc/self/statm")
        assertNotNull(statm)
        val rss = ProcStats.rssPages(statm!!)
        assertNotNull(rss)
        assertTrue("JVM RSS should be > 0 pages, was $rss", rss!! > 0L)

        // /proc/self/io is all zeros on a cold page cache, but the keys must parse.
        val io = proc("/proc/self/io")
        if (io != null) {
            val bytes = ProcStats.ioBytes(io)
            assertNotNull("read_bytes/write_bytes must parse", bytes)
            assertTrue(bytes!!.first >= 0L && bytes.second >= 0L)
        }
    }

    @Test
    fun `samples a live busy process`() {
        assumeTrue(isLinux)

        // A single-threaded busy loop: one wall second is at most one core-second
        // of ticks, so the second sample must land near 100%. (Sandbox cgroup
        // quotas can throttle that, hence the low bar — the point is that a
        // definitely-working process reports a definitely-nonzero rate.)
        val (busy, pid) = startBusyLoop()
        try {
            val sampler = VmMetricsSampler(pid = pid)
            val t0 = System.currentTimeMillis()

            val first = sampler.sample(nowMs = t0, vcpuTids = emptyList(), uptimeMs = 0L)
            assertNotNull(first)
            assertEquals(0f, first!!.cpuPercent, 0.001f) // no delta yet
            assertTrue("live process RSS should be > 0, was ${first.rssMb}", first.rssMb > 0L)

            Thread.sleep(1_100)
            val t1 = System.currentTimeMillis()
            val second = sampler.sample(nowMs = t1, vcpuTids = emptyList(), uptimeMs = t1 - t0)

            assertNotNull(second)
            assertTrue(
                "busy loop should measure a real fraction of a core, got ${second!!.cpuPercent}",
                second.cpuPercent > 10f,
            )
            assertTrue("one thread cannot exceed one core, got ${second.cpuPercent}", second.cpuPercent <= 100f)
            assertEquals(listOf(second.cpuPercent), second.cpuHistory)
        } finally {
            busy.destroyForcibly()
            busy.waitFor()
        }
    }

    @Test
    fun `finds a live child through the real proc`() {
        assumeTrue(isLinux)

        // Two children at once, only one named like qemu: the scan must pick
        // the qemu one, not the lone-child fallback.
        val (loop, loopPid) = startBusyLoop()
        val script = File.createTempFile("qemu-busy", ".sh").apply {
            writeText("#!/bin/sh\necho \$\$\nwhile :; do :; done\n")
            setExecutable(true)
        }
        val (qemu, qemuPid) = try {
            val process = ProcessBuilder(script.absolutePath).start()
            process to process.inputStream.bufferedReader().readLine().trim().toInt()
        } finally {
            script.delete()
        }
        try {
            val parent = myPid()
            assertEquals(qemuPid, ChildProcess.findChildPid(parentPid = parent))
            assertTrue("sanity: both children exist", loopPid > 0 && qemuPid > 0)
            assertTrue("sanity: they are different processes", loopPid != qemuPid)
        } finally {
            loop.destroyForcibly(); loop.waitFor()
            qemu.destroyForcibly(); qemu.waitFor()
        }
    }

    @Test
    fun `a reaped pid samples as null against the real proc`() {
        assumeTrue(isLinux)

        val gone = ProcessBuilder("/bin/sh", "-c", "echo \$\$; exit 0").start()
        val pid = gone.inputStream.bufferedReader().readLine().trim().toInt()
        gone.waitFor() // reaped: its /proc entry is gone
        val sampler = VmMetricsSampler(pid = pid)
        assertNull(sampler.sample(nowMs = 0L, vcpuTids = emptyList(), uptimeMs = 0L))
    }
}
