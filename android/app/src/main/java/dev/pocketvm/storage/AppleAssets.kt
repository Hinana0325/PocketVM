package dev.pocketvm.storage

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * iPhone 11（`t8030`）机型需要的固件和磁盘镜像。
 *
 * 文件名与 Inferno 官方文档一致：kernelcache / DeviceTree / SEP 固件 / trustcache /
 * AP ticket 来自 iOS 14.0 beta 5 的还原包，nvme.1 是系统盘（APFS 容器），
 * nvme.2..7 依次是 firmware / syscfg / ctrl_bits / nvram / effaceable / panic_log。
 *
 * 这些文件受 Apple EULA 约束，**不进 APK、不进公开仓库**：用户自己把还原包 zip
 * 导入到应用私有目录，App 只做校验和路径拼接。
 */
object AppleAssets {

    /** APFS 系统盘在 Inferno 的磁盘列表里叫 nvme.1。 */
    const val ROOT_DISK = "nvme.1"

    /** 机器要的 SEP nvram 是 64 KiB；旧还原包给的是 2 KiB，导入时补齐。 */
    private const val SEP_NVRAM_BYTES = 64L * 1024

    data class Spec(val name: String, val minBytes: Long, val label: String)

    val specs: List<Spec> = listOf(
        Spec("kernelcache.research.iphone12b", 8L shl 20, "iOS 内核缓存"),
        Spec("DeviceTree.n104ap.im4p", 1, "设备树"),
        Spec("sep-firmware.n104.RELEASE.new.img4", 1L shl 20, "SEP 固件"),
        Spec("AppleSEPROM-Cebu-B1", 1, "SEP ROM"),
        Spec("038-44135-124.dmg.trustcache", 1, "TrustCache"),
        Spec("root_ticket.der", 1, "AP ticket"),
        Spec("sep_nvram", 2048, "SEP nvram"),
        Spec("sep_ssc", 64L shl 10, "SEP SSC"),
        Spec("nvram", 4096, "nvram"),
        Spec(ROOT_DISK, 1L shl 30, "iOS 系统盘"),
        Spec("nvme.2", 4096, "firmware 分区"),
        Spec("nvme.3", 4096, "syscfg 分区"),
        Spec("nvme.4", 4096, "ctrl_bits 分区"),
        Spec("nvme.6", 4096, "effaceable 分区"),
        Spec("nvme.7", 4096, "panic_log 分区"),
    )

    data class Status(
        val present: Boolean,
        val missing: List<String>,
        val totalBytes: Long,
        val rootDiskBytes: Long,
    )

    fun dir(layout: VmStorageLayout, vmId: String): File = File(layout.vmDir(vmId), "apple")

    fun file(layout: VmStorageLayout, vmId: String, name: String): File =
        File(dir(layout, vmId), name)

    fun status(layout: VmStorageLayout, vmId: String): Status {
        val dir = dir(layout, vmId)
        val missing = specs.filter { spec ->
            val f = File(dir, spec.name)
            !f.isFile || f.length() < spec.minBytes
        }.map { it.name }
        val total = specs.sumOf { spec -> File(dir, spec.name).length() }
        return Status(
            present = missing.isEmpty(),
            missing = missing,
            totalBytes = total,
            rootDiskBytes = File(dir, ROOT_DISK).length(),
        )
    }

    /** 启动前的可读校验；返回空列表表示资源齐全。 */
    fun validate(layout: VmStorageLayout, vmId: String): List<String> {
        val status = status(layout, vmId)
        if (status.present) return emptyList()
        val detail = specs.filter { it.name in status.missing }
            .joinToString("、") { "${it.name}（${it.label}）" }
        return listOf("iOS 资源不完整，缺少或过小：$detail")
    }

    /**
     * 从还原包 zip 里取出需要的文件。按 basename 匹配，兼容 `necessaryfile/` 这种前缀；
     * 其他条目（dmg、说明文件、build 产物）直接跳过。
     *
     * @param onProgress 每写完一个文件回调一次（文件名 + 累计字节）
     */
    fun importZip(
        input: InputStream,
        targetDir: File,
        onProgress: (String, Long) -> Unit = { _, _ -> },
    ): List<String> {
        val wanted = specs.associateBy { it.name }
        val written = mutableListOf<String>()
        var total = 0L
        targetDir.mkdirs()
        ZipInputStream(input.buffered(1 shl 20)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val spec = wanted[name] ?: continue
                val out = File(targetDir, name)
                val part = File(targetDir, "$name.part")
                part.outputStream().buffered(1 shl 20).use { output ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        total += n
                    }
                }
                if (part.length() < spec.minBytes) {
                    part.delete()
                    throw IllegalStateException("${spec.label}（$name）只有 ${part.length()} 字节，小于要求的 ${spec.minBytes}")
                }
                if (name == "sep_nvram" && part.length() < SEP_NVRAM_BYTES) {
                    // 旧还原包是 2 KiB，新机器要 64 KiB；补零不改变已有内容。
                    part.appendBytes(ByteArray((SEP_NVRAM_BYTES - part.length()).toInt()))
                }
                part.renameTo(out)
                written += name
                onProgress(name, total)
            }
        }
        if (written.isEmpty()) {
            throw IllegalStateException("压缩包里没有找到 iOS 资源（应包含 kernelcache、sep-firmware、nvme.1 等）")
        }
        return written
    }
}
