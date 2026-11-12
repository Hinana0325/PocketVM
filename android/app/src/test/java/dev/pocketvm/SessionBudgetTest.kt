package dev.pocketvm

import dev.pocketvm.runtime.SessionBudget
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多机同跑的内存预算（roadmap P3）：纯函数判定。 */
class SessionBudgetTest {

    @Test
    fun `unknown device memory never blocks`() {
        assertNull(SessionBudget.blockReason(deviceMemoryMb = 0, runningMemoryMb = 0, requestedMemoryMb = 8192))
    }

    @Test
    fun `single vm within budget is allowed`() {
        assertNull(SessionBudget.blockReason(15159, 0, 8192))
    }

    @Test
    fun `second vm is blocked when budget cannot hold both plus reserve`() {
        // 已运行 8 GB，再开 8 GB：8192+8192+1024 > 15159
        val reason = SessionBudget.blockReason(15159, 8192, 8192)
        assertTrue(reason != null && reason.contains("内存不足"))
    }

    @Test
    fun `small second vm is allowed`() {
        // 4096+2048+1024 = 7168 <= 15159
        assertNull(SessionBudget.blockReason(15159, 4096, 2048))
    }
}
