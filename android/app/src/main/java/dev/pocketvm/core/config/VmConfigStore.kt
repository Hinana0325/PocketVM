package dev.pocketvm.core.config

import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Configs live next to their disk, so deleting the VM directory removes the VM.
 * There is no database on purpose: a broken config must never make the app
 * unlaunchable, hence the defensive [loadAll].
 */
class VmConfigStore(private val layout: VmStorageLayout) {

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

    fun save(config: VmConfig) {
        val file = layout.configFile(config.id)
        file.parentFile?.mkdirs()
        // Atomic-ish write: a half-written config.json is worse than none.
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(json.encodeToString(VmConfig.serializer(), config))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(vmId: String): Boolean {
        layout.configFile(vmId).delete()
        return layout.deleteVm(vmId)
    }
}
