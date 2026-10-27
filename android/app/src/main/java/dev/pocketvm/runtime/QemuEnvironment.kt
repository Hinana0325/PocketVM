package dev.pocketvm.runtime

import android.content.Context
import dev.pocketvm.storage.VmStorageLayout
import java.io.File
import java.util.concurrent.TimeUnit

enum class BinaryOrigin {
    /** Shipped in jniLibs and therefore extracted to an exec-capable, read-only path. */
    PACKAGED_NATIVE_LIB,

    /** Side-loaded into app storage; only executable on devices that allow it. */
    APP_PRIVATE,

    MISSING,
}

data class QemuBinary(
    val file: File,
    val origin: BinaryOrigin,
    val version: String? = null,
    val versionLine: String? = null,
    val executable: Boolean = false,
    val diagnostic: String? = null,
)

/**
 * Locates the execution layer and proves it actually runs.
 *
 * This is deliberately more than "does the file exist". Since Android 10, W^X
 * forbids executing files from writable app data, so a binary dropped into
 * `filesDir` fails with EACCES even though it is present and chmod +x. The only
 * supported shipping channel is a `lib*.so` file in `jniLibs`, which the package
 * manager extracts to `/data/app/.../lib/arm64` — read-only and exec-able.
 *
 * Therefore: probe by execution, never by existence check alone.
 */
class QemuEnvironment(context: Context) {

    private val appContext = context.applicationContext

    fun resolve(layout: VmStorageLayout): QemuEnvironmentReport {
        val nativeLibDir = appContext.applicationInfo?.nativeLibraryDir?.let { File(it) }
        val packaged = nativeLibDir
            ?.listFiles()
            ?.filter { it.name.startsWith("libqemu-system") }
            ?.sortedByDescending { it.name.contains("aarch64") }
            ?.map { it to BinaryOrigin.PACKAGED_NATIVE_LIB }
            ?: emptyList()

        val sideLoaded = listOf(
            File(layout.root, "qemu/bin/qemu-system-aarch64"),
        ).filter { it.exists() }.map { it to BinaryOrigin.APP_PRIVATE }

        val candidates = (packaged + sideLoaded).filter { it.first.isFile }
        if (candidates.isEmpty()) {
            return QemuEnvironmentReport(
                systemBinary = null,
                imageBinary = null,
                abortReason = "nativeLibraryDir=${nativeLibDir?.absolutePath} 下没有 libqemu-system-*.so，" +
                    "应用私有目录也没有 qemu-system-aarch64",
            )
        }

        var probed: List<QemuBinary> = candidates.map { (file, origin) -> probe(file, origin, layout) }
        // One retry after an explicit chmod: some package managers extract libs as 0644.
        if (probed.none { it.executable }) {
            candidates.forEach { (file, _) -> file.setExecutable(true, false) }
            probed = candidates.map { (file, origin) -> probe(file, origin, layout) }
        }

        val usable = probed.filter { it.executable }
        // qemu-img is a *different* binary; reporting a failed system probe as
        // the image tool would send diagnostics chasing the wrong file.
        val img = resolveQemuImg(layout)?.let { probe(it, BinaryOrigin.PACKAGED_NATIVE_LIB, layout) }
        return QemuEnvironmentReport(
            systemBinary = usable.firstOrNull { it.origin == BinaryOrigin.PACKAGED_NATIVE_LIB } ?: usable.firstOrNull(),
            imageBinary = img,
            abortReason = if (usable.isEmpty()) {
                probed.joinToString("; ") { "${it.file.name}: ${it.diagnostic}" }
            } else {
                null
            },
            staticExecOk = probeStaticExec(nativeLibDir, layout),
        )
    }

    /**
     * ChefKiss Inferno 的 iOS 执行层（`libqemu-inferno-system-aarch64.so`）。
     * 与主执行层并存：它只认 `t8030` 机型，Windows/Linux 那条链路继续用原来的
     * QEMU 11.1.2。找不到时返回 null，由 iOS 机型的启动路径给出明确错误。
     */
    fun resolveInferno(layout: VmStorageLayout): QemuBinary? {
        val nativeLibDir = appContext.applicationInfo?.nativeLibraryDir?.let { File(it) }
        val candidates = nativeLibDir?.listFiles()
            ?.filter { it.name.startsWith("libqemu-inferno") }
            ?.sorted()
            ?: emptyList()
        var probed = candidates.filter { it.isFile }
            .map { probe(it, BinaryOrigin.PACKAGED_NATIVE_LIB, layout) }
        if (probed.none { it.executable }) {
            candidates.forEach { it.setExecutable(true, false) }
            probed = candidates.filter { it.isFile }
                .map { probe(it, BinaryOrigin.PACKAGED_NATIVE_LIB, layout) }
        }
        return probed.firstOrNull { it.executable }
    }

    /** `qemu-img` is optional: without it PocketVM falls back to a raw preallocated disk. */
    fun resolveQemuImg(layout: VmStorageLayout): File? {
        val nativeLibDir = appContext.applicationInfo?.nativeLibraryDir?.let { File(it) }
        val candidates = buildList {
            nativeLibDir?.listFiles()
                ?.filter { it.name.startsWith("libqemu-img") || it.name.startsWith("qemu-img") }
                ?.forEach { add(it) }
            add(File(layout.root, "qemu/bin/qemu-img"))
        }
        // Same 0644-extraction workaround as the system binary: try a chmod
        // before giving up on an otherwise present qemu-img.
        candidates.filter { it.isFile }.forEach { it.setExecutable(true, false) }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    fun dataDir(layout: VmStorageLayout): String? =
        layout.qemuShareDir.takeIf { it.isDirectory }?.absolutePath

    /**
     * Environment for the child process. `LD_LIBRARY_PATH` must include the
     * native library dir or a dynamically linked QEMU will not find its own
     * dependencies (glib, pixman, ...).
     */
    fun childEnvironment(layout: VmStorageLayout): Map<String, String> {
        val nativeLibDir = appContext.applicationInfo?.nativeLibraryDir ?: ""
        return buildMap {
            put("PATH", "/system/bin:/system/xbin:/vendor/bin")
            put("LD_LIBRARY_PATH", listOf(nativeLibDir, "${layout.root}/qemu/lib").joinToString(":"))
            put("HOME", layout.root.absolutePath)
            put("TMPDIR", layout.tmpDir.absolutePath)
            put("LANG", "C")
        }
    }

    /**
     * Runs the 400 KB static probe (a single write(2), no libc buffering, no
     * glib). A failure here means static bionic executables do not work on this
     * ROM, so a static QEMU can never start — the build must be dynamically
     * linked and executed through /system/bin/linker64.
     */
    private fun probeStaticExec(nativeLibDir: File?, layout: VmStorageLayout): Boolean? {
        // 16 KB 页设备（部分天玑 9xxx / Android 15+）：探针本身是 4 KB 对齐的
        // ET_EXEC，exec 必然失败，不能把它算成「静态执行不可用」。
        if (!DeviceInfo.shouldProbeStaticExec(DeviceInfo.pageSizeKb())) return null
        val file = nativeLibDir?.let { File(it, "libpvm-probe.so") }?.takeIf { it.isFile } ?: return null
        file.setExecutable(true, false)
        val process = try {
            ProcessBuilder(file.absolutePath)
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnvironment(layout)) }
                .start()
        } catch (t: Throwable) {
            return false
        }
        val finished = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        return process.exitValue() == 0 && output.contains("pvm-probe-ok")
    }

    private fun probe(file: File, origin: BinaryOrigin, layout: VmStorageLayout): QemuBinary {
        if (!file.exists()) {
            return QemuBinary(file, BinaryOrigin.MISSING, diagnostic = "文件不存在")
        }
        file.setExecutable(true, false)

        val process = try {
            ProcessBuilder(listOf(file.absolutePath, "--version"))
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnvironment(layout)) }
                .start()
        } catch (t: Throwable) {
            return QemuBinary(
                file = file,
                origin = origin,
                executable = false,
                diagnostic = "${t.javaClass.simpleName}: ${t.message}",
            )
        }

        val finished = try {
            process.waitFor(8, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            process.destroyForcibly()
            return QemuBinary(file, origin, executable = false, diagnostic = "执行 --version 超时（疑似架构不匹配或缺少动态库）")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.exitValue()
        val line = output.lineSequence().firstOrNull { it.isNotBlank() }
        val version = VERSION_REGEX.find(output)?.groupValues?.get(1)

        return QemuBinary(
            file = file,
            origin = origin,
            version = version,
            versionLine = line,
            executable = exit == 0 || version != null,
            diagnostic = when {
                exit == 0 || version != null -> null
                // bionic 的 static-PIE 不做自重定位：进程在 main 之前就 SIGSEGV。
                exit == 139 -> "启动即段错误（SIGSEGV）。执行层多为 static-PIE 构建，" +
                    "需要用 -static -no-pie 重新编译（bionic 不支持静态 PIE 自举）。"
                else -> "exit=$exit, output=${output.take(300)}"
            },
        )
    }

    private companion object {
        val VERSION_REGEX = Regex("""version\s+([0-9][^\s]*)""")
    }
}

data class QemuEnvironmentReport(
    val systemBinary: QemuBinary?,
    val imageBinary: QemuBinary?,
    val abortReason: String?,
    /**
     * Whether a trivial *statically linked* bionic binary can run on this device.
     * Null when the probe is not packaged. This separates "our QEMU build is
     * broken" from "static executables do not work on this ROM at all".
     */
    val staticExecOk: Boolean? = null,
) {
    val usable: QemuBinary? get() = systemBinary
    val ready: Boolean get() = usable != null
}
