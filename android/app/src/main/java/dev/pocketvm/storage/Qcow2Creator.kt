package dev.pocketvm.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a minimal but *valid* QCOW2 v3 image header, without `qemu-img`.
 *
 * Why this exists: the whole point of a 256 GB virtual disk on a phone is that it
 * only occupies what the guest actually writes. A raw sparse file cannot promise
 * that — `/Android/data` lives on FUSE (sdcardfs), where hole punching is not
 * guaranteed, so a 256 GB raw file risks becoming 256 GB of real flash.
 * qcow2 is sparse by construction: the file length is metadata + allocated
 * clusters, independent of the virtual size.
 *
 * Layout produced (cluster = 64 KiB, refcount_order = 4):
 *
 * ```
 * cluster 0        header (104 bytes, rest zero)
 * cluster 1        refcount table (512 entries, [0] -> cluster 2)
 * cluster 2        refcount block  (one uint16 per cluster, 0..n = 1)
 * cluster 3..n     L1 table, all entries 0 => no L2 allocated yet
 * ```
 *
 * Total file size is 4 clusters (256 KiB) for any virtual size up to 32 TiB.
 * QEMU allocates L2 tables and data clusters lazily as the guest writes, and
 * extends the refcount table by itself when the first block runs out.
 */
object Qcow2Creator {

    private const val MAGIC = 0x514649FB
    private const val VERSION = 3
    private const val CLUSTER_BITS = 16
    private const val CLUSTER_SIZE = 1L shl CLUSTER_BITS
    private const val HEADER_LENGTH = 104
    private const val REFCOUNT_ORDER = 4
    private const val REFCOUNT_TABLE_CLUSTERS = 1L

    /** One L1 entry points at an L2 table, which maps 512 MiB with 64 KiB clusters. */
    private const val COVERAGE_PER_L1_ENTRY = CLUSTER_SIZE * (CLUSTER_SIZE / 8)

    private const val HEADER_CLUSTER = 0L
    private const val REFCOUNT_TABLE_CLUSTER = 1L
    private const val REFCOUNT_BLOCK_CLUSTER = 2L
    private const val L1_TABLE_CLUSTER = 3L

    fun create(file: File, virtualSizeBytes: Long): File {
        require(virtualSizeBytes > 0) { "virtual size must be positive" }
        file.parentFile?.mkdirs()

        val l1Entries = ((virtualSizeBytes + COVERAGE_PER_L1_ENTRY - 1) / COVERAGE_PER_L1_ENTRY).toInt()
        val l1Clusters = ((l1Entries * 8L + CLUSTER_SIZE - 1) / CLUSTER_SIZE).toInt()
        val totalClusters = L1_TABLE_CLUSTER + l1Clusters

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(totalClusters * CLUSTER_SIZE)
            // Zero-fill the metadata we rely on being zero: a reused file path
            // could otherwise leave stale L1 entries behind.
            raf.seek(0)
            raf.write(ByteArray((totalClusters * CLUSTER_SIZE).toInt()))

            raf.seek(REFCOUNT_BLOCK_CLUSTER * CLUSTER_SIZE)
            val refcounts = ByteBuffer.allocate(CLUSTER_SIZE.toInt()).order(ByteOrder.BIG_ENDIAN)
            for (cluster in 0 until totalClusters) {
                refcounts.putShort(1) // every metadata cluster has exactly one reference
            }
            raf.write(refcounts.array())

            raf.seek(REFCOUNT_TABLE_CLUSTER * CLUSTER_SIZE)
            val table = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            table.putLong(REFCOUNT_BLOCK_CLUSTER * CLUSTER_SIZE)
            raf.write(table.array())

            raf.seek(HEADER_CLUSTER)
            raf.write(header(virtualSizeBytes, l1Entries))
            raf.fd.sync()
        }
        return file
    }

    fun isQcow2(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            val magic = ByteArray(4)
            // read() may return short; readFully guarantees all 4 bytes.
            RandomAccessFile(file, "r").use { it.readFully(magic) }
            ByteBuffer.wrap(magic).order(ByteOrder.BIG_ENDIAN).int == MAGIC
        } catch (t: Throwable) {
            false
        }
    }

    /** Virtual (guest-visible) size in bytes, read back from an existing image. */
    fun virtualSize(file: File): Long? {
        if (!isQcow2(file)) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(32)
                raf.readFully(buffer)
                val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
                bb.position(24)
                bb.long
            }
        } catch (t: Throwable) {
            null
        }
    }

    enum class Verdict {
        /** Header and every metadata pointer are inside the file. */
        OK,
        /** The file cannot be a usable qcow2 at all. */
        BROKEN,
        /** Unreadable, but not provably corrupt (I/O error). */
        UNKNOWN,
    }

    data class StructureReport(val verdict: Verdict, val detail: String? = null)

    /**
     * Cheap structural pre-flight used when `qemu-img` is not packaged.
     *
     * Deliberately conservative: it only reports BROKEN for things that cannot be
     * a valid qcow2 (bad magic/version, header fields out of range, L1 or refcount
     * structures pointing past EOF). It does **not** inspect L2 tables, so leaked
     * clusters are invisible here — that needs `qemu-img check -r leaks`.
     */
    fun inspect(file: File): StructureReport {
        if (!file.isFile) return StructureReport(Verdict.UNKNOWN, "文件不存在")
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < HEADER_LENGTH) {
                    return StructureReport(Verdict.BROKEN, "文件只有 $length 字节，不足一个 qcow2 头部")
                }
                val raw = ByteArray(HEADER_LENGTH)
                raf.readFully(raw)
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                val magic = bb.getInt(0)
                if (magic != MAGIC) {
                    return StructureReport(Verdict.BROKEN, "magic=0x${Integer.toHexString(magic)}，不是 qcow2")
                }
                val version = bb.getInt(4)
                if (version !in 2..3) {
                    return StructureReport(Verdict.BROKEN, "不支持的 qcow2 版本 $version")
                }
                val clusterBits = bb.getInt(20)
                if (clusterBits !in 9..21) {
                    return StructureReport(Verdict.BROKEN, "cluster_bits=$clusterBits 超出 9..21")
                }
                val clusterSize = 1L shl clusterBits
                val virtualSize = bb.getLong(24)
                if (virtualSize <= 0) {
                    return StructureReport(Verdict.BROKEN, "虚拟大小为 $virtualSize 字节")
                }
                val headerLength = bb.getInt(100)
                if (headerLength !in 72..clusterSize.toInt()) {
                    return StructureReport(Verdict.BROKEN, "header_length=$headerLength 与 cluster 大小不匹配")
                }
                val l1Size = bb.getInt(36)
                val l1Offset = bb.getLong(40)
                if (l1Size <= 0 || l1Size > (1 shl 24)) {
                    return StructureReport(Verdict.BROKEN, "l1_size=$l1Size 不合理")
                }
                if (l1Offset % clusterSize != 0L || l1Offset + l1Size * 8L > length) {
                    return StructureReport(
                        Verdict.BROKEN,
                        "L1 表（offset=$l1Offset, size=$l1Size）超出文件末尾 $length",
                    )
                }
                val refcountOffset = bb.getLong(48)
                val refcountClusters = bb.getInt(56)
                if (refcountOffset % clusterSize != 0L || refcountClusters <= 0 ||
                    refcountOffset + refcountClusters * clusterSize > length
                ) {
                    return StructureReport(
                        Verdict.BROKEN,
                        "引用计数表（offset=$refcountOffset, clusters=$refcountClusters）超出文件末尾 $length",
                    )
                }

                // Every non-zero L1 entry must point at an in-file, aligned cluster.
                val l1 = ByteArray(l1Size * 8)
                raf.seek(l1Offset)
                raf.readFully(l1)
                val entries = ByteBuffer.wrap(l1).order(ByteOrder.BIG_ENDIAN)
                // Bits 9..55 hold the host offset; bit 63 is QCOW_OFLAG_COPIED,
                // which QEMU sets on every normal, non-backing entry. Anything
                // else set (bits 0..8, 56..62) is genuinely malformed.
                val offsetMask = ((1L shl 47) - 1) shl 9
                val copiedFlag = 1L shl 63
                for (i in 0 until l1Size) {
                    val entry = entries.getLong(i * 8)
                    if (entry == 0L) continue
                    val offset = entry and offsetMask
                    val flag = entry and copiedFlag
                    if ((offset or flag) != entry) {
                        return StructureReport(
                            Verdict.BROKEN,
                            "L1[$i]=0x${java.lang.Long.toHexString(entry)} 含非法标志位或未对齐",
                        )
                    }
                    if (offset + clusterSize > length) {
                        return StructureReport(Verdict.BROKEN, "L1[$i] 指向文件末尾之外（$offset > $length）")
                    }
                }
                StructureReport(Verdict.OK)
            }
        } catch (t: Throwable) {
            StructureReport(Verdict.UNKNOWN, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun header(virtualSizeBytes: Long, l1Entries: Int): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(MAGIC)                                   // 0  magic
        bb.putInt(VERSION)                                 // 4  version
        bb.putLong(0)                                      // 8  backing_file_offset
        bb.putInt(0)                                       // 16 backing_file_size
        bb.putInt(CLUSTER_BITS)                            // 20 cluster_bits
        bb.putLong(virtualSizeBytes)                       // 24 size
        bb.putInt(0)                                       // 32 crypt_method
        bb.putInt(l1Entries)                               // 36 l1_size
        bb.putLong(L1_TABLE_CLUSTER * CLUSTER_SIZE)        // 40 l1_table_offset
        bb.putLong(REFCOUNT_TABLE_CLUSTER * CLUSTER_SIZE)  // 48 refcount_table_offset
        bb.putInt(REFCOUNT_TABLE_CLUSTERS.toInt())         // 56 refcount_table_clusters
        bb.putInt(0)                                       // 60 nb_snapshots
        bb.putLong(0)                                      // 64 snapshots_offset
        bb.putLong(0)                                      // 72 incompatible_features
        bb.putLong(0)                                      // 80 compatible_features
        bb.putLong(0)                                      // 88 autoclear_features
        bb.putInt(REFCOUNT_ORDER)                          // 96 refcount_order
        bb.putInt(HEADER_LENGTH)                           // 100 header_length
        return bb.array()
    }
}
