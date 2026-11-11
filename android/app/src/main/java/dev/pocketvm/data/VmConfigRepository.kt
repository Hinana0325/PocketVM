package dev.pocketvm.data

import dev.pocketvm.core.config.VmConfigStore
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 虚拟机配置的唯一读写入口（P2 数据层）。
 *
 * 一手存储是 Room（[VmDatabase]，`pocketvm.db`）；旧的 `config.json` 只在
 * 第一次启动时做一次性迁移源，迁移成功后不再写。读写的调用方
 *（VmViewModel / CreateVmViewModel）只认这个类，不直接碰 DAO 或 JSON 文件。
 *
 * 迁移（[ensureMigrated]）的规则：
 * - 只跑一次：内存里的 [AtomicBoolean] + Room 里 `meta` 表的完成标记双重
 *   把关；标记与配置行在 [VmConfigDao.importLegacy] 的同一个事务里写，
 *   不会出现「配置进了库、标记没写上」导致下次重跑、把已删的虚拟机复活。
 * - 读不出的 `config.json` 会被旧版 [VmConfigStore.loadAll] 静默跳过
 *   （与 P2 之前列表页的行为一致）；文件本身留在磁盘上可手动恢复，这里
 *   只记一笔 AgentFeed 事件，不静默丢数据。
 * - 空库（全新安装、一个虚拟机都没有）也会写标记：否则每次启动都以为
 *   「还没迁过」。
 *
 * 方法都是 suspend，内部自己切到 IO 线程：调用方在主线程调也安全。
 * 运行清单（RunManifestStore）、诊断事件、AgentFeed 是追加型日志，
 * 不是结构化配置，仍走文件，不进这个仓库。
 */
class VmConfigRepository @Inject constructor(
    private val database: VmDatabase,
    private val legacyStore: VmConfigStore,
    private val layout: VmStorageLayout,
) {

    private val dao get() = database.vmConfigDao()

    private val migrated = AtomicBoolean(false)

    suspend fun ensureMigrated() {
        if (migrated.get()) return
        withContext(Dispatchers.IO) {
            if (migrated.get()) return@withContext
            if (dao.getMeta(VmConfigDao.KEY_LEGACY_IMPORT_DONE) != null) {
                migrated.set(true)
                return@withContext
            }
            val legacy = legacyStore.loadAll()
            dao.importLegacy(legacy.map { VmConfigEntity.fromVmConfig(it) })
            migrated.set(true)
            val importedIds = legacy.map { it.id }.toSet()
            val skipped = layout.vmsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.filter { it !in importedIds }
                .orEmpty()
            if (skipped.isNotEmpty()) {
                AgentFeed.event(
                    "room.migration_skipped",
                    "count" to skipped.size,
                    "vmIds" to skipped.joinToString(","),
                )
            }
        }
    }

    suspend fun loadAll(): List<VmConfig> = withContext(Dispatchers.IO) {
        ensureMigrated()
        dao.getAll().map { it.toVmConfig() }
    }

    suspend fun load(id: String): VmConfig? = withContext(Dispatchers.IO) {
        ensureMigrated()
        dao.getById(id)?.toVmConfig()
    }

    suspend fun save(config: VmConfig) {
        withContext(Dispatchers.IO) {
            ensureMigrated()
            dao.upsert(VmConfigEntity.fromVmConfig(config))
        }
    }

    /**
     * 删配置行 + 删整台虚拟机的目录（磁盘、日志、vars），与旧版
     * `VmConfigStore.delete` 的行为一致：删 VM 就是删目录。
     */
    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            ensureMigrated()
            dao.deleteById(id)
            layout.deleteVm(id)
        }
    }
}
