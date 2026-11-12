package dev.pocketvm.ui

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketvm.data.VmConfigRepository
import dev.pocketvm.diagnostics.RunManifestStore
import dev.pocketvm.runtime.NetworkMonitor
import dev.pocketvm.runtime.QemuEnvironment
import dev.pocketvm.runtime.VmSessionHost
import dev.pocketvm.storage.DiskProvisioner
import dev.pocketvm.storage.FirmwareManager
import dev.pocketvm.storage.VmStorageLayout
import javax.inject.Inject
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.diagnostics.SessionReport
import dev.pocketvm.storage.AppleAssets
import dev.pocketvm.storage.Qcow2Creator
import dev.pocketvm.runtime.AnsiText
import dev.pocketvm.runtime.BatteryOptimization
import dev.pocketvm.runtime.LaunchOutcome
import dev.pocketvm.runtime.SshConsole
import dev.pocketvm.runtime.SshDefaults
import dev.pocketvm.runtime.SshState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File



/** SSH 控制台保留的输出上限：超出后从头截断，防止长命令把 UiState 撑爆。 */
private const val SSH_OUTPUT_LIMIT = 128 * 1024



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
    val runtime: VmRuntimeState = VmRuntimeState.Idle,
    /** 所有 VM 的运行态（多机同跑）；列表/详情按 vmId 取用。 */
    val runtimes: Map<String, VmRuntimeState> = emptyMap(),
    /** 当前 UI 聚焦的 VM（控制台/诊断显示它的状态流）。 */
    val activeVmId: String? = null,
    val metricsByVm: Map<String, dev.pocketvm.diagnostics.VmMetrics?> = emptyMap(),
    val diagByVm: Map<String, dev.pocketvm.diagnostics.DiagSnapshot> = emptyMap(),
    val diagEventsByVm: Map<String, List<dev.pocketvm.diagnostics.DiagEvent>> = emptyMap(),
    val log: String = "",
    /**
     * Tail of the guest's serial console. QEMU stays silent on a healthy launch,
     * so this — not [log] — is what actually shows the firmware picking a boot
     * option, the installer asking for a keypress, or a Shell drop.
     */
    val serial: String = "",
    val argv: kotlin.collections.List<String> = emptyList(),
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
    /** vmId -> 启动报表（按 session 聚合全部 run manifest）；UI 只展示，不重算。 */
    val sessionReports: kotlin.collections.Map<String, SessionReport> = emptyMap(),
    /** Phone's Wi-Fi/USB-tether IPv4, shown as the VNC endpoint hint. */
    val localIp: String? = null,
    /** 本机是否有默认网络（ConnectivityManager 实时推，默认 true 免得首帧闪「未连接」）。 */
    val networkAvailable: Boolean = true,
    /** Latest performance sample of the running VM; null when nothing runs. */
    val metrics: dev.pocketvm.diagnostics.VmMetrics? = null,
    /** 诊断看板数据：T2 watchdog 的 diag / diagEvents 在这里映射为 UI 契约。 */
    val diagnostics: WatchdogUiState = WatchdogUiState(),
    /** 电池优化白名单状态；null 表示未知。false 时诊断页提示去设置「无限制」。 */
    val batteryWhitelisted: Boolean? = null,
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
    /** 最近一次按键完成的设备时间；UI 用它区分“本次点按”的反馈与旧状态。 */
    val lastGuestKeyAtEpochMs: Long = 0L,
    /** vmId -> iPhone 11 资源（固件 / NVMe 镜像）是否齐全。 */
    val appleAssets: Map<String, AppleAssets.Status> = emptyMap(),
    /** vmId -> 导入的 Android 磁盘镜像字节数（0 = 未导入）。 */
    val androidDisks: Map<String, Long> = emptyMap(),
    /** vmId -> 磁盘镜像格式（raw / qcow2）。 */
    val androidFormats: Map<String, String> = emptyMap(),
)

/**
 * 主 ViewModel（P3 起由 Hilt 注入）。
 *
 * 依赖全部走构造函数注入，不再从 AppContainer 里取；Application 只以
 * @ApplicationContext 的 Context 形式进来（原来 AndroidViewModel 的
 * getApplication() 全部改成 appContext——startActivity 用的 intent 自带
 * FLAG_ACTIVITY_NEW_TASK，Application Context 直接起没有问题）。
 * SshConsole 仍是每个 ViewModel 一份的内部会话对象，不进依赖图。
 */
@HiltViewModel
class VmViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessions: VmSessionHost,
    private val configRepository: VmConfigRepository,
    private val environment: QemuEnvironment,
    private val firmwareManager: FirmwareManager,
    private val layout: VmStorageLayout,
    private val diskProvisioner: DiskProvisioner,
    private val manifests: RunManifestStore,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** SSH 控制台：一条 VM 会话只挂一条 SSH 连接。 */
    private val sshConsole = SshConsole()


    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        probeEnvironment()
        refresh()
        collectRuntime()
        collectNetwork()
        tickLog()
    }

    // ---------- environment ----------

    fun probeEnvironment() {
        viewModelScope.launch {
            val (report, firmwarePaths) = withContext(Dispatchers.IO) {
                environment.resolve(layout) to
                    firmwareManager.available().map { f -> f.absolutePath }
            }
            val snapshot = EnvSnapshot(
                ready = report.ready,
                binaryPath = report.usable?.file?.absolutePath,
                version = report.usable?.version,
                origin = report.usable?.origin?.name,
                imageTool = report.imageBinary?.takeIf { it.executable }?.file?.absolutePath,
                dataDir = environment.dataDir(layout),
                nativeLibDir = appContext.applicationInfo.nativeLibraryDir,
                detail = report.abortReason ?: report.imageBinary?.diagnostic,
                staticExec = report.staticExecOk,
                soc = dev.pocketvm.runtime.DeviceInfo.socLabel(),
                pageSizeKb = dev.pocketvm.runtime.DeviceInfo.pageSizeKb(),
            )
            _ui.update {
                it.copy(
                    env = snapshot,
                    localIp = networkMonitor.state.value.localIpv4,
                    firmwareOptions = firmwarePaths,
                )
            }
        }
    }

    // ---------- 控制台准备（P1：导航归 NavController 管，这里只做业务准备） ----------

    /** 最近一次准备控制台的虚拟机；换了虚拟机才清 SSH 会话。 */
    private var lastConsoleVmId: String? = null

    /**
     * 准备进入某台虚拟机的控制台：换了虚拟机就清掉旧 SSH 会话（免得串台），
     * 读一份当前日志/串口尾部，刷新电池白名单状态。
     * 不再改 navigation 状态——去控制台是调用方用 navController.navigate 做的。
     */
    fun openConsole(vmId: String) {
        if (lastConsoleVmId != vmId) {
            // 换了虚拟机：旧 SSH 会话没有意义，清掉输出免得串台。
            viewModelScope.launch(Dispatchers.IO) { sshConsole.disconnect() }
            _ui.update { it.copy(sshOutput = "", sshRunning = false) }
            lastConsoleVmId = vmId
        }
        refreshBatteryState()
        _ui.update {
            it.copy(
                log = vmId?.let { sessions.readLog(it) } ?: "",
                serial = vmId?.let { sessions.readSerialTail(it) } ?: "",
                notice = null,
            )
        }
    }

    /** 切换 UI 聚焦的 VM（控制台/诊断按它展示状态流）。 */
    fun focus(vmId: String) {
        _ui.update { st ->
            st.copy(
                activeVmId = vmId,
                runtime = st.runtimes[vmId] ?: VmRuntimeState.Idle,
                metrics = st.metricsByVm[vmId],
                diagnostics = WatchdogUiState(
                    enabled = true,
                    snapshot = st.diagByVm[vmId],
                    events = st.diagEventsByVm[vmId] ?: emptyList(),
                ),
            )
        }
    }

    /** 刷新电池优化白名单状态；从设置页返回后下次打开控制台会重新读。 */
    fun refreshBatteryState() {
        viewModelScope.launch(Dispatchers.IO) {
            val whitelisted = BatteryOptimization.isWhitelisted(appContext)
            _ui.update { it.copy(batteryWhitelisted = whitelisted) }
        }
    }

    /** 打开系统电池优化白名单申请页；用户仍需在弹窗里点「允许」才生效。 */
    fun requestBatteryWhitelist() {
        runCatching { appContext.startActivity(BatteryOptimization.requestIntent(appContext)) }
        _ui.update { it.copy(notice = "已打开电池优化设置：请把 PocketVM 设为「无限制」") }
    }



    fun deleteVm(vmId: String) {
        // 只拦「这一台」还在运行/准备/停止；另一台在运行不该阻止删除 B。
        val runtime = _ui.value.runtime
        val busy = sessions.isRunning(vmId) ||
            (runtime as? VmRuntimeState.Preparing)?.vmId == vmId ||
            (runtime as? VmRuntimeState.Stopping)?.vmId == vmId
        if (busy) {
            _ui.update { it.copy(notice = "虚拟机正在运行或正在停止，无法删除") }
            return
        }
        // P2：删配置行 + 整台虚拟机目录走 Room 仓库（suspend，先判忙再动手）。
        viewModelScope.launch {
            configRepository.delete(vmId)
            refresh()
            _ui.update { it.copy(notice = "已删除虚拟机") }
        }
    }

    // ---------- lifecycle ----------

    fun start(vmId: String) {
        viewModelScope.launch {
            // P2：配置从 Room 读（suspend，仓库内部切 IO）；读不到就不启动。
            val vm = configRepository.load(vmId) ?: return@launch
            // 跳转到控制台由调用方用 navController.navigate 做（P1）：
            // 这里只清状态、起 QEMU；从列表/详情启动时调用方会同时导航到控制台。
            _ui.update {
                it.copy(
                    busy = true, notice = null, log = "",
                    sshOutput = "", sshRunning = false,
                )
            }
            withContext(Dispatchers.IO) { sshConsole.disconnect() }
            val outcome = sessions.start(vm)
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
                configRepository.save(persisted)
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
                        sessions.session(vmId).preflightNote?.let { append("；").append(it) }
                    }
                }
                is LaunchOutcome.Rejected -> outcome.report.errors.joinToString("\n") { it.message }
                is LaunchOutcome.Failed -> listOfNotNull(outcome.message, outcome.hint).joinToString("\n")
            }
            _ui.update {
                it.copy(
                    busy = false,
                    notice = message,
                    log = sessions.readLog(vmId),
                    serial = sessions.readSerialTail(vmId),
                    argv = vmId?.let { id -> sessions.currentRun(id)?.argv } ?: it.argv,
                )
            }
        }
    }

    fun stop(vmId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = "正在请求关机…") }
            sessions.stop(vmId)
            // 前台服务/唤醒锁由 VmSession 在进程真正退出后释放。
            val stillRunning = sessions.isRunning(vmId)
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (stillRunning) "已发送关机请求，等待 Windows 响应…" else "虚拟机已停止",
                    log = sessions.readLog(vmId),
                )
            }
        }
    }

    /**
     * 重读某台虚拟机的日志 / 串口尾部 / 本次启动参数。
     * P1：vmId 由调用方（控制台页）传进来，不再从已删除的 Screen 里取。
     */
    fun refreshLog(vmId: String?) {
        _ui.update {
            it.copy(
                log = vmId?.let { sessions.readLog(it) } ?: "",
                serial = vmId?.let { sessions.readSerialTail(it) } ?: "",
                argv = vmId?.let { id -> sessions.currentRun(id)?.argv } ?: it.argv,
            )
        }
    }

    // ---------- 草稿 ViewModel 的回调入口（CreateVmViewModel 用） ----------

    /** 重新从磁盘读虚拟机列表与状态（草稿保存 / 导入取消后调用）。 */
    fun refreshVms() {
        refresh()
    }

    /** 发一条通知（进 snackbar；只保留最新一条，展示后由 PocketVmRoot 清掉）。 */
    fun postNotice(message: String) {
        _ui.update { it.copy(notice = message) }
    }

    /** 设置当前导入进度（null = 没有导入在进行）；列表页的导入卡与向导共用。 */
    fun setImporting(progress: ImportProgress?) {
        _ui.update { it.copy(importing = progress) }
    }

    /** ISO 导入完成：把文件加入镜像库（向导「使用已导入镜像」下拉的数据源）。 */
    fun addImportedImage(path: String) {
        _ui.update { it.copy(imageOptions = (it.imageOptions + path).distinct().sorted()) }
    }

    /** 启动时把磁盘上已有的 ISO 一次性并进镜像库。 */
    fun addImportedImages(paths: List<String>) {
        _ui.update { it.copy(imageOptions = (it.imageOptions + paths).distinct().sorted()) }
    }

    /** iOS 还原包导入 / 校验完成：更新该台虚拟机的资源状态（列表卡与向导共用）。 */
    fun setAppleStatus(vmId: String, status: AppleAssets.Status) {
        _ui.update { it.copy(appleAssets = it.appleAssets + (vmId to status)) }
    }

    /** Android 磁盘镜像大小变化（导入 / 校验时更新；格式不变）。 */
    fun setAndroidDiskSize(vmId: String, size: Long) {
        _ui.update { it.copy(androidDisks = it.androidDisks + (vmId to size)) }
    }

    /** Android 磁盘镜像导入完成：同时更新大小与格式（qcow2 / raw）。 */
    fun setAndroidDisk(vmId: String, size: Long, format: String) {
        _ui.update {
            it.copy(
                androidDisks = it.androidDisks + (vmId to size),
                androidFormats = it.androidFormats + (vmId to format),
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
            // P2：诊断包里的 config.json 写 Room 里的当前配置，不写磁盘旧文件。
            val config = configRepository.load(vmId)
            val result = withContext(Dispatchers.IO) { sessions.exportDiagnostics(vmId, config) }
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
            val running = sessions.isRunning(vmId)
            if (!running) {
                _ui.update { it.copy(notice = "虚拟机未运行") }
                return@launch
            }
            _ui.update { it.copy(notice = "正在向 UEFI Shell 输入引导命令…") }
            val ok = withContext(Dispatchers.IO) { sessions.session(vmId).bootFromShell("fs0") }
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
        if (!sessions.isRunning(vmId)) {
            _ui.update {
                it.copy(
                    lastGuestKeyLabel = label,
                    lastGuestKeyOk = false,
                    lastGuestKeyAtEpochMs = System.currentTimeMillis(),
                )
            }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { sessions.session(vmId).sendKey(qcode) }
            _ui.update {
                it.copy(
                    lastGuestKeyLabel = label,
                    lastGuestKeyOk = ok,
                    lastGuestKeyAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    /** 组合键，例如 Ctrl+Alt+Del。 */
    fun sendGuestCombo(vmId: String, qcodes: List<String>) {
        if (!sessions.isRunning(vmId)) return
        viewModelScope.launch(Dispatchers.IO) { sessions.session(vmId).sendKeys(qcodes) }
    }

    /** 把一段文本打给客户机（US 布局子集），末尾由调用方决定是否带回车。 */
    fun typeGuestText(vmId: String, text: String) {
        val payload = text.take(200)
        if (payload.isEmpty()) return
        if (!sessions.isRunning(vmId)) {
            _ui.update { it.copy(notice = "这台虚拟机没有在运行") }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { sessions.session(vmId).typeText(payload) }
            if (!ok) {
                _ui.update { it.copy(notice = "文本发送失败：只支持 US 布局的字母/数字/常用符号") }
            }
        }
    }

    // ---------- media ----------






    /**
     * Once Windows is installed the ISO is only a boot-order obstacle. Eject it
     * and, if the VM is running, restart straight from the virtual disk — this
     * is the one step every user needs after setup and used to require rebuilding
     * the VM from scratch.
     */
    fun ejectIso(vmId: String) {
        viewModelScope.launch {
            val vm = configRepository.load(vmId) ?: return@launch
            if (vm.isoPath == null) return@launch
            val updated = vm.copy(isoPath = null, bootFromCd = false)
            configRepository.save(updated)
            refresh()

            if (!sessions.isRunning(vmId)) {
                _ui.update { it.copy(notice = "已卸载安装介质，下次启动将从虚拟磁盘引导") }
                return@launch
            }

            _ui.update { it.copy(busy = true, notice = "已卸载安装介质，正在从虚拟磁盘重启…") }
            sessions.stop(vmId)
            val started = sessions.start(updated) is LaunchOutcome.Started
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (started) "已卸载介质，正在从虚拟磁盘启动" else "介质已卸载，但重启失败，请手动启动",
                    log = sessions.readLog(vmId),
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
                val vms = configRepository.loadAll()
                val usage = vms.associate { vm ->
                    vm.id to diskProvisioner.usedBytes(layout.diskFile(vm.id))
                }
                Triple(
                    vms,
                    usage,
                    firmwareManager.available().map { f -> f.absolutePath },
                )
            }
            val images = withContext(Dispatchers.IO) {
                layout.imagesDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".iso", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.map { it.absolutePath }
                    ?: emptyList()
            }
            val lastRuns = withContext(Dispatchers.IO) {
                data.first.mapNotNull { vm -> manifests.latest(vm.id)?.let { vm.id to it } }.toMap()
            }
            val sessionReports = withContext(Dispatchers.IO) {
                data.first.associate { vm ->
                    vm.id to SessionReport.build(manifests.listAll(vm.id))
                }
            }
            val appleAssets = withContext(Dispatchers.IO) {
                data.first.filter { it.platform == VmPlatform.APPLE_T8030 }
                    .associate { vm -> vm.id to AppleAssets.status(layout, vm.id) }
            }
            val androidDisks = withContext(Dispatchers.IO) {
                data.first.filter { it.platform == VmPlatform.ANDROID_ARM64 }
                    .associate { vm -> vm.id to layout.diskFile(vm.id).length() }
            }
            val androidFormats = withContext(Dispatchers.IO) {
                data.first.filter { it.platform == VmPlatform.ANDROID_ARM64 }
                    .associate { vm ->
                        val file = layout.diskFile(vm.id)
                        vm.id to if (Qcow2Creator.isQcow2(file)) "qcow2" else "raw"
                    }
            }
            _ui.update {
                it.copy(
                    vms = data.first,
                    diskUsage = data.second,
                    firmwareOptions = data.third,
                    imageOptions = images,
                    lastRuns = lastRuns,
                    sessionReports = sessionReports,
                    appleAssets = appleAssets,
                    androidDisks = androidDisks,
                    androidFormats = androidFormats,
                    deviceMemoryMb = deviceMemoryMb(),
                    freeSpaceMb = layout.freeSpaceBytes() / (1024 * 1024),
                )
            }
        }
    }


    /**
     * P2：订阅 ConnectivityManager 的网络状态（NetworkMonitor 跟 App 走，
     * 这里只合流进 UiState）；网络一变就更新局域网地址与连接状态，
     * VNC 连接信息页直接读，不用手动刷新。
     */
    private fun collectNetwork() {
        viewModelScope.launch {
            networkMonitor.state.collect { net ->
                _ui.update { it.copy(localIp = net.localIpv4, networkAvailable = net.connected) }
            }
        }
    }

    private fun collectRuntime() {
        viewModelScope.launch {
            // 运行结束时清单会被重写（outcome / exitCode / finishedAt），
            // 「最近一次运行」与启动报表必须重读；只在聚焦的 VM 离开运行态时
            // refresh，避免每个状态帧都做磁盘 IO。
            var wasLive = false
            sessions.states.collect { map ->
                _ui.update { st ->
                    val active = st.activeVmId
                        ?: map.entries.firstOrNull { it.value is VmRuntimeState.Running }?.key
                    st.copy(
                        runtimes = map,
                        activeVmId = active,
                        runtime = active?.let { map[it] } ?: VmRuntimeState.Idle,
                    )
                }
                val state = _ui.value.runtime
                // Keep the wakelock through the whole shutdown: dropping it while
                // Stopping is in flight is exactly when Android freezes the app.
                val keepAlive = state is VmRuntimeState.Running ||
                    state is VmRuntimeState.Preparing ||
                    state is VmRuntimeState.Stopping
                val terminal = state is VmRuntimeState.Exited || state is VmRuntimeState.Failed
                if (wasLive && terminal) refresh()
                wasLive = keepAlive
                if (!keepAlive) {
                    // 前台服务由各 VmSession 收尾（引用计数）；这里只处理 UI 侧的 SSH 状态。
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
            sessions.metrics.collect { map ->
                _ui.update { st ->
                    st.copy(metricsByVm = map, metrics = st.activeVmId?.let { id -> map[id] })
                }
            }
        }
        // T2 的看门狗快照与事件都已在内存里（每 5 秒整体替换），这里只做
        // 合流与 UI 契约映射；不解析 jsonl，也不在 UI 侧重算判定。
        viewModelScope.launch {
            combine(sessions.diag, sessions.diagEvents) { snapshots, events -> snapshots to events }
                .collect { (snapshots, eventsByVm) ->
                    _ui.update { st ->
                        st.copy(
                            diagByVm = snapshots,
                            diagEventsByVm = eventsByVm,
                            diagnostics = WatchdogUiState(
                                enabled = true,
                                snapshot = st.activeVmId?.let { id -> snapshots[id] },
                                events = st.activeVmId?.let { id -> eventsByVm[id] } ?: emptyList(),
                            ),
                        )
                    }
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
                        val id = _ui.value.activeVmId?.takeIf { sessions.isRunning(it) }
                        LogTick(
                            log = id?.let { sessions.readLog(it) } ?: "",
                            serial = id?.let { sessions.readSerialTail(it) } ?: "",
                            vmId = id,
                            usedBytes = id?.let {
                                diskProvisioner.usedBytes(layout.diskFile(it))
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
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem / (1024 * 1024)
    }


}
