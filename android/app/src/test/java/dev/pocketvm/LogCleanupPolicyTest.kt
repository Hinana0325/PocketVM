package dev.pocketvm

import dev.pocketvm.work.LogCleanup
import dev.pocketvm.work.LogCleanup.FileInfo
import dev.pocketvm.work.LogCleanup.RunSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3：日志清理策略的纯 JVM 测试。
 *
 * 只测纯函数（[LogCleanup.runIdOf] / [LogCleanup.groupRunSets] /
 * [LogCleanup.planRunSets] / [LogCleanup.planTempFiles]）；真删文件
 * 的 [LogCleanup.run] 要 Android 的文件系统，留给真机验证覆盖。
 */
class LogCleanupPolicyTest {

    private val nowMs = 1_800_000_000_000L // 2027-01-15 左右的固定时间点
    private val dayMs = 24L * 60 * 60 * 1000

    private fun file(name: String, ageDays: Long, size: Long = 100L) =
        FileInfo(name, nowMs - ageDays * dayMs, size)

    private fun runSet(id: String, ageDays: Long): RunSet = RunSet(
        runId = id,
        files = listOf(
            file("run-$id.log", ageDays),
            file("run-$id.serial.log", ageDays),
            file("run-$id.watchdog.jsonl", ageDays),
            file("run-$id.json", ageDays),
        ),
    )

    @Test
    fun `runIdOf parses all four run file suffixes`() {
        assertEquals("abcdef01", LogCleanup.runIdOf("run-abcdef01.log"))
        assertEquals("abcdef01", LogCleanup.runIdOf("run-abcdef01.serial.log"))
        assertEquals("abcdef01", LogCleanup.runIdOf("run-abcdef01.watchdog.jsonl"))
        assertEquals("abcdef01", LogCleanup.runIdOf("run-abcdef01.json"))
    }

    @Test
    fun `runIdOf rejects non-run files`() {
        assertNull(LogCleanup.runIdOf("config.json"))
        assertNull(LogCleanup.runIdOf("agent.jsonl"))
        assertNull(LogCleanup.runIdOf("run-abcdef01.txt"))
        assertNull(LogCleanup.runIdOf("run-.log"))
        assertNull(LogCleanup.runIdOf("disk.qcow2"))
    }

    @Test
    fun `groupRunSets groups the four files of one run together`() {
        val files = listOf(
            file("run-aaaa0001.log", 100),
            file("run-aaaa0001.serial.log", 100),
            file("run-aaaa0001.json", 100),
            file("run-bbbb0002.log", 100),
            file("config.json", 100),
        )
        val groups = LogCleanup.groupRunSets(files)
        assertEquals(2, groups.size)
        val first = groups.first { it.runId == "aaaa0001" }
        assertEquals(3, first.files.size)
        assertEquals(1, groups.first { it.runId == "bbbb0002" }.files.size)
    }

    @Test
    fun `planRunSets keeps the latest 20 runs even when old`() {
        // 25 组全是 100 天前的：只能删最旧的 5 组，最新 20 组必须留着。
        val runs = (1..25).map { i -> runSet("run%04d".format(i), 100L) }
        // 年龄相同，newestMs 相同——用 lastModified 区分新旧：越往后越新。
        val staggered = runs.mapIndexed { index, run ->
            run.copy(files = run.files.map { it.copy(lastModifiedMs = nowMs - (100L * dayMs) + index * 1000L) })
        }
        val doomed = LogCleanup.planRunSets(staggered, nowMs)
        assertEquals(5, doomed.size)
        assertEquals((1..5).map { "run%04d".format(it) }.toSet(), doomed.map { it.runId }.toSet())
    }

    @Test
    fun `planRunSets deletes nothing when fewer than 20 runs exist`() {
        val runs = (1..5).map { runSet("run%04d".format(it), 200L) }
        assertTrue(LogCleanup.planRunSets(runs, nowMs).isEmpty())
    }

    @Test
    fun `planRunSets deletes nothing younger than 90 days`() {
        val runs = (1..25).mapIndexed { index, i ->
            runSet("run%04d".format(i), 10L).copy(
                files = runSet("run%04d".format(i), 10L).files
                    .map { it.copy(lastModifiedMs = nowMs - 10L * dayMs + index * 1000L) },
            )
        }
        assertTrue(LogCleanup.planRunSets(runs, nowMs).isEmpty())
    }

    @Test
    fun `planRunSets never deletes the currently running VM logs`() {
        // 30 组：29 组是 100 天前的，1 组是 1 小时前正在跑的。
        // 最新 20 组保留（正在跑的那组一定在里面）；可删的是最旧的 10 组。
        val old = (1..29).mapIndexed { index, i ->
            runSet("run%04d".format(i), 100L).copy(
                files = runSet("run%04d".format(i), 100L).files
                    .map { it.copy(lastModifiedMs = nowMs - 100L * dayMs + index * 1000L) },
            )
        }
        val live = RunSet(
            runId = "live0001",
            files = listOf(
                file("run-live0001.log", 0).copy(lastModifiedMs = nowMs - 600_000L),
                file("run-live0001.json", 0).copy(lastModifiedMs = nowMs - 600_000L),
            ),
        )
        val doomed = LogCleanup.planRunSets(old + live, nowMs)
        assertEquals(10, doomed.size)
        assertTrue(doomed.none { it.runId == "live0001" })
    }

    @Test
    fun `planTempFiles deletes only stale part and tmp files`() {
        val files = listOf(
            file("win11.iso.123456789.part", 2),
            file("disk.qcow2.part", 2),
            file("run-abcdef01.json.tmp", 2),
            file("win11.iso.123456789.part", 0), // 刚写的同名文件：年龄 0 天，不删
            file("win11.iso", 200), // 不是临时文件，不删
            file("config.json", 200),
        )
        val doomed = LogCleanup.planTempFiles(files, nowMs)
        assertEquals(3, doomed.size)
        assertTrue(doomed.all { it.name.endsWith(".part") || it.name.endsWith(".tmp") })
    }
}
