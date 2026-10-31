package dev.pocketvm

import dev.pocketvm.diagnostics.DiagContract
import dev.pocketvm.diagnostics.DiagVerdict
import dev.pocketvm.diagnostics.DiagVncBanner
import dev.pocketvm.diagnostics.QmpProbe
import dev.pocketvm.diagnostics.RuntimeWatchdog
import dev.pocketvm.diagnostics.RuntimeWatchdogSampler
import dev.pocketvm.diagnostics.ThreadCpu
import dev.pocketvm.diagnostics.ThreadDistribution
import dev.pocketvm.diagnostics.ThreadRole
import dev.pocketvm.diagnostics.VncBannerProbe
import dev.pocketvm.diagnostics.VncProbe
import dev.pocketvm.diagnostics.WatchdogDetector
import dev.pocketvm.diagnostics.WatchdogJson
import dev.pocketvm.diagnostics.WatchdogLog
import dev.pocketvm.diagnostics.WatchdogSample
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 看门狗的纯逻辑单测：假 /proc 夹具、临时串口文件、异常窗口判据、T4 §6.2
 * JSONL 契约、T6 §5 快照映射、轮转与 VNC banner 探测。全部跑在 JVM 上。
 *
 * 真机停摆的判读依赖这里定义的字段语义：main_thread_dticks/vcpu_dticks 与
 * main_cpu/vcpu_sum 是归因线索，window_anomaly/signals 才是「异常」本身。
 */
class RuntimeWatchdogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pid = 42

    /** `/proc/<pid>/task/<tid>/stat`：comm 之后第 11/12 个字段是 utime/stime。 */
    private fun stat(tid: Int, utime: Long, stime: Long): String =
        "$tid (qemu-system-aarch64) S 1 1 1 0 -1 4194560 100 0 0 0 $utime $stime 0 0 20 0 1 0 12345"

    private fun baseFiles(): MutableMap<String, String> = mutableMapOf(
        "/proc/42/task/42/stat" to stat(42, 10, 5),
        "/proc/42/task/42/schedstat" to "0 0 0",
        "/proc/42/task/700/stat" to stat(700, 0, 0),
        "/proc/42/task/700/schedstat" to "0 0 0",
        "/proc/42/task/701/stat" to stat(701, 0, 0),
        "/proc/42/task/701/schedstat" to "0 0 0",
    )

    private fun sampler(
        files: MutableMap<String, String>,
        serialLog: File? = null,
        qmpStatus: () -> QmpProbe = { QmpProbe(true, 3, "running") },
        vncProbe: (() -> VncProbe)? = { VncProbe(true, "RFB 003.008", 1, null) },
        vcpuTids: List<Int> = listOf(700, 701),
    ) = RuntimeWatchdogSampler(
        pid = pid,
        vcpuTids = vcpuTids,
        serialLog = serialLog,
        qmpStatus = qmpStatus,
        vncProbe = vncProbe,
        readFile = { path -> files[path] },
        clockTicksPerSecond = 100,
        intervalMs = 5_000,
    )

    // ---------------------------------------------------------------- sampler

    @Test
    fun `first sample reports absolute values and no rates`() {
        val sample = sampler(baseFiles()).sample(nowMs = 1_000)

        assertEquals(3L, sample.qmp.rttMs)
        assertEquals("running", sample.qmp.status)
        assertEquals("RFB 003.008", sample.vnc?.banner)
        assertNull("没有串口日志就是未知，不是 0", sample.serial)
        assertNull(sample.threads!!.mainCpuPercent)
        assertEquals(listOf(null, null), sample.threads!!.vcpuCpuPercents)
        assertNull(sample.dtMs)
        assertTrue(sample.procReadable)
    }

    @Test
    fun `second sample turns schedstat deltas into cpu and stat deltas into dticks`() {
        val files = baseFiles()
        val sampler = sampler(files)
        sampler.sample(nowMs = 1_000)

        // 5 s 内：主线程 0.5 s（10%），vCPU0 满核（100%），vCPU1 半核（50%）。
        files["/proc/42/task/42/schedstat"] = "500000000 0 0"
        files["/proc/42/task/700/schedstat"] = "5000000000 0 0"
        files["/proc/42/task/701/schedstat"] = "2500000000 0 0"
        // 同窗口的 utime+stime 增量：主线程 +50 tick，vCPU0 +500，vCPU1 0。
        files["/proc/42/task/42/stat"] = stat(42, 60, 5)
        files["/proc/42/task/700/stat"] = stat(700, 500, 0)

        val second = sampler.sample(nowMs = 6_000)

        assertEquals(10f, second.threads!!.mainCpuPercent!!, 0.01f)
        assertEquals(listOf(100f, 50f), second.threads!!.vcpuCpuPercents)
        assertEquals(150f, second.threads!!.vcpuSumPercent!!, 0.01f)
        assertEquals(5_000L, second.dtMs)

        val mainThread = second.threads!!.threads.first { it.role == ThreadRole.MAIN }
        assertEquals(50L, mainThread.statTicksDelta)
        val vcpuDticks = second.threads!!.threads
            .filter { it.role == ThreadRole.VCPU }
            .map { it.statTicksDelta }
        assertEquals(listOf(500L, 0L), vcpuDticks)
    }

    @Test
    fun `stat ticks are the fallback when schedstat is unreadable`() {
        val files = baseFiles()
        files.remove("/proc/42/task/42/schedstat")
        files.remove("/proc/42/task/700/schedstat")
        files.remove("/proc/42/task/701/schedstat")
        val sampler = sampler(files)
        sampler.sample(nowMs = 1_000)

        // 100 Hz 下 5 s = 500 tick：主线程满核，vCPU0 半核，vCPU1 不动。
        files["/proc/42/task/42/stat"] = stat(42, 510, 5)
        files["/proc/42/task/700/stat"] = stat(700, 250, 0)

        val second = sampler.sample(nowMs = 6_000)

        assertEquals(100f, second.threads!!.mainCpuPercent!!, 0.01f)
        assertEquals(listOf(50f, 0f), second.threads!!.vcpuCpuPercents)
    }

    @Test
    fun `an unreadable main thread marks proc unreadable without dropping vcpu data`() {
        val files = baseFiles()
        val sampler = sampler(files)
        sampler.sample(nowMs = 1_000)

        files.remove("/proc/42/task/42/stat")
        files.remove("/proc/42/task/42/schedstat")
        files["/proc/42/task/700/schedstat"] = "5000000000 0 0"

        val second = sampler.sample(nowMs = 6_000)

        assertFalse(second.procReadable)
        assertNull("主线程读不到就是未知，不是 0", second.threads!!.mainCpuPercent)
        assertEquals(100f, second.threads!!.vcpuCpuPercents[0]!!, 0.01f)
    }

    @Test
    fun `serial delta follows file growth and refuses to guess across truncation`() {
        val serial = temp.newFile("run-a.serial.log")
        serial.writeText("abc")
        val sampler = sampler(baseFiles(), serialLog = serial)

        val first = sampler.sample(nowMs = 1_000)
        assertEquals(3L, first.serial!!.bytes)
        assertNull("首拍没有增量的基准", first.serial!!.deltaBytes)

        serial.appendText("defgh")
        val second = sampler.sample(nowMs = 6_000)
        assertEquals(8L, second.serial!!.bytes)
        assertEquals(5L, second.serial!!.deltaBytes)

        // 文件被重建（长度回退）：增量未知，不能算成负数或巨大假峰值。
        serial.writeText("a")
        val third = sampler.sample(nowMs = 11_000)
        assertEquals(1L, third.serial!!.bytes)
        assertNull(third.serial!!.deltaBytes)
    }

    // --------------------------------------------------------------- detector

    private fun qmpOk() = QmpProbe(true, 2, "running")
    private fun qmpDown() = QmpProbe(false, null, null, "query-status timeout after 1500 ms")
    private fun vncOk() = VncProbe(true, "RFB 003.008", 1, null)
    private fun vncSilent() = VncProbe(true, null, 1, "no RFB banner within 500 ms")

    private fun distribution(main: Float?, vcpu: List<Float?>): ThreadDistribution {
        val known = vcpu.filterNotNull()
        return ThreadDistribution(main, vcpu, known.takeIf { it.isNotEmpty() }?.sum(), emptyList())
    }

    /** 带完整线程明细的分布，用于 JSONL/快照断言。 */
    private fun distributionWithThreads(
        main: Float?,
        vcpu: List<Float?>,
        mainDticks: Long? = 50L,
        vcpuDticks: List<Long?> = vcpu.map { percent -> if (percent == null) null else 500L },
    ): ThreadDistribution {
        val threads = buildList {
            add(ThreadCpu(pid, ThreadRole.MAIN, main, 10, mainDticks, 500_000_000))
            vcpu.forEachIndexed { index, percent ->
                add(ThreadCpu(700 + index, ThreadRole.VCPU, percent, 0, vcpuDticks.getOrNull(index), 5_000_000_000))
            }
        }
        val known = vcpu.filterNotNull()
        return ThreadDistribution(main, vcpu, known.takeIf { it.isNotEmpty() }?.sum(), threads)
    }

    private fun sample(
        qmp: QmpProbe = qmpOk(),
        vnc: VncProbe? = vncOk(),
        threads: ThreadDistribution? = distribution(10f, listOf(100f, 50f)),
        procReadable: Boolean = true,
        dtMs: Long? = 5_000,
    ) = WatchdogSample(
        epochMs = 0,
        dtMs = dtMs,
        intervalMs = 5_000,
        qmp = qmp,
        vnc = vnc,
        serial = null,
        threads = threads,
        procReadable = procReadable,
    )

    @Test
    fun `two consecutive qmp timeouts open a window and recovery closes it`() {
        val detector = WatchdogDetector()

        val first = detector.evaluate(sample(qmp = qmpDown()))
        assertFalse("单拍超时不开窗", first.inWindow)
        assertEquals(listOf("qmp_timeout"), first.signals)
        assertEquals(DiagVerdict.WATCH, first.diagnosis)

        val second = detector.evaluate(sample(qmp = qmpDown()))
        assertTrue(second.windowOpened)
        assertTrue(second.inWindow)
        assertEquals(1, second.windowId)
        assertEquals(2, second.windowSamples)
        assertEquals(DiagVerdict.SUSPECTED_STALL, second.diagnosis)

        val healthy = detector.evaluate(sample())
        assertTrue(healthy.windowClosed)
        assertEquals(1, healthy.closedWindowId)
        assertFalse(healthy.inWindow)
        assertEquals(DiagVerdict.HEALTHY, healthy.diagnosis)
    }

    @Test
    fun `a single bad sample between healthy ones never opens a window`() {
        val detector = WatchdogDetector()

        assertFalse(detector.evaluate(sample(qmp = qmpDown())).windowOpened)
        assertFalse(detector.evaluate(sample()).windowOpened)
        assertFalse(detector.evaluate(sample(qmp = qmpDown())).windowOpened)
        assertFalse(detector.evaluate(sample()).windowOpened)
    }

    @Test
    fun `vnc silent with busy vcpus opens a stalled window`() {
        val detector = WatchdogDetector()
        val busy = distribution(main = 0.5f, vcpu = listOf(100f, 100f))

        val first = detector.evaluate(sample(vnc = vncSilent(), threads = busy))
        assertEquals(DiagVerdict.WATCH, first.diagnosis)
        val second = detector.evaluate(sample(vnc = vncSilent(), threads = busy))

        assertTrue(second.windowOpened)
        assertTrue(second.signals.contains("vnc_no_banner"))
        assertEquals("main_idle_vcpu_busy", second.stallProfile)
        assertEquals(DiagVerdict.STALLED, second.diagnosis)
    }

    @Test
    fun `main idle with busy vcpus alone is normal guest load, not a window`() {
        val detector = WatchdogDetector()
        val busy = distribution(main = 1f, vcpu = listOf(100f, 100f))

        val first = detector.evaluate(sample(threads = busy))
        val second = detector.evaluate(sample(threads = busy))

        assertFalse(first.inWindow)
        assertFalse(second.inWindow)
        assertEquals("main_idle_vcpu_busy", second.stallProfile)
        assertEquals(DiagVerdict.HEALTHY, second.diagnosis)
    }

    @Test
    fun `window ids increment per episode instead of being reused`() {
        val detector = WatchdogDetector()

        detector.evaluate(sample(qmp = qmpDown()))
        assertEquals(1, detector.evaluate(sample(qmp = qmpDown())).windowId)
        detector.evaluate(sample())

        detector.evaluate(sample(qmp = qmpDown()))
        val reopened = detector.evaluate(sample(qmp = qmpDown()))
        assertTrue(reopened.windowOpened)
        assertEquals(2, reopened.windowId)
    }

    @Test
    fun `unreadable proc is a hard signal of its own`() {
        val detector = WatchdogDetector()
        detector.evaluate(sample(procReadable = false))
        val second = detector.evaluate(sample(procReadable = false))
        assertTrue(second.windowOpened)
        assertEquals(listOf("proc_unreadable"), second.signals)
    }

    @Test
    fun `first sample is sampling and has no verdict yet`() {
        val verdict = WatchdogDetector().evaluate(sample(dtMs = null))
        assertEquals(DiagVerdict.SAMPLING, verdict.diagnosis)
    }

    // ------------------------------------------------------------------ JSONL

    @Test
    fun `watchdog json line satisfies the t4 contract`() {
        val detector = WatchdogDetector()
        val bad = sample(
            qmp = qmpDown(),
            vnc = vncSilent(),
            threads = distributionWithThreads(main = 0.4f, vcpu = listOf(100f, null)),
        )
        detector.evaluate(bad)
        val verdict = detector.evaluate(bad)
        val line = WatchdogJson.line(bad, verdict, seq = 7, vmId = "vm-a", runId = "run-b", pid = pid)

        assertFalse("NDJSON 一行不能含换行", line.contains("\n"))
        assertTrue(line.startsWith("{\"ts_utc\":\"1970-01-01T00:00:00Z\",\"vm_id\":\"vm-a\",\"run_id\":\"run-b\",\"qemu_pid\":42,"))
        assertTrue(line.contains("\"qmp_ok\":false"))
        assertTrue(line.contains("\"qmp_error\":\"query-status timeout after 1500 ms\""))
        assertTrue(line.contains("\"vnc_connect_ok\":true"))
        assertTrue(line.contains("\"vnc_banner\":null"))
        assertTrue(line.contains("\"serial_total_bytes\":null"))
        assertTrue(line.contains("\"main_thread_dticks\":50"))
        assertTrue(line.contains("\"vcpu_dticks\":[500,null]"))
        assertTrue(line.contains("\"main_cpu\":0.4"))
        assertTrue(line.contains("\"vcpu_cpu\":[100.0,null]"))
        assertTrue(line.contains("\"window_anomaly\":true"))
        assertTrue(line.contains("\"window_id\":1"))
        assertTrue(line.contains("\"signals\":[\"qmp_timeout\",\"vnc_no_banner\"]"))
        assertTrue(line.contains("\"verdict\":\"STALLED\""))
    }

    @Test
    fun `watchdog log rotates at the byte cap and keeps the newest lines`() {
        val file = temp.newFile("run-a.watchdog.jsonl")
        val log = WatchdogLog(file, maxBytes = 80)

        log.append("a".repeat(40))
        log.append("b".repeat(40))
        log.append("c".repeat(40))

        val rotated = File(file.absolutePath + ".1")
        assertTrue("超过上限必须轮转", rotated.isFile)
        assertEquals("b".repeat(40), rotated.readText().trim())
        assertEquals("c".repeat(40), file.readText().trim())
    }

    @Test
    fun `runner writes one line per sample and stops when the process exits`() = runBlocking {
        val files = baseFiles()
        val file = temp.newFile("runner.watchdog.jsonl")
        val watchdog = RuntimeWatchdog(
            sampler = sampler(files),
            detector = WatchdogDetector(),
            log = WatchdogLog(file),
            vmId = "vm-a",
            runId = "run-1",
            pid = pid,
        )
        var calls = 0

        watchdog.run(isAlive = { calls++ < 3 }, sleep = { })

        val lines = file.readText().trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.first().contains("\"seq\":1"))
        assertTrue(lines.last().contains("\"seq\":3"))
        assertTrue(lines.first().contains("\"run_id\":\"run-1\""))
    }

    // ------------------------------------------------------------ diag contract

    @Test
    fun `snapshot maps a stalled sample into the ui contract`() {
        val detector = WatchdogDetector()
        val bad = sample(
            qmp = qmpDown(),
            vnc = vncSilent(),
            threads = distributionWithThreads(main = 0.4f, vcpu = listOf(100f, 100f)),
        )
        detector.evaluate(bad)
        val verdict = detector.evaluate(bad)

        val snapshot = DiagContract.snapshot(
            "vm-a", "run-b", bad, verdict,
            processUptimeMs = 12_345L,
            processCpuPercent = 88f,
        )

        assertEquals("vm-a", snapshot.vmId)
        assertEquals("run-b", snapshot.runId)
        assertEquals(DiagVerdict.STALLED, snapshot.verdict)
        assertEquals(1, snapshot.badWindows)
        assertEquals(true, snapshot.qmpTimedOut)
        assertNull(snapshot.qmpRttMs)
        assertEquals(DiagVncBanner.NO_RESPONSE, snapshot.vncBanner)
        assertEquals(0.4f, snapshot.mainThreadCpuPercent!!, 0.01f)
        assertEquals(listOf(100f, 100f), snapshot.vcpuPercents)
        assertEquals(12_345L, snapshot.processUptimeMs)
        assertEquals(88f, snapshot.processCpuPercent!!, 0.01f)
        assertTrue("串口不可用要上报", snapshot.unavailableSignals.contains("serial"))
        assertFalse(
            "性能仪表可用时不该报 process-metrics",
            snapshot.unavailableSignals.contains("process-metrics"),
        )
        assertEquals(5_000, snapshot.windowMs)
    }

    @Test
    fun `window open emits qmp, banner and cpu events`() {
        val detector = WatchdogDetector()
        val bad = sample(
            qmp = qmpDown(),
            vnc = vncSilent(),
            threads = distributionWithThreads(main = 0.4f, vcpu = listOf(100f, 100f)),
        )
        assertTrue("首拍未开窗时不产生事件", DiagContract.eventsOnSample(bad, detector.evaluate(bad)).isEmpty())
        val verdict = detector.evaluate(bad)
        val kinds = DiagContract.eventsOnSample(bad, verdict).map { it.kind }

        assertTrue(kinds.contains("QMP_TIMEOUT"))
        assertTrue(kinds.contains("VNC_BANNER_TIMEOUT"))
        assertTrue(kinds.contains("CPU_SPIN"))
        assertTrue(kinds.contains("MAIN_THREAD_STALL"))
    }

    @Test
    fun `a long sampling gap is reported as a sample gap event`() {
        val gap = sample(dtMs = 20_000)
        val verdict = WatchdogDetector().evaluate(gap)
        val events = DiagContract.eventsOnSample(gap, verdict)
        assertTrue(events.any { it.kind == "SAMPLE_GAP" })
    }

    // -------------------------------------------------------------- VNC probe

    private fun loopbackServer(): ServerSocket =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    @Test
    fun `vnc probe reads the RFB banner from a live listener`() {
        val server = loopbackServer()
        val port = server.localPort
        val writer = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getOutputStream().write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    Thread.sleep(100)
                }
            }
        }

        val probe = VncBannerProbe.probe(port, timeoutMs = 1_000)
        server.close()
        writer.join(2_000)

        assertTrue(probe.connected)
        assertEquals("RFB 003.008", probe.banner)
        assertTrue(probe.bannerSeen)
        assertNull(probe.error)
    }

    @Test
    fun `vnc probe reports connected without banner when the server stays silent`() {
        val server = loopbackServer()
        val port = server.localPort
        val accepter = thread(isDaemon = true) {
            runCatching { server.accept().use { Thread.sleep(500) } }
        }

        val started = System.currentTimeMillis()
        val probe = VncBannerProbe.probe(port, timeoutMs = 150)
        val elapsed = System.currentTimeMillis() - started
        server.close()
        accepter.join(2_000)

        assertTrue("监听还在，TCP 握手成功", probe.connected)
        assertNull("accept 后不说话就是停摆的形态", probe.banner)
        assertFalse(probe.bannerSeen)
        assertTrue("探测必须有界（实际 ${elapsed}ms）", elapsed < 1_000)
    }

    @Test
    fun `vnc probe reports a refused connection`() {
        val server = loopbackServer()
        val port = server.localPort
        server.close()

        val probe = VncBannerProbe.probe(port, timeoutMs = 200)

        assertFalse(probe.connected)
        assertNull(probe.banner)
    }
}
