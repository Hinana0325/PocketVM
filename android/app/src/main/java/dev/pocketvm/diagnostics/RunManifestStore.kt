package dev.pocketvm.diagnostics

import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.serialization.json.Json
import java.io.File

class RunManifestStore(private val layout: VmStorageLayout) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun write(manifest: RunManifest) {
        val file = layout.manifestFile(manifest.vmId, manifest.runId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(RunManifest.serializer(), manifest))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun read(vmId: String, runId: String): RunManifest? {
        val file = layout.manifestFile(vmId, runId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<RunManifest>(file.readText()) }.getOrNull()
    }

    fun latest(vmId: String): RunManifest? =
        layout.logDir(vmId).listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
            ?.let { runCatching { json.decodeFromString<RunManifest>(it.readText()) }.getOrNull() }
}
