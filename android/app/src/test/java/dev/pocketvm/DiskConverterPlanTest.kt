package dev.pocketvm

import dev.pocketvm.storage.DiskConverter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转换计划的纯逻辑：格式名映射、无操作判定、空间门禁。
 *
 * 真正的 `qemu-img convert` 需要执行层二进制，只能在真机/CI 上验；
 * 这里保证「该不该转、转不转得下」这两件事不会算错。
 */
class DiskConverterPlanTest {

    @Test
    fun `format maps to the qemu-img spelling`() {
        assertEquals("qcow2", DiskConverter.Format.QCOW2.qemuName)
        assertEquals("raw", DiskConverter.Format.RAW.qemuName)
    }

    @Test
    fun `same format is a noop`() {
        val plan = plan(from = DiskConverter.Format.QCOW2, to = DiskConverter.Format.QCOW2)
        assertTrue(plan.isNoop)
    }

    @Test
    fun `converting between formats is not a noop`() {
        assertFalse(plan(from = DiskConverter.Format.RAW, to = DiskConverter.Format.QCOW2).isNoop)
        assertFalse(plan(from = DiskConverter.Format.QCOW2, to = DiskConverter.Format.RAW).isNoop)
    }

    @Test
    fun `free space gate uses the target size`() {
        // 目标 10 GB，可用 12 GB → 能转
        assertTrue(plan(targetBytes = 10_000, freeBytes = 12_000).fits)
        // 可用正好等于目标 → 能转（边界）
        assertTrue(plan(targetBytes = 10_000, freeBytes = 10_000).fits)
        // 可用差一个字节 → 不能转
        assertFalse(plan(targetBytes = 10_000, freeBytes = 9_999).fits)
    }

    @Test
    fun `needed space equals target size because the source stays in place`() {
        val plan = plan(targetBytes = 4_000, freeBytes = 0)
        // 转换期间原盘还在，所以额外需要的空间就是目标大小本身。
        assertEquals(4_000, plan.neededFreeBytes)
    }

    private fun plan(
        from: DiskConverter.Format = DiskConverter.Format.RAW,
        to: DiskConverter.Format = DiskConverter.Format.QCOW2,
        targetBytes: Long = 1024,
        freeBytes: Long = 4096,
    ) = DiskConverter.Plan(
        source = File("/tmp/disk.qcow2"),
        from = from,
        to = to,
        sourceBytes = 512,
        targetBytes = targetBytes,
        freeBytes = freeBytes,
    )
}
