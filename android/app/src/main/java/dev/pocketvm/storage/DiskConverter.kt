package dev.pocketvm.storage

import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.runtime.QemuEnvironment
import dev.pocketvm.runtime.VmSessionHost
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 磁盘格式转换（qcow2 ⇄ raw）。
 *
 * 为什么需要：用户导入的镜像多半是 raw，raw 在 FUSE 上会真的吃掉整个虚拟容量，
 * 而且没有内部快照；导入后转成 qcow2 才能省空间、才能用 `SnapshotManager`。
 * 反方向（qcow2 → raw）只用于导出，必须显式确认，因为 raw 会占满虚拟容量。
 *
 * 安全约束：
 * - 只在 VM 停止时转换（跑着的镜像读写会得到损坏的结果）。
 * - 先写到 `<disk>.convert` 临时文件，成功后再原子替换；失败时原盘一个字节不动。
 * - 空间检查按「转换后目标大小 + 原盘仍在」估算，宁可提前拒绝也不写满用户的存储。
 */
@Singleton
class DiskConverter @Inject constructor(
    private val layout: VmStorageLayout,
    private val qemu: QemuEnvironment,
    private val sessions: VmSessionHost,
) {

    enum class Format(val qemuName: String) {
        QCOW2("qcow2"),
        RAW("raw"),
    }

    /** `qemu-img info` 里我们关心的字段。 */
    data class DiskInfo(
        val format: Format,
        val virtualBytes: Long,
        val actualBytes: Long,
    )

    /**
     * 一次转换的计划。[neededFreeBytes] 是**额外**需要的空间：
     * 转换期间原盘和临时文件同时存在，所以不能只看目标大小。
     */
    data class Plan(
        val source: File,
        val from: Format,
        val to: Format,
        val sourceBytes: Long,
        val targetBytes: Long,
        val freeBytes: Long,
    ) {
        val isNoop: Boolean get() = from == to
        val neededFreeBytes: Long get() = targetBytes
        val fits: Boolean get() = freeBytes >= neededFreeBytes
    }

    /**
     * 当前磁盘格式。只看魔数，不调 qemu-img，所以可以安全地在 UI 刷新时同步调用。
     *
     * 返回值只有 qcow2 / raw 两种：这条链路里其它格式（vmdk/vdi）不会出现。
     * 磁盘不存在时返回 null。
     */
    fun currentFormat(vmId: String): Format? {
        val disk = layout.diskFile(vmId)
        if (!disk.isFile) return null
        return if (Qcow2Creator.isQcow2(disk)) Format.QCOW2 else Format.RAW
    }

    suspend fun plan(vmId: String, to: Format): Result<Plan> = withContext(Dispatchers.IO) {
        runCatching {
            requireStopped(vmId)
            val disk = layout.diskFile(vmId)
            require(disk.isFile) { "磁盘不存在，请先启动一次虚拟机" }
            val exe = qemu.resolveQemuImg(layout) ?: error("缺少 qemu-img，无法转换")

            val info = queryInfo(exe, disk) ?: error("读取磁盘信息失败")
            val targetBytes = when (to) {
                // raw 按虚拟容量分配：稀疏能不能保住取决于底层文件系统。
                Format.RAW -> info.virtualBytes
                // qcow2 有元数据开销，给 10% 余量。
                Format.QCOW2 -> (info.actualBytes * 11 / 10).coerceAtLeast(1024 * 1024)
            }
            Plan(
                source = disk,
                from = info.format,
                to = to,
                sourceBytes = info.actualBytes,
                targetBytes = targetBytes,
                freeBytes = layout.freeSpaceBytes(),
            )
        }
    }

    /**
     * 执行转换。调用前建议先 [plan] 并把 [Plan.fits] 的结果交给用户确认。
     *
     * 成功后磁盘仍在 `vms/<id>/disk.qcow2`（文件名保留，格式由魔数判断），
     * 所以调用方不需要改任何路径引用。
     */
    suspend fun convert(vmId: String, to: Format): Result<DiskInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val plan = plan(vmId, to).getOrThrow()
            require(!plan.isNoop) { "磁盘已经是 ${plan.to.qemuName}，不需要转换" }
            require(plan.fits) {
                "空间不足：需要 ${plan.neededFreeBytes} 字节，可用 ${plan.freeBytes} 字节"
            }
            val exe = qemu.resolveQemuImg(layout) ?: error("缺少 qemu-img，无法转换")

            val disk = plan.source
            val tmp = File(disk.parentFile, "${disk.name}.convert")
            val backup = File(disk.parentFile, "${disk.name}.bak")
            tmp.delete()
            backup.delete()

            val startedAt = System.currentTimeMillis()
            AgentFeed.event(
                "disk.convert.start",
                "vm" to vmId,
                "from" to plan.from.qemuName,
                "to" to plan.to.qemuName,
            )

            val (code, out) = runQemuImg(
                exe,
                "convert",
                "-f", plan.from.qemuName,
                "-O", plan.to.qemuName,
                disk.absolutePath,
                tmp.absolutePath,
                timeoutSec = CONVERT_TIMEOUT_SEC,
            )
            if (code != 0 || !tmp.isFile) {
                tmp.delete()
                error("qemu-img convert 失败（exit=$code）：${out.take(300)}")
            }

            // 先给原盘改名留退路，再让新盘上位；任一步失败都回滚。
            if (!disk.renameTo(backup)) {
                tmp.delete()
                error("无法替换磁盘：原文件重命名失败")
            }
            if (!tmp.renameTo(disk)) {
                backup.renameTo(disk)
                tmp.delete()
                error("无法替换磁盘：新文件上位失败，原盘已还原")
            }
            backup.delete()

            val result = queryInfo(exe, disk) ?: error("转换完成但读不回磁盘信息")
            AgentFeed.event(
                "disk.convert.done",
                "vm" to vmId,
                "format" to result.format.qemuName,
                "actualBytes" to result.actualBytes,
                "elapsedMs" to (System.currentTimeMillis() - startedAt),
            )
            result
        }
    }

    // ---------- internals ----------

    private fun requireStopped(vmId: String) {
        if (sessions.isRunning(vmId)) error("虚拟机正在运行，请先停止再转换磁盘格式")
    }

    /** 用 `qemu-img info --output=json` 拿格式与容量；解析失败返回 null。 */
    private fun queryInfo(exe: File, disk: File): DiskInfo? {
        val (code, out) = runQemuImg(exe, "info", "--output=json", disk.absolutePath, timeoutSec = 30)
        if (code != 0) return null
        return runCatching {
            val json = JSONObject(out.substring(out.indexOf('{')).trim())
            val format = when (json.optString("format").lowercase()) {
                "qcow2" -> Format.QCOW2
                "raw" -> Format.RAW
                // 其它格式（vmdk/vdi…）不在这条链路里，按 raw 处理没有意义，直接判失败。
                else -> return null
            }
            DiskInfo(
                format = format,
                virtualBytes = json.optLong("virtual-size", 0L),
                actualBytes = json.optLong("actual-size", disk.length()),
            )
        }.getOrNull()
    }

    private fun runQemuImg(
        exe: File,
        vararg args: String,
        timeoutSec: Long,
    ): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .also { it.environment().putAll(qemu.childEnvironment(layout)) }
            .start()
        // 边跑边读，避免管道写满导致 waitFor 假超时。
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        val output = runCatching { outputFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        if (!finished) {
            process.destroyForcibly()
            return -1 to "qemu-img 超时（${timeoutSec}s）"
        }
        process.exitValue() to output
    }.getOrElse { t -> -1 to (t.message ?: t.javaClass.simpleName) }

    private companion object {
        /** 12 GB 的盘在 TCG 手机上转换可能十几分钟，给足余量。 */
        const val CONVERT_TIMEOUT_SEC = 1800L
    }
}
