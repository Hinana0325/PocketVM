package dev.pocketvm.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts the El Torito EFI boot image from a Windows ISO.
 *
 * Why: the firmware (edk2) is asked to boot `\EFI\BOOT\BOOTAA64.EFI`. On a CD
 * that means either an ISO9660/UDF driver reading the whole disc, or the El
 * Torito path where the firmware maps the small FAT "boot image" embedded in the
 * ISO as its own block device. The latter is what every PC does, but whether a
 * given edk2 build exposes it over USB mass storage is not something we can rely
 * on — on the device we tested, the firmware dropped straight to the UEFI shell.
 *
 * So: pull that embedded FAT image out and hand it to QEMU as a *separate*
 * read-only USB disk with bootindex 1. The CD stays attached for Windows Setup
 * to read `sources/` with its own drivers.
 *
 * Layout used (ECMA-119 + El Torito):
 * ```
 * LBA 16        primary volume descriptor, block size at offset 128
 * LBA 16..n     volume descriptor set; type 0 = boot record -> catalog LBA @71
 * catalog LBA   validation entry (32 B) + initial entry (32 B) @+32
 *               initial entry: media type @1, sector count @6, load RBA @8
 * load RBA      the boot image, sectorCount * 512 bytes
 * ```
 */
object IsoBootImage {

    private const val SECTOR = 2048
    private const val MAX_CATALOG_SCAN = 32

    /**
     * @return the extracted image, or null when [iso] is not an El Torito ISO
     *   with an EFI boot entry (or reading fails). Callers must treat null as
     *   "boot the CD the normal way".
     */
    fun ensure(iso: File, cacheDir: File): File? = try {
        if (!iso.isFile) {
            null
        } else {
            val target = File(cacheDir, "${iso.nameWithoutExtension}.boot.img")
            val stamp = File(cacheDir, "${iso.nameWithoutExtension}.boot.stamp")
            val signature = "${iso.length()}:${iso.lastModified()}"
            if (target.isFile && target.length() > 0 && stamp.isFile && stamp.readText() == signature) {
                target
            } else {
                extract(iso, target)?.also { stamp.writeText(signature) }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** Visible for tests: parse and write the boot image, or return null. */
    fun extract(iso: File, out: File): File? {
        RandomAccessFile(iso, "r").use { raf ->
            val blockSize = readBlockSize(raf) ?: return null
            val catalogLba = findBootCatalog(raf, blockSize) ?: return null
            val catalogOffset = catalogLba * blockSize
            // 512 bytes is enough for the validation entry plus 15 entries.
            val catalog = ByteArray(512)
            if (!readFully(raf, catalogOffset, catalog)) return null
            // Validation entry: header id 0x01 and signature 0x55AA at the end.
            if (catalog[0].toInt() and 0xFF != 0x01) return null
            if (u8(catalog, 30) != 0x55 || u8(catalog, 31) != 0xAA) return null

            // Layout (El Torito 1.0): 32-byte validation entry, then 32-byte
            // entries. A 0x91 entry is a *section header* carrying the platform
            // id for the entries that follow; 0x88 is a bootable entry whose
            // byte 1 is the media type. Reading platform from the boot entry
            // itself is the classic mistake - it silently picks the x86 image
            // out of a multi-arch ISO.
            data class BootEntry(val platform: Int, val media: Int, val sectors: Int, val rba: Long)

            val entries = mutableListOf<BootEntry>()
            var platform = 0 // 0x00 = BIOS, 0xEF = EFI
            var i = 32
            while (i + 32 <= catalog.size) {
                when (u8(catalog, i)) {
                    0x91 -> platform = u8(catalog, i + 1) // section header
                    0x88 -> {
                        val media = u8(catalog, i + 1)
                        val sectors = le16(catalog, i + 6)
                        val rba = le32(catalog, i + 8)
                        if (sectors > 0 && rba > 0) {
                            entries += BootEntry(platform, media, sectors, rba)
                        }
                    }
                }
                i += 32
            }

            // Prefer an EFI no-emulation entry; otherwise accept the only
            // no-emulation entry there is (ARM64-only media has no BIOS image).
            val chosen = entries.firstOrNull { it.platform == 0xEF && it.media == 0 }
                ?: entries.singleOrNull { it.media == 0 }
                ?: return null
            val sectorCount = chosen.sectors
            val loadRba = chosen.rba
            val length = sectorCount.toLong() * 512L
            if (loadRba * SECTOR + length > iso.length()) return null
            if (length > 64L * 1024 * 1024) return null // sanity: boot images are a few MB

            // The EFI boot image must be a FAT filesystem (partition-less super
            // floppy); anything else would just confuse the firmware again.
            val head = ByteArray(96)
            if (!readFully(raf, loadRba * SECTOR, head)) return null
            val fat12 = head.copyOfRange(54, 57).toString(Charsets.US_ASCII) == "FAT"
            val fat32 = head.copyOfRange(82, 85).toString(Charsets.US_ASCII) == "FAT"
            if (!fat12 && !fat32) return null

            out.parentFile?.mkdirs()
            val buffer = ByteArray(1 shl 16)
            var written = 0L
            raf.seek(loadRba * SECTOR)
            out.outputStream().buffered().use { output ->
                while (written < length) {
                    val want = minOf(buffer.size.toLong(), length - written).toInt()
                    val read = raf.read(buffer, 0, want)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            }
            return if (written == length) out else null
        }
    }

    private fun readBlockSize(raf: RandomAccessFile): Int? {
        val pvd = ByteArray(132)
        if (!readFully(raf, 16L * SECTOR, pvd)) return null
        if (pvd[0].toInt() != 1) return null
        if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
        val size = le16(pvd, 128)
        return if (size in 512..4096) size else null
    }

    private fun findBootCatalog(raf: RandomAccessFile, blockSize: Int): Long? {
        val descriptor = ByteArray(SECTOR)
        for (i in 0 until MAX_CATALOG_SCAN) {
            if (!readFully(raf, (16L + i) * blockSize, descriptor)) return null
            if (String(descriptor, 1, 5, Charsets.US_ASCII) != "CD001") return null
            when (descriptor[0].toInt() and 0xFF) {
                0 -> {
                    val id = String(descriptor, 7, 23, Charsets.US_ASCII)
                    if (!id.startsWith("EL TORITO")) return null
                    return le32(descriptor, 71).takeIf { it > 0 }
                }
                255 -> return null
            }
        }
        return null
    }

    private fun readFully(raf: RandomAccessFile, offset: Long, into: ByteArray): Boolean = try {
        raf.seek(offset)
        raf.readFully(into)
        true
    } catch (t: Throwable) {
        false
    }

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private fun le16(bytes: ByteArray, at: Int): Int =
        ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun le32(bytes: ByteArray, at: Int): Long =
        ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
}
