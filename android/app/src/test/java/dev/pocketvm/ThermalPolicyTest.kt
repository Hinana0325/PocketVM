package dev.pocketvm

import android.os.PowerManager
import dev.pocketvm.runtime.AffinityMode
import dev.pocketvm.runtime.ThermalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun `none and light stay on performance cores`() {
        assertEquals(AffinityMode.PERFORMANCE, ThermalPolicy.modeFor(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(AffinityMode.PERFORMANCE, ThermalPolicy.modeFor(PowerManager.THERMAL_STATUS_LIGHT))
    }

    @Test
    fun `moderate and above move to efficiency cores`() {
        for (status in listOf(
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        )) {
            assertEquals(AffinityMode.EFFICIENCY, ThermalPolicy.modeFor(status))
        }
    }

    @Test
    fun `unknown status defaults to efficiency`() {
        assertEquals(AffinityMode.EFFICIENCY, ThermalPolicy.modeFor(99))
    }

    @Test
    fun `only severe and above warn`() {
        assertFalse(ThermalPolicy.shouldWarn(PowerManager.THERMAL_STATUS_NONE))
        assertFalse(ThermalPolicy.shouldWarn(PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(ThermalPolicy.shouldWarn(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(ThermalPolicy.shouldWarn(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalPolicy.shouldWarn(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(ThermalPolicy.shouldWarn(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `labels are chinese`() {
        assertEquals("正常", ThermalPolicy.label(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("轻微发热", ThermalPolicy.label(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals("中度发热", ThermalPolicy.label(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("严重发热", ThermalPolicy.label(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("危急", ThermalPolicy.label(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals("性能核", ThermalPolicy.modeLabel(AffinityMode.PERFORMANCE))
        assertEquals("能效核", ThermalPolicy.modeLabel(AffinityMode.EFFICIENCY))
        assertEquals("未绑核", ThermalPolicy.modeLabel(AffinityMode.OFF))
    }
}
