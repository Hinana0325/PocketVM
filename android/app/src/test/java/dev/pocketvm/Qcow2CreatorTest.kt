package dev.pocketvm

import dev.pocketvm.storage.Qcow2Creator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class Qcow2CreatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gib = 1024L * 1024 * 1024

    @Test
    fun `a 256 GiB image occupies four clusters regardless of virtual size`() {
        val file = File(temp.root, "thin.qcow2")
        Qcow2Creator.create(file, 256 * gib)

        assertTrue(Qcow2Creator.isQcow2(file))
        assertEquals(256 * gib, Qcow2Creator.virtualSize(file))
        // header + refcount table + refcount block + one L1 cluster
        assertEquals(4 * 64 * 1024L, file.length())
    }

    @Test
    fun `qemu-img accepts the generated image`() {
        val qemuImg = findQemuImg()
        assumeTrue("qemu-img 不可用，跳过交叉校验", qemuImg != null)

        val file = File(temp.root, "check.qcow2")
        Qcow2Creator.create(file, 256 * gib)

        val info = run(qemuImg!!, "info", "--output=json", file.absolutePath)
        assertTrue("format 不是 qcow2：$info", info.contains("\"format\""))
        assertTrue("format 不是 qcow2：$info", info.contains("qcow2"))
        assertTrue("virtual-size 不对：$info", info.contains((256 * gib).toString()))

        val check = run(qemuImg, "check", file.absolutePath)
        assertTrue("qemu-img check 报错：$check", !check.contains("ERROR") && !check.contains("corrupt"))
    }

    @Test
    fun `capacity grows one L1 cluster per 32 TiB`() {
        val file = File(temp.root, "big.qcow2")
        Qcow2Creator.create(file, 40L * 1024 * gib) // 40 TiB
        assertTrue(file.length() > 4 * 64 * 1024L)
        assertTrue(Qcow2Creator.isQcow2(file))
    }

    @Test
    fun `raw file is not mistaken for qcow2`() {
        val raw = File(temp.root, "disk.raw")
        raw.writeBytes(ByteArray(4096))
        assertTrue(!Qcow2Creator.isQcow2(raw))
        assertEquals(null, Qcow2Creator.virtualSize(raw))
    }

    @Test
    fun `structural inspection accepts a freshly created image`() {
        val file = File(temp.root, "inspect-ok.qcow2")
        Qcow2Creator.create(file, 256 * gib)
        assertEquals(Qcow2Creator.Verdict.OK, Qcow2Creator.inspect(file).verdict)
    }

    @Test
    fun `structural inspection rejects a truncated image`() {
        val file = File(temp.root, "truncated.qcow2")
        Qcow2Creator.create(file, 256 * gib)
        // Simulate a crash that lost everything but part of the header cluster.
        java.io.RandomAccessFile(file, "rw").use { it.setLength(100) }
        assertEquals(Qcow2Creator.Verdict.BROKEN, Qcow2Creator.inspect(file).verdict)
    }

    @Test
    fun `structural inspection rejects a broken magic`() {
        val file = File(temp.root, "bad-magic.qcow2")
        Qcow2Creator.create(file, 256 * gib)
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        }
        assertEquals(Qcow2Creator.Verdict.BROKEN, Qcow2Creator.inspect(file).verdict)
    }

    @Test
    fun `structural inspection rejects metadata pointing past eof`() {
        val file = File(temp.root, "bad-refcount.qcow2")
        Qcow2Creator.create(file, 256 * gib)
        java.io.RandomAccessFile(file, "rw").use { raf ->
            // refcount_table_offset lives at byte 48 and is big-endian.
            raf.seek(48)
            raf.write(java.nio.ByteBuffer.allocate(8).putLong(Long.MAX_VALUE).array())
        }
        assertEquals(Qcow2Creator.Verdict.BROKEN, Qcow2Creator.inspect(file).verdict)
    }

    @Test
    fun `structural inspection reports unknown for a missing file`() {
        assertEquals(
            Qcow2Creator.Verdict.UNKNOWN,
            Qcow2Creator.inspect(File(temp.root, "nope.qcow2")).verdict,
        )
    }

    @Test
    fun `structural inspection does not flag a real allocated image`() {
        val qemuImg = findQemuImg()
        val qemuIo = listOf("/usr/bin/qemu-io", "/usr/local/bin/qemu-io").firstOrNull { File(it).canExecute() }
        assumeTrue("qemu-img/qemu-io 不可用，跳过", qemuImg != null && qemuIo != null)

        val file = File(temp.root, "allocated.qcow2")
        run(qemuImg!!, "create", "-q", "-f", "qcow2", file.absolutePath, "64M")
        // Allocate L2 tables and data clusters, i.e. the shape a real Windows
        // install produces — much more metadata than our own empty image.
        run(qemuIo!!, "-c", "write -P 0xAA 0 8M", file.absolutePath)

        assertEquals(Qcow2Creator.Verdict.OK, Qcow2Creator.inspect(file).verdict)
    }

    private fun findQemuImg(): String? {
        val candidates = listOf("/usr/bin/qemu-img", "/usr/local/bin/qemu-img", "/opt/homebrew/bin/qemu-img")
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(30, TimeUnit.SECONDS)
        return output
    }
}
