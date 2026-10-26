package dev.pocketvm

import dev.pocketvm.runtime.DeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `static probe only runs on 4 KB page devices`() {
        // 探针是 4 KB 对齐的 ET_EXEC；16 KB 页设备 exec 不了它。
        assertTrue(DeviceInfo.shouldProbeStaticExec(4))
        assertFalse(DeviceInfo.shouldProbeStaticExec(16))
        assertFalse(DeviceInfo.shouldProbeStaticExec(64))
        assertFalse(DeviceInfo.shouldProbeStaticExec(0))
    }
}
