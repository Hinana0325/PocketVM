package dev.pocketvm

import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.storage.SnapshotMeta
import dev.pocketvm.storage.SnapshotMetaCodec
import dev.pocketvm.storage.SnapshotPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
