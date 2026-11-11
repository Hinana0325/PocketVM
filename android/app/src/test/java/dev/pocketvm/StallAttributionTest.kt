package dev.pocketvm

import dev.pocketvm.diagnostics.StallAttributor
import dev.pocketvm.diagnostics.StallHypothesis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停摆归因解释器的纯逻辑单测：把 Track A 采到的状态字符/syscall 解释成假设。
 * 这里不测停摆判定（那是 WatchdogDetector），只测「给定证据，归因指向哪」。
 */
class StallAttributionTest {

    @Test
    fun `syscall number is the first decimal token`() {
        assertEquals(98, StallAttributor.syscallNumber("98 0x0 0x7fff 0x7abc"))
        assertEquals(73, StallAttributor.syscallNumber("73"))
        assertEquals(68, StallAttributor.syscallNumber("  68 0x3 0x1000  "))
    }

    @Test
    fun `running and unreadable syscall are null not zero`() {
        assertNull(StallAttributor.syscallNumber("running"))
        assertNull(StallAttributor.syscallNumber(null))
        assertNull(StallAttributor.syscallNumber(""))
        assertNull(StallAttributor.syscallNumber("   "))
        assertNull(StallAttributor.syscallNumber("garbage 0x1"))
    }

    @Test
    fun `syscall names decode known numbers and fall back for unknown`() {
        assertEquals("futex", StallAttributor.syscallName(98))
        assertEquals("pwrite64", StallAttributor.syscallName(68))
        assertEquals("fsync", StallAttributor.syscallName(82))
        assertEquals("ppoll", StallAttributor.syscallName(73))
        assertEquals("epoll_pwait", StallAttributor.syscallName(22))
        assertEquals("syscall#999", StallAttributor.syscallName(999))
        assertNull(StallAttributor.syscallName(null))
    }

    @Test
    fun `main D state attributes to H2 fuse io with decoded syscall`() {
        val a = StallAttributor.attribute(mainState = "D", mainSyscall = "68 0x3 0x1000")
        assertEquals(StallHypothesis.H2_MAIN_FUSE_IO, a.hypothesis)
        assertEquals("pwrite64", a.mainSyscallName)
        assertEquals("D", a.mainState)
        assertTrue(a.conclusion.contains("D 态"))
        assertTrue(a.conclusion.contains("pwrite64"))
    }

    @Test
    fun `main futex with a D vcpu attributes to H1 and names the vcpu syscall`() {
        val a = StallAttributor.attribute(
            mainState = "S",
            mainSyscall = "98 0x0 0x7fff",
            vcpuStates = listOf("R", "D"),
            vcpuSyscalls = listOf(null, "68 0x3 0x1000"),
        )
        assertEquals(StallHypothesis.H1_VCPU_FUSE_BQL, a.hypothesis)
        assertEquals("futex", a.mainSyscallName)
        assertTrue("H1 结论应点名 D 态 vCPU 的 I/O syscall", a.conclusion.contains("pwrite64"))
    }

    @Test
    fun `H1 without vcpu syscall detail still attributes and says sync io`() {
        val a = StallAttributor.attribute(
            mainState = "S",
            mainSyscall = "98 0x0",
            vcpuStates = listOf("D"),
            vcpuSyscalls = emptyList(),
        )
        assertEquals(StallHypothesis.H1_VCPU_FUSE_BQL, a.hypothesis)
        assertTrue(a.conclusion.contains("同步 I/O"))
    }

    @Test
    fun `main futex with no D vcpu attributes to H3 lock wait`() {
        val a = StallAttributor.attribute(
            mainState = "S",
            mainSyscall = "98 0x0",
            vcpuStates = listOf("R", "R"),
            vcpuSyscalls = listOf(null, null),
        )
        assertEquals(StallHypothesis.H3_LOCK_WAIT, a.hypothesis)
        assertTrue(a.suggestion.contains("thread=single"))
    }

    @Test
    fun `main futex with no vcpu evidence is still H3 not H1`() {
        val a = StallAttributor.attribute(mainState = "S", mainSyscall = "98 0x0")
        assertEquals(StallHypothesis.H3_LOCK_WAIT, a.hypothesis)
    }

    @Test
    fun `main ppoll is normal poll not a stall blocking point`() {
        val a = StallAttributor.attribute(mainState = "S", mainSyscall = "73 0x0")
        assertEquals(StallHypothesis.NORMAL_POLL, a.hypothesis)
        assertEquals("ppoll", a.mainSyscallName)
    }

    @Test
    fun `main epoll_pwait is also normal poll`() {
        val a = StallAttributor.attribute(mainState = "S", mainSyscall = "22 0x0")
        assertEquals(StallHypothesis.NORMAL_POLL, a.hypothesis)
    }

    @Test
    fun `main R state is on cpu not a blocking stall`() {
        val a = StallAttributor.attribute(mainState = "R", mainSyscall = "running")
        assertEquals(StallHypothesis.MAIN_ON_CPU, a.hypothesis)
        assertNull("running 没有 syscall 号", a.mainSyscallName)
    }

    @Test
    fun `main S sleeping on a non futex non poll syscall is other sleep`() {
        val a = StallAttributor.attribute(mainState = "S", mainSyscall = "56 0x0")
        assertEquals(StallHypothesis.OTHER_SLEEP, a.hypothesis)
        assertEquals("openat", a.mainSyscallName)
    }

    @Test
    fun `unreadable main state is insufficient evidence`() {
        val a = StallAttributor.attribute(mainState = null, mainSyscall = null)
        assertEquals(StallHypothesis.INSUFFICIENT, a.hypothesis)
        assertNull(a.mainState)
        assertTrue(a.conclusion.contains("不可读"))
    }

    @Test
    fun `a zombie main state is insufficient not a normal verdict`() {
        val a = StallAttributor.attribute(mainState = "Z", mainSyscall = null)
        assertEquals(StallHypothesis.INSUFFICIENT, a.hypothesis)
        assertTrue(a.conclusion.contains("Z"))
    }

    // ---- H4 系统冻结（采样间隙优先于线程证据） ----

    @Test
    fun `sampling gap overrides thread evidence and attributes to system freeze`() {
        val a = StallAttributor.attribute(
            mainState = "S",
            mainSyscall = "98 0x0",
            vcpuStates = listOf("D"),
            suspendGapMs = 62_000,
        )
        assertEquals(StallHypothesis.H4_SYSTEM_FREEZE, a.hypothesis)
        assertTrue("结论要点名中断时长", a.conclusion.contains("62"))
        assertTrue(a.conclusion.contains("冻结"))
        assertTrue("建议要给出电池无限制的动作", a.suggestion.contains("无限制"))
        assertTrue("建议要给 shell 侧确认命令", a.suggestion.contains("cgroup.freeze"))
    }

    @Test
    fun `sampling gap with unreadable proc still attributes to freeze not insufficient`() {
        val a = StallAttributor.attribute(mainState = null, mainSyscall = null, suspendGapMs = 20_000)
        assertEquals(StallHypothesis.H4_SYSTEM_FREEZE, a.hypothesis)
    }

    @Test
    fun `without a gap thread evidence keeps its original attribution`() {
        val a = StallAttributor.attribute(mainState = "S", mainSyscall = "98 0x0", suspendGapMs = null)
        assertEquals(StallHypothesis.H3_LOCK_WAIT, a.hypothesis)
    }
}