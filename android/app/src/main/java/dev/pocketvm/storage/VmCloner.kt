package dev.pocketvm.storage

import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.data.VmConfigRepository
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.runtime.QemuEnvironment
import dev.pocketvm.runtime.VmSessionHost
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 复制虚拟机（roadmap VM Manager 的「复制」）。
 *
 * 只在源 VM 停止时允许。磁盘用 `qemu-img convert -O qcow2` 生成**干净镜像**：
 * 当前状态保留、qcow2 内部快照丢弃（复制语义是「一份新的机器」，不搬历史）；
 * UEFI 变量（vars.fd）与 TPM 状态（如存在）原样复制。
 * qemu-img 不可用时回退为整文件复制（包括内部快照）。
 */
@Singleton
class VmCloner @Inject constructor(
    private val layout: VmStorageLayout,
    private val qemu: QemuEnvironment,
    private val repository: VmConfigRepository,
    private val sessions: VmSessionHost,
) {

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    suspend fun clone(source: VmConfig, existingNames: Collection<String>): Result<VmConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                SnapshotPolicy.blockReason(
                    if (sessions.isRunning(source.id)) source.id else null,
                    sessions.stateOf(source.id),
                )?.let { error(it) }

                val sourceDisk = layout.diskFile(source.id)
                require(sourceDisk.isFile && sourceDisk.length() > 0) { "源虚拟机还没有磁盘，无法复制" }
                require(layout.freeSpaceBytes() > sourceDisk.length()) { "剩余空间不足，无法复制磁盘" }

                val newId = UUID.randomUUID().toString()
                val newDir = layout.vmDir(newId).apply { mkdirs() }
                val targetDisk = layout.diskFile(newId)

                _progress.value = "正在复制磁盘（可能需要几分钟）…"
                val exe = qemu.resolveQemuImg(layout)
                var converted = false
                if (exe != null) {
                    val (code, out) = runQemuImg(
                        exe, "convert", "-O", "qcow2", sourceDisk.absolutePath, targetDisk.absolutePath,
                    )
                    converted = code == 0
                    if (!converted && targetDisk.exists()) targetDisk.delete()
                    if (!converted) {
                        AgentFeed.event("vm.clone_convert_failed", "vm" to source.id, "error" to out.take(200))
                    }
                }
                if (!converted) {
                    // 回退：整文件复制（qcow2 内部快照也随之保留）。
                    sourceDisk.copyTo(targetDisk, overwrite = true)
                }

                layout.varsFile(source.id).takeIf { it.isFile }?.copyTo(layout.varsFile(newId), overwrite = true)
                val tpmState = File(layout.vmDir(source.id), "tpm/state")
                if (tpmState.isDirectory) {
                    tpmState.copyRecursively(File(newDir, "tpm/state"), overwrite = true)
                }

                val copy = source.copy(
                    id = newId,
                    name = CloneNaming.uniqueCloneName(source.name, existingNames),
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                repository.save(copy)
                _progress.value = null
                AgentFeed.event("vm.cloned", "source" to source.id, "clone" to newId, "converted" to converted)
                copy
            }.also { result ->
                if (result.exceptionOrNull() != null) _progress.value = null
            }
        }

    private fun runQemuImg(exe: File, vararg args: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .also { it.environment().putAll(qemu.childEnvironment(layout)) }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(900, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "qemu-img 超时（900s）"
        }
        process.exitValue() to output
    }.getOrElse { t -> -1 to (t.message ?: t.javaClass.simpleName) }
}
