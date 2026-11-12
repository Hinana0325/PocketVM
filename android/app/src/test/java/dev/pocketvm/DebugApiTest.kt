package dev.pocketvm

import dev.pocketvm.data.DebugPreferences
import dev.pocketvm.debug.BuildInfo
import dev.pocketvm.debug.DebugApi
import dev.pocketvm.debug.DebugSnapshotBuilder
import dev.pocketvm.debug.DiagnosticsResponse
import dev.pocketvm.debug.SnapshotResponse
import dev.pocketvm.debug.StatusResponse
import dev.pocketvm.debug.VmSummary
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调试接口的纯函数契约（docs/debug-api.md §9）：
 * token 生成、鉴权判据、lines 钳制、快照 JSON 结构。路由级测试依赖
 * Android 运行时（VmSession/DataStore），这里不搭。
 */
class DebugApiTest {

    @Test
    fun `token is 64 lowercase hex and unique per call`() {
        val a = DebugPreferences.newToken(SecureRandom())
        val b = DebugPreferences.newToken(SecureRandom())
        assertTrue("长度 64：${a.length}", a.length == 64)
        assertTrue("全小写 hex：$a", Regex("^[0-9a-f]{64}$").matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `authorized rejects missing wrong and mismatched tokens`() {
        val token = "a".repeat(64)
        assertFalse(DebugApi.authorized(null, token))
        assertFalse(DebugApi.authorized("", token))
        assertFalse(DebugApi.authorized(token, null))
        assertFalse(DebugApi.authorized(token, ""))
        assertFalse(DebugApi.authorized(token, "b".repeat(64)))
        // 长度不同也不能因为"前缀相同"通过。
        assertFalse(DebugApi.authorized(token, token.dropLast(1)))
        assertFalse(DebugApi.authorized(token, token + "0"))
        assertTrue(DebugApi.authorized(token, token))
    }

    @Test
    fun `lines are clamped into 1 to 1000`() {
        assertEquals(200, DebugApi.clampLines(null))
        assertEquals(1, DebugApi.clampLines(0))
        assertEquals(1, DebugApi.clampLines(-5))
        assertEquals(200, DebugApi.clampLines(200))
        assertEquals(1000, DebugApi.clampLines(2000))
    }

    @Test
    fun `snapshot json has required sections and no credentials`() {
        val build = BuildInfo(
            appVersion = "0.11.0-test",
            versionCode = 58,
            packageName = "dev.pocketvm.debug",
            agpVersion = "9.1.1",
            kotlinVersion = "2.2.10",
        )
        val status = StatusResponse(
            app = build,
            uptimeMs = 1234,
            vmCount = 1,
            runningVmId = "vm-1",
            runningVmName = "Windows 11",
            serverStartedAtEpochMs = 99,
        )
        val vm = VmSummary(
            id = "vm-1",
            name = "Windows 11",
            platform = "UEFI_ARM64",
            running = true,
            cpuCores = 8,
            memoryMb = 8192,
            diskSizeGb = 64,
            diskUsedBytes = 12_000_000_000,
            displayDevice = "RAMFB",
            vncPort = 5900,
        )
        val diag = DiagnosticsResponse(vmId = "vm-1", agentFeed = listOf("{\"event\":\"app.start\"}"))
        val logs = mapOf("vm-1" to (1..250).map { "line-$it" })

        val snap: SnapshotResponse = DebugSnapshotBuilder.build(
            generatedAtEpochMs = 42,
            status = status,
            vms = listOf(vm),
            diagnostics = diag,
            logs = logs,
        )

        // 日志尾巴只保留最后 200 行。
        assertEquals(200, snap.logs.getValue("vm-1").size)
        assertEquals("line-51", snap.logs.getValue("vm-1").first())

        val text = Json { encodeDefaults = true }.encodeToString(SnapshotResponse.serializer(), snap)
        for (field in listOf("status", "vms", "diagnostics", "logs", "generatedAtEpochMs")) {
            assertTrue("缺少字段 $field", text.contains("\"$field\""))
        }
        // 排除清单：token/密码类字段一个都不能出现。
        for (forbidden in listOf("token", "Token", "password", "vncPassword")) {
            assertFalse("快照里不应出现 $forbidden", text.contains(forbidden))
        }
    }
}
