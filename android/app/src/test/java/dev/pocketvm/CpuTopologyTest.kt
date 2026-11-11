package dev.pocketvm

import dev.pocketvm.runtime.CpuTopology
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CpuTopologyTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    @Test
    fun `capacities distinguish prime mid and little cores`() {
        // 代表值（X4 1024 / A720 830 / A520 320）：容量比频率拉得开，
        // 60% 阈值把中核留在性能档，40% 阈值把小核划为能效档。
        val capacities = mapOf(
            0 to 320L, 1 to 320L,
            2 to 830L, 3 to 830L, 4 to 830L,
            5 to 830L, 6 to 830L,
            7 to 1024L,
        )
        val topology = CpuTopology.fromCapacities(capacities)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), topology.bigCores)
        assertEquals("fc", topology.maskHex)
        assertEquals(listOf(0, 1), topology.littleCores)
        assertEquals("3", topology.efficiencyMaskHex)
    }

    @Test
    fun `homogeneous capacities are not pinned`() {
        val topology = CpuTopology.fromCapacities(mapOf(0 to 1024L, 1 to 1024L, 2 to 1024L))
        assertNull(topology.maskHex)
        assertNull(topology.efficiencyMaskHex)
        assertEquals(emptyList<Int>(), topology.littleCores)
    }

    @Test
    fun `two-tier capacities keep only the top tier for performance`() {
        val topology = CpuTopology.fromCapacities(
            mapOf(0 to 300L, 1 to 300L, 2 to 800L, 3 to 800L),
        )
        assertEquals(listOf(2, 3), topology.bigCores)
        assertEquals("c", topology.maskHex)
        assertEquals(listOf(0, 1), topology.littleCores)
        assertEquals("3", topology.efficiencyMaskHex)
    }

    @Test
    fun `single top-tier core is not pinned`() {
        // 顶档只有一颗核：绑核会让全部 vCPU 挤在一颗核上，不如不绑。
        val topology = CpuTopology.fromCapacities(
            mapOf(0 to 300L, 1 to 300L, 2 to 500L, 3 to 1024L),
        )
        assertNull(topology.maskHex)
        assertEquals(listOf(3), topology.bigCores)
    }

    @Test
    fun `read prefers cpu_capacity over max freq`() {
        // 假 sysfs：容量说 2/3 是性能核，频率故意写反（0/1 更高）。
        writeCpu(0, capacity = 320L, maxFreq = 3_000_000L)
        writeCpu(1, capacity = 320L, maxFreq = 3_000_000L)
        writeCpu(2, capacity = 1024L, maxFreq = 1_800_000L)
        writeCpu(3, capacity = 1024L, maxFreq = 1_800_000L)
        val topology = CpuTopology.read(temp.root)
        assertEquals(listOf(2, 3), topology.bigCores)
        assertEquals("c", topology.maskHex)
        assertEquals(listOf(0, 1), topology.littleCores)
    }

    @Test
    fun `read falls back to max freq when any capacity is missing`() {
        writeCpu(0, capacity = 320L, maxFreq = 1_800_000L)
        writeCpu(1, capacity = null, maxFreq = 1_800_000L)
        writeCpu(2, capacity = 1024L, maxFreq = 2_600_000L)
        writeCpu(3, capacity = 1024L, maxFreq = 2_600_000L)
        val topology = CpuTopology.read(temp.root)
        assertEquals(listOf(2, 3), topology.bigCores)
        assertEquals("c", topology.maskHex)
    }

    private fun writeCpu(cpu: Int, capacity: Long?, maxFreq: Long) {
        val dir = File(temp.root, "cpu$cpu").apply { mkdirs() }
        capacity?.let { File(dir, "cpu_capacity").writeText("$it\n") }
        File(dir, "cpufreq").mkdirs()
        File(dir, "cpufreq/cpuinfo_max_freq").writeText("$maxFreq\n")
    }
}
