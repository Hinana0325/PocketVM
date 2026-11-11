package dev.pocketvm

import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.diagnostics.SessionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动报表聚合的纯逻辑单测。
 *
 * 契约：按 sessionId 分组（旧清单回退 runId）、成功判据是「进入过 RUNNING」、
 * 启动耗时只取成功 attempt、exit 直方图统计每一次 attempt、空输入不把 0/0
 * 显示成 0%。
 */
class SessionReportTest {

    private fun manifest(
        runId: String,
        sessionId: String? = null,
        attempt: Int = 1,
        startedAtEpochMs: Long = 1_000,
        qmpConfirmedAtEpochMs: Long? = null,
        runningAtEpochMs: Long? = null,
        profile: String? = null,
        outcome: RunManifest.Outcome = RunManifest.Outcome.STARTING,
        exitCode: Int? = null,
        cdTransport: String? = null,
        usbInput: Boolean? = null,
        serialEnabled: Boolean? = null,
    ): RunManifest = RunManifest(
        runId = runId,
        vmId = "vm-1",
        vmName = "Windows 11",
        appVersion = "0.10.36",
        startedAtEpochMs = startedAtEpochMs,
        executablePath = "/data/app/lib/arm64/libqemu-system-aarch64.so",
        sessionId = sessionId,
        attempt = attempt,
        profile = profile,
        outcome = outcome,
        exitCode = exitCode,
        qmpConfirmedAtEpochMs = qmpConfirmedAtEpochMs,
        runningAtEpochMs = runningAtEpochMs,
        cdTransport = cdTransport,
        usbInput = usbInput,
        serialEnabled = serialEnabled,
    )

    @Test
    fun `ladder attempts group into one session and the boot attempt supplies the timings`() {
        val report = SessionReport.build(
            listOf(
                manifest(
                    runId = "run-a1",
                    sessionId = "sess-1",
                    attempt = 1,
                    startedAtEpochMs = 1_000,
                    qmpConfirmedAtEpochMs = 1_500,
                    profile = "STANDARD",
                    outcome = RunManifest.Outcome.LAUNCH_FAILED,
                    exitCode = 1,
                ),
                manifest(
                    runId = "run-b2",
                    sessionId = "sess-1",
                    attempt = 2,
                    startedAtEpochMs = 2_000,
                    qmpConfirmedAtEpochMs = 2_500,
                    runningAtEpochMs = 6_000,
                    profile = "COMPAT",
                    outcome = RunManifest.Outcome.RUNNING,
                    cdTransport = "VIRTIO_SCSI",
                    usbInput = false,
                    serialEnabled = true,
                ),
            ),
        )

        assertEquals(1, report.sessions.size)
        val session = report.sessions.single()
        assertEquals("sess-1", session.sessionId)
        assertEquals(2, session.attempts)
        assertEquals(1_000L, session.startedAtEpochMs)
        assertTrue("任一 attempt 进入 RUNNING 即成功", session.succeeded)
        assertEquals("COMPAT", session.finalProfile)
        assertEquals(500L, session.timeToQmpMs)
        assertEquals(4_000L, session.timeToRunningMs)
        assertEquals(listOf(1), session.exitCodes)
        assertEquals("VIRTIO_SCSI", session.cdTransport)
        assertEquals(false, session.usbInput)
        assertEquals(true, session.serialEnabled)
        assertEquals(1, report.totals.sessionCount)
        assertEquals(2, report.totals.attemptCount)
        assertEquals(1, report.totals.successCount)
    }

    @Test
    fun `old manifests without session id fall back to one session per run`() {
        val report = SessionReport.build(
            listOf(
                manifest(
                    runId = "old-ok",
                    startedAtEpochMs = 1_000,
                    runningAtEpochMs = 4_000,
                    outcome = RunManifest.Outcome.EXITED_OK,
                    exitCode = 0,
                ),
                manifest(
                    runId = "old-failed",
                    startedAtEpochMs = 2_000,
                    outcome = RunManifest.Outcome.LAUNCH_FAILED,
                    exitCode = 1,
                ),
            ),
        )

        assertEquals(2, report.sessions.size)
        assertEquals(setOf("old-ok", "old-failed"), report.sessions.map { it.sessionId }.toSet())
        assertEquals(1, report.totals.successCount)
        assertEquals(0.5f, report.totals.successRate ?: -1f, 0.0001f)
    }

    @Test
    fun `a failed session keeps its qmp timing but has no running timing`() {
        val report = SessionReport.build(
            listOf(
                manifest(
                    runId = "run-fail",
                    sessionId = "sess-fail",
                    startedAtEpochMs = 1_000,
                    qmpConfirmedAtEpochMs = 2_000,
                    profile = "STANDARD",
                    outcome = RunManifest.Outcome.LAUNCH_FAILED,
                    exitCode = 132,
                ),
            ),
        )

        val session = report.sessions.single()
        assertFalse(session.succeeded)
        assertEquals(1_000L, session.timeToQmpMs)
        assertNull("失败会话不能凭空有启动耗时", session.timeToRunningMs)
        assertEquals(RunManifest.Outcome.LAUNCH_FAILED, session.finalOutcome)
        assertEquals(132, session.finalExitCode)
        assertEquals(0, report.totals.successCount)
        assertEquals(0f, report.totals.successRate ?: -1f, 0.0001f)
        assertEquals(1_000L, report.totals.medianTimeToQmpMs)
        assertNull(report.totals.medianTimeToRunningMs)
    }

    @Test
    fun `an old RUNNING outcome without runningAt still counts as success`() {
        val report = SessionReport.build(
            listOf(
                manifest(
                    runId = "old-running",
                    outcome = RunManifest.Outcome.RUNNING,
                    runningAtEpochMs = null,
                ),
            ),
        )

        assertTrue("旧清单以 outcome 兜底判定成功", report.sessions.single().succeeded)
        assertNull(report.sessions.single().timeToRunningMs)
    }

    @Test
    fun `sessions are ordered newest first`() {
        val report = SessionReport.build(
            listOf(
                manifest(runId = "a", startedAtEpochMs = 100),
                manifest(runId = "b", startedAtEpochMs = 300),
                manifest(runId = "c", startedAtEpochMs = 200),
            ),
        )

        assertEquals(listOf("b", "c", "a"), report.sessions.map { it.sessionId })
    }

    @Test
    fun `exit histogram counts every attempt across sessions`() {
        val report = SessionReport.build(
            listOf(
                manifest(runId = "r1", sessionId = "s1", attempt = 1, exitCode = 1),
                manifest(runId = "r2", sessionId = "s1", attempt = 2, exitCode = 132),
                manifest(runId = "r3", sessionId = "s2", attempt = 1, exitCode = 0),
            ),
        )

        assertEquals(mapOf(0 to 1, 1 to 1, 132 to 1), report.totals.exitHistogram)
        assertEquals(2, report.totals.sessionCount)
        assertEquals(3, report.totals.attemptCount)
    }

    @Test
    fun `median handles an even sample without floating point`() {
        val report = SessionReport.build(
            listOf(
                manifest(runId = "r1", startedAtEpochMs = 1, runningAtEpochMs = 1_001),
                manifest(runId = "r2", startedAtEpochMs = 2, runningAtEpochMs = 2_002),
                manifest(runId = "r3", startedAtEpochMs = 3, runningAtEpochMs = 3_003),
                manifest(runId = "r4", startedAtEpochMs = 4, runningAtEpochMs = 10_004),
            ),
        )

        assertEquals(2_500L, report.totals.medianTimeToRunningMs)
        assertNull("没有 QMP 时间戳时中位数必须为 null", report.totals.medianTimeToQmpMs)
    }

    @Test
    fun `empty input yields an empty report with null rates`() {
        val report = SessionReport.build(emptyList())

        assertTrue(report.sessions.isEmpty())
        assertEquals(0, report.totals.sessionCount)
        assertEquals(0, report.totals.attemptCount)
        assertEquals(0, report.totals.successCount)
        assertNull("0/0 不能显示成 0%", report.totals.successRate)
        assertNull(report.totals.medianTimeToQmpMs)
        assertNull(report.totals.medianTimeToRunningMs)
        assertTrue(report.totals.profileDistribution.isEmpty())
        assertTrue(report.totals.exitHistogram.isEmpty())
    }

    @Test
    fun `profile distribution counts sessions once by their final attempt`() {
        val report = SessionReport.build(
            listOf(
                manifest(runId = "r1", sessionId = "s1", attempt = 1, profile = "STANDARD"),
                manifest(runId = "r2", sessionId = "s1", attempt = 2, profile = "COMPAT"),
                manifest(runId = "r3", sessionId = "s2", attempt = 1, profile = "STANDARD"),
            ),
        )

        assertEquals(mapOf("STANDARD" to 1, "COMPAT" to 1), report.totals.profileDistribution)
    }

    @Test
    fun `negative clock skew is reported as unknown instead of a negative duration`() {
        val report = SessionReport.build(
            listOf(
                manifest(
                    runId = "r1",
                    startedAtEpochMs = 5_000,
                    qmpConfirmedAtEpochMs = 4_000,
                    runningAtEpochMs = 3_000,
                    outcome = RunManifest.Outcome.RUNNING,
                ),
            ),
        )

        val session = report.sessions.single()
        assertNull(session.timeToQmpMs)
        assertNull(session.timeToRunningMs)
    }
}
