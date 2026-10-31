package dev.pocketvm

import dev.pocketvm.core.model.RunManifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RunManifest 可观测字段的契约测试。
 *
 * 这些字段是 P1 报表的回溯基础：旧版本写下的 run-*.json 必须仍能解码，
 * 新字段必须能完整 round-trip；sessionId 与 runId 的层级不能被混淆。
 */
class RunManifestTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `a manifest written before this change still decodes`() {
        // 0.10.30 及更早的 run-<id>.json：没有 sessionId 等可观测字段。
        val old = """
            {
              "runId": "ab12cd34",
              "vmId": "vm-1",
              "vmName": "Windows 11",
              "appVersion": "0.10.30-qemu11.1.2-edk2026.08",
              "startedAtEpochMs": 1760000000000,
              "finishedAtEpochMs": 1760000060000,
              "executablePath": "/data/app/lib/arm64/libqemu-system-aarch64.so",
              "argv": ["--version"],
              "vncPort": 5900,
              "profile": "STANDARD",
              "attempt": 1,
              "exitCode": 0,
              "outcome": "EXITED_OK",
              "failureHint": null,
              "logExcerpt": null
            }
        """.trimIndent()

        val manifest = json.decodeFromString(RunManifest.serializer(), old)

        assertEquals("ab12cd34", manifest.runId)
        assertEquals(RunManifest.Outcome.EXITED_OK, manifest.outcome)
        assertEquals(0, manifest.exitCode)
        assertNull("旧文件没有 sessionId，不能凭空造一个", manifest.sessionId)
        assertNull(manifest.qmpConfirmedAtEpochMs)
        assertNull(manifest.runningAtEpochMs)
        assertNull(manifest.cdTransport)
        assertNull(manifest.usbInput)
        assertNull(manifest.serialEnabled)
    }

    @Test
    fun `observability fields survive an encode-decode round trip`() {
        val manifest = RunManifest(
            runId = "run-1",
            vmId = "vm-1",
            vmName = "Windows 11",
            appVersion = "0.10.30",
            startedAtEpochMs = 1_000,
            executablePath = "/x/libqemu-system-aarch64.so",
            sessionId = "sess-1",
            qmpConfirmedAtEpochMs = 2_000,
            runningAtEpochMs = 3_000,
            cdTransport = "VIRTIO_SCSI",
            usbInput = false,
            serialEnabled = true,
        )

        val encoded = json.encodeToString(RunManifest.serializer(), manifest)
        val decoded = json.decodeFromString(RunManifest.serializer(), encoded)

        assertEquals(manifest, decoded)
        assertEquals("sess-1", decoded.sessionId)
        assertEquals(2_000L, decoded.qmpConfirmedAtEpochMs)
        assertEquals(3_000L, decoded.runningAtEpochMs)
        assertEquals("VIRTIO_SCSI", decoded.cdTransport)
        assertEquals(false, decoded.usbInput)
        assertEquals(true, decoded.serialEnabled)
    }

    @Test
    fun `new fields are optional so existing constructor call sites stay valid`() {
        val manifest = RunManifest(
            runId = "r",
            vmId = "v",
            vmName = "n",
            appVersion = "a",
            startedAtEpochMs = 0,
            executablePath = "e",
        )

        assertNull(manifest.sessionId)
        assertNull(manifest.qmpConfirmedAtEpochMs)
        assertNull(manifest.runningAtEpochMs)
        assertNull(manifest.cdTransport)
        assertNull(manifest.usbInput)
        assertNull(manifest.serialEnabled)
    }

    @Test
    fun `ladder attempts share a session id but keep distinct run ids`() {
        val failedAttempt = RunManifest(
            runId = "run-a1",
            vmId = "vm-1",
            vmName = "Windows 11",
            appVersion = "0.10.30",
            startedAtEpochMs = 1_000,
            executablePath = "/x/libqemu-system-aarch64.so",
            sessionId = "sess-1",
            attempt = 1,
            outcome = RunManifest.Outcome.LAUNCH_FAILED,
            qmpConfirmedAtEpochMs = 1_500,
        )
        val runningAttempt = RunManifest(
            runId = "run-b2",
            vmId = "vm-1",
            vmName = "Windows 11",
            appVersion = "0.10.30",
            startedAtEpochMs = 2_000,
            executablePath = "/x/libqemu-system-aarch64.so",
            sessionId = "sess-1",
            attempt = 2,
            outcome = RunManifest.Outcome.RUNNING,
            qmpConfirmedAtEpochMs = 2_500,
            runningAtEpochMs = 5_000,
        )

        assertEquals(failedAttempt.sessionId, runningAttempt.sessionId)
        assertNotEquals(failedAttempt.runId, runningAttempt.runId)
        assertNull("失败档位没有 runningAt", failedAttempt.runningAtEpochMs)
        assertEquals(5_000L, runningAttempt.runningAtEpochMs)
    }
}
