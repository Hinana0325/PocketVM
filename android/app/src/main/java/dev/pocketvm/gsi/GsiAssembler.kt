package dev.pocketvm.gsi

import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.runtime.QemuEnvironment
import dev.pocketvm.storage.VmStorageLayout
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GSI 组件拼装（roadmap Image Manager 的第一步）。
 *
 * 输入：kernel / ramdisk.img / system.img / vendor.img（sparse 自动展开）。
 * 输出：一张 **GPT 物理分区盘**（metadata / system / vendor / userdata），
 * 用 qemu-img 转成 qcow2 放到 VM 的 disk 路径。
 *
 * 明确边界：这一步只做「拼装」。启动链（first-stage fstab / boot_devices、
 * 以及 GSI 只有 goldfish 图形 HAL 导致的显示不可用）仍未打通，拼出的盘主要
 * 用于实验、导出与后续研究，UI 里必须如实标注。
 */
@Singleton
class GsiAssembler @Inject constructor(
    private val layout: VmStorageLayout,
    private val qemu: QemuEnvironment,
) {

    enum class Component(val label: String, val fileName: String, val hint: String) {
        KERNEL("内核 Image", "kernel", "直接内核启动用，先留存（启动链未打通）"),
        RAMDISK("ramdisk.img", "ramdisk.img", "first-stage init，先留存（启动链未打通）"),
        SYSTEM("system.img", "system.img", "GSI 系统镜像（sparse 自动展开）"),
        VENDOR("vendor.img", "vendor.img", "vendor 镜像（sparse 自动展开）"),
    }

    companion object {
        const val METADATA_BYTES = 16L * 1024 * 1024
    }

    fun gsiDir(vmId: String): File = File(layout.vmDir(vmId), "gsi").apply { mkdirs() }

    fun componentFile(vmId: String, component: Component): File =
        File(gsiDir(vmId), component.fileName)

    fun importedComponents(vmId: String): Map<Component, File> =
        Component.entries.mapNotNull { c ->
            componentFile(vmId, c).takeIf { it.isFile && it.length() > 0 }?.let { c to it }
        }.toMap()

    /** 展开后的字节数（sparse 读头，raw 取文件长度）。 */
    fun expandedBytes(file: File): Long {
        val header = SparseImage.readHeader(file)
        return header?.expandedBytes ?: file.length()
    }

    /**
     * 拼装并替换 [layout.diskFile] 指向的系统盘。
     * [replaceExisting] 为 false 时，已有磁盘会拒绝（UI 需二次确认）。
     */
    suspend fun assemble(
        vmId: String,
        userdataGb: Int = 2,
        replaceExisting: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val system = componentFile(vmId, Component.SYSTEM)
            val vendor = componentFile(vmId, Component.VENDOR)
            require(system.isFile && system.length() > 0) { "先导入 system.img" }
            require(vendor.isFile && vendor.length() > 0) { "先导入 vendor.img" }

            val target = layout.diskFile(vmId)
            if (target.isFile && target.length() > 0 && !replaceExisting) {
                error("这台虚拟机已有磁盘，确认替换后才能继续拼装")
            }

            val systemBytes = expandedBytes(system)
            val vendorBytes = expandedBytes(vendor)
            val userdataBytes = userdataGb.coerceIn(1, 16) * 1024L * 1024 * 1024
            val plan = GptDisk.plan(
                listOf(
                    GptDisk.PartSpec("metadata", METADATA_BYTES),
                    GptDisk.PartSpec("system", systemBytes),
                    GptDisk.PartSpec("vendor", vendorBytes),
                    GptDisk.PartSpec("userdata", userdataBytes),
                ),
            )
            val diskBytes = plan.diskSectors * GptDisk.SECTOR
            val needBytes = diskBytes * 2 // raw + qcow2 同时存在
            require(layout.freeSpaceBytes() > needBytes) {
                "剩余空间不足：需要约 ${needBytes / (1024 * 1024)} MB"
            }

            val raw = File(gsiDir(vmId), "assemble.raw")
            onProgress("写入 GPT 分区表…")
            GptDisk.write(raw, plan)

            onProgress("写入 system 分区…")
            writeComponent(raw, system, plan, "system", onProgress)
            onProgress("写入 vendor 分区…")
            writeComponent(raw, vendor, plan, "vendor", onProgress)

            val exe = qemu.resolveQemuImg(layout) ?: error("缺少 qemu-img，无法生成 qcow2")
            val tmp = File(gsiDir(vmId), "assemble.qcow2.tmp")
            onProgress("转换为 qcow2（可能需要几分钟）…")
            val (code, out) = runQemuImg(
                exe,
                "convert", "-f", "raw", "-O", "qcow2", raw.absolutePath, tmp.absolutePath,
            )
            check(code == 0) { "qemu-img convert 失败（exit=$code）：${out.take(300)}" }
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            raw.delete()
            AgentFeed.event(
                "gsi.assembled",
                "vm" to vmId,
                "diskMb" to diskBytes / (1024 * 1024),
                "systemMb" to systemBytes / (1024 * 1024),
                "vendorMb" to vendorBytes / (1024 * 1024),
            )
            onProgress("拼装完成")
            target
        }
    }

    private fun writeComponent(
        raw: File,
        image: File,
        plan: GptDisk.Layout,
        name: String,
        onProgress: (String) -> Unit,
    ) {
        val part = plan.partitions.first { it.name == name }
        val offset = part.firstLba * GptDisk.SECTOR
        RandomAccessFile(raw, "rw").use { raf ->
            raf.seek(offset)
            val sink = BufferedOutputStream(object : java.io.OutputStream() {
                override fun write(b: Int) = raf.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
            }, 1 shl 20)
            if (SparseImage.isSparse(image)) {
                image.inputStream().use { input ->
                    SparseImage.expand(input, sink) { done ->
                        if (done % (64L * 1024 * 1024) < (1 shl 20)) {
                            onProgress("写入 $name：${done / (1024 * 1024)} MB")
                        }
                    }
                }
            } else {
                image.inputStream().use { it.copyTo(sink, 1 shl 20) }
            }
            sink.flush()
        }
    }

    private fun runQemuImg(exe: File, vararg args: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .also { it.environment().putAll(qemu.childEnvironment(layout)) }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "qemu-img 超时（600s）"
        }
        process.exitValue() to output
    }.getOrElse { t -> -1 to (t.message ?: t.javaClass.simpleName) }
}
