package dev.pocketvm

import dev.pocketvm.storage.IsoBootImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The parser is the only thing standing between "Windows ISO boots" and
 * "firmware drops to the UEFI shell", so it is tested against a synthetic
 * ISO built byte by byte (ECMA-119 + El Torito).
 */
class IsoBootImageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sector = 2048

    private fun syntheticIso(payload: ByteArray, efSection: Boolean = true, bootable: Boolean = true): File {
        val iso = File(temp.root, "windows.iso")
        iso.outputStream().buffered().use { out ->
            repeat(16) { out.write(ByteArray(sector)) } // system area

            val pvd = ByteArray(sector)
            pvd[0] = 1
            "CD001".toByteArray(Charsets.US_ASCII).copyInto(pvd, 1)
            pvd[6] = 1
            pvd[128] = (sector and 0xFF).toByte()
            pvd[129] = ((sector shr 8) and 0xFF).toByte()
            out.write(pvd)

            val boot = ByteArray(sector)
            boot[0] = 0 // boot record descriptor
            "CD001".toByteArray(Charsets.US_ASCII).copyInto(boot, 1)
            "EL TORITO SPECIFICATION".toByteArray(Charsets.US_ASCII).copyInto(boot, 7)
            putLe32(boot, 71, 20) // catalog LBA
            out.write(boot)

            repeat(2) { out.write(ByteArray(sector)) } // 18, 19

            val catalog = ByteArray(sector)
            catalog[0] = 1
            catalog[30] = 0x55
            catalog[31] = 0xAA.toByte()

            var entry = 32
            if (efSection) {
                // Section header carries the platform id for what follows.
                catalog[entry] = 0x91.toByte()
                catalog[entry + 1] = 0xEF.toByte()
                catalog[entry + 2] = 1
                entry += 32
            }
            catalog[entry] = if (bootable) 0x88.toByte() else 0x00
            catalog[entry + 1] = 0 // media: no emulation
            putLe16(catalog, entry + 6, payload.size / 512)
            putLe32(catalog, entry + 8, 21) // image starts at LBA 21
            out.write(catalog)

            out.write(payload)
        }
        return iso
    }

    /** A plausible FAT boot sector: the parser refuses anything else. */
    private fun fatImage(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }.also {
        "FAT16   ".toByteArray(Charsets.US_ASCII).copyInto(it, 54)
    }

    @Test
    fun `extracts the el torito image byte for byte`() {
        val payload = fatImage(4096)
        val iso = syntheticIso(payload)
        val out = File(temp.root, "boot.img")

        val result = IsoBootImage.extract(iso, out)

        assertTrue("应成功提取", result != null)
        assertEquals(payload.size.toLong(), out.length())
        assertArrayEquals(payload, out.readBytes())
    }

    @Test
    fun `ignores a disc whose boot entry is not bootable`() {
        val iso = syntheticIso(fatImage(1024), bootable = false)
        assertNull(IsoBootImage.extract(iso, File(temp.root, "boot.img")))
    }

    @Test
    fun `accepts a lone no-emulation entry without a section header`() {
        // ARM64-only media has no BIOS image, so the initial entry is the EFI
        // one even though its platform byte is 0.
        val payload = fatImage(2048)
        val iso = syntheticIso(payload, efSection = false)
        assertTrue(IsoBootImage.extract(iso, File(temp.root, "boot.img")) != null)
    }

    @Test
    fun `refuses a boot image that is not a filesystem`() {
        val notFat = ByteArray(2048) { 0x41 }
        assertNull(IsoBootImage.extract(syntheticIso(notFat), File(temp.root, "boot.img")))
    }

    @Test
    fun `returns null for a file that is not an iso`() {
        val junk = File(temp.root, "junk.iso").apply { writeBytes(ByteArray(64 * 1024)) }
        assertNull(IsoBootImage.extract(junk, File(temp.root, "boot.img")))
    }

    private fun putLe16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putLe32(target: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) target[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }
}
