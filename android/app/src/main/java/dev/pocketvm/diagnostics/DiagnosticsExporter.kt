package dev.pocketvm.diagnostics

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import javax.inject.Inject
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One file that answers "why did it fail?" without asking the user to run adb.
 * Contains: config, every run manifest, every log, device facts, QEMU probe.
 */
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext context: Context,
    private val layout: VmStorageLayout,
) {

    private val appContext = context.applicationContext

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private companion object {
        const val MAX_WHOLE_FILE_BYTES = 4L * 1024 * 1024
    }

    /**
     * [config] 是调用方从 Room 读到的当前配置：P2 起磁盘上的 config.json
     * 不再更新（只读迁移源），包里的 config.json 必须写 live 配置才可信。
     * 传 null 时回退到磁盘文件（虚拟机已删、或旧版调用）。
     */
    fun export(
        vmId: String,
        environmentSummary: String? = null,
        config: VmConfig? = null,
    ): Result<File> = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
        val out = File(outDir, "pocketvm-$vmId-$stamp.zip")

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun addFile(prefix: String, file: File) {
                if (!file.isFile) return
                // QEMU logs can reach hundreds of MB; readBytes() would OOM.
                // Small files ship whole, large ones ship their tail (the part
                // that contains the failure anyway).
                if (file.length() <= MAX_WHOLE_FILE_BYTES) {
                    add("$prefix/${file.name}", file.readBytes())
                } else {
                    add("$prefix/${file.name}.tail", LogTail.read(file, maxBytes = 256 * 1024).toByteArray())
                }
            }

            add("device.txt", deviceReport().toByteArray())
            environmentSummary?.let { add("qemu-probe.txt", it.toByteArray()) }
            // The agent feed goes in first: when QEMU dies inside machine init it
            // writes nothing to run-*.log, so this stream is the only account of
            // what the app was doing up to that point.
            addFile("debug", layout.agentFeedFile())

            val vmDir = layout.vmDir(vmId)
            if (config != null) {
                add("vm/config.json", json.encodeToString(VmConfig.serializer(), config).toByteArray())
            } else {
                addFile("vm", File(vmDir, "config.json"))
            }
            val logs = layout.logDir(vmId).listFiles()
                ?.sortedBy { it.name }
                ?: emptyList()
            // Serial console captures (run-*.serial.log) come first so a UEFI
            // Shell drop is the first thing a reviewer sees in the zip.
            val serial = logs.filter { it.name.endsWith(".serial.log") }
            // 运行看门狗时间线是停摆归因的第一手证据（T4 §6.2：异常窗口必须
            // 随包发布）。单独点名，避免未来日志过滤/改名把它漏掉。
            val watchdog = logs.filter { it.name.endsWith(".watchdog.jsonl") }
            serial.forEach { addFile("vm/runs", it) }
            watchdog.forEach { addFile("vm/runs", it) }
            (logs - serial.toSet() - watchdog.toSet()).forEach { addFile("vm/runs", it) }
        }
        out
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PocketVM 诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun deviceReport(): String {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return buildString {
            appendLine("PocketVM diagnostics")
            appendLine("time=${Date()}")
            appendLine("app=${appContext.packageName} version=${appVersion()}")
            appendLine("model=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("device=${Build.DEVICE} product=${Build.PRODUCT}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("soc=${dev.pocketvm.runtime.DeviceInfo.socLabel()}")
            appendLine("pageSizeKb=${dev.pocketvm.runtime.DeviceInfo.pageSizeKb()}")
            appendLine("nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}")
            appendLine("totalMemMb=${mem.totalMem / (1024 * 1024)}")
            appendLine("availMemMb=${mem.availMem / (1024 * 1024)}")
            appendLine("lowMemory=${mem.lowMemory}")
            appendLine("storageRoot=${layout.root.absolutePath}")
            appendLine("storageFreeMb=${layout.freeSpaceBytes() / (1024 * 1024)}")
        }
    }

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}
