package dev.pocketvm

import dev.pocketvm.gsi.GptDisk
import dev.pocketvm.gsi.SparseImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** GSI 拼装的两个纯函数引擎：sparse 展开与 GPT 写入/回读。 */
class GsiAssemblyTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---------- sparse ----------

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun sparseStream(): ByteArray {
        val out = ByteArrayOutputStream()
        // header: magic, major, minor, file_hdr_sz, chunk_hdr_sz, blk_sz, total_blks, total_chunks, checksum
        out.write(le32(SparseImage.MAGIC))
        out.write(le16(1))
        out.write(le16(0))
        out.write(le16(28))
        out.write(le16(12))
        out.write(le32(4))
        out.write(le32(6))
        out.write(le32(4))
        out.write(le32(0))
        // chunk 1: RAW, 1 block, "ABCD"
        out.write(le16(0xCAC1)); out.write(le16(0)); out.write(le32(1)); out.write(le32(16))
        out.write("ABCD".toByteArray())
        // chunk 2: FILL, 2 blocks, pattern 0x11223344
        out.write(le16(0xCAC2)); out.write(le16(0)); out.write(le32(2)); out.write(le32(16))
        out.write(le32(0x11223344))
        // chunk 3: DONT_CARE, 3 blocks
        out.write(le16(0xCAC3)); out.write(le16(0)); out.write(le32(3)); out.write(le32(12))
        // chunk 4: CRC32, 0 blocks
        out.write(le16(0xCAC4)); out.write(le16(0)); out.write(le32(0)); out.write(le32(16))
        out.write(le32(0))
        return out.toByteArray()
    }

    @Test
    fun `sparse expand handles raw fill dont-care and crc chunks`() {
        val input = sparseStream().inputStream()
        val header = SparseImage.readHeader(sparseStream().inputStream())
        assertNotNull(header)
        assertEquals(4, header!!.blockSize)
        assertEquals(24L, header.expandedBytes)

        val out = ByteArrayOutputStream()
        val written = SparseImage.expand(input, out)
        assertEquals(24L, written)
        val bytes = out.toByteArray()
        assertEquals("ABCD", String(bytes, 0, 4, Charsets.US_ASCII))
        // FILL：0x11223344 小端字节序列重复两遍
        val pattern = byteArrayOf(0x44, 0x33, 0x22, 0x11)
        for (i in 0 until 8) assertEquals(pattern[i % 4], bytes[4 + i])
        // DONT_CARE：全零
        for (i in 12 until 24) assertEquals(0, bytes[i].toInt())
    }

    @Test
    fun `non sparse file is detected`() {
        val raw = temp.newFile("raw.img").apply { writeBytes(ByteArray(64) { 1 }) }
        assertNull(SparseImage.readHeader(raw))
        assertTrue(!SparseImage.isSparse(raw))
    }

    // ---------- GPT ----------

    @Test
    fun `gpt plan aligns partitions and reserves backup area`() {
        val layout = GptDisk.plan(
            listOf(
                GptDisk.PartSpec("metadata", 16L * 1024 * 1024),
                GptDisk.PartSpec("system", 100L * 1024 * 1024),
                GptDisk.PartSpec("userdata", 256L * 1024 * 1024),
            ),
        )
        assertEquals(2048L, layout.partitions.first().firstLba) // 1 MiB 对齐
        layout.partitions.zipWithNext { a, b ->
            assertTrue("分区不能重叠：${a.name}->${b.name}", b.firstLba > a.lastLba)
        }
        val last = layout.partitions.last().lastLba
        assertTrue("备份表与备份头要留够空间", layout.diskSectors > last + 33)
        assertEquals(layout.diskSectors - 1, layout.backupLba)
    }

    @Test
    fun `gpt write and parse round trip keeps names and bounds`() {
        val layout = GptDisk.plan(
            listOf(
                GptDisk.PartSpec("metadata", 1L * 1024 * 1024),
                GptDisk.PartSpec("system", 4L * 1024 * 1024),
                GptDisk.PartSpec("vendor", 2L * 1024 * 1024),
                GptDisk.PartSpec("userdata", 8L * 1024 * 1024),
            ),
        )
        val file = temp.newFile("disk.img")
        GptDisk.write(file, layout)
        assertEquals(layout.diskSectors * 512, file.length())

        val parsed = GptDisk.parse(file)
        assertNotNull(parsed)
        val (parts, sectors) = parsed!!
        assertEquals(layout.diskSectors, sectors)
        assertEquals(listOf("metadata", "system", "vendor", "userdata"), parts.map { it.name })
        parts.zip(layout.partitions) { a, b ->
            assertEquals(b.firstLba, a.firstLba)
            assertEquals(b.lastLba, a.lastLba)
        }
        // 备份头在最后一个扇区。
        RandomAccessFile(file, "r").use { raf ->
            raf.seek((layout.backupLba) * 512)
            val sig = ByteArray(8)
            raf.readFully(sig)
            assertEquals("EFI PART", String(sig, Charsets.US_ASCII))
        }
    }
}
