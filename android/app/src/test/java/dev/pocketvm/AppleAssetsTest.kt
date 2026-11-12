package dev.pocketvm

import dev.pocketvm.storage.AppleAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppleAssetsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sepSpec = AppleAssets.specs.first { it.name == AppleAssets.SEP_NVRAM }

    private fun sepFile(bytes: Int): File =
        File(temp.root, AppleAssets.SEP_NVRAM).apply { writeBytes(ByteArray(bytes) { 0x5a }) }

    private fun zipOf(vararg entries: Pair<String, Int>): ByteArrayInputStream {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, size) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(size) { 0x5a })
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(buffer.toByteArray())
    }

    @Test
    fun `problem reports a missing sep nvram explicitly`() {
        assertEquals("缺少文件", AppleAssets.problem(temp.root, sepSpec))
    }

    @Test
    fun `problem rejects a 2 KiB sep nvram and accepts exactly 64 KiB`() {
        sepFile(2048)
        val short = AppleAssets.problem(temp.root, sepSpec)
        assertTrue("2 KiB 应该被判为不合格：$short", short != null)
        assertTrue(short!!.contains("2048"))

        sepFile(AppleAssets.SEP_NVRAM_BYTES.toInt())
        assertNull(AppleAssets.problem(temp.root, sepSpec))
    }

    @Test
    fun `repair pads a short sep nvram preserving its content and is idempotent`() {
        sepFile(2048)
        val note = AppleAssets.repairDir(temp.root)
        assertTrue(note != null)
        assertEquals(AppleAssets.SEP_NVRAM_BYTES, File(temp.root, AppleAssets.SEP_NVRAM).length())

        val bytes = File(temp.root, AppleAssets.SEP_NVRAM).readBytes()
        assertEquals(0x5a.toByte(), bytes[0])
        assertEquals(0x5a.toByte(), bytes[2047])
        assertEquals(0.toByte(), bytes[2048])
        assertEquals(0.toByte(), bytes.last())

        assertNull("第二次 repair 应该无改动", AppleAssets.repairDir(temp.root))
        assertNull(AppleAssets.problem(temp.root, sepSpec))
    }

    @Test
    fun `repair never truncates an oversized sep nvram`() {
        sepFile(128 * 1024)
        assertNull(AppleAssets.repairDir(temp.root))
        assertEquals(128L * 1024, File(temp.root, AppleAssets.SEP_NVRAM).length())
    }

    @Test
    fun `validate names the sep nvram size problem instead of a generic missing asset`() {
        sepFile(2048)
        val messages = AppleAssets.validateDir(temp.root)
        assertTrue(messages.isNotEmpty())
        val sepMessage = messages.first { it.contains(AppleAssets.SEP_NVRAM) }
        assertTrue(sepMessage.contains("64"))
        assertTrue(sepMessage.contains("自动补零"))
        assertFalse("不该让用户重新导入整个还原包", sepMessage.contains("缺少或过小"))
    }

    @Test
    fun `importZip pads sep nvram and keeps the optional restore ramdisk`() {
        val written = AppleAssets.importZip(
            zipOf(
                "necessaryfile/sep_nvram" to 2048,
                "necessaryfile/038-44135-124.dmg" to 2 * 1024 * 1024,
            ),
            temp.root,
        )
        assertTrue(written.contains(AppleAssets.SEP_NVRAM))
        assertTrue(written.contains(AppleAssets.RESTORE_RAMDISK))
        assertEquals(
            AppleAssets.SEP_NVRAM_BYTES,
            File(temp.root, AppleAssets.SEP_NVRAM).length(),
        )
        assertEquals(2L * 1024 * 1024, File(temp.root, AppleAssets.RESTORE_RAMDISK).length())
        // 可选资源不参与必需校验：只有它和 sep_nvram 时，缺的是其余 14 项。
        assertTrue(AppleAssets.validateDir(temp.root).any { it.contains("iOS 资源不完整") })
    }

    @Test
    fun `importZip reports the real byte count and re-import replaces the old file`() {
        val tooSmall = runCatching {
            AppleAssets.importZip(zipOf("sep_nvram" to 16), temp.root)
        }.exceptionOrNull()
        assertTrue(tooSmall?.message?.contains("16 字节") == true)

        AppleAssets.importZip(zipOf("sep_nvram" to 2048), temp.root)
        assertEquals(AppleAssets.SEP_NVRAM_BYTES, File(temp.root, AppleAssets.SEP_NVRAM).length())
        AppleAssets.importZip(zipOf("sep_nvram" to 4096), temp.root)
        assertEquals(AppleAssets.SEP_NVRAM_BYTES, File(temp.root, AppleAssets.SEP_NVRAM).length())
    }
}
