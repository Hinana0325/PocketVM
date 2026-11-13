package dev.pocketvm.storage

import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.runtime.QemuEnvironment
import dev.pocketvm.runtime.VmSessionHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 快照进度必须在成功、失败和异常取消时都复位，避免界面永久停留在忙碌态。 */
internal inline fun <T> withSnapshotProgress(
    update: (String?) -> Unit,
    message: String,
    block: () -> T,
): T {
    update(message)
    try {
        return block()
    } finally {
        update(null)
    }
}

/**
 * 一台 VM 的快照元数据。
 *
 * 磁盘用 qcow2 **内部快照**（`qemu-img snapshot -c/-a/-d`，秒级、不复制 12 GB）：
 * UEFI 变量（vars.fd，64 MB）与 TPM 状态（如存在）另存副本，恢复时一并回滚。
 * 只在 VM 停止时允许操作（跑着的 qcow2 不能安全写内部快照）。
 */
@Serializable
data class SnapshotMeta(
    val id: String,
    val label: String,
    val createdAtEpochMs: Long,
    /** qcow2 内部快照名（`qemu-img snapshot -l` 里的 Tag）。 */
    val diskTag: String,
    val varsBytes: Long,
    val tpmIncluded: Boolean,
    /** 恢复前自动创建的保险快照。 */
    val auto: Boolean = false,
    /**
     * true = 运行时快照：QMP `savevm` 存了内存 + 设备状态，恢复后不用重启。
     *
     * 代价是**不含** UEFI 变量（pflash）与 TPM 状态，恢复后这两项保持恢复时的值；
     * 要连变量一起回滚只能停机做停止态快照。
     */
    val live: Boolean = false,
)

object SnapshotMetaCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(meta: SnapshotMeta): String = json.encodeToString(SnapshotMeta.serializer(), meta)

    fun decode(text: String): SnapshotMeta? =
        runCatching { json.decodeFromString(SnapshotMeta.serializer(), text) }.getOrNull()

    /** `20261112-141530-a1b2`：时间可读、后缀防同秒冲突；纯小写字母数字与短横线。 */
    fun newId(nowMs: Long, suffix: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "$stamp-${suffix.lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(4).padEnd(4, '0')}"
    }

    /** qcow2 快照 Tag：只允许字母数字与短横线，长度受 qcow2 限制，这里固定前缀。 */
    fun diskTag(id: String): String = "pvm-${id.take(200)}"
}

/** 快照操作的前置条件（纯函数，便于单测）。 */
object SnapshotPolicy {
    fun blockReason(runningVmId: String?, runtime: VmRuntimeState): String? = when {
        runningVmId != null -> "虚拟机正在运行，请先停止再操作快照"
        runtime is VmRuntimeState.Preparing -> "虚拟机正在启动，请等启动完成并停止后再操作快照"
        runtime is VmRuntimeState.Stopping -> "虚拟机正在关机，请等停止完成后再操作快照"
        else -> null
    }

    /**
     * 运行时快照的前置条件：必须在运行态，且不在启动/关机过程中。
     *
     * 和 [blockReason] 相反——停止态该用普通快照（能连 UEFI 变量一起存）。
     */
    fun liveBlockReason(runningVmId: String?, runtime: VmRuntimeState): String? = when {
        runningVmId == null -> "虚拟机没有在运行，请用普通快照"
        runtime is VmRuntimeState.Preparing -> "虚拟机正在启动，请等启动完成"
        runtime is VmRuntimeState.Stopping -> "虚拟机正在关机，请等关机完成"
        else -> null
    }

    /** 恢复入口按快照类型选门禁；运行时快照在停止时禁用，停止态快照反之。 */
    fun restoreAllowed(
        snapshot: SnapshotMeta,
        blockReason: String?,
        liveBlockReason: String?,
    ): Boolean = if (snapshot.live) liveBlockReason == null else blockReason == null

    /** 所有快照都通过 qemu-img 删除内部 Tag，因此只允许在停止状态删除。 */
    fun deleteAllowed(blockReason: String?): Boolean = blockReason == null
}

/**
 * 快照管理：创建 / 列表 / 恢复 / 删除。
 *
 * 失败一律返回 Result，不抛到 UI；所有 qemu-img 调用带 60s 超时，避免挂死。
 * 恢复前会先为当前状态自动建一份 `auto` 快照，误恢复可再回滚。
 */
@Singleton
class SnapshotManager @Inject constructor(
    private val layout: VmStorageLayout,
    private val qemu: QemuEnvironment,
    private val sessions: VmSessionHost,
) {

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    suspend fun list(vmId: String): List<SnapshotMeta> = withContext(Dispatchers.IO) {
        val dir = layout.snapshotsDir(vmId)
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { runCatching { SnapshotMetaCodec.decode(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtEpochMs }
    }

    suspend fun create(vmId: String, label: String): Result<SnapshotMeta> = withContext(Dispatchers.IO) {
        runCatching {
            requireStopped(vmId)
            val disk = layout.diskFile(vmId)
            require(disk.isFile) { "磁盘还不存在，先启动一次虚拟机" }
            require(Qcow2Creator.isQcow2(disk)) { "只有 qcow2 磁盘支持快照" }
            val exe = qemu.resolveQemuImg(layout) ?: error("缺少 qemu-img，无法创建快照")

            val meta = writeSnapshot(vmId, label, exe, auto = false)
            AgentFeed.event("snapshot.created", "vm" to vmId, "id" to meta.id, "auto" to meta.auto)
            meta
        }
    }

    suspend fun restore(vmId: String, snapshotId: String): Result<Unit> = withContext(Dispatchers.IO) {
        withSnapshotProgress({ _progress.value = it }, "正在准备恢复快照…") {
            runCatching {
                requireStopped(vmId)
                val meta = readMeta(vmId, snapshotId) ?: error("快照不存在")
                val exe = qemu.resolveQemuImg(layout) ?: error("缺少 qemu-img，无法恢复快照")
                val disk = layout.diskFile(vmId)
                require(disk.isFile) { "磁盘不存在，无法恢复" }

                // 先给当前状态留退路：自动快照失败就中止恢复。
                _progress.value = "正在创建恢复前自动快照…"
                writeSnapshot(vmId, "恢复前自动快照 ${SnapshotMetaCodec.diskTag(meta.id)}", exe, auto = true)

                _progress.value = "正在回滚磁盘…"
                val (code, out) = runQemuImg(exe, "snapshot", "-a", meta.diskTag, disk.absolutePath)
                check(code == 0) { "qemu-img snapshot -a 失败（exit=$code）：${out.take(300)}" }

                varsBackupFile(vmId, meta.id).takeIf { it.isFile }?.let { vars ->
                    _progress.value = "正在恢复 UEFI 变量…"
                    atomicCopy(vars, layout.varsFile(vmId))
                }
                val tpmDir = tpmBackupDir(vmId, meta.id)
                if (meta.tpmIncluded && tpmDir.isDirectory) {
                    _progress.value = "正在恢复 TPM 状态…"
                    val target = File(layout.vmDir(vmId), "tpm/state")
                    target.deleteRecursively()
                    tpmDir.copyRecursively(target, overwrite = true)
                }
                AgentFeed.event("snapshot.restored", "vm" to vmId, "id" to meta.id)
            }
        }
    }

    suspend fun delete(vmId: String, snapshotId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireStopped(vmId)
            val meta = readMeta(vmId, snapshotId) ?: error("快照不存在")
            val exe = qemu.resolveQemuImg(layout)
            val disk = layout.diskFile(vmId)
            if (exe != null && disk.isFile) {
                // Tag 不存在（比如磁盘被换过）不算失败，删附属物即可。
                runQemuImg(exe, "snapshot", "-d", meta.diskTag, disk.absolutePath)
            }
            metaFile(vmId, meta.id).delete()
            varsBackupFile(vmId, meta.id).delete()
            tpmBackupDir(vmId, meta.id).deleteRecursively()
            AgentFeed.event("snapshot.deleted", "vm" to vmId, "id" to meta.id)
        }
    }

    /**
     * 运行中快照：不用关机，`savevm` 把内存 + 设备状态写进 qcow2 内部快照。
     *
     * 与停止态快照的差别：**没有** UEFI 变量（pflash）和 TPM 状态的副本。恢复后这两项
     * 保持恢复那一刻的值；要连变量一起回滚必须停机做 [create]。
     */
    suspend fun createLive(vmId: String, label: String): Result<SnapshotMeta> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireLive(vmId)
                val disk = layout.diskFile(vmId)
                require(disk.isFile) { "磁盘还不存在，先启动一次虚拟机" }
                require(Qcow2Creator.isQcow2(disk)) { "只有 qcow2 磁盘支持运行时快照" }

                val suffix = (System.nanoTime() and 0xFFFF).toString(16)
                val id = SnapshotMetaCodec.newId(System.currentTimeMillis(), suffix)
                val tag = SnapshotMetaCodec.diskTag(id)

                _progress.value = "正在创建运行时快照（含内存，可能需要一会儿）…"
                try {
                    sessions.saveVmState(vmId, tag).getOrThrow()
                } finally {
                    _progress.value = null
                }

                val meta = SnapshotMeta(
                    id = id,
                    label = label.ifBlank {
                        "运行时快照 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
                    },
                    createdAtEpochMs = System.currentTimeMillis(),
                    diskTag = tag,
                    varsBytes = 0,
                    tpmIncluded = false,
                    live = true,
                )
                metaFile(vmId, id).let { file ->
                    file.parentFile?.mkdirs()
                    file.writeText(SnapshotMetaCodec.encode(meta))
                }
                AgentFeed.event("snapshot.created", "vm" to vmId, "id" to id, "live" to true)
                meta
            }
        }

    /** 回到运行时快照。要求 VM 正在运行——只有活着的 QEMU 进程才能 `loadvm`。 */
    suspend fun restoreLive(vmId: String, snapshotId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireLive(vmId)
                val meta = readMeta(vmId, snapshotId) ?: error("快照不存在")
                require(meta.live) { "这不是运行时快照；停止态快照请先关机再恢复" }

                _progress.value = "正在恢复到运行时快照…"
                try {
                    sessions.loadVmState(vmId, meta.diskTag).getOrThrow()
                } finally {
                    _progress.value = null
                }
                AgentFeed.event("snapshot.restored", "vm" to vmId, "id" to meta.id, "live" to true)
            }
        }

    // ---------- internals ----------

    private fun requireStopped(vmId: String) {
        val state = sessions.stateOf(vmId)
        SnapshotPolicy.blockReason(if (sessions.isRunning(vmId)) vmId else null, state)
            ?.let { error(it) }
    }

    private fun requireLive(vmId: String) {
        val state = sessions.stateOf(vmId)
        SnapshotPolicy.liveBlockReason(if (sessions.isRunning(vmId)) vmId else null, state)
            ?.let { error(it) }
    }

    private fun writeSnapshot(vmId: String, label: String, exe: File, auto: Boolean): SnapshotMeta {
        val disk = layout.diskFile(vmId)
        val suffix = (System.nanoTime() and 0xFFFF).toString(16)
        val id = SnapshotMetaCodec.newId(System.currentTimeMillis(), suffix)
        val tag = SnapshotMetaCodec.diskTag(id)

        return withSnapshotProgress(
            update = { _progress.value = it },
            message = if (auto) "正在创建自动快照…" else "正在创建快照…",
        ) {
            val (code, out) = runQemuImg(exe, "snapshot", "-c", tag, disk.absolutePath)
            check(code == 0) { "qemu-img snapshot -c 失败（exit=$code）：${out.take(300)}" }

            val vars = layout.varsFile(vmId)
            var varsBytes = 0L
            if (vars.isFile) {
                varsBackupFile(vmId, id).let { target ->
                    target.parentFile?.mkdirs()
                    vars.copyTo(target, overwrite = true)
                    varsBytes = target.length()
                }
            }

            val tpmState = File(layout.vmDir(vmId), "tpm/state")
            val tpmIncluded = tpmState.isDirectory
            if (tpmIncluded) tpmState.copyRecursively(tpmBackupDir(vmId, id), overwrite = true)

            val meta = SnapshotMeta(
                id = id,
                label = label.ifBlank { "快照 ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())}" },
                createdAtEpochMs = System.currentTimeMillis(),
                diskTag = tag,
                varsBytes = varsBytes,
                tpmIncluded = tpmIncluded,
                auto = auto,
            )
            metaFile(vmId, id).let { file ->
                file.parentFile?.mkdirs()
                file.writeText(SnapshotMetaCodec.encode(meta))
            }
            meta
        }
    }

    private fun readMeta(vmId: String, id: String): SnapshotMeta? =
        metaFile(vmId, id).takeIf { it.isFile }?.let { SnapshotMetaCodec.decode(it.readText()) }

    private fun metaFile(vmId: String, id: String) = File(layout.snapshotsDir(vmId), "$id.json")
    private fun varsBackupFile(vmId: String, id: String) = File(layout.snapshotsDir(vmId), "$id.vars.fd")
    private fun tpmBackupDir(vmId: String, id: String) = File(layout.snapshotsDir(vmId), "$id.tpm")

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun runQemuImg(exe: File, vararg args: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .also { it.environment().putAll(qemu.childEnvironment(layout)) }
            .start()
        runQemuImgProcess(process, timeoutSec = 60)
    }.getOrElse { t -> -1 to (t.message ?: t.javaClass.simpleName) }
}
