package dev.pocketvm

import dev.pocketvm.diagnostics.ChildProcess
import org.junit.Assert.assertEquals
import org.junit.Test

class ChildProcessTest {

    /** /proc/<pid>/stat with the given ppid; comm deliberately contains ") ". */
    private fun stat(pid: Int, ppid: Int, comm: String = "qemu-system-aarch64"): String =
        "$pid ($comm) S $ppid 1 1 0 -1 4194560 100 0 0 0 5 5 0 0 20 0 1 0 0 0 0 0 0"

    /** Fake /proc: pid -> (ppid, cmdline or null when unreadable). */
    private fun fakeProc(
        entries: Map<Int, Pair<Int, String?>>,
    ): Pair<(String) -> List<String>, (String) -> String?> {
        val listDir: (String) -> List<String> = { path ->
            if (path == "/proc") entries.keys.map { it.toString() } + listOf("self", "cpuinfo")
            else emptyList()
        }
        val readFile: (String) -> String? = { path ->
            val pid = path.removePrefix("/proc/").substringBefore('/').toIntOrNull()
            if (pid == null) null else {
                val entry = entries[pid]
                if (entry == null) null else when {
                    path.endsWith("/stat") -> stat(pid, entry.first)
                    path.endsWith("/cmdline") -> entry.second
                    else -> null
                }
            }
        }
        return listDir to readFile
    }

    @Test
    fun `picks the qemu child among the proc noise`() {
        val (listDir, readFile) = fakeProc(
            mapOf(
                1000 to (1 to null), // the app itself
                1 to (0 to "/sbin/init"), // init is not our child
                900 to (1 to "/system/bin/surfaceflinger"), // unrelated
                4242 to (1000 to "qemu-system-aarch64\u0000-machine\u0000virt\u0000"), // the one
            ),
        )
        assertEquals(4242, ChildProcess.findChildPid(1000, listDir, readFile))
    }

    @Test
    fun `lone child is taken when its cmdline cannot be read`() {
        // cmdline unreadable (zombie, or SELinux): a single direct child is
        // the qemu process by construction.
        val (listDir, readFile) = fakeProc(mapOf(1000 to (1 to null), 5555 to (1000 to null)))
        assertEquals(5555, ChildProcess.findChildPid(1000, listDir, readFile))
    }

    @Test
    fun `qemu belonging to someone else is not our child`() {
        val (listDir, readFile) = fakeProc(mapOf(1000 to (1 to null), 777 to (1 to "qemu-system-aarch64")))
        assertEquals(-1, ChildProcess.findChildPid(1000, listDir, readFile))
    }

    @Test
    fun `ambiguous children with no qemu name yield -1`() {
        val (listDir, readFile) = fakeProc(
            mapOf(
                1000 to (1 to null),
                6001 to (1000 to "/system/bin/some-helper"),
                6002 to (1000 to "/system/bin/another-helper"),
            ),
        )
        assertEquals(-1, ChildProcess.findChildPid(1000, listDir, readFile))
    }

    @Test
    fun `ppid survives a comm full of parens and spaces`() {
        assertEquals(4242, ChildProcess.ppidOf(stat(pid = 1, ppid = 4242, comm = "qemu ) weird")))
        assertEquals(7, ChildProcess.ppidOf("1 (x) R 7 1 1 0 -1 0 0 0 0 0 0 0 0 20 0 1 0 0 0 0 0 0"))
    }
}
