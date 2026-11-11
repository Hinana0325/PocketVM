package dev.pocketvm

import android.os.PowerManager
import dev.pocketvm.runtime.AffinityMode
import dev.pocketvm.runtime.CpuTopology
import dev.pocketvm.runtime.ThermalSource
import dev.pocketvm.runtime.VmPerformanceScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmPerformanceSchedulerTest {

    private class FakeThermal(var status: Int) : ThermalSource {
        var listener: ((Int) -> Unit)? = null
        var unregisterCalls = 0

        override fun currentStatus(): Int = status

        override fun register(onStatus: (Int) -> Unit): (() -> Unit)? {
            listener = onStatus
            return { unregisterCalls++ }
        }
    }

    private val topology = CpuTopology.Topology(
        bigCores = listOf(2, 3, 4, 5, 6, 7),
        maskHex = "fc",
        littleCores = listOf(0, 1),
        efficiencyMaskHex = "3",
    )

    private fun scheduler(
        thermal: FakeThermal,
        topo: CpuTopology.Topology? = topology,
        affinities: MutableList<Pair<Int, String>> = mutableListOf(),
        priorities: MutableList<Pair<Int, Int>> = mutableListOf(),
    ): VmPerformanceScheduler = VmPerformanceScheduler(
        // Unconfined：arm/温控回调里的同步工作在调用线程直接跑完，单测无需等。
        scope = CoroutineScope(Dispatchers.Unconfined),
        thermalSource = thermal,
        readTopology = { topo ?: CpuTopology.Topology(emptyList(), null) },
        applyAffinity = { tid, mask -> affinities += tid to mask; true },
        applyPriority = { tid, priority -> priorities += tid to priority; true },
    )

    @Test
    fun `arm binds vcpu threads to performance cores and raises priority`() {
        val affinities = mutableListOf<Pair<Int, String>>()
        val priorities = mutableListOf<Pair<Int, Int>>()
        val s = scheduler(FakeThermal(PowerManager.THERMAL_STATUS_NONE), affinities = affinities, priorities = priorities)
        s.arm(listOf(101, 102))

        assertEquals(listOf(101 to "fc", 102 to "fc"), affinities)
        assertEquals(listOf(101 to -4, 102 to -4), priorities)
        val state = s.state.value
        assertEquals(AffinityMode.PERFORMANCE, state?.mode)
        assertEquals("fc", state?.appliedMaskHex)
        assertEquals(listOf(101, 102), state?.appliedTids)
    }

    @Test
    fun `moderate heat moves vcpu threads to efficiency cores and restores priority`() {
        val affinities = mutableListOf<Pair<Int, String>>()
        val priorities = mutableListOf<Pair<Int, Int>>()
        val thermal = FakeThermal(PowerManager.THERMAL_STATUS_NONE)
        val s = scheduler(thermal, affinities = affinities, priorities = priorities)
        s.arm(listOf(101))

        thermal.listener?.invoke(PowerManager.THERMAL_STATUS_MODERATE)

        assertEquals(listOf(101 to "fc", 101 to "3"), affinities)
        assertEquals(listOf(101 to -4, 101 to 0), priorities)
        assertEquals(AffinityMode.EFFICIENCY, s.state.value?.mode)
        assertEquals("3", s.state.value?.appliedMaskHex)
    }

    @Test
    fun `cooling down restores performance cores`() {
        val affinities = mutableListOf<Pair<Int, String>>()
        val thermal = FakeThermal(PowerManager.THERMAL_STATUS_MODERATE)
        val s = scheduler(thermal, affinities = affinities)
        s.arm(listOf(101))
        assertEquals(AffinityMode.EFFICIENCY, s.state.value?.mode)

        thermal.listener?.invoke(PowerManager.THERMAL_STATUS_LIGHT)

        assertEquals(listOf(101 to "3", 101 to "fc"), affinities)
        assertEquals(AffinityMode.PERFORMANCE, s.state.value?.mode)
    }

    @Test
    fun `same thermal level does not rebind`() {
        val affinities = mutableListOf<Pair<Int, String>>()
        val thermal = FakeThermal(PowerManager.THERMAL_STATUS_NONE)
        val s = scheduler(thermal, affinities = affinities)
        s.arm(listOf(101))

        thermal.listener?.invoke(PowerManager.THERMAL_STATUS_LIGHT)

        assertEquals(listOf(101 to "fc"), affinities)
    }

    @Test
    fun `no topology means no binding but thermal listener still registers`() {
        val affinities = mutableListOf<Pair<Int, String>>()
        val thermal = FakeThermal(PowerManager.THERMAL_STATUS_NONE)
        val s = scheduler(thermal, topo = CpuTopology.Topology(emptyList(), null), affinities = affinities)
        s.arm(listOf(101))

        assertTrue(affinities.isEmpty())
        assertEquals(AffinityMode.OFF, s.state.value?.mode)
        assertNull(s.state.value?.appliedMaskHex)
        assertTrue(thermal.listener != null)
    }

    @Test
    fun `hot start arms directly in efficiency mode`() {
        val affinities = mutableListOf<Pair<Int, String>>()
        val s = scheduler(
            FakeThermal(PowerManager.THERMAL_STATUS_SEVERE),
            affinities = affinities,
        )
        s.arm(listOf(101))

        assertEquals(listOf(101 to "3"), affinities)
        assertEquals(AffinityMode.EFFICIENCY, s.state.value?.mode)
    }

    @Test
    fun `disarm unregisters listener and clears state`() {
        val thermal = FakeThermal(PowerManager.THERMAL_STATUS_NONE)
        val s = scheduler(thermal)
        s.arm(listOf(101))
        assertTrue(s.state.value != null)

        s.disarm()

        assertEquals(1, thermal.unregisterCalls)
        assertNull(s.state.value)
    }
}
