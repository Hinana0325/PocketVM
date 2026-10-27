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
import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.storage.AppleAssets
import dev.pocketvm.runtime.AnsiText
import dev.pocketvm.runtime.LaunchOutcome
import dev.pocketvm.runtime.SshConsole
import dev.pocketvm.runtime.SshDefaults
import dev.pocketvm.runtime.SshState
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
    data object Environment : Screen
    data object About : Screen
    data object Create : Screen
    data class Console(val vmId: String) : Screen
}

/** Whether this screen sits behind the bottom navigation bar. */
val Screen.isTopLevel: Boolean
    get() = this is Screen.List || this is Screen.Environment || this is Screen.About

/** SSH 控制台保留的输出上限：超出后从头截断，防止长命令把 UiState 撑爆。 */
private const val SSH_OUTPUT_LIMIT = 128 * 1024

data class ImportProgress(val copiedBytes: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (copiedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** One poll of the live run's on-disk state; a tuple would be unreadable. */
private data class LogTick(
    val log: String,
    val serial: String,
    val vmId: String?,
    val usedBytes: Long?,
)

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
    /**
     * Tail of the guest's serial console. QEMU stays silent on a healthy launch,
     * so this — not [log] — is what actually shows the firmware picking a boot
     * option, the installer asking for a keypress, or a Shell drop.
     */
    val serial: String = "",
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
    /** Latest performance sample of the running VM; null when nothing runs. */
    val metrics: dev.pocketvm.diagnostics.VmMetrics? = null,
    /** SSH 控制台连接状态；密码不在 UiState 里。 */
    val sshState: SshState = SshState.Disconnected,
    /** SSH 输出尾部（stdout + stderr），最多保留 [SSH_OUTPUT_LIMIT] 字节。 */
    val sshOutput: String = "",
    /** 正在建连或执行命令。 */
    val sshRunning: Boolean = false,
    /** PTY 交互 shell 是否打开。 */
    val sshShellOpen: Boolean = false,
    /** 最近一次客户机按键的名称与结果，用来给按键面板即时反馈。 */
    val lastGuestKeyLabel: String? = null,
    val lastGuestKeyOk: Boolean? = null,
    /** vmId -> iPhone 11 资源（固件 / NVMe 镜像）是否齐全。 */
    val appleAssets: Map<String, AppleAssets.Status> = emptyMap(),
) {
    val hasUnsavedChanges: Boolean get() = draft != null && draft != baseline
}

class VmViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as PocketVmApp).container

    /** SSH 控制台：一条 VM 会话只挂一条 SSH 连接。 */
    private val sshConsole = SshConsole()

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
        if ((_ui.value.screen as? Screen.Console)?.vmId != vmId) {
            // 换了虚拟机：旧 SSH 会话没有意义，清掉输出免得串台。
            viewModelScope.launch(Dispatchers.IO) { sshConsole.disconnect() }
            _ui.update { it.copy(sshOutput = "", sshRunning = false) }
        }
        _ui.update {
            it.copy(
                screen = Screen.Console(vmId),
                log = container.session.readLog(vmId),
                serial = container.session.readSerialTail(vmId),
                notice = null,
            )
        }
    }

    fun back() {
        _ui.update { it.copy(screen = Screen.List, draft = null, editing = false, baseline = null, notice = null) }
        refresh()
    }

    /** Bottom-navigation: jump to a top-level destination. */
    fun navigate(screen: Screen) {
        _ui.update { it.copy(screen = screen, notice = null) }
    }

    // ---------- draft editing ----------

    fun updateDraft(transform: (VmConfig) -> VmConfig) {
        val current = _ui.value.draft ?: return
        val next = transform(current)
        _ui.update { it.copy(draft = next) }
        refreshIssues(next)
    }

    fun setPlatform(platform: VmPlatform) = updateDraft { draft ->
        if (draft.platform == platform) return@updateDraft draft
        val name = if (platform == VmPlatform.APPLE_T8030 &&
            (draft.name.isBlank() || draft.name == VmConfig.DEFAULT_NAME)
        ) {
            "iPhone 11"
        } else {
            draft.name
        }
        // t8030 在 TCG 下每多一个 vCPU/GB 都是实打实的宿主内存与线程开销；
        // 现有脚本用 7 vCPU / 6 GB，手机端先按 4 / 3 GB 起步，用户仍可再调。
        draft.copy(
            platform = platform,
            name = name,
            cpuCores = if (platform == VmPlatform.APPLE_T8030) draft.cpuCores.coerceAtMost(4) else draft.cpuCores,
            memoryMb = if (platform == VmPlatform.APPLE_T8030) draft.memoryMb.coerceAtMost(3072) else draft.memoryMb,
        )
    }

    /**
     * 导入 iOS 还原包 zip。一个 25 GB 的 nvme.1 解压要几分钟，所以进度按
     * 「已写字节数」推进；中途离开页面也继续写完，避免留下半个镜像。
     */
    fun importAppleBundle(uri: Uri) {
        val draft = _ui.value.draft ?: return
        val target = AppleAssets.dir(container.layout, draft.id)
        viewModelScope.launch {
            _ui.update { it.copy(importing = ImportProgress(0, null), notice = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("无法打开所选文件")
                    input.use { stream ->
                        AppleAssets.importZip(stream, target) { _, total ->
                            _ui.update { state ->
                                state.copy(importing = ImportProgress(total, null))
                            }
                        }
                    }
                }
            }
            result.fold(
                onSuccess = { names ->
                    val status = withContext(Dispatchers.IO) {
                        AppleAssets.status(container.layout, draft.id)
                    }
                    _ui.update {
                        it.copy(
                            importing = null,
                            notice = "已导入 ${names.size} 个 iOS 资源文件（共 ${status.totalBytes / (1024 * 1024)} MB）",
                            appleAssets = it.appleAssets + (draft.id to status),
                        )
                    }
                    refreshIssues(draft)
                },
                onFailure = { t ->
                    _ui.update {
                        it.copy(importing = null, notice = "iOS 资源导入失败：${t.message}")
                    }
                },
            )
        }
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
        // 只拦「这一台」还在运行/准备/停止；另一台在运行不该阻止删除 B。
        val runtime = _ui.value.runtime
        val busy = container.session.runningVmId() == vmId ||
            (runtime as? VmRuntimeState.Preparing)?.vmId == vmId ||
            (runtime as? VmRuntimeState.Stopping)?.vmId == vmId
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
                it.copy(
                    busy = true, notice = null, log = "", screen = Screen.Console(vmId),
                    sshOutput = "", sshRunning = false,
                )
            }
            withContext(Dispatchers.IO) { sshConsole.disconnect() }
            val outcome = container.session.start(vm)
            // The CD fell back to virtio-scsi: remember it so the next start skips
            // the crash entirely (AUTO no longer retries USB). Input is left as
            // configured — the downgrade now keeps USB key/tablet, and virtio
            // input is the one that drops keys on this hardware.
            // Only persists when the transport actually changed, so an explicit
            // VIRTIO_SCSI choice is never silently overridden.
            val finalTransport = when (outcome) {
                is LaunchOutcome.Started -> outcome.cdTransport
                is LaunchOutcome.Failed -> outcome.cdTransport
                else -> null
            }
            // 一次写回：降级和实际端口可能同时发生，分两次 save 会用同一个旧快照
            // 互相覆盖（第二次把 cdTransport 又写回 AUTO）。
            var persisted = vm
            if (finalTransport == CdTransport.VIRTIO_SCSI && vm.cdTransport != CdTransport.VIRTIO_SCSI) {
                persisted = persisted.copy(cdTransport = CdTransport.VIRTIO_SCSI)
            }
            (outcome as? LaunchOutcome.Started)?.let { started ->
                if (started.vncPort != persisted.vncPort) {
                    persisted = persisted.copy(vncPort = started.vncPort)
                }
            }
            if (persisted != vm) {
                container.configStore.save(persisted)
                refresh()
            }
            val message = when (outcome) {
                is LaunchOutcome.Started -> {
                    buildString {
                        if (outcome.failed.isEmpty()) {
                            append("QEMU 已启动（${outcome.profile.label}），固件画面就绪后连接 VNC")
                        } else {
                            // Say what actually changed. A USB-crash retry keeps the
                            // same profile and only moves the CD bus — calling that
                            // "已自动切到标准模式" read as 标准→标准 and buried the
                            // real change, which the preflight note spells out.
                            append("首次启动失败（${outcome.failed.joinToString("；")}）")
                            if (outcome.profileChanged) {
                                append("，已自动切到${outcome.profile.label}模式")
                            }
                            append("；重试后启动成功")
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
                    log = container.session.readLog(vmId),
                    serial = container.session.readSerialTail(vmId),
                    argv = container.session.currentRun(vmId)?.argv ?: it.argv,
                )
            }
        }
    }

    fun stop(vmId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = "正在请求关机…") }
            container.session.stop(vmId)
            // 前台服务/唤醒锁由 VmSession 在进程真正退出后释放。
            val stillRunning = container.session.isRunning()
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (stillRunning) "已发送关机请求，等待 Windows 响应…" else "虚拟机已停止",
                    log = container.session.readLog(vmId),
                )
            }
        }
    }

    fun refreshLog() {
        val vmId = (_ui.value.screen as? Screen.Console)?.vmId
        _ui.update {
            it.copy(
                log = container.session.readLog(vmId),
                serial = container.session.readSerialTail(vmId),
                argv = container.session.currentRun(vmId)?.argv ?: it.argv,
            )
        }
    }

    // ---------- SSH 控制台 ----------

    fun sshConnect(host: String, port: Int, user: String, password: String) {
        SshDefaults.validate(host, port, user)?.let { message ->
            _ui.update { it.copy(notice = message) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(sshRunning = true) }
            val failure = withContext(Dispatchers.IO) {
                sshConsole.connect(host, port, user, password)
            }
            _ui.update {
                val line = if (failure == null) "[SSH] 已连接 $user@$host:$port\n" else "[SSH] $failure\n"
                it.copy(
                    sshRunning = false,
                    sshOutput = (it.sshOutput + "\n" + line).takeLast(SSH_OUTPUT_LIMIT),
                    notice = failure,
                )
            }
        }
    }

    fun sshRun(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        if (sshConsole.state.value !is SshState.Connected) {
            _ui.update { it.copy(notice = "先连接 SSH") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    sshRunning = true,
                    sshOutput = (it.sshOutput + "\n$ " + cmd + "\n").takeLast(SSH_OUTPUT_LIMIT),
                )
            }
            val code = withContext(Dispatchers.IO) {
                sshConsole.run(cmd) { chunk ->
                    _ui.update { s -> s.copy(sshOutput = (s.sshOutput + chunk).takeLast(SSH_OUTPUT_LIMIT)) }
                }
            }
            _ui.update {
                it.copy(
                    sshRunning = false,
                    sshOutput = if (code == null) {
                        it.sshOutput
                    } else {
                        (it.sshOutput + "[exit $code]\n").takeLast(SSH_OUTPUT_LIMIT)
                    },
                )
            }
        }
    }

    fun sshDisconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sshConsole.disconnect() }
            _ui.update {
                it.copy(
                    sshShellOpen = false,
                    sshOutput = (it.sshOutput + "\n[SSH] 已断开\n").takeLast(SSH_OUTPUT_LIMIT),
                )
            }
        }
    }

    fun sshClearOutput() {
        _ui.update { it.copy(sshOutput = "") }
    }

    fun sshOpenShell() {
        if (sshConsole.state.value !is SshState.Connected) {
            _ui.update { it.copy(notice = "先连接 SSH") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(sshRunning = true) }
            val failure = withContext(Dispatchers.IO) {
                sshConsole.openShell(
                    onOutput = { chunk ->
                        val text = AnsiText.strip(chunk)
                        if (text.isNotEmpty()) {
                            _ui.update { s ->
                                s.copy(sshOutput = (s.sshOutput + text).takeLast(SSH_OUTPUT_LIMIT))
                            }
                        }
                    },
                    onClosed = {
                        _ui.update { s -> s.copy(sshShellOpen = false) }
                    },
                )
            }
            _ui.update {
                val line = if (failure == null) {
                    "\n[SSH] 交互终端已打开（「^C」发送 Ctrl+C）\n"
                } else {
                    "\n[SSH] $failure\n"
                }
                it.copy(
                    sshRunning = false,
                    sshShellOpen = failure == null && sshConsole.isShellOpen(),
                    sshOutput = (it.sshOutput + line).takeLast(SSH_OUTPUT_LIMIT),
                    notice = failure,
                )
            }
        }
    }

    fun sshCloseShell() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sshConsole.closeShell() }
            _ui.update {
                it.copy(
                    sshShellOpen = false,
                    sshOutput = (it.sshOutput + "\n[SSH] 交互终端已关闭\n").takeLast(SSH_OUTPUT_LIMIT),
                )
            }
        }
    }

    fun sshShellSend(line: String) {
        if (line.isBlank()) return
        // PTY 会把输入回显到输出里，这里不再重复追加。
        viewModelScope.launch(Dispatchers.IO) { sshConsole.sendShellLine(line) }
    }

    fun sshShellCtrlC() {
        viewModelScope.launch(Dispatchers.IO) { sshConsole.sendShellCtrlC() }
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

    /**
     * Types `fs0:\EFI\BOOT\BOOTAA64.EFI` into a guest that is sitting at the
     * UEFI Shell prompt. Only meaningful while running, and only when the
     * firmware failed to pick a boot option on its own.
     */
    fun bootFromShell(vmId: String) {
        viewModelScope.launch {
            val running = (container.session.state.value as? VmRuntimeState.Running)?.vmId == vmId
            if (!running) {
                _ui.update { it.copy(notice = "虚拟机未运行") }
                return@launch
            }
            _ui.update { it.copy(notice = "正在向 UEFI Shell 输入引导命令…") }
            val ok = withContext(Dispatchers.IO) { container.session.bootFromShell("fs0") }
            _ui.update {
                it.copy(
                    notice = if (ok) {
                        "已输入引导命令，Shell 的回应见「固件输出」；" +
                            "若报 not recognized，说明固件的启动项里没有可用的安装介质"
                    } else {
                        "无法发送按键：QMP 通道不可用"
                    },
                )
            }
        }
    }

    fun clearNotice(shown: String? = null) {
        _ui.update {
            // 只清掉刚刚展示过的那一条；展示期间新到的通知不能被误吞。
            if (shown == null || it.notice == shown) it.copy(notice = null) else it
        }
    }

    /**
     * 单个按键（方向键、Esc、F2 等）。手机 VNC 客户端通常没有这些键，
     * 所以这是固件菜单/安装界面唯一可靠的输入途径。静默发送，不弹提示。
     */
    fun sendGuestKey(vmId: String, qcode: String, label: String) {
        if (container.session.runningVmId() != vmId) {
            _ui.update { it.copy(lastGuestKeyLabel = label, lastGuestKeyOk = false) }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { container.session.sendKey(qcode) }
            _ui.update { it.copy(lastGuestKeyLabel = label, lastGuestKeyOk = ok) }
        }
    }

    /** 组合键，例如 Ctrl+Alt+Del。 */
    fun sendGuestCombo(vmId: String, qcodes: List<String>) {
        if (container.session.runningVmId() != vmId) return
        viewModelScope.launch(Dispatchers.IO) { container.session.sendKeys(qcodes) }
    }

    /** 把一段文本打给客户机（US 布局子集），末尾由调用方决定是否带回车。 */
    fun typeGuestText(vmId: String, text: String) {
        val payload = text.take(200)
        if (payload.isEmpty()) return
        if (container.session.runningVmId() != vmId) {
            _ui.update { it.copy(notice = "这台虚拟机没有在运行") }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { container.session.typeText(payload) }
            if (!ok) {
                _ui.update { it.copy(notice = "文本发送失败：只支持 US 布局的字母/数字/常用符号") }
            }
        }
    }

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
                            val draft = state.draft?.let { d ->
                                val name = if (d.name == VmConfig.DEFAULT_NAME || d.name.isBlank())
                                    VmConfig.nameFromIso(file.absolutePath) ?: d.name
                                else d.name
                                d.copy(isoPath = file.absolutePath, bootFromCd = true, name = name)
                            }
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

    fun importDriverIso(uri: Uri) {
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
                        withContext(Dispatchers.IO) { file.delete() }
                        _ui.update { it.copy(importing = null, notice = "已离开编辑页，导入的镜像已丢弃") }
                    } else {
                        _ui.update { state ->
                            val draft = state.draft?.copy(driverIsoPath = file.absolutePath)
                            state.copy(
                                draft = draft,
                                importing = null,
                                imageOptions = (state.imageOptions + file.absolutePath).distinct().sorted(),
                                notice = "已导入驱动盘 ${file.name}",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _ui.update { it.copy(importing = null, notice = "驱动盘导入失败：${error.message}") }
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
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (started) "已卸载介质，正在从虚拟磁盘启动" else "介质已卸载，但重启失败，请手动启动",
                    log = container.session.readLog(vmId),
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
            val appleAssets = withContext(Dispatchers.IO) {
                data.first.filter { it.platform == VmPlatform.APPLE_T8030 }
                    .associate { vm -> vm.id to AppleAssets.status(container.layout, vm.id) }
            }
            _ui.update {
                it.copy(
                    vms = data.first,
                    diskUsage = data.second,
                    firmwareOptions = data.third,
                    imageOptions = images,
                    lastRuns = lastRuns,
                    appleAssets = appleAssets,
                    deviceMemoryMb = deviceMemoryMb(),
                    freeSpaceMb = container.layout.freeSpaceBytes() / (1024 * 1024),
                )
            }
        }
    }

    private fun refreshIssues(config: VmConfig) {
        // iOS 机型的校验和 UEFI 完全不同：没有固件/ISO/qcow2，只有「资源是否导入」。
        if (config.platform == VmPlatform.APPLE_T8030) {
            viewModelScope.launch {
                val status = withContext(Dispatchers.IO) {
                    AppleAssets.status(container.layout, config.id)
                }
                val issues = buildList {
                    if (!status.present) {
                        add(
                            Issue(
                                Severity.ERROR,
                                "APPLE_ASSETS_MISSING",
                                "iOS 资源不完整：缺少 ${status.missing.joinToString("、")}。" +
                                    "请在下方导入还原包 zip。",
                            ),
                        )
                    }
                    if (config.memoryMb < 2048) {
                        add(
                            Issue(
                                Severity.WARNING,
                                "APPLE_MEMORY_LOW",
                                "iOS 建议至少 2 GB 内存；4 GB 客户机在部分机型上会因宿主内存不足而失败。",
                            ),
                        )
                    }
                    if (config.vncAccess == VncAccess.LAN) {
                        add(
                            Issue(
                                Severity.ERROR,
                                "VNC_LAN_UNAVAILABLE",
                                "局域网模式暂不可用（缺少 DES 后端），请改回「仅本机」+ adb forward。",
                            ),
                        )
                    }
                }
                if (_ui.value.draft == config || _ui.value.draft == null) {
                    _ui.update {
                        it.copy(issues = issues, appleAssets = it.appleAssets + (config.id to status))
                    }
                }
            }
            return
        }
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
                if (!keepAlive) {
                    // 前台服务由 VmSession 收尾；这里只处理 UI 侧的 SSH 状态。
                    if (sshConsole.state.value !is SshState.Disconnected) {
                        viewModelScope.launch(Dispatchers.IO) { sshConsole.disconnect() }
                    }
                    if (_ui.value.sshShellOpen) {
                        _ui.update { it.copy(sshShellOpen = false) }
                    }
                }
            }
        }
        viewModelScope.launch {
            container.session.metrics.collect { sample ->
                _ui.update { it.copy(metrics = sample) }
            }
        }
        viewModelScope.launch {
            sshConsole.state.collect { ssh ->
                _ui.update { it.copy(sshState = ssh) }
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
                    // All of these are file reads: never on Main.
                    val tick = withContext(Dispatchers.IO) {
                        val id = container.session.runningVmId()
                        LogTick(
                            log = container.session.readLog(id),
                            serial = container.session.readSerialTail(id),
                            vmId = id,
                            usedBytes = id?.let {
                                container.diskProvisioner.usedBytes(container.layout.diskFile(it))
                            },
                        )
                    }
                    _ui.update { current ->
                        val nextUsage = if (tick.vmId != null && tick.usedBytes != null) {
                            current.diskUsage + (tick.vmId to tick.usedBytes)
                        } else {
                            current.diskUsage
                        }
                        current.copy(
                            log = if (tick.log != current.log) tick.log else current.log,
                            serial = if (tick.serial != current.serial) tick.serial else current.serial,
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
