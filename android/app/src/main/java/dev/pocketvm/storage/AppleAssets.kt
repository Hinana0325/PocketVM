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

    const val SEP_NVRAM = "sep_nvram"

    /** 重做 restore 时要用 `-initrd` 挂的还原 ramdisk；正常启动不需要。 */
    const val RESTORE_RAMDISK = "038-44135-124.dmg"

    /**
     * Inferno 的 `at24c-eeprom` 会按定值校验后盾文件，必须**精确** 64 KiB：
     * 旧还原包给的是 2 KiB，直接挂会报 `Backing file size 65536 != 2048`，
     * SEP 拿不到 nvram 又会把 `data-protection` 启动任务带崩。
     */
    const val SEP_NVRAM_BYTES = 64L * 1024

    data class Spec(
        val name: String,
        val minBytes: Long,
        val label: String,
        /** 非 null 时要求文件大小严格相等（QEMU 设备按定值校验后盾文件）。 */
        val exactBytes: Long? = null,
    )

    val specs: List<Spec> = listOf(
        Spec("kernelcache.research.iphone12b", 8L shl 20, "iOS 内核缓存"),
        Spec("DeviceTree.n104ap.im4p", 1, "设备树"),
        Spec("sep-firmware.n104.RELEASE.new.img4", 1L shl 20, "SEP 固件"),
        Spec("AppleSEPROM-Cebu-B1", 1, "SEP ROM"),
        Spec("038-44135-124.dmg.trustcache", 1, "TrustCache"),
        Spec("root_ticket.der", 1, "AP ticket"),
        // minBytes 仍按旧还原包的 2 KiB 收（导入时会补零），但成品必须精确 64 KiB。
        Spec(SEP_NVRAM, 2048, "SEP nvram", exactBytes = SEP_NVRAM_BYTES),
        Spec("sep_ssc", 64L shl 10, "SEP SSC"),
        Spec("nvram", 4096, "nvram"),
        Spec(ROOT_DISK, 1L shl 30, "iOS 系统盘"),
        Spec("nvme.2", 4096, "firmware 分区"),
        Spec("nvme.3", 4096, "syscfg 分区"),
        Spec("nvme.4", 4096, "ctrl_bits 分区"),
        Spec("nvme.6", 4096, "effaceable 分区"),
        Spec("nvme.7", 4096, "panic_log 分区"),
    )

    /** 可选资源：导入时顺手收下，但**不参与** `status()` / `validate()` 的必需校验。 */
    val optionalSpecs: List<Spec> = listOf(
        Spec(RESTORE_RAMDISK, 1L shl 20, "还原 ramdisk"),
    )

    /** 返回一句可读的问题描述；文件合格时返回 null。抽成纯函数方便 JVM 单测。 */
    fun problem(dir: File, spec: Spec): String? {
        val file = File(dir, spec.name)
        if (!file.isFile) return "缺少文件"
        val size = file.length()
        val exact = spec.exactBytes
        if (exact != null && size != exact) return "大小 $size 字节，必须是 $exact 字节"
        if (size < spec.minBytes) return "只有 $size 字节，小于 ${spec.minBytes} 字节"
        return null
    }

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
        val appleDir = dir(layout, vmId)
        val missing = specs.filter { problem(appleDir, it) != null }.map { it.name }
        val total = specs.sumOf { spec -> File(appleDir, spec.name).length() }
        return Status(
            present = missing.isEmpty(),
            missing = missing,
            totalBytes = total,
            rootDiskBytes = File(appleDir, ROOT_DISK).length(),
        )
    }

    /** 启动前的可读校验；返回空列表表示资源齐全。 */
    fun validate(layout: VmStorageLayout, vmId: String): List<String> = validateDir(dir(layout, vmId))

    fun validateDir(appleDir: File): List<String> {
        val problems = specs.mapNotNull { spec -> problem(appleDir, spec)?.let { spec to it } }
        if (problems.isEmpty()) return emptyList()
        // sep_nvram 的大小错误有专门的自愈办法，不能混进「缺少或过小」里让用户重新导入 24 GB。
        val sep = problems.firstOrNull { it.first.name == SEP_NVRAM }
        val others = problems.filter { it.first.name != SEP_NVRAM }
        return buildList {
            if (sep != null) {
                add(
                    "$SEP_NVRAM ${sep.second}：Inferno 的 SEP EEPROM 要求精确 " +
                        "64 KiB（$SEP_NVRAM_BYTES 字节）。重新启动一次虚拟机会自动补零，或在编辑页重新导入还原包。",
                )
            }
            if (others.isNotEmpty()) {
                val detail = others.joinToString("、") { (spec, why) ->
                    "${spec.name}（${spec.label}：$why）"
                }
                add("iOS 资源不完整：$detail")
            }
        }
    }

    /**
     * 把旧版导入 / 手工拷贝留下的 2 KiB `sep_nvram` 补零到 64 KiB。
     * 只补短、不截断长的（避免破坏用户已有内容）；幂等，无改动返回 null。
     * 返回的字符串可直接作为启动提示展示。
     */
    fun repair(layout: VmStorageLayout, vmId: String): String? = repairDir(dir(layout, vmId))

    fun repairDir(appleDir: File): String? {
        val file = File(appleDir, SEP_NVRAM)
        if (!file.isFile) return null
        val before = file.length()
        if (before >= SEP_NVRAM_BYTES) return null
        file.appendBytes(ByteArray((SEP_NVRAM_BYTES - before).toInt()))
        return "$SEP_NVRAM 已由 $before 字节补齐到 $SEP_NVRAM_BYTES 字节" +
            "（Inferno 的 at24c-eeprom 要求精确 64 KiB）"
    }

    /**
     * 从还原包 zip 里取出需要的文件。按 basename 匹配，兼容 `necessaryfile/` 这种前缀；
     * `038-44135-124.dmg` 还原 ramdisk 也一并收下（重做 restore 时要用 `-initrd`），
     * 其余条目（说明文件、build 产物、IPSW 大包）直接跳过。
     *
     * @param onProgress 每写完一个文件回调一次（文件名 + 累计字节）
     */
    fun importZip(
        input: InputStream,
        targetDir: File,
        onProgress: (String, Long) -> Unit = { _, _ -> },
    ): List<String> {
        val wanted = (specs + optionalSpecs).associateBy { it.name }
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
                val size = part.length()
                if (size < spec.minBytes) {
                    part.delete()
                    throw IllegalStateException(
                        "${spec.label}（$name）只有 $size 字节，小于要求的 ${spec.minBytes}",
                    )
                }
                if (name == SEP_NVRAM && size < SEP_NVRAM_BYTES) {
                    // 旧还原包是 2 KiB，新机器要 64 KiB；补零不改变已有内容。
                    part.appendBytes(ByteArray((SEP_NVRAM_BYTES - size).toInt()))
                }
                // POSIX rename 可以原子覆盖；先走这条路，别先删旧的——
                // 万一 rename 失败，用户那块 24 GB 的 nvme.1 就没了。
                if (!part.renameTo(out)) {
                    val retried = out.exists() && out.delete() && part.renameTo(out)
                    if (!retried) {
                        part.delete()
                        throw IllegalStateException("无法保存 ${spec.label}（$name）")
                    }
                }
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
