package dev.pocketvm.core.config

import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 旧版 JSON 配置存储（P2 起只读：一次性迁移源）。
 *
 * P2 之前：配置就住在 `vms/<id>/config.json`，这里是唯一读写入口。
 * P2 之后：一手存储是 Room（[dev.pocketvm.data.VmConfigRepository]）；
 * 这个类只剩 [loadAll]/[load]，供首次启动时把旧配置一次性导进 Room
 *（[dev.pocketvm.data.VmConfigRepository.ensureMigrated]），导完就不再写
 * 旧文件。`save`/`delete` 已删掉——代码层面保证旧文件不会再被写入；
 * 删虚拟机（行 + 目录）走仓库的 `delete`。
 *
 * 读的防御姿态不变：单个 config.json 损坏只跳过那一台，不让 App 起不来。
 */
class VmConfigStore @Inject constructor(private val layout: VmStorageLayout) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun loadAll(): List<VmConfig> =
        layout.vmsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { load(it.name) }
            ?.sortedByDescending { it.createdAtEpochMs }
            ?: emptyList()

    fun load(vmId: String): VmConfig? {
        val file = layout.configFile(vmId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<VmConfig>(file.readText()) }.getOrNull()
    }
}
