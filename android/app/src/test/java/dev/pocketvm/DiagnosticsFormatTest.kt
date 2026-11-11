package dev.pocketvm

import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.diagnostics.DiagEvent
import dev.pocketvm.diagnostics.DiagSnapshot
import dev.pocketvm.diagnostics.DiagVerdict
import dev.pocketvm.diagnostics.DiagVncBanner
import dev.pocketvm.diagnostics.SessionReport
import dev.pocketvm.diagnostics.StallHypothesis
import dev.pocketvm.ui.DiagTone
import dev.pocketvm.ui.DiagnosticsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T6 看板的格式化与展示层纯函数测试。
 *
 * 这里不测停摆判定——那是 T2 的 WatchdogDetector；UI 只被测「不许把
 * null 显示成 0」「串口静默不标红」「过期降级」「中文映射」这些契约。
 */
class DiagnosticsFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun epoch(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long =
        ZonedDateTime.of(y, m, d, h, min, s, 0, zone).toInstant().toEpochMilli()

    private fun snapshot(
        verdict: DiagVerdict = DiagVerdict.HEALTHY,
        badWindows: Int = 0,
        qmpRttMs: Int? = 42,
        qmpTimedOut: Boolean? = false,
        vncBanner: DiagVncBanner? = DiagVncBanner.OK,
        vncConnectMs: Int? = 18,
        serialBytesDelta: Long? = 0L,
        mainThreadCpuPercent: Float? = 0.8f,
        vcpuPercents: List<Float>? = listOf(96f, 97f, 95f, 96f),
        processAlive: Boolean? = true,
        processUptimeMs: Long? = 761_000L,
        procReadable: Boolean = true,
        sampledAtEpochMs: Long = epoch(2026, 10, 31, 14, 33, 12),
        windowMs: Int = 5_000,
        unavailableSignals: List<String> = emptyList(),
        mainState: String? = null,
        mainSyscall: String? = null,
        vcpuStates: List<String?> = emptyList(),
        vcpuSyscalls: List<String?> = emptyList(),
        processSuspendGapMs: Long? = null,
    ): DiagSnapshot = DiagSnapshot(
        vmId = "vm-1",
        runId = "run-1",
        windowMs = windowMs,
        sampledAtEpochMs = sampledAtEpochMs,
        verdict = verdict,
        badWindows = badWindows,
        qmpRttMs = qmpRttMs,
        qmpTimedOut = qmpTimedOut,
        vncBanner = vncBanner,
        vncConnectMs = vncConnectMs,
        serialBytesDelta = serialBytesDelta,
        serialBytesTotal = 0L,
        processAlive = processAlive,
        processUptimeMs = processUptimeMs,
        processCpuPercent = 320f,
        mainThreadCpuPercent = mainThreadCpuPercent,
        vcpuPercents = vcpuPercents,
        procReadable = procReadable,
        unavailableSignals = unavailableSignals,
        mainState = mainState,
        mainSyscall = mainSyscall,
        vcpuStates = vcpuStates,
        vcpuSyscalls = vcpuSyscalls,
        processSuspendGapMs = processSuspendGapMs,
    )

    private fun event(
        kind: String = "QMP_TIMEOUT",
        consecutive: Int = 2,
        evidence: String = "QMP query-status 连续 2 拍无响应",
        epochMs: Long = epoch(2026, 10, 31, 14, 33, 5),
    ) = DiagEvent(epochMs = epochMs, kind = kind, windowMs = 5_000, consecutive = consecutive, evidence = evidence)

    // ---- 判定 ----

    @Test
    fun `verdict 文案与 tone 覆盖全部七种`() {
        val cases = mapOf(
            DiagVerdict.NOT_RUNNING to Triple("未运行", "虚拟机未运行，没有实时信号。", DiagTone.IDLE),
            DiagVerdict.SAMPLING to Triple("采样中", "正在等待第二个采样窗口，暂不下结论。", DiagTone.IDLE),
            DiagVerdict.HEALTHY to Triple("信号正常", "主循环响应正常。", DiagTone.OK),
            DiagVerdict.WATCH to Triple("观察中", "有一个窗口出现异常，继续观察。", DiagTone.IDLE),
            DiagVerdict.SUSPECTED_STALL to Triple("疑似停摆", "关键信号同时异常，主循环可能已停摆。", DiagTone.ERROR),
            DiagVerdict.STALLED to Triple("已停摆", "连续多个异常窗口，主循环已停止响应。", DiagTone.ERROR),
            DiagVerdict.UNKNOWN to Triple("无法判定", "信号不足（部分数据不可读），暂不下结论。", DiagTone.IDLE),
        )
        cases.forEach { (verdict, expected) ->
            val ui = DiagnosticsFormat.verdictUi(verdict)
            assertEquals(expected.first, ui.label)
            assertEquals(expected.second, ui.conclusion)
            assertEquals(expected.third, ui.tone)
        }
    }

    @Test
    fun `null 判定按未运行处理`() {
        assertEquals("未运行", DiagnosticsFormat.verdictUi(null).label)
    }

    @Test
    fun `停摆动作只在 T2 判定与进程存活时出现`() {
        assertTrue(DiagnosticsFormat.offersStop(snapshot(verdict = DiagVerdict.STALLED, processAlive = true)))
        assertFalse(DiagnosticsFormat.offersStop(snapshot(verdict = DiagVerdict.STALLED, processAlive = false)))
        assertFalse(DiagnosticsFormat.offersStop(snapshot(verdict = DiagVerdict.SUSPECTED_STALL)))
        assertTrue(DiagnosticsFormat.offersLogs(DiagVerdict.SUSPECTED_STALL))
        assertTrue(DiagnosticsFormat.offersLogs(DiagVerdict.STALLED))
        assertFalse(DiagnosticsFormat.offersLogs(DiagVerdict.HEALTHY))
    }

    // ---- 样本新鲜度 ----

    @Test
    fun `3 个窗口内不算过期 超过才降级为无法判定`() {
        val s = snapshot(sampledAtEpochMs = epoch(2026, 10, 31, 14, 33, 12))
        val sampled = s.sampledAtEpochMs
        assertEquals(15_000L, DiagnosticsFormat.staleThresholdMs(5_000))
        assertFalse(DiagnosticsFormat.isStale(s, sampled + 15_000))
        assertTrue(DiagnosticsFormat.isStale(s, sampled + 15_001))
        assertEquals(DiagVerdict.HEALTHY, DiagnosticsFormat.displayVerdict(s, sampled + 15_000))
        assertEquals(DiagVerdict.UNKNOWN, DiagnosticsFormat.displayVerdict(s, sampled + 20_000))
    }

    @Test
    fun `过期提示带上相对时间`() {
        val s = snapshot(sampledAtEpochMs = epoch(2026, 10, 31, 14, 33, 12))
        val note = DiagnosticsFormat.staleNote(s, s.sampledAtEpochMs + 20_000)!!
        assertTrue(note.contains("样本已过期"))
        assertTrue(note.contains("20 秒前"))
        assertNull(DiagnosticsFormat.staleNote(s, s.sampledAtEpochMs + 3_000))
    }

    @Test
    fun `相对时间`() {
        assertEquals("3 秒前", DiagnosticsFormat.relativeAge(10_000, 7_000))
        assertEquals("0 秒前", DiagnosticsFormat.relativeAge(7_000, 10_000))
        assertEquals("1 分前", DiagnosticsFormat.relativeAge(100_000, 10_000))
        assertEquals("1 小时前", DiagnosticsFormat.relativeAge(3_700_000, 0))
    }

    // ---- 数字格式 ----

    @Test
    fun `QMP 往返格式`() {
        assertEquals("不可用", DiagnosticsFormat.formatQmpRtt(null))
        assertEquals("42 ms", DiagnosticsFormat.formatQmpRtt(42))
        assertEquals("1.2 s", DiagnosticsFormat.formatQmpRtt(1_200))
        assertEquals("2.5 s", DiagnosticsFormat.formatQmpRtt(2_500))
    }

    @Test
    fun `字节与窗口格式`() {
        assertEquals("0 B", DiagnosticsFormat.formatBytes(0))
        assertEquals("1.5 KB", DiagnosticsFormat.formatBytes(1_536))
        assertEquals("1.0 MB", DiagnosticsFormat.formatBytes(1_048_576))
        assertEquals("5 秒", DiagnosticsFormat.formatWindow(5_000))
        assertEquals("2.5 秒", DiagnosticsFormat.formatWindow(2_500))
        assertEquals("0 B / 5 秒", DiagnosticsFormat.formatBytesDelta(0, 5_000))
        assertEquals("不可用", DiagnosticsFormat.formatBytesDelta(null, 5_000))
    }

    @Test
    fun `百分比与运行时长格式`() {
        assertEquals("97%", DiagnosticsFormat.formatPercent(97f))
        assertEquals("0.8%", DiagnosticsFormat.formatPercent(0.8f, 1))
        assertEquals("0.0%", DiagnosticsFormat.formatPercent(0f, 1))
        assertEquals("不可用", DiagnosticsFormat.formatPercent(null))
        assertEquals("—", DiagnosticsFormat.formatUptimeMs(null))
        assertEquals("12:41", DiagnosticsFormat.formatUptimeMs(761_000))
        assertEquals("1:00:00", DiagnosticsFormat.formatUptimeMs(3_600_000))
        assertEquals(95.75f, DiagnosticsFormat.vcpuAveragePercents(listOf(96f, 97f, 95f, 95f))!!, 0.001f)
        assertNull(DiagnosticsFormat.vcpuAveragePercents(null))
        assertNull(DiagnosticsFormat.vcpuAveragePercents(emptyList()))
    }

    @Test
    fun `时钟与日期用固定时区格式`() {
        val at = epoch(2026, 10, 31, 14, 33, 5)
        assertEquals("14:33:05", DiagnosticsFormat.formatClock(at, zone))
        assertEquals("2026-10-31 14:33:05", DiagnosticsFormat.formatDateTime(at, zone))
    }

    // ---- 四个信号 ----

    @Test
    fun `QMP 信号覆盖正常 较慢 超时 不可用`() {
        assertEquals("正常", DiagnosticsFormat.qmpSignal(snapshot(qmpRttMs = 42)).state)
        assertEquals("较慢", DiagnosticsFormat.qmpSignal(snapshot(qmpRttMs = 800)).state)
        assertEquals(DiagTone.IDLE, DiagnosticsFormat.qmpSignal(snapshot(qmpRttMs = 800)).tone)
        val timedOut = DiagnosticsFormat.qmpSignal(snapshot(qmpRttMs = null, qmpTimedOut = true))
        assertEquals("超时", timedOut.state)
        assertEquals("无响应", timedOut.value)
        assertEquals(DiagTone.ERROR, timedOut.tone)
        assertEquals("不可用", DiagnosticsFormat.qmpSignal(snapshot(qmpRttMs = null, qmpTimedOut = null)).state)
    }

    @Test
    fun `VNC 信号覆盖四种握手结果与不可用`() {
        assertEquals("握手正常", DiagnosticsFormat.vncSignal(snapshot()).state)
        assertEquals("18 ms", DiagnosticsFormat.vncSignal(snapshot()).value)
        assertEquals("无响应", DiagnosticsFormat.vncSignal(snapshot(vncBanner = DiagVncBanner.NO_RESPONSE)).state)
        assertEquals("握手超时", DiagnosticsFormat.vncSignal(snapshot(vncBanner = DiagVncBanner.TIMEOUT)).state)
        assertEquals("连接被拒", DiagnosticsFormat.vncSignal(snapshot(vncBanner = DiagVncBanner.REFUSED)).state)
        assertEquals("不可用", DiagnosticsFormat.vncSignal(snapshot(vncBanner = null)).state)
        assertEquals(DiagTone.ERROR, DiagnosticsFormat.vncSignal(snapshot(vncBanner = DiagVncBanner.TIMEOUT)).tone)
    }

    @Test
    fun `串口静默与不可用都不标红`() {
        val silent = DiagnosticsFormat.serialSignal(snapshot(serialBytesDelta = 0L))
        assertEquals("静默", silent.state)
        assertEquals("0 B / 5 秒", silent.value)
        assertEquals(DiagTone.IDLE, silent.tone)

        val output = DiagnosticsFormat.serialSignal(snapshot(serialBytesDelta = 1_536L))
        assertEquals("有输出", output.state)
        assertEquals("1.5 KB / 5 秒", output.value)
        assertEquals(DiagTone.IDLE, output.tone)

        val unavailable = DiagnosticsFormat.serialSignal(snapshot(serialBytesDelta = null))
        assertEquals("不可用", unavailable.state)
        assertEquals("—", unavailable.value)
        assertEquals(DiagTone.IDLE, unavailable.tone)
    }

    @Test
    fun `主线程信号区分推进 缓慢 停滞与不可用`() {
        assertEquals("推进中", DiagnosticsFormat.mainThreadSignal(snapshot(mainThreadCpuPercent = 0.8f)).state)
        assertEquals("缓慢", DiagnosticsFormat.mainThreadSignal(snapshot(mainThreadCpuPercent = 0.05f)).state)
        val stalled = DiagnosticsFormat.mainThreadSignal(snapshot(mainThreadCpuPercent = 0f))
        assertEquals("停滞", stalled.state)
        assertEquals(DiagTone.ERROR, stalled.tone)
        assertTrue(stalled.value.contains("0.0%"))
        assertTrue(stalled.value.contains("vCPU 平均 96%"))
        assertEquals("不可用", DiagnosticsFormat.mainThreadSignal(snapshot(mainThreadCpuPercent = null)).state)
    }

    @Test
    fun `关键信号顺序与依据行`() {
        val signals = DiagnosticsFormat.signals(snapshot())
        assertEquals(listOf("QMP 查询", "VNC 握手", "串口输出", "主线程推进"), signals.map { it.label })
        assertEquals(
            "QMP 正常 · VNC 握手正常 · 主线程 推进中",
            DiagnosticsFormat.signalBasis(snapshot()),
        )
        assertEquals(
            "本次运行累计 2 个异常窗口（窗口 5 秒）",
            DiagnosticsFormat.badWindowText(snapshot(badWindows = 2)),
        )
        assertNull(DiagnosticsFormat.badWindowText(snapshot(badWindows = 0)))
    }

    // ---- 事件 ----

    @Test
    fun `事件类型中文映射 未知类型保留原文`() {
        assertEquals("QMP 超时", DiagnosticsFormat.eventLabel("QMP_TIMEOUT"))
        assertEquals("VNC 握手超时", DiagnosticsFormat.eventLabel("VNC_BANNER_TIMEOUT"))
        assertEquals("主线程停摆", DiagnosticsFormat.eventLabel("MAIN_THREAD_STALL"))
        assertEquals("vCPU 空转", DiagnosticsFormat.eventLabel("CPU_SPIN"))
        assertEquals("采样中断", DiagnosticsFormat.eventLabel("SAMPLE_GAP"))
        assertEquals("主线程读取失败", DiagnosticsFormat.eventLabel("PROC_UNREADABLE"))
        assertEquals("QMP 状态异常", DiagnosticsFormat.eventLabel("QMP_STATUS"))
        assertEquals("未知事件", DiagnosticsFormat.eventLabel(null))
        assertEquals("未知事件（原文：FUTURE_KIND）", DiagnosticsFormat.eventLabel("FUTURE_KIND"))
    }

    @Test
    fun `事件行包含时间与连续窗口 采样中断不写连续第 0`() {
        assertEquals(
            "14:33:05  QMP 超时 · 连续第 2 个异常窗口",
            DiagnosticsFormat.eventHeadline(event(), zone),
        )
        assertEquals(
            "14:33:05  采样中断",
            DiagnosticsFormat.eventHeadline(
                event(kind = "SAMPLE_GAP", consecutive = 0),
                zone,
            ),
        )
    }

    @Test
    fun `事件默认展示 5 条 展开到 20 条`() {
        val events = (1..25).map { event(epochMs = it.toLong() * 1_000) }
        assertEquals(5, DiagnosticsFormat.visibleEvents(events, expanded = false).size)
        assertEquals(20, DiagnosticsFormat.visibleEvents(events, expanded = true).size)
    }

    // ---- 复制摘要与原始快照 ----

    @Test
    fun `判读摘要包含契约字段与最近事件`() {
        val s = snapshot(
            verdict = DiagVerdict.STALLED,
            badWindows = 2,
            qmpRttMs = null,
            qmpTimedOut = true,
            vncBanner = DiagVncBanner.TIMEOUT,
            vncConnectMs = null,
            serialBytesDelta = 0L,
            mainThreadCpuPercent = 0f,
        )
        val text = DiagnosticsFormat.summaryText(
            vmName = "Win11 ARM64",
            vmId = "vm-1",
            snapshot = s,
            latestEvent = event(),
            nowMs = s.sampledAtEpochMs + 2_000,
            zone = zone,
        )
        assertTrue(text.contains("PocketVM 诊断摘要"))
        assertTrue(text.contains("时间：2026-10-31 14:33:12"))
        assertTrue(text.contains("虚拟机：Win11 ARM64（vm-1）"))
        assertTrue(text.contains("判定：已停摆"))
        assertTrue(text.contains("QMP：超时（无响应）"))
        assertTrue(text.contains("VNC：握手超时（无 banner）"))
        assertTrue(text.contains("串口：0 B / 5 秒（静默）"))
        assertTrue(text.contains("主线程：0.0% · vCPU 平均 96%"))
        assertTrue(text.contains("运行时长：12:41"))
        assertTrue(text.contains("最近事件：14:33:05  QMP 超时 · 连续第 2 个异常窗口"))
    }

    @Test
    fun `原始快照列出全部字段且长度受限`() {
        val text = DiagnosticsFormat.rawSnapshotText(snapshot(unavailableSignals = listOf("proc-main-thread")))
        assertTrue(text.contains("vmId=vm-1"))
        assertTrue(text.contains("runId=run-1"))
        assertTrue(text.contains("verdict=HEALTHY"))
        assertTrue(text.contains("vcpuPercents=96.0,97.0,95.0,96.0"))
        assertTrue(text.contains("unavailableSignals=proc-main-thread"))
        assertTrue(text.length <= 8_192)
    }

    // ---- 停摆归因（P1 归因半侧） ----

    @Test
    fun `只有停摆判定才给出归因 健康样本不强行解释`() {
        assertNull(DiagnosticsFormat.stallAttribution(snapshot(verdict = DiagVerdict.HEALTHY)))
        assertNull(DiagnosticsFormat.stallAttribution(snapshot(verdict = DiagVerdict.WATCH)))
        val stalled = snapshot(verdict = DiagVerdict.STALLED, mainState = "D", mainSyscall = "82 0x3")
        assertEquals(StallHypothesis.H2_MAIN_FUSE_IO, DiagnosticsFormat.stallAttribution(stalled)?.hypothesis)
    }

    @Test
    fun `判读摘要在停摆时带上归因结论与建议`() {
        val s = snapshot(
            verdict = DiagVerdict.STALLED,
            mainState = "D",
            mainSyscall = "68 0x3 0x1000",
            vcpuStates = listOf("R", "D"),
        )
        val text = DiagnosticsFormat.summaryText(
            vmName = "Win11 ARM64",
            vmId = "vm-1",
            snapshot = s,
            latestEvent = null,
            nowMs = s.sampledAtEpochMs + 2_000,
            zone = zone,
        )
        assertTrue(text.contains("停摆归因：H2 · 主线程卡 I/O"))
        assertTrue(text.contains("pwrite64"))
        assertTrue(text.contains("建议："))
    }

    @Test
    fun `健康摘要不含停摆归因`() {
        val s = snapshot(verdict = DiagVerdict.HEALTHY, mainState = "S", mainSyscall = "73 0x0")
        val text = DiagnosticsFormat.summaryText(
            vmName = "Win11 ARM64",
            vmId = "vm-1",
            snapshot = s,
            latestEvent = null,
            nowMs = s.sampledAtEpochMs + 2_000,
            zone = zone,
        )
        assertFalse(text.contains("停摆归因"))
    }

    @Test
    fun `原始快照列出状态与 syscall 证据`() {
        val text = DiagnosticsFormat.rawSnapshotText(
            snapshot(mainState = "S", mainSyscall = "98 0x0", vcpuStates = listOf("R", "D"), vcpuSyscalls = listOf(null, "68 0x3")),
        )
        assertTrue(text.contains("mainState=S"))
        assertTrue(text.contains("mainSyscall=98 0x0"))
        assertTrue(text.contains("vcpuStates=R,D"))
        assertTrue(text.contains("vcpuSyscalls=null,68 0x3"))
    }

    // ---- 系统冻结归因（H4，采样间隙触发，不要求停摆判定） ----

    @Test
    fun `健康样本带采样间隙时给出冻结归因`() {
        val s = snapshot(verdict = DiagVerdict.HEALTHY, processSuspendGapMs = 62_000)
        val a = DiagnosticsFormat.stallAttribution(s)!!
        assertEquals(StallHypothesis.H4_SYSTEM_FREEZE, a.hypothesis)
        assertTrue(a.suggestion.contains("无限制"))
    }

    @Test
    fun `冻结归因进入复制摘要 前缀区别于停摆归因`() {
        val s = snapshot(verdict = DiagVerdict.HEALTHY, processSuspendGapMs = 62_000)
        val text = DiagnosticsFormat.summaryText(
            vmName = "Win11 ARM64",
            vmId = "vm-1",
            snapshot = s,
            latestEvent = null,
            nowMs = s.sampledAtEpochMs + 2_000,
            zone = zone,
        )
        assertTrue(text.contains("冻结归因：H4 · 系统冻结进程"))
        assertTrue(text.contains("无限制"))
        assertFalse(text.contains("停摆归因"))
    }

    @Test
    fun `原始快照列出采样间隙`() {
        assertTrue(
            DiagnosticsFormat.rawSnapshotText(snapshot(processSuspendGapMs = 62_000))
                .contains("processSuspendGapMs=62000"),
        )
        assertTrue(
            DiagnosticsFormat.rawSnapshotText(snapshot()).contains("processSuspendGapMs=null"),
        )
    }

    @Test
    fun `运行清单摘要与结果映射`() {
        val started = epoch(2026, 10, 31, 14, 0, 0)
        val manifest = RunManifest(
            runId = "run-1",
            vmId = "vm-1",
            vmName = "Win11 ARM64",
            appVersion = "0.10.30",
            startedAtEpochMs = started,
            finishedAtEpochMs = started + 100_000,
            executablePath = "/data/app/lib/arm64/libqemu-system-aarch64.so",
            outcome = RunManifest.Outcome.EXITED_ERROR,
            exitCode = 132,
            failureHint = "执行层被 SIGILL 杀死（exit=132）",
            profile = "standard",
            attempt = 1,
        )
        assertEquals("异常退出", DiagnosticsFormat.outcomeLabel(RunManifest.Outcome.EXITED_ERROR))
        assertEquals("用户停止", DiagnosticsFormat.outcomeLabel(RunManifest.Outcome.STOPPED_BY_USER))
        assertEquals(100_000L, DiagnosticsFormat.manifestDurationMs(manifest))
        val text = DiagnosticsFormat.manifestSummaryText("Win11 ARM64", "vm-1", manifest, zone)
        assertTrue(text.contains("最近一次运行：异常退出（exit=132）"))
        assertTrue(text.contains("启动档位：standard（第 1 次尝试）"))
        assertTrue(text.contains("运行时长：01:40"))
        assertTrue(text.contains("失败归因：执行层被 SIGILL 杀死（exit=132）"))
    }

    @Test
    fun `无运行清单时的失败摘要可用`() {
        val text = DiagnosticsFormat.failureSummaryText(
            vmName = "Win11 ARM64",
            vmId = "vm-1",
            reason = "无法启动 QEMU 进程",
            hint = "检查执行层是否完整",
        )
        assertTrue(text.contains("最近一次运行：启动失败"))
        assertTrue(text.contains("失败原因：无法启动 QEMU 进程"))
        assertTrue(text.contains("失败归因：检查执行层是否完整"))
    }

    @Test
    fun `启动档位标签映射`() {
        assertEquals("标准", DiagnosticsFormat.profileLabel("STANDARD"))
        assertEquals("兼容", DiagnosticsFormat.profileLabel("compat"))
        assertEquals("最小（不挂介质）", DiagnosticsFormat.profileLabel("MINIMAL_NO_MEDIA"))
        assertEquals("iPhone 11", DiagnosticsFormat.profileLabel("APPLE_T8030"))
        assertEquals("—", DiagnosticsFormat.profileLabel(null))
        assertEquals("—", DiagnosticsFormat.profileLabel(""))
        assertEquals("CUSTOM", DiagnosticsFormat.profileLabel("CUSTOM"))
    }

    @Test
    fun `会话标题区分成功失败与启动耗时`() {
        val started = epoch(2026, 10, 31, 14, 0, 0)
        val failed = RunManifest(
            runId = "run-1",
            vmId = "vm-1",
            vmName = "Win11 ARM64",
            appVersion = "0.10.36",
            startedAtEpochMs = started,
            executablePath = "/x/libqemu-system-aarch64.so",
            sessionId = "sess-1",
            attempt = 1,
            profile = "STANDARD",
            outcome = RunManifest.Outcome.LAUNCH_FAILED,
            exitCode = 132,
        )
        val failedSession = SessionReport.build(listOf(failed)).sessions.single()
        val failedHeadline = DiagnosticsFormat.sessionHeadline(failedSession, zone)
        assertTrue(failedHeadline.contains("10-31 14:00"))
        assertTrue(failedHeadline.contains("标准"))
        assertTrue(failedHeadline.contains("失败"))
        assertTrue(failedHeadline.contains("exit 132"))

        val running = failed.copy(
            runId = "run-2",
            attempt = 2,
            profile = "COMPAT",
            outcome = RunManifest.Outcome.RUNNING,
            exitCode = null,
            qmpConfirmedAtEpochMs = started + 2_000,
            runningAtEpochMs = started + 12_000,
        )
        val successSession = SessionReport.build(listOf(failed, running)).sessions.single()
        val successHeadline = DiagnosticsFormat.sessionHeadline(successSession, zone)
        assertTrue(successHeadline.contains("兼容"))
        assertTrue(successHeadline.contains("第 2 次尝试"))
        assertTrue(successHeadline.contains("成功"))
        assertTrue(successHeadline.contains("到 RUNNING 12.0 s"))
        assertFalse(successHeadline.contains("exit"))
    }

    @Test
    fun `启动耗时按量级选择单位`() {
        assertEquals("不可用", DiagnosticsFormat.formatDurationMs(null))
        assertEquals("214 ms", DiagnosticsFormat.formatDurationMs(214))
        assertEquals("999 ms", DiagnosticsFormat.formatDurationMs(999))
        assertEquals("1.0 s", DiagnosticsFormat.formatDurationMs(1_000))
        assertEquals("2.7 s", DiagnosticsFormat.formatDurationMs(2_720))
        assertEquals("1 分 30 秒", DiagnosticsFormat.formatDurationMs(90_000))
    }

    @Test
    fun `会话明细显示生效的设备开关`() {
        val manifest = RunManifest(
            runId = "run-1",
            vmId = "vm-1",
            vmName = "Win11 ARM64",
            appVersion = "0.10.36",
            startedAtEpochMs = 1_000,
            executablePath = "/x/libqemu-system-aarch64.so",
            sessionId = "sess-1",
            runningAtEpochMs = 2_000,
            outcome = RunManifest.Outcome.RUNNING,
            cdTransport = "VIRTIO_SCSI",
            usbInput = false,
            serialEnabled = true,
        )
        val session = SessionReport.build(listOf(manifest)).sessions.single()
        assertEquals(
            "CD VIRTIO_SCSI · USB 输入 关 · 串口 开",
            DiagnosticsFormat.sessionDetail(session),
        )

        val unknown = SessionReport.build(
            listOf(manifest.copy(cdTransport = null, usbInput = null, serialEnabled = null)),
        ).sessions.single()
        assertEquals("CD — · USB 输入 — · 串口 —", DiagnosticsFormat.sessionDetail(unknown))
    }
}
