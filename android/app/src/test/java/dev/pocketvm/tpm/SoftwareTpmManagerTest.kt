package dev.pocketvm.tpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * swtpm 启动参数拼装。纯函数，不 spawn 进程——宿主机是 x86_64，
 * arm64 的 swtpm 二进制在这里跑不起来，真的启动只在真机上验。
 */
class SoftwareTpmManagerTest {

    @Test
    fun `argv is socket tpm2 with state dir and unixio ctrl`() {
        val argv = swtpmArgv(
            executable = "/data/app/lib/arm64/libswtpm.so",
            stateDir = "/storage/emulated/0/Android/data/dev.pocketvm/files/vms/win11/tpm/state",
            socketPath = "/data/user/0/dev.pocketvm/files/internal/run/win11.tpm.sock",
        )
        assertEquals(
            listOf(
                "/data/app/lib/arm64/libswtpm.so",
                "socket",
                "--tpm2",
                "--tpmstate",
                "dir=/storage/emulated/0/Android/data/dev.pocketvm/files/vms/win11/tpm/state",
                "--ctrl",
                "type=unixio,path=/data/user/0/dev.pocketvm/files/internal/run/win11.tpm.sock",
            ),
            argv,
        )
    }

    @Test
    fun `argv never daemonizes`() {
        val argv = swtpmArgv("/libswtpm.so", "/state", "/run/x.tpm.sock")
        assertFalse(argv.contains("--daemon"))
        assertFalse(argv.any { it.contains("daemon", ignoreCase = true) })
    }
}
