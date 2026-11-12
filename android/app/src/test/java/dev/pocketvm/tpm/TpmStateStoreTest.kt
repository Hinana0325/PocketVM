package dev.pocketvm.tpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [TpmStateStore] 的路径规则。纯 JVM：只拼 `java.io.File` 路径，不碰 Android。
 */
class TpmStateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): TpmStateStore {
        val vmsDir = temp.newFolder("vms")
        val runDir = temp.newFolder("run")
        return TpmStateStore(vmsDir, runDir)
    }

    // Windows 上 File.absolutePath 用反斜杠，断言统一按 / 比较。
    private fun java.io.File.slashPath(): String = absolutePath.replace('\\', '/')

    @Test
    fun `state dir is vms-vmId-tpm-state and is created`() {
        val store = store()
        val dir = store.stateDir("win11")
        assertTrue(dir.slashPath().endsWith("vms/win11/tpm/state"))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `socket is run-vmId-tpm-sock on internal storage`() {
        val store = store()
        val socket = store.socketFile("win11")
        assertTrue(socket.slashPath().endsWith("run/win11.tpm.sock"))
        // socket 文件本身不由 store 创建，是 swtpm 启动时建的。
        assertFalse(socket.exists())
    }

    @Test
    fun `swtpm log sits beside state dir, not inside it`() {
        val store = store()
        val log = store.logFile("win11")
        assertTrue(log.slashPath().endsWith("vms/win11/tpm/swtpm.log"))
        assertFalse(log.slashPath().contains("tpm/state"))
    }

    @Test
    fun `path traversal vm ids are rejected`() {
        val store = store()
        for (bad in listOf(".", "..", "../x", "a/b", "a b", "", "a:b")) {
            assertThrows(IllegalArgumentException::class.java) { store.stateDir(bad) }
            assertThrows(IllegalArgumentException::class.java) { store.socketFile(bad) }
        }
    }

    @Test
    fun `overlong socket path fails fast`() {
        // 人为造一个很深的 run 目录，让 socket 绝对路径超过 108 字节。
        var deep = temp.root
        repeat(12) { deep = java.io.File(deep, "0123456789abcdef").apply { mkdirs() } }
        val store = TpmStateStore(temp.newFolder("vms"), deep)
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.socketFile("win11")
        }
        assertTrue(error.message!!.contains("过长"))
    }

    @Test
    fun `socket path under the limit is accepted`() {
        val store = store()
        val socket = store.socketFile("win11")
        assertTrue(socket.absolutePath.length < TpmStateStore.MAX_SOCKET_PATH_LENGTH)
    }

    @Test
    fun `deleteSocketFile only removes the socket`() {
        val store = store()
        val stateDir = store.stateDir("win11")
        java.io.File(stateDir, "tpm2-00.permall").writeText("fake-state")
        val socket = store.socketFile("win11")
        socket.writeText("stale")
        assertTrue(store.deleteSocketFile("win11"))
        assertFalse(socket.exists())
        // 状态文件原样保留。
        assertTrue(java.io.File(stateDir, "tpm2-00.permall").exists())
    }

    @Test
    fun `state dir lookup without creating`() {
        val store = store()
        assertFalse(store.stateDirFile("win11").exists())
        store.stateDir("win11")
        assertTrue(store.stateDirFile("win11").isDirectory)
        assertEquals(store.stateDir("win11"), store.stateDirFile("win11"))
    }
}
