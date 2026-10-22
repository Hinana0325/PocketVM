package dev.pocketvm.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.pocketvm.storage.VmStorageLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One file that answers "why did it fail?" without asking the user to run adb.
 * Contains: config, every run manifest, every log, device facts, QEMU probe.
 */
class DiagnosticsExporter(
    context: Context,
    private val layout: VmStorageLayout,
) {

    private val appContext = context.applicationContext

    private companion object {
        const val MAX_WHOLE_FILE_BYTES = 4L * 1024 * 1024
    }

    fun export(vmId: String, environmentSummary: String? = null): Result<File> = runCatching {
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

            val vmDir = layout.vmDir(vmId)
            addFile("vm", File(vmDir, "config.json"))
            val logs = layout.logDir(vmId).listFiles()
                ?.sortedBy { it.name }
                ?: emptyList()
            // Serial console captures (run-*.serial.log) come first so a UEFI
            // Shell drop is the first thing a reviewer sees in the zip.
            logs.filter { it.name.endsWith(".serial.log") }.forEach { addFile("vm/runs", it) }
            logs.forEach { addFile("vm/runs", it) }
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
