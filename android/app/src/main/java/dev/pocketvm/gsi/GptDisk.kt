package dev.pocketvm.gsi

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * 最小 GPT 写入器：给 GSI 拼装出「物理分区盘」用（不做 dm-super）。
 *
 * 结构：LBA0 保护性 MBR；LBA1 主 GPT 头；LBA2..33 分区表；
 * 最后一个 LBA 备份头、其前 32 个 LBA 是备份分区表。分区对齐 1 MiB。
 * 分区名必须是 Android ueventd 认识的 by-name（metadata/system/vendor/userdata）。
 */
object GptDisk {

    const val SECTOR = 512
    private const val ENTRY_COUNT = 128
    private const val ENTRY_SIZE = 128
    private const val HEADER_SIZE = 92
    private const val ENTRY_SECTORS = (ENTRY_COUNT * ENTRY_SIZE) / SECTOR // 32
    private const val FIRST_USABLE = 2L + ENTRY_SECTORS // 34
    private const val ALIGN_SECTORS = 2048L // 1 MiB

    /** Linux 文件系统数据 GUID（与 Android by-name 分工无关，只是类型占位）。 */
    private const val BASIC_DATA_GUID = "ebd0a0a2-b9e5-4433-87c0-68b6b72699c7"

    data class PartSpec(val name: String, val bytes: Long)

    data class Partition(val name: String, val firstLba: Long, val lastLba: Long, val uniqueGuid: UUID)

    data class Layout(
        val diskSectors: Long,
        val partitions: List<Partition>,
        val backupLba: Long,
    )

    /** 只计算布局，不写文件（纯函数）。磁盘大小取「分区总长 + 对齐 + 备份区」向上对齐。 */
    fun plan(specs: List<PartSpec>): Layout {
        var cursor = FIRST_USABLE
        val parts = specs.map { spec ->
            require(spec.name.isNotBlank() && spec.name.length <= 36) { "分区名非法：${spec.name}" }
            require(spec.bytes > 0) { "分区 `${spec.name}` 大小必须大于 0" }
            val sectors = ((spec.bytes + SECTOR - 1) / SECTOR)
            val first = alignUp(cursor)
            val last = first + sectors - 1
            cursor = last + 1
            Partition(spec.name, first, last, UUID.randomUUID())
        }
        val diskSectors = alignUp(cursor + ENTRY_SECTORS + 1) + ENTRY_SECTORS + 1
        return Layout(diskSectors, parts, backupLba = diskSectors - 1)
    }

    /**
     * 把 MBR 与主/备 GPT 写进 [file]（文件需已按 [Layout.diskSectors] 预分配）。
     * 分区内容由调用方按 [Layout.partitions] 的 LBA 偏移写入。
     */
    fun write(file: File, layout: Layout) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(layout.diskSectors * SECTOR)
            raf.seek(0)
            raf.write(protectiveMbr(layout.diskSectors))

            val entries = entryArray(layout)
            val entriesCrc = crc32(entries)
            val diskGuid = UUID.randomUUID()

            raf.seek(SECTOR.toLong()) // LBA1
            raf.write(header(layout, entriesCrc, diskGuid, currentLba = 1))
            raf.seek(2L * SECTOR)
            raf.write(entries)

            raf.seek((layout.backupLba - ENTRY_SECTORS) * SECTOR)
            raf.write(entries)
            raf.seek(layout.backupLba * SECTOR)
            raf.write(header(layout, entriesCrc, diskGuid, currentLba = layout.backupLba))
        }
    }

    /** 读回主 GPT（测试与自检用）。 */
    fun parse(file: File): Pair<List<Partition>, Long>? {
        RandomAccessFile(file, "r").use { raf ->
            val sector = ByteArray(SECTOR)
            raf.seek(SECTOR.toLong())
            raf.readFully(sector)
            val buffer = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
            val signature = ByteArray(8)
            buffer.get(signature)
            if (String(signature, Charsets.US_ASCII) != "EFI PART") return null
            buffer.position(72)
            val entryLba = buffer.long
            val count = buffer.int
            val size = buffer.int
            val arr = ByteArray(count * size)
            raf.seek(entryLba * SECTOR)
            raf.readFully(arr)
            val out = ArrayList<Partition>()
            val entryBuf = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) {
                val typeGuid = guid(entryBuf)
                if (typeGuid == UUID(0, 0)) {
                    entryBuf.position(entryBuf.position() + size - 16)
                    return@repeat
                }
                val unique = guid(entryBuf)
                val first = entryBuf.long
                val last = entryBuf.long
                entryBuf.getLong() // attributes
                val nameBytes = ByteArray(72)
                entryBuf.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_16LE).trimEnd('\u0000')
                out.add(Partition(name, first, last, unique))
            }
            return out to (raf.length() / SECTOR)
        }
    }

    private fun protectiveMbr(diskSectors: Long): ByteArray {
        val mbr = ByteArray(SECTOR)
        val e = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        e.position(446)
        e.put(0x00) // boot
        e.put(0x00) // CHS start
        e.put(0x02)
        e.put(0x00)
        e.put(0xEE.toByte()) // GPT protective
        e.put(0xFF.toByte())
        e.put(0xFF.toByte())
        e.put(0xFF.toByte())
        e.putInt(1)
        e.putInt(minOf(diskSectors - 1, 0xFFFFFFFFL).toInt())
        e.putShort(0xAA55.toShort())
        return mbr
    }

    private fun entryArray(layout: Layout): ByteArray {
        val arr = ByteArray(ENTRY_COUNT * ENTRY_SIZE)
        val buffer = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
        for (part in layout.partitions) {
            putGuid(buffer, UUID.fromString(BASIC_DATA_GUID))
            putGuid(buffer, part.uniqueGuid)
            buffer.putLong(part.firstLba)
            buffer.putLong(part.lastLba)
            buffer.putLong(0) // attributes
            val name = part.name.take(36)
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            buffer.put(nameBytes)
            // 每个条目固定 128 字节：补齐到下一个条目。
            val used = 16 + 16 + 8 + 8 + 8 + nameBytes.size
            repeat(ENTRY_SIZE - used) { buffer.put(0) }
        }
        return arr
    }

    private fun header(layout: Layout, entriesCrc: Int, diskGuid: UUID, currentLba: Long): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        b.put("EFI PART".toByteArray(Charsets.US_ASCII))
        b.putInt(0x00010000)
        b.putInt(HEADER_SIZE)
        b.putInt(0) // header CRC placeholder
        b.putInt(0) // reserved
        b.putLong(currentLba)
        b.putLong(layout.backupLba)
        b.putLong(FIRST_USABLE)
        b.putLong(layout.backupLba - ENTRY_SECTORS - 1)
        putGuid(b, diskGuid)
        b.putLong(2) // partition entry LBA（备份表在备份头之前，固定 32 扇区）
        b.putInt(ENTRY_COUNT)
        b.putInt(ENTRY_SIZE)
        b.putInt(entriesCrc)
        // header CRC 在字段写入后计算（CRC 字段本身按 0 计入）。
        val crc = CRC32()
        crc.update(h, 0, HEADER_SIZE)
        b.putInt(16, crc.value.toInt())
        return h
    }

    private fun putGuid(buffer: ByteBuffer, uuid: UUID) {
        buffer.putInt(Integer.reverseBytes((uuid.mostSignificantBits ushr 32).toInt()))
        buffer.putShort(java.lang.Short.reverseBytes((uuid.mostSignificantBits ushr 16).toShort()))
        buffer.putShort(java.lang.Short.reverseBytes(uuid.mostSignificantBits.toShort()))
        buffer.putLong(uuid.leastSignificantBits)
    }

    private fun guid(buffer: ByteBuffer): UUID {
        val a = Integer.reverseBytes(buffer.int).toLong() and 0xFFFFFFFFL
        val b = java.lang.Short.reverseBytes(buffer.short).toLong() and 0xFFFFL
        val c = java.lang.Short.reverseBytes(buffer.short).toLong() and 0xFFFFL
        val d = buffer.long
        return UUID((a shl 32) or (b shl 16) or c, d)
    }

    private fun crc32(bytes: ByteArray): Int = CRC32().also { it.update(bytes) }.value.toInt()

    private fun alignUp(sector: Long): Long = (sector + ALIGN_SECTORS - 1) / ALIGN_SECTORS * ALIGN_SECTORS
}
