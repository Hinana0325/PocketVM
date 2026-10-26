package dev.pocketvm

import dev.pocketvm.runtime.CpuTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuTopologyTest {

    @Test
    fun `snapdragon 8 gen 3 picks the six performance cores`() {
        val freqs = mapOf(
            0 to 2_265_600L, 1 to 2_265_600L,
            2 to 3_148_800L, 3 to 3_148_800L, 4 to 3_148_800L,
            5 to 2_956_800L, 6 to 2_956_800L,
            7 to 3_302_400L,
        )
        val topology = CpuTopology.fromMaxFreqs(freqs)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), topology.bigCores)
        assertEquals("fc", topology.maskHex)
    }

    @Test
    fun `homogeneous cores are not pinned`() {
        val topology = CpuTopology.fromMaxFreqs(mapOf(0 to 2_000_000L, 1 to 2_000_000L, 2 to 2_000_000L))
        assertNull("同频设备不需要绑核", topology.maskHex)
        assertEquals(listOf(0, 1, 2), topology.bigCores)
    }

    @Test
    fun `missing or single core returns no mask`() {
        assertNull(CpuTopology.fromMaxFreqs(emptyMap()).maskHex)
        assertNull(CpuTopology.fromMaxFreqs(mapOf(0 to 1_800_000L)).maskHex)
        assertNull(CpuTopology.fromMaxFreqs(mapOf(0 to 0L, 1 to 0L)).maskHex)
    }

    @Test
    fun `two-tier clusters keep only the top tier`() {
        val topology = CpuTopology.fromMaxFreqs(
            mapOf(0 to 1_800_000L, 1 to 1_800_000L, 2 to 2_600_000L, 3 to 2_600_000L),
        )
        assertEquals(listOf(2, 3), topology.bigCores)
        assertEquals("c", topology.maskHex)
    }
}
