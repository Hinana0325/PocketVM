package dev.pocketvm

import dev.pocketvm.diagnostics.ProcessHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wedge verdict is what turns "the app says 运行中" into something checkable,
 * so the pure decision must not fire on a scheduling hiccup and must not claim
 * anything when it has no data.
 */
class ProcessHealthTest {

    @Test
    fun `no verdict without two samples`() {
        assertNull(ProcessHealth.stalled(null, ProcessHealth.Sample(10_000, 5)))
        assertNull(ProcessHealth.stalled(ProcessHealth.Sample(0, 5), null))
        assertNull(ProcessHealth.stalled(null, null))
    }

    @Test
    fun `an interval too short to judge is not a verdict`() {
        assertNull(
            ProcessHealth.stalled(
                ProcessHealth.Sample(0, 100),
                ProcessHealth.Sample(1_000, 100),
                minIntervalMs = 5_000,
            ),
        )
    }

    @Test
    fun `frozen tick count over a long interval is a stall`() {
        assertEquals(
            true,
            ProcessHealth.stalled(
                ProcessHealth.Sample(0, 4_000),
                ProcessHealth.Sample(10_000, 4_000),
            ),
        )
    }

    @Test
    fun `advancing ticks mean the guest is still executing`() {
        assertEquals(
            false,
            ProcessHealth.stalled(
                ProcessHealth.Sample(0, 4_000),
                ProcessHealth.Sample(10_000, 4_120),
            ),
        )
    }

    @Test
    fun `nonsense pids yield no sample instead of throwing`() {
        assertNull(ProcessHealth.sample(-1))
        assertNull(ProcessHealth.sample(0))
    }
}