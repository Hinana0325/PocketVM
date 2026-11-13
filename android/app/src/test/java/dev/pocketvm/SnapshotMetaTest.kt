package dev.pocketvm

import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.storage.SnapshotMeta
import dev.pocketvm.storage.SnapshotMetaCodec
import dev.pocketvm.storage.SnapshotPolicy
import dev.pocketvm.storage.withSnapshotProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照的纯函数契约：id/tag 规则、元数据编解码、停止状态门禁。 */
class SnapshotMetaTest {

    @Test
    fun `snapshot id is timestamp plus short suffix`() {
        val id = SnapshotMetaCodec.newId(1_700_000_000_000, "A1B2")
        assertTrue(id, Regex("^\\d{8}-\\d{6}-[a-z0-9]{4}$").matches(id))
        val other = SnapshotMetaCodec.newId(1_700_000_000_000, "ffff")
        assertTrue(id != other)
    }

    @Test
    fun `disk tag is namespaced and stable`() {
        val tag = SnapshotMetaCodec.diskTag("20261112-141530-a1b2")
        assertEquals("pvm-20261112-141530-a1b2", tag)
        assertTrue(tag.length < 200)
    }

    @Test
    fun `metadata round trips`() {
        val meta = SnapshotMeta(
            id = "20261112-141530-a1b2",
            label = "装完驱动后",
            createdAtEpochMs = 1_700_000_000_000,
            diskTag = "pvm-20261112-141530-a1b2",
            varsBytes = 67_108_864,
            tpmIncluded = true,
            auto = false,
        )
        val decoded = SnapshotMetaCodec.decode(SnapshotMetaCodec.encode(meta))
        assertEquals(meta, decoded)
        assertNull(SnapshotMetaCodec.decode("not json"))
        assertNotNull(decoded)
    }

    @Test
    fun `only stopped vms may touch snapshots`() {
        assertNull(SnapshotPolicy.blockReason(null, VmRuntimeState.Idle))
        assertNull(SnapshotPolicy.blockReason(null, VmRuntimeState.Exited("vm", 0)))
        assertTrue(SnapshotPolicy.blockReason("vm", VmRuntimeState.Idle) != null)
        assertTrue(SnapshotPolicy.blockReason(null, VmRuntimeState.Preparing("vm", "创建虚拟磁盘")) != null)
        assertTrue(SnapshotPolicy.blockReason(null, VmRuntimeState.Stopping("vm")) != null)
    }

    @Test
    fun `live snapshots only when the vm is actually running`() {
        val running = VmRuntimeState.Running("vm", 4242, 1_700_000_000_000, 5901, "/tmp/run.log")
        assertNull(SnapshotPolicy.liveBlockReason("vm", running))
        // 没在跑就该用停止态快照（能连 UEFI 变量一起存）。
        assertTrue(SnapshotPolicy.liveBlockReason(null, VmRuntimeState.Idle) != null)
        assertTrue(SnapshotPolicy.liveBlockReason(null, VmRuntimeState.Exited("vm", 0)) != null)
        // 启动/关机过程中即使进程还在，也不能存内存快照。
        assertTrue(SnapshotPolicy.liveBlockReason("vm", VmRuntimeState.Preparing("vm", "启动 QEMU")) != null)
        assertTrue(SnapshotPolicy.liveBlockReason("vm", VmRuntimeState.Stopping("vm")) != null)
    }

    @Test
    fun `live flag round trips and defaults to false`() {
        val live = SnapshotMeta(
            id = "20261113-221500-ab12",
            label = "运行中",
            createdAtEpochMs = 1_700_000_000_000,
            diskTag = "pvm-20261113-221500-ab12",
            varsBytes = 0,
            tpmIncluded = false,
            live = true,
        )
        assertEquals(live, SnapshotMetaCodec.decode(SnapshotMetaCodec.encode(live)))
    }

    @Test
    fun `legacy snapshot json without live field still decodes`() {
        // 0.11.0 之前写下的快照元数据没有 live 字段，升级后必须还能读出来。
        val legacy = """
            {
              "id": "20261112-141530-a1b2",
              "label": "旧快照",
              "createdAtEpochMs": 1700000000000,
              "diskTag": "pvm-20261112-141530-a1b2",
              "varsBytes": 67108864,
              "tpmIncluded": true,
              "auto": false
            }
        """.trimIndent()

        val meta = SnapshotMetaCodec.decode(legacy)

        assertNotNull(meta)
        assertEquals(false, meta!!.live)
        assertEquals(true, meta.tpmIncluded)
    }

    @Test
    fun `live snapshot deletion follows stopped-state policy instead of restore policy`() {
        val live = SnapshotMeta(
            id = "20261113-221500-ab12",
            label = "运行中",
            createdAtEpochMs = 1_700_000_000_000,
            diskTag = "pvm-20261113-221500-ab12",
            varsBytes = 0,
            tpmIncluded = false,
            live = true,
        )
        val running = VmRuntimeState.Running("vm", 4242, 1_700_000_000_000, 5901, "/tmp/run.log")
        val runningBlock = SnapshotPolicy.blockReason("vm", running)
        val runningLiveBlock = SnapshotPolicy.liveBlockReason("vm", running)
        val stoppedBlock = SnapshotPolicy.blockReason(null, VmRuntimeState.Idle)
        val stoppedLiveBlock = SnapshotPolicy.liveBlockReason(null, VmRuntimeState.Idle)

        assertTrue(SnapshotPolicy.restoreAllowed(live, runningBlock, runningLiveBlock))
        assertFalse(SnapshotPolicy.deleteAllowed(runningBlock))
        assertFalse(SnapshotPolicy.restoreAllowed(live, stoppedBlock, stoppedLiveBlock))
        assertTrue(SnapshotPolicy.deleteAllowed(stoppedBlock))
    }

    @Test
    fun `snapshot progress is cleared when the operation fails`() {
        val updates = mutableListOf<String?>()

        val failure = assertThrows(IllegalStateException::class.java) {
            withSnapshotProgress({ updates.add(it) }, "working") {
                error("boom")
            }
        }

        assertEquals("boom", failure.message)
        assertEquals(listOf("working", null), updates)
    }
}
