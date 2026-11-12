package dev.pocketvm

import dev.pocketvm.runtime.AccelerationFacts
import dev.pocketvm.runtime.AccelerationPolicy
import dev.pocketvm.runtime.AccelerationVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 硬件加速只读判定（roadmap §5）：纯函数，不碰设备。 */
class AccelerationPolicyTest {

    private fun facts(
        exists: Boolean = false,
        readable: Boolean = false,
        writable: Boolean = false,
        openError: String? = null,
    ) = AccelerationFacts(
        kvmExists = exists,
        kvmReadable = readable,
        kvmWritable = writable,
        openError = openError,
        runningUnderHypervisor = false,
        kernelRelease = "test",
    )

    @Test
    fun `no kvm node means absent`() {
        assertEquals(AccelerationVerdict.ABSENT, AccelerationPolicy.verdict(facts()))
    }

    @Test
    fun `node present but open denied means present but denied`() {
        val f = facts(exists = true, openError = "Permission denied")
        assertEquals(AccelerationVerdict.PRESENT_BUT_DENIED, AccelerationPolicy.verdict(f))
    }

    @Test
    fun `node present but not writable is still denied`() {
        val f = facts(exists = true, readable = true, writable = false, openError = null)
        assertEquals(AccelerationVerdict.PRESENT_BUT_DENIED, AccelerationPolicy.verdict(f))
    }

    @Test
    fun `node readable writable and openable means available`() {
        val f = facts(exists = true, readable = true, writable = true, openError = null)
        assertEquals(AccelerationVerdict.AVAILABLE, AccelerationPolicy.verdict(f))
    }

    @Test
    fun `summary always explains next step and never promises acceleration`() {
        for (f in listOf(
            facts(),
            facts(exists = true, openError = "Permission denied"),
            facts(exists = true, readable = true, writable = true),
        )) {
            val text = AccelerationPolicy.summary(f)
            assertTrue("摘要不能为空", text.isNotBlank())
            // 只读探测，不允许出现「已启用」这类承诺。
            assertTrue(text, !text.contains("已启用"))
        }
    }
}
