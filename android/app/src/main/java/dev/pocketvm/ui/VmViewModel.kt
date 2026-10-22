package dev.pocketvm.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketvm.AppContainer
import dev.pocketvm.PocketVmApp
import dev.pocketvm.core.config.Issue
import dev.pocketvm.core.config.Severity
import dev.pocketvm.core.config.ValidationInput
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.runtime.LaunchOutcome
import dev.pocketvm.runtime.VmKeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object List : Screen
    data object Create : Screen
    data class Console(val vmId: String) : Screen
}

data class ImportProgress(val copiedBytes: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (copiedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** What the environment probe found — shown verbatim so users can trust it. */
data class EnvSnapshot(
    val ready: Boolean,
    val binaryPath: String? = null,
    val version: String? = null,
    val origin: String? = null,
    val imageTool: String? = null,
    val dataDir: String? = null,
    val nativeLibDir: String? = null,
    val detail: String? = null,
    /** Result of the packaged static-binary probe; null when not packaged. */
    val staticExec: Boolean? = null,
    /** SoC and page size matter for ARM64 emulation quirks. */
    val soc: String? = null,
    val pageSizeKb: Long? = null,
)

data class UiState(
    val vms: kotlin.collections.List<VmConfig> = emptyList(),
    val screen: Screen = Screen.List,
    val draft: VmConfig? = null,
    /** True when the wizard was opened for an existing VM. */
    val editing: Boolean = false,
    /** Snapshot the draft was opened from, used to detect unsaved edits. */
    val baseline: VmConfig? = null,
    val runtime: VmRuntimeState = VmRuntimeState.Idle,
    val log: String = "",
    val argv: kotlin.collections.List<String> = emptyList(),
    val issues: kotlin.collections.List<Issue> = emptyList(),
    val env: EnvSnapshot? = null,
    val firmwareOptions: kotlin.collections.List<String> = emptyList(),
    /** ISOs already copied into images/, reusable without a second 5 GB copy. */
    val imageOptions: kotlin.collections.List<String> = emptyList(),
    val busy: Boolean = false,
    val importing: ImportProgress? = null,
    val notice: String? = null,
    val shareFile: File? = null,
    val deviceMemoryMb: Long = 0,
    val freeSpaceMb: Long = 0,
    /** vmId -> bytes actually occupied by disk.qcow2 (thin provisioning reality check). */
    val diskUsage: kotlin.collections.Map<String, Long> = emptyMap(),
    /** vmId -> manifest of the most recent launch, for "last time it crashed" hints. */
    val lastRuns: kotlin.collections.Map<String, RunManifest> = emptyMap(),
    /** Phone's Wi-Fi/USB-tether IPv4, shown as the VNC endpoint hint. */
    val localIp: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != null && draft != baseline
}

class VmViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as PocketVmApp).container

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        probeEnvironment()
        refresh()
        collectRuntime()
        tickLog()
    }

    // ---------- environment ----------

    fun probeEnvironment() {
        viewModelScope.launch {
            val (report, firmwarePaths) = withContext(Dispatchers.IO) {
                container.environment.resolve(container.layout) to
                    container.firmwareManager.available().map { f -> f.absolutePath }
            }
            val snapshot = EnvSnapshot(
                ready = report.ready,
                binaryPath = report.usable?.file?.absolutePath,
                version = report.usable?.version,
                origin = report.usable?.origin?.name,
                imageTool = report.imageBinary?.takeIf { it.executable }?.file?.absolutePath,
                dataDir = container.environment.dataDir(container.layout),
                nativeLibDir = getApplication<Application>().applicationInfo.nativeLibraryDir,
                detail = report.abortReason ?: report.imageBinary?.diagnostic,
                staticExec = report.staticExecOk,
                soc = dev.pocketvm.runtime.DeviceInfo.socLabel(),
                pageSizeKb = dev.pocketvm.runtime.DeviceInfo.pageSizeKb(),
            )
            _ui.update {
                it.copy(
                    env = snapshot,
                    localIp = dev.pocketvm.runtime.NetworkInfo.localIpv4(),
                    firmwareOptions = firmwarePaths,
                )
            }
        }
    }

    // ---------- navigation ----------

    fun newVm() {
        viewModelScope.launch {
            val firmware = withContext(Dispatchers.IO) {
                container.firmwareManager.available().firstOrNull()?.absolutePath
            }
            val draft = VmConfig.create("Windows 11 ARM64").let { base ->
                base.copy(
                    memoryMb = suggestedMemoryMb(),
                    firmwareCodePath = firmware,
                )
            }
            _ui.update {
                it.copy(
                    screen = Screen.Create,
                    draft = draft,
                    editing = false,
                    baseline = draft,
                    issues = emptyList(),
                    notice = null,
                )
            }
            refreshIssues(draft)
        }
    }

    fun editVm(vmId: String) {
        val vm = container.configStore.load(vmId) ?: return
        _ui.update {
            it.copy(screen = Screen.Create, draft = vm, editing = true, baseline = vm, notice = null)
        }
        refreshIssues(vm)
    }

    fun openConsole(vmId: String) {
        _ui.update { it.copy(screen = Screen.Console(vmId), log = container.session.readLog(), notice = null) }
    }

    fun back() {
        _ui.update { it.copy(screen = Screen.List, draft = null, editing = false, baseline = null, notice = null) }
        refresh()
    }

    // ---------- draft editing ----------

    fun updateDraft(transform: (VmConfig) -> VmConfig) {
        val current = _ui.value.draft ?: return
        val next = transform(current)
        _ui.update { it.copy(draft = next) }
        refreshIssues(next)
    }

    fun saveDraft() {
        val draft = _ui.value.draft ?: return
        container.configStore.save(draft)
        val errors = _ui.value.issues.count { it.severity == Severity.ERROR }
        refresh()
        _ui.update {
            it.copy(
                screen = Screen.List,
                draft = null,
                editing = false,
                baseline = null,
                notice = if (errors > 0) {
                    "已保存 ${draft.name}，但还有 $errors 个错误，需修复后才能启动"
                } else {
                    "已保存 ${draft.name}"
                },
            )
        }
    }

    fun deleteVm(vmId: String) {
        // Block deletion in *any* active state: Preparing/Stopping still have a
        // QEMU process (or one about to fork) writing to the qcow2 and pflash.
        val busy = container.session.isRunning() ||
            _ui.value.runtime is VmRuntimeState.Preparing ||
            _ui.value.runtime is VmRuntimeState.Stopping
        if (busy) {
            _ui.update { it.copy(notice = "虚拟机正在运行或正在停止，无法删除") }
            return
        }
        container.configStore.delete(vmId)
        refresh()
        _ui.update { it.copy(notice = "已删除虚拟机") }
    }

    // ---------- lifecycle ----------

    fun start(vmId: String) {
        val vm = container.configStore.load(vmId) ?: return
        viewModelScope.launch {
            // Jump straight to the console: that is where the launch progress
            // steps and the reason for a failure are shown. Staying on the list
            // made a 20 s prepare phase look like a dead button.
            _ui.update {
                it.copy(busy = true, notice = null, log = "", screen = Screen.Console(vmId))
            }
            val outcome = container.session.start(vm)
            val message = when (outcome) {
                is LaunchOutcome.Started -> {
                    VmKeepAliveService.start(getApplication())
                    if (outcome.vncPort != vm.vncPort) {
                        // Persist the port we actually bound so the next start and
                        // the VNC hint stay in sync with reality.
                        container.configStore.save(vm.copy(vncPort = outcome.vncPort))
                        refresh()
                    }
                    buildString {
                        if (outcome.failed.isEmpty()) {
                            append("QEMU 已启动（${outcome.profile.label}），固件画面就绪后连接 VNC")
                        } else {
                            append("标准模式未成功（${outcome.failed.joinToString("；")}），")
                            append("已自动切到${outcome.profile.label}模式启动")
                        }
                        container.session.preflightNote?.let { append("；").append(it) }
                    }
                }
                is LaunchOutcome.Rejected -> outcome.report.errors.joinToString("\n") { it.message }
                is LaunchOutcome.Failed -> listOfNotNull(outcome.message, outcome.hint).joinToString("\n")
            }
            _ui.update {
                it.copy(
                    busy = false,
                    notice = message,
                    log = container.session.readLog(),
                    argv = container.session.currentRun()?.argv ?: it.argv,
                )
            }
        }
    }

    fun stop(vmId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = "正在请求关机…") }
            container.session.stop(vmId)
            // Only release the keep-alive once the process is really gone;
            // releasing it before an ACPI shutdown finishes is how Android ends
            // up killing the app mid-write.
            val stillRunning = container.session.isRunning()
            if (!stillRunning) VmKeepAliveService.stop(getApplication())
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (stillRunning) "已发送关机请求，等待 Windows 响应…" else "虚拟机已停止",
                    log = container.session.readLog(),
                )
            }
        }
    }

    fun refreshLog() {
        _ui.update {
            it.copy(
                log = container.session.readLog(),
                argv = container.session.currentRun()?.argv ?: it.argv,
            )
        }
    }

    fun exportDiagnostics(vmId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { container.session.exportDiagnostics(vmId) }
            _ui.update { state ->
                result.fold(
                    onSuccess = { state.copy(shareFile = it, notice = "诊断包已生成") },
                    onFailure = { state.copy(notice = "生成诊断包失败：${it.message}") },
                )
            }
        }
    }

    fun consumeShare() = _ui.update { it.copy(shareFile = null) }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    // ---------- media ----------

    fun importIso(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(importing = ImportProgress(0, null), notice = null) }
            val result = withContext(Dispatchers.IO) {
                container.isoImporter.import(uri) { copied, total ->
                    _ui.update { it.copy(importing = ImportProgress(copied, total)) }
                }
            }
            result.fold(
                onSuccess = { file ->
                    val attached = _ui.value.draft != null
                    if (!attached) {
                        // The user left the wizard mid-copy: without this the ISO
                        // sits in images/ as an orphan nobody can reach.
                        withContext(Dispatchers.IO) { file.delete() }
                        _ui.update { it.copy(importing = null, notice = "已离开编辑页，导入的镜像已丢弃") }
                    } else {
                        _ui.update { state ->
                            val draft = state.draft?.copy(isoPath = file.absolutePath, bootFromCd = true)
                            state.copy(
                                draft = draft,
                                importing = null,
                                imageOptions = (state.imageOptions + file.absolutePath).distinct().sorted(),
                                notice = "已导入 ${file.name}",
                            )
                        }
                        _ui.value.draft?.let { refreshIssues(it) }
                    }
                },
                onFailure = { error ->
                    _ui.update { it.copy(importing = null, notice = "导入失败：${error.message}") }
                },
            )
        }
    }

    fun importFirmware(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { container.isoImporter.importFirmware(uri) }
            result.fold(
                onSuccess = { file ->
                    _ui.update { it.copy(notice = "已导入固件 ${file.name}") }
                    probeEnvironment()
                    updateDraft { it.copy(firmwareCodePath = file.absolutePath) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(notice = "固件导入失败：${error.message}") }
                },
            )
        }
    }

    /**
     * Once Windows is installed the ISO is only a boot-order obstacle. Eject it
     * and, if the VM is running, restart straight from the virtual disk — this
     * is the one step every user needs after setup and used to require rebuilding
     * the VM from scratch.
     */
    fun ejectIso(vmId: String) {
        viewModelScope.launch {
            val vm = container.configStore.load(vmId) ?: return@launch
            if (vm.isoPath == null) return@launch
            val updated = vm.copy(isoPath = null, bootFromCd = false)
            container.configStore.save(updated)
            refresh()

            if (container.session.runningVmId() != vmId) {
                _ui.update { it.copy(notice = "已卸载安装介质，下次启动将从虚拟磁盘引导") }
                return@launch
            }

            _ui.update { it.copy(busy = true, notice = "已卸载安装介质，正在从虚拟磁盘重启…") }
            container.session.stop(vmId)
            val started = container.session.start(updated) is LaunchOutcome.Started
            if (started) VmKeepAliveService.start(getApplication())
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (started) "已卸载介质，正在从虚拟磁盘启动" else "介质已卸载，但重启失败，请手动启动",
                    log = container.session.readLog(),
                )
            }
        }
    }

    // ---------- internals ----------

    private fun refresh() {
        // available() may decompress the 64 MiB bundled firmware on first call:
        // strictly off the main thread or cold start ANRs.
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val vms = container.configStore.loadAll()
                val usage = vms.associate { vm ->
                    vm.id to container.diskProvisioner.usedBytes(container.layout.diskFile(vm.id))
                }
                Triple(
                    vms,
                    usage,
                    container.firmwareManager.available().map { f -> f.absolutePath },
                )
            }
            val images = withContext(Dispatchers.IO) {
                container.layout.imagesDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".iso", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.map { it.absolutePath }
                    ?: emptyList()
            }
            val lastRuns = withContext(Dispatchers.IO) {
                data.first.mapNotNull { vm -> container.manifests.latest(vm.id)?.let { vm.id to it } }.toMap()
            }
            _ui.update {
                it.copy(
                    vms = data.first,
                    diskUsage = data.second,
                    firmwareOptions = data.third,
                    imageOptions = images,
                    lastRuns = lastRuns,
                    deviceMemoryMb = deviceMemoryMb(),
                    freeSpaceMb = container.layout.freeSpaceBytes() / (1024 * 1024),
                )
            }
        }
    }

    private fun refreshIssues(config: VmConfig) {
        // resolve() may trigger the bundled-firmware decompress; keep it off the
        // main thread. Draft edits arrive rapidly, so latest-write-wins via the
        // config identity check after the IO hop.
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val vmDir = container.layout.vmDir(config.id)
                container.validator.validate(
                    ValidationInput(
                        config = config,
                        firmwareCode = container.firmwareManager.resolve(config),
                        firmwareVars = container.layout.varsFile(config.id),
                        isoFile = config.isoPath?.let(::File),
                        diskParentWritable = vmDir.exists() && vmDir.canWrite(),
                        deviceMemoryMb = deviceMemoryMb(),
                        freeSpaceBytes = container.layout.freeSpaceBytes(),
                        qemuAvailable = _ui.value.env?.ready == true,
                        otherVms = _ui.value.vms.filter { it.id != config.id },
                        qemuDetail = _ui.value.env?.detail,
                    ),
                )
            }
            // Drop stale results if the user kept editing while we were on IO.
            if (_ui.value.draft == config || _ui.value.draft == null) {
                _ui.update { it.copy(issues = report.issues) }
            }
        }
    }

    private fun collectRuntime() {
        viewModelScope.launch {
            container.session.state.collect { state ->
                _ui.update { it.copy(runtime = state) }
                // Keep the wakelock through the whole shutdown: dropping it while
                // Stopping is in flight is exactly when Android freezes the app.
                val keepAlive = state is VmRuntimeState.Running ||
                    state is VmRuntimeState.Preparing ||
                    state is VmRuntimeState.Stopping
                if (!keepAlive) VmKeepAliveService.stop(getApplication())
            }
        }
    }

    private fun tickLog() {
        viewModelScope.launch {
            while (true) {
                val snapshot = _ui.value
                val running = snapshot.runtime is VmRuntimeState.Running ||
                    snapshot.runtime is VmRuntimeState.Stopping
                if (running) {
                    // Both the tail read and the stat are file I/O: never on Main.
                    val (text, runningId, used) = withContext(Dispatchers.IO) {
                        val id = container.session.runningVmId()
                        Triple(
                            container.session.readLog(),
                            id,
                            id?.let { container.diskProvisioner.usedBytes(container.layout.diskFile(it)) },
                        )
                    }
                    _ui.update { current ->
                        val nextUsage = if (runningId != null && used != null) {
                            current.diskUsage + (runningId to used)
                        } else {
                            current.diskUsage
                        }
                        current.copy(
                            log = if (text != current.log) text else current.log,
                            diskUsage = nextUsage,
                        )
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun deviceMemoryMb(): Long {
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem / (1024 * 1024)
    }

    private fun suggestedMemoryMb(): Int {
        val total = deviceMemoryMb()
        val candidate = ((total * 0.5).toInt() / 512) * 512
        return candidate.coerceIn(2048, 6144)
    }

    val warningCount: Int get() = _ui.value.issues.count { it.severity == Severity.WARNING }
}
