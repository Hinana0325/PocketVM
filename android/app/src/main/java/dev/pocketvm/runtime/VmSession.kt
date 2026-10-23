package dev.pocketvm.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import dev.pocketvm.core.config.ConfigValidator
import dev.pocketvm.core.config.ValidationInput
import dev.pocketvm.core.config.ValidationReport
import dev.pocketvm.core.model.BootStage
import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.LaunchProfile
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.diagnostics.DiagnosticsExporter
import dev.pocketvm.diagnostics.LogTail
import dev.pocketvm.diagnostics.ProcessHealth
import dev.pocketvm.diagnostics.RunManifestStore
import dev.pocketvm.storage.CheckOutcome
import dev.pocketvm.storage.DiskProvisioner
import dev.pocketvm.storage.DiskResult
import dev.pocketvm.storage.IsoBootImage
import dev.pocketvm.storage.FirmwareManager
import dev.pocketvm.storage.FirmwareResult
import dev.pocketvm.storage.QemuAssets
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What Windows Setup prints before a CD boot, and the last moment the app can
 * still answer it: the loader gives up after ~5 s and falls through to the next
 * boot option. Matched against the serial stream, which may split it mid-word.
 */
private const val CD_BOOT_PROMPT = "Press any key to boot from CD"

sealed interface LaunchOutcome {
    /**
     * [profile] is the one that actually worked; [failed] lists earlier attempts
     * in the fallback ladder, so the UI can say "标准模式失败，已用兼容模式启动".
     */
    data class Started(
        val profile: LaunchProfile = LaunchProfile.STANDARD,
        val failed: List<String> = emptyList(),
        /** Port actually used; differs from the config when the preferred one was busy. */
        val vncPort: Int = 5900,
    ) : LaunchOutcome
    data class Rejected(val report: ValidationReport) : LaunchOutcome
    data class Failed(val message: String, val hint: String? = null) : LaunchOutcome
}

/**
 * Owns exactly one QEMU process.
 *
 * Ordering in [start] is the contract: probe → validate → prepare storage → write
 * manifest → fork → confirm over QMP. The manifest is written *before* the fork so
 * that a crash between fork and confirmation still leaves the exact argv on disk.
 */
class VmSession(
    context: Context,
    private val layout: VmStorageLayout,
    private val environment: QemuEnvironment,
    private val firmwareManager: FirmwareManager,
    private val diskProvisioner: DiskProvisioner,
    private val manifests: RunManifestStore,
    private val diagnostics: DiagnosticsExporter,
    private val validator: ConfigValidator = ConfigValidator(),
    private val commandBuilder: QemuCommandBuilder = QemuCommandBuilder(),
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VmRuntimeState>(VmRuntimeState.Idle)
    val state: StateFlow<VmRuntimeState> = _state.asStateFlow()

    private var qemuProcess: Process? = null
    private var monitorJob: Job? = null
    private var serialWatcherJob: Job? = null
    private var healthJob: Job? = null

    /** Last CPU-tick sample; the only way to tell "running" from "wedged". */
    private var lastHealth: ProcessHealth.Sample? = null

    /** Set when a health sample showed a live process burning no CPU. */
    @Volatile
    private var sawStall = false

    /**
     * True while the serial console is attached. The QMP keypress fallback below
     * consults it: two independent sources answering the same boot prompt is how
     * stray Enter keystrokes end up inside the booted installer.
     */
    private val serialWatcherLive = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Latest stage reported to the console. Monotonic on purpose — a burst of
     * console output after a Shell drop must not walk the UI back to "loading".
     */
    @Volatile
    private var bootStage = BootStage.FIRMWARE

    private var qmp: QmpClient? = null
    private var currentManifest: RunManifest? = null
    private var currentLog: File? = null

    /** Which VM the live process belongs to. The UI can open any VM's console. */
    @Volatile
    private var activeVmId: String? = null

    @Volatile
    private var stopRequested = false

    var environmentSummary: String? = null
        private set

    /** Outcome of the post-crash disk check performed by the last [start]. */
    var preflightNote: String? = null
        private set

    fun isRunning(): Boolean = qemuProcess?.isAlive == true

    /** vmId of the live VM, or null when nothing is running. */
    fun runningVmId(): String? = if (isRunning()) activeVmId else null

    fun currentRun(): RunManifest? = currentManifest

    /** Injects one keystroke into the guest (used for the boot prompt). */
    fun sendKey(qcode: String = "ret"): Boolean =
        runCatching { qmp?.sendKey(qcode) == true }.getOrDefault(false)

    /**
     * Types the UEFI Shell boot command into the guest. When the firmware drops
     * to the shell instead of booting the installer, this is the one line the
     * user would otherwise have to hunt-and-peck on a phone keyboard.
     *
     * Blocking (roughly 40 ms per character); call it off the main thread.
     */
    fun bootFromShell(shellFs: String = "fs0"): Boolean = runCatching {
        qmp?.typeText("$shellFs:\\EFI\\BOOT\\BOOTAA64.EFI\r") == true
    }.getOrDefault(false)

    suspend fun start(config: VmConfig): LaunchOutcome = mutex.withLock {
        if (isRunning()) return@withLock LaunchOutcome.Failed("已有虚拟机在运行，请先停止。")

        AgentFeed.attach(layout.agentFeedFile())
        AgentFeed.event(
            "session.start",
            "vm" to config.id,
            "name" to config.name,
            "mode" to config.launchMode,
            "cpu" to config.cpuCores,
            "memMb" to config.memoryMb,
            "diskGb" to config.diskSizeGb,
            "controller" to config.storageController,
            "bootFromCd" to config.bootFromCd,
            "iso" to config.isoPath,
            "vncPort" to config.vncPort,
            "vncAccess" to config.vncAccess,
        )

        stopRequested = false
        preflightNote = null
        bootStage = BootStage.FIRMWARE
        val notes = mutableListOf<String>()
        val probe = withContext(Dispatchers.IO) { environment.resolve(layout) }
        environmentSummary = buildString {
            appendLine("nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}")
            appendLine("systemBinary=${probe.systemBinary?.file?.absolutePath} version=${probe.systemBinary?.version}")
            appendLine("imageBinary=${probe.imageBinary?.file?.absolutePath} diagnostic=${probe.imageBinary?.diagnostic}")
            appendLine("staticExecProbe=${probe.staticExecOk}")
            appendLine("device=${DeviceInfo.vendor()} ${Build.MODEL} soc=${DeviceInfo.socLabel()} abi=${DeviceInfo.abi()}")
            appendLine("pageSizeKb=${DeviceInfo.pageSizeKb()} totalMemMb=${deviceMemoryMb()}")
            appendLine("launchMode=${config.launchMode}")
            probe.abortReason?.let { appendLine("abortReason=$it") }
        }

        AgentFeed.event(
            "probe",
            "binary" to probe.systemBinary?.file?.absolutePath,
            "version" to probe.systemBinary?.version,
            "origin" to probe.systemBinary?.origin,
            "executable" to probe.systemBinary?.executable,
            "qemuImg" to probe.imageBinary?.file?.absolutePath,
            "staticExec" to probe.staticExecOk,
            "ready" to probe.ready,
            "abortReason" to probe.abortReason,
        )

        val vmDir = layout.vmDir(config.id)
        val disk = layout.diskFile(config.id)
        val vars = layout.varsFile(config.id)
        val iso = config.isoPath?.let(::File)

        // 1. Validate before touching the filesystem.
        val validation = validator.validate(
            ValidationInput(
                config = config,
                firmwareCode = firmwareManager.resolve(config),
                firmwareVars = vars,
                isoFile = iso,
                diskParentWritable = vmDir.exists() && vmDir.canWrite(),
                deviceMemoryMb = deviceMemoryMb(),
                freeSpaceBytes = layout.freeSpaceBytes(),
                qemuAvailable = probe.ready,
                qemuDetail = probe.abortReason,
            ),
        )
        AgentFeed.event(
            "validation",
            "errors" to validation.errors.map { it.code },
            "warnings" to validation.warnings.map { it.code },
        )
        if (validation.hasErrors) {
            val first = validation.errors.first()
            _state.value = VmRuntimeState.Failed(config.id, first.message, probe.abortReason)
            AgentFeed.event(
                "session.rejected",
                "vm" to config.id,
                "code" to first.code,
                "message" to first.message,
            )
            return@withLock LaunchOutcome.Rejected(validation)
        }

        val binary = probe.usable
            ?: return@withLock LaunchOutcome.Failed("未找到可执行的 QEMU。", probe.abortReason)

        _state.value = VmRuntimeState.Preparing(config.id, "准备 UEFI 固件")
        val firmware = when (val result = withContext(Dispatchers.IO) { firmwareManager.prepare(config, vars) }) {
            is FirmwareResult.Failure -> return@withLock fail(config.id, result.message)
            is FirmwareResult.Ready -> {
                result.note?.let { notes += it }
                result.files
            }
        }
        AgentFeed.event(
            "firmware",
            "code" to firmware.codePath,
            "codeBytes" to File(firmware.codePath).length(),
            "vars" to firmware.varsPath,
            "varsBytes" to File(firmware.varsPath).length(),
        )

        _state.value = VmRuntimeState.Preparing(config.id, "准备虚拟磁盘")
        val diskReady = when (val result = withContext(Dispatchers.IO) { diskProvisioner.ensure(config, disk) }) {
            is DiskResult.Failure -> return@withLock fail(config.id, result.message)
            is DiskResult.Ready -> result
        }
        AgentFeed.event(
            "disk",
            "path" to diskReady.file.absolutePath,
            "format" to diskReady.format,
            "created" to diskReady.created,
            "usedBytes" to diskReady.file.length(),
        )

        // A previous session that died (killed by Android, crash, power loss) can
        // leave a qcow2 with leaked or inconsistent metadata. Booting on top of
        // that is how a repairable image becomes an unrecoverable one.
        val unclean = withContext(Dispatchers.IO) {
            !diskReady.created && lastRunWasUnclean(config.id)
        }
        if (unclean) {
            _state.value = VmRuntimeState.Preparing(config.id, "检查虚拟磁盘一致性")
            val check = withContext(Dispatchers.IO) { diskProvisioner.check(diskReady.file) }
            AgentFeed.event("disk.check", "outcome" to check.outcome, "detail" to check.detail)
            when (check.outcome) {
                CheckOutcome.CLEAN -> notes += "上次异常退出，磁盘检查通过"
                CheckOutcome.REPAIRED -> notes += "上次异常退出，已修复磁盘元数据：${check.detail.orEmpty()}"
                CheckOutcome.SKIPPED -> notes += "上次异常退出，${check.detail ?: "未做磁盘检查"}"
                CheckOutcome.BROKEN -> return@withLock fail(
                    config.id,
                    "虚拟磁盘一致性检查失败，已阻止启动以免进一步损坏。",
                    check.detail,
                )
            }
        }

        // QEMU needs its runtime data (keymaps + pc-bios ROMs) or VNC keystrokes
        // are dropped and virtio-net cannot initialise.
        val dataDir = withContext(Dispatchers.IO) { QemuAssets.ensureSharedData(appContext, layout) }
            ?.absolutePath
            ?: environment.dataDir(layout)
        val romPresent = dataDir?.let { File(it, "efi-virtio.rom").isFile } == true
        if (!romPresent) {
            notes += "缺少 efi-virtio.rom，本次启动已禁用网卡"
        }
        environmentSummary = (environmentSummary ?: "") +
            "qemuShare=$dataDir romPresent=$romPresent\n"

        // Control channel and file access are profile-independent: check once.
        val socket = layout.qmpSocket(config.id).also { it.delete() }

        // Serial console (firmware + guest output). Only attached when a CD is in
        // play, because it is what lets us auto-recover from a UEFI Shell drop.
        val serialSocketPath = if (config.bootFromCd && iso != null) {
            layout.serialSocket(config.id).also { it.delete() }.absolutePath
        } else {
            null
        }

        // Emulated storage is FUSE, which cannot host unix sockets. If the path
        // is wrong, QEMU exits 1 with a bare "Failed to bind socket: Permission
        // denied" that looks like an exec-permission problem. Catch it here.
        val socketProblem = withContext(Dispatchers.IO) { probeUnixSocket(socket) }
        if (socketProblem != null) {
            return@withLock fail(
                config.id,
                "无法创建 QMP 控制套接字：${socket.absolutePath}",
                socketProblem,
            )
        }

        // Open every file QEMU is about to open. Without this, a permission
        // problem on emulated storage surfaces as QEMU "exit=1" with a message
        // that looks like an execution-layer bug.
        val fileProblem = withContext(Dispatchers.IO) {
            when {
                !diskReady.file.isFile ->
                    "虚拟磁盘不存在：${diskReady.file.absolutePath}"
                else -> writableProblem(diskReady.file, "虚拟磁盘")
                    ?: writableProblem(vars, "UEFI 变量存储")
                    ?: iso?.let { file ->
                        if (!file.isFile) "ISO 不存在：${file.absolutePath}"
                        else readableProblem(file, "ISO")
                    }
                    ?: readableProblem(File(firmware.codePath), "UEFI 固件")
            }
        }
        if (fileProblem != null) {
            return@withLock fail(config.id, fileProblem, "路径位于外部存储时，请确认应用仍有存储访问权限。")
        }

        // A busy VNC port fails every profile identically, which looks exactly
        // like "the emulator cannot start". Pick a free one and carry it through
        // the whole launch so the manifest and UI tell the truth.
        val effectivePort = withContext(Dispatchers.IO) {
            pickVncPort(config.vncPort, allInterfaces = config.vncAccess == VncAccess.LAN)
        }
        if (effectivePort != config.vncPort) {
            notes += "VNC 端口 ${config.vncPort} 被占用，本次改用 $effectivePort"
        }

        // Extract the El Torito EFI boot image and attach it as its own disk:
        // firmware then boots a plain FAT image instead of having to read the
        // whole ISO (which some edk2 builds over USB mass storage cannot do).
        //
        // This is also the one variable that differs from the build that reached
        // the firmware prompt, so it is allowed to be dropped at runtime: if an
        // attempt dies with it attached, the same profile is retried without it.
        var bootImage = if (config.bootFromCd && iso != null) {
            withContext(Dispatchers.IO) { IsoBootImage.ensure(iso, layout.imagesDir) }
        } else {
            null
        }

        // Fallback ladder: 标准 → 兼容 → 最小 (or a single profile when the user
        // pinned one). Each attempt gets its own log and manifest, so a failed
        // profile does not erase the evidence of why it failed.
        val ladder = LaunchProfile.ladder(config.launchMode)
        val failedAttempts = mutableListOf<String>()
        var lastHint: String? = null
        var representativeDetail: String? = null
        var bootImageDropped = false
        var configFatal = false
        // CD transport is a boot-or-not decision (see CdTransport), so it gets its
        // own fallback rung: AUTO starts on USB and drops to virtio-scsi if the
        // USB stack kills QEMU.
        var cdTransport = when (config.cdTransport) {
            CdTransport.AUTO -> CdTransport.USB
            else -> config.cdTransport
        }
        var cdTransportDowngraded = false

        var index = 0
        while (index < ladder.size) {
            val profile = ladder[index]
            _state.value = VmRuntimeState.Preparing(
                config.id,
                when {
                    bootImageDropped -> "重试${profile.label}（不带引导镜像）"
                    index == 0 -> "启动 QEMU（${profile.label}）"
                    else -> "尝试${profile.label}模式"
                },
            )

            val runId = UUID.randomUUID().toString().take(8)
            val logFile = layout.logFile(config.id, runId).also { it.parentFile?.mkdirs() }
            socket.delete()
            layout.serialSocket(config.id).delete()
            currentLog = logFile
            // Serial is only useful when a CD is actually attached — the no-media
            // rung has nothing for the shell recovery to boot. 0.9.2 computed this
            // path once, before the ladder, and ignored dropMedia.
            val attemptSerial = serialSocketPath?.takeUnless { profile.dropMedia }

            val argv = commandBuilder.build(
                LaunchRequest(
                    executable = binary.file.absolutePath,
                    firmware = firmware,
                    diskPath = diskReady.file.absolutePath,
                    diskFormat = diskReady.format,
                    isoPath = iso?.absolutePath,
                    cpuCores = config.cpuCores,
                    memoryMb = config.memoryMb,
                    vncPort = effectivePort,
                    vncBindAddress = if (config.vncAccess == VncAccess.LAN) "0.0.0.0" else "127.0.0.1",
                    vncPasswordProtected = !config.vncPassword.isNullOrBlank(),
                    qmpSocketPath = socket.absolutePath,
                    bootFromCd = config.bootFromCd,
                    vmName = config.name,
                    storageController = config.storageController,
                    cdTransport = cdTransport,
                    profile = profile,
                    bootImagePath = bootImage?.absolutePath,
                    // A missing option ROM must not block the VM itself.
                    enableNet = if (romPresent) null else false,
                    dataDir = dataDir,
                    serialSocketPath = attemptSerial,
                    extraArgs = config.extraArgs,
                ),
            )

            val manifest = RunManifest(
                runId = runId,
                vmId = config.id,
                vmName = config.name,
                appVersion = appVersion(),
                startedAtEpochMs = System.currentTimeMillis(),
                executablePath = binary.file.absolutePath,
                executableVersion = binary.version,
                argv = argv,
                firmwareCodePath = firmware.codePath,
                firmwareVarsPath = firmware.varsPath,
                diskPath = diskReady.file.absolutePath,
                diskFormat = diskReady.format,
                isoPath = iso?.absolutePath,
                vncPort = effectivePort,
                vncBindAddress = if (config.vncAccess == VncAccess.LAN) "0.0.0.0" else "127.0.0.1",
                vncPasswordProtected = !config.vncPassword.isNullOrBlank(),
                qmpSocketPath = socket.absolutePath,
                logPath = logFile.absolutePath,
                profile = profile.name,
                attempt = index + 1,
            )
            currentManifest = manifest
            manifests.write(manifest)
            AgentFeed.event(
                "attempt.start",
                "run" to runId,
                "profile" to profile.name,
                "attempt" to index + 1,
                "bootImage" to (bootImage != null),
                "serial" to (attemptSerial != null),
                "media" to (iso != null && !profile.dropMedia),
                // The full argv, because on a device the only other way to get it
                // is reading /proc/<pid>/cmdline — and by then the process that
                // had it is usually already gone.
                "argv" to argv.joinToString(" "),
            )

            val launched = try {
                ProcessBuilder(argv)
                    .directory(vmDir)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .apply { environment().putAll(environment.childEnvironment(layout)) }
                    .start()
            } catch (t: Throwable) {
                manifests.write(
                    manifest.copy(
                        finishedAtEpochMs = System.currentTimeMillis(),
                        outcome = RunManifest.Outcome.LAUNCH_FAILED,
                        failureHint = t.message,
                    ),
                )
                // Exec failure (EACCES, ENOEXEC) will not be fixed by a smaller
                // tb-size; stop the ladder instead of retrying it three times.
                return@withLock fail(config.id, "无法启动 QEMU：${t.message}", t.message)
            }

            qemuProcess = launched
            activeVmId = config.id

            // "Process alive" is not proof of a working VM: QEMU can abort during
            // machine init, and it also takes a moment to die. connect() gives up
            // as soon as the child is gone instead of waiting out the timeout.
            val client = QmpClient(socket.absolutePath)
            val confirmed = withContext(Dispatchers.IO) {
                client.connect(totalWaitMs = 20_000) { launched.isAlive }
            }
            // The QMP greeting is sent while chardevs initialise, *before* machine
            // init finishes. A bad device or pflash image can therefore survive
            // the handshake and die a moment later — requiring a short stability
            // window before this profile is declared good.
            var healthy = confirmed && launched.isAlive
            if (healthy) {
                healthy = withContext(Dispatchers.IO) {
                    !launched.waitFor(2_500, TimeUnit.MILLISECONDS) && launched.isAlive
                }
            }
            AgentFeed.event(
                "attempt.qmp",
                "run" to runId,
                "confirmed" to confirmed,
                "alive" to launched.isAlive,
                "healthy" to healthy,
            )
            if (healthy) {
                // Arm the password before announcing "running": with password=on
                // and no key set every client is rejected, so the window must be
                // as short as possible and is never observable by the UI.
                config.vncPassword?.takeIf { it.isNotBlank() }?.let { password ->
                    withContext(Dispatchers.IO) { client.setVncPassword(password) }
                }

                qmp = client
                manifests.write(manifest.copy(outcome = RunManifest.Outcome.RUNNING))
                _state.value = VmRuntimeState.Running(
                    vmId = config.id,
                    pid = pidOf(launched),
                    startedAtEpochMs = System.currentTimeMillis(),
                    vncPort = effectivePort,
                    logPath = logFile.absolutePath,
                )
                AgentFeed.event(
                    "attempt.running",
                    "run" to runId,
                    "profile" to profile.name,
                    "pid" to pidOf(launched),
                    "vncPort" to effectivePort,
                    "log" to logFile.absolutePath,
                )
                monitorJob = scope.launch { awaitExit(launched, manifest, logFile) }
                startHealthWatch(launched)
                // Watch the firmware serial console for a UEFI Shell drop and
                // recover automatically — the backup plan for devices whose edk2
                // will not auto-boot the CD.
                startSerialWatcher(launched, attemptSerial, layout.serialLogFile(config.id, runId), logFile)
                if (config.bootFromCd) {
                    // Fallback only: the serial watcher answers the same prompt
                    // the moment it sees it, and two independent sources typing
                    // Enter is exactly how stray keys end up inside the booted
                    // installer. This path exists for devices whose console never
                    // attached at all.
                    scope.launch {
                        delay(7_000)
                        if (serialWatcherLive.get()) return@launch
                        AgentFeed.event("boot.qmp_fallback", "reason" to "serial console 未接入")
                        runCatching { client.sendKey("ret") }
                        delay(5_000)
                        runCatching { client.sendKey("ret") }
                    }
                }
                if (bootImage != null) {
                    notes += "已用提取的引导镜像启动，固件不依赖 ISO9660"
                }
                preflightNote = notes.takeIf { it.isNotEmpty() }?.joinToString("；")
                return@withLock LaunchOutcome.Started(profile, failedAttempts.toList(), effectivePort)
            }

            // This profile failed. Keep the log, clean up, try the next one.
            val code = exitCodeOf(launched)
            runCatching { launched.destroyForcibly() }
            runCatching { serialWatcherJob?.cancel() }
            runCatching { healthJob?.cancel() }
            qemuProcess = null
            activeVmId = null
            client.close()
            socket.delete()
            val logText = LogTail.read(logFile, maxBytes = 16 * 1024, maxLines = 200)
            val hint = FailureHints.fromLog(logText, code)
            // The single most useful thing in the whole failure path: QEMU's own
            // first error line ("-drive ...: Could not open ..." / "Failed to
            // bind ..."). Without it every diagnosis is guesswork.
            val detail = firstErrorLine(logText)
            manifests.write(
                manifest.copy(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    exitCode = code,
                    outcome = RunManifest.Outcome.LAUNCH_FAILED,
                    failureHint = hint,
                    logExcerpt = detail,
                ),
            )
            AgentFeed.event(
                "attempt.failed",
                "run" to runId,
                "profile" to profile.name,
                "attempt" to index + 1,
                "exitCode" to code,
                "signal" to signalName(code),
                // 0 means QEMU was killed before writing a single byte, which is
                // exactly why FailureHints finds nothing and failureHint stays
                // null in the manifest.
                "logBytes" to logFile.length(),
                "hint" to hint,
                "detail" to detail,
            )
            lastHint = hint
            if (detail != null) representativeDetail = detail
            failedAttempts += buildString {
                append(profile.label).append(" exit=").append(code)
                signalName(code)?.let { append("/").append(it) }
                if (bootImage != null) append("（带引导镜像）")
            }

            // A rejection from the command line — or from a path every profile shares —
            // reads identically under 标准/兼容/最小, so the rest of the ladder would
            // only repeat it. One device run turned a single VNC-password rejection
            // into five identical attempts whose summary named none of them.
            if (FailureHints.isConfigFatal(logText)) {
                configFatal = true
                AgentFeed.event(
                    "ladder.aborted",
                    "run" to runId,
                    "profile" to profile.name,
                    "detail" to detail,
                )
                break
            }

            // Boot image attached and the attempt still died: drop that variable
            // and retry the *same* profile before spending the rest of the ladder.
            // exit >= 128 means the process was killed by a signal (132 = SIGILL,
            // 139 = SIGSEGV) - a configuration-independent crash that no smaller
            // tb-size will fix.
            if (bootImage != null) {
                bootImage = null
                bootImageDropped = true
                notes += "引导镜像挂载失败（exit=$code），本次改为从光盘引导"
                continue
            }

            // The CD sat on USB and QEMU was killed by a signal: that is the
            // SIGILL the USB mass-storage chain used to produce on this device.
            // Retry the same profile with the CD on virtio-scsi + AHCI — it does
            // not boot Windows, but it proves the box runs and keeps a usable
            // screen, which beats spending the whole ladder on one bad bus.
            if (cdTransport == CdTransport.USB && code >= 128) {
                cdTransport = CdTransport.VIRTIO_SCSI
                cdTransportDowngraded = true
                notes += "USB 光驱导致 QEMU 崩溃（exit=$code），本次改为 virtio-scsi + AHCI"
                continue
            }
            index += 1
        }

        // One representative line beats four identical paragraphs: the ladder is
        // only useful if the reader can see *why* every rung failed.
        val summary = buildString {
            if (failedAttempts.size == 1) {
                append("启动失败（").append(failedAttempts.first()).append("）")
            } else {
                append("所有启动模式都失败（").append(failedAttempts.joinToString(" / ")).append("）")
            }
            append("\nQEMU：").append(representativeDetail ?: "没有任何输出")
            if (configFatal) {
                append("\n（QEMU 拒绝的是启动参数，与加速档位无关，已跳过其余档位）")
            }
            if (bootImageDropped) {
                append("\n（引导镜像已挂载时 QEMU 直接崩溃，本次已回退为光盘引导）")
            }
            if (cdTransportDowngraded) {
                append("\n（USB 光驱导致 QEMU 崩溃，已回退为 virtio-scsi + AHCI：" +
                    "能进固件但 WinPE 读不到 virtio-scsi 上的 install.wim，需改用 USB 或注入 vioscsi 驱动）")
            }
        }
        AgentFeed.event(
            "session.failed",
            "vm" to config.id,
            "attempts" to failedAttempts,
            "detail" to representativeDetail,
            "hint" to lastHint,
        )
        _state.value = VmRuntimeState.Failed(config.id, summary, lastHint ?: representativeDetail)
        return@withLock LaunchOutcome.Failed(summary, lastHint ?: representativeDetail)
    }

    /**
     * Graceful stop: ACPI power button first, `quit` only when the guest ignores
     * it. Killing the process while Windows writes to disk is how you get a
     * corrupted qcow2, so the forced path waits as long as it reasonably can.
     */
    suspend fun stop(vmId: String, graceMs: Long = 25_000) {
        // The mutex serializes with start(): calling stop while a launch is in
        // progress waits for it to settle instead of being silently swallowed.
        mutex.withLock {
            val active = qemuProcess ?: return@withLock
            val target = activeVmId
            if (vmId != target) {
                // The console of a non-running VM must not kill somebody else's.
                return@withLock
            }
            AgentFeed.event("stop.requested", "vm" to vmId)
            stopRequested = true
            _state.value = VmRuntimeState.Stopping(vmId)
            val client = qmp
            withContext(Dispatchers.IO) {
                client?.systemPowerdown()
                val deadline = System.currentTimeMillis() + graceMs
                var down = false
                while (System.currentTimeMillis() < deadline && active.isAlive) {
                    down = active.waitFor(500, TimeUnit.MILLISECONDS)
                    if (down) break
                }
                if (!down && active.isAlive) {
                    client?.quit()
                    down = active.waitFor(5, TimeUnit.SECONDS)
                }
                if (!down && active.isAlive) {
                    active.destroy()
                    down = active.waitFor(3, TimeUnit.SECONDS)
                }
                // Last resort: SIGKILL. Better a possibly-dirty qcow2 than an
                // immortal process that Android kills anyway (same corruption,
                // plus a stuck UI and a stale wake lock).
                if (!down && active.isAlive) {
                    active.destroyForcibly()
                    active.waitFor(3, TimeUnit.SECONDS)
                }
            }
            AgentFeed.event("stop.done", "vm" to vmId, "alive" to active.isAlive)
            // Settle the state transition before releasing the lock so a
            // start() right after stop() sees a clean slate.
            val monitor = monitorJob
            if (monitor != null) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) { monitor.join() }
            }
        }
    }

    fun readLog(maxLines: Int = 400): String = currentLog?.let { LogTail.read(it, maxLines = maxLines) } ?: ""

    /**
     * Tail of the guest's serial console for the current run.
     *
     * This is the only in-app view of what the guest is doing: the guest screen
     * goes to a VNC client, and QEMU itself prints nothing on a healthy launch.
     * The firmware trace — boot options, a Shell drop, the installer's prompt —
     * lands here and nowhere else, so the console screen shows it verbatim.
     */
    fun readSerialTail(maxLines: Int = 120): String {
        val manifest = currentManifest ?: return ""
        val file = layout.serialLogFile(manifest.vmId, manifest.runId)
        if (!file.exists()) return ""
        return LogTail.read(file, maxBytes = 64 * 1024, maxLines = maxLines)
    }

    /**
     * Publishes [stage] to the UI, never moving backwards.
     *
     * Called from the serial watcher and the health probe, i.e. from two
     * coroutines that can fire in any order; the monotonic check keeps the
     * reported stage meaningful when they interleave.
     */
    private fun publishStage(stage: BootStage) {
        if (stage.ordinal <= bootStage.ordinal) return
        bootStage = stage
        _state.update { current ->
            if (current is VmRuntimeState.Running) current.copy(stage = stage) else current
        }
        AgentFeed.event("boot.stage", "stage" to stage.name, "label" to stage.label)
    }

    /**
     * Watches the firmware serial console and auto-recovers from a UEFI Shell drop.
     *
     * On some Snapdragon devices edk2 lands in the UEFI Shell instead of booting
     * the CD. Rather than make the user hunt-and-peck [bootFromShell] on a phone
     * keyboard, we detect `Shell>` on the serial line and type the EFI boot
     * command ourselves — trying each filesystem the firmware is likely to have
     * mounted the CD or the extracted boot image on. Every byte is also written to
     * [serialLog] (always), so a still-failing launch ships a readable trace in
     * the diagnostics zip.
     *
     * The serial chardev is a Unix socket QEMU opens as a server; we connect as a
     * client and read raw bytes. Carriage returns are the shell's Enter key, so
     * `fs0:\EFI\BOOT\BOOTAA64.EFI\r` is exactly what a person would type.
     */
    private fun startSerialWatcher(
        process: Process,
        socketPath: String?,
        serialLog: File?,
        qemuLog: File?,
    ) {
        serialWatcherJob?.cancel()
        val path = socketPath ?: return
        val logFile = serialLog ?: return
        serialWatcherJob = scope.launch(Dispatchers.IO) {
            // The chardev may not be open the instant QMP reports ready: retry.
            val socket = runCatching {
                var s: LocalSocket? = null
                for (attempt in 0 until 20) {
                    if (!process.isAlive) return@runCatching null
                    s = try {
                        LocalSocket().apply {
                            connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                        }
                    } catch (t: Throwable) {
                        delay(500)
                        null
                    }
                    if (s != null) break
                }
                s
            }.getOrNull() ?: return@launch

            val input = socket.inputStream
            val output = socket.outputStream
            serialWatcherLive.set(true)
            AgentFeed.event("serial.connected", "path" to path)
            runCatching { socket.soTimeout = 1500 }
            val log = runCatching {
                logFile.bufferedWriter().also {
                    it.write("=== PocketVM serial console capture ===\n")
                    it.flush()
                }
            }.getOrNull()
            val serial = StringBuilder(8192)
            // Measured on the Xiaomi 14 Pro: this QEMU_EFI.fd has no AHCI driver,
            // so the install CD on ich9-ahci is invisible to the firmware and
            // `fs0:\EFI\BOOT\BOOTAA64.EFI` reports "not recognized". What the
            // Shell does offer is the extracted El Torito boot image on
            // virtio-blk, and that loader still needs the CD. The candidates stay
            // as a last resort for devices that do mount a filesystem here.
            val candidates = listOf("fs0", "fs1", "fs2", "fs3")
            // 0 = nothing typed yet, 1 = `map` sent, n>1 = candidate n-2 sent.
            var shellStep = 0
            var bootPromptSeen = false
            var lastCommandAt = 0L
            var lastCdKeyAt = 0L
            var totalBytes = 0L
            val bytes = ByteArray(256)
            var consecutiveReadFailures = 0
            try {
                while (process.isAlive) {
                    // Anything thrown by this read means only "no data yet".
                    // LocalSocket reports a read timeout as IOException("Try
                    // again") (EAGAIN), not as SocketTimeoutException, so catching
                    // only SocketTimeoutException let it escape the coroutine and
                    // killed the whole app. Bounded, so a permanently broken
                    // socket cannot spin here forever.
                    val n = try {
                        input.read(bytes)
                    } catch (t: Throwable) {
                        if (++consecutiveReadFailures > 200) break
                        delay(50)
                        continue
                    }
                    consecutiveReadFailures = 0
                    if (n < 0) break
                    val chunk = String(bytes, 0, n, Charsets.US_ASCII)
                    val firstBytes = totalBytes == 0L
                    totalBytes += n
                    serial.append(chunk)
                    log?.write(chunk)
                    log?.flush()
                    // Keep only the recent tail; "Shell>" survives well within it.
                    if (serial.length > 8192) serial.delete(0, serial.length - 8192)
                    // Anything at all on the console means firmware is executing,
                    // which is strictly more than "QEMU is up".
                    if (firstBytes) publishStage(BootStage.LOADING)

                    val now = System.currentTimeMillis()

                    // Windows 安装盘从 CD 引导前会先问 "Press any key to boot from
                    // CD or DVD"，5 秒内没人按键就放弃、回落下一个启动项 —— 手机上
                    // 用户根本来不及切到 VNC 去按，于是永远停在 UEFI Shell。串口输入
                    // 是能到达 ConIn 的（我们在 Shell 里打的命令确实被执行了），所以
                    // 这里替他回车。冷却 3 秒：一次提示只按一次，多按的键会漏进后面
                    // 已经接管控制台的客户机。
                    if (serial.contains(CD_BOOT_PROMPT) && now - lastCdKeyAt > 3_000) {
                        lastCdKeyAt = now
                        bootPromptSeen = true
                        publishStage(BootStage.BOOT_PROMPT)
                        AgentFeed.event("serial.boot_prompt", "path" to path, "bytes" to totalBytes)
                        sendSerial(output, "\r")
                        qemuLog?.let { appendLogMarker(it, "客户机等待“按任意键从 CD 启动”，已自动回车") }
                    }

                    // 兜底：固件没选中启动项、掉进 UEFI Shell 时，替用户敲一条 EFI
                    // 引导命令。一次只发一条，并等上一条的结论（Shell 报错也要几秒），
                    // 否则盲打出来的字符会掺进下一阶段的客户机 —— 实测就是这样把
                    // BOOTAA64.EFI 打成了 BOOTA.EFI。
                    if (!bootPromptSeen && shellStep <= candidates.size &&
                        serial.contains("Shell>") && now - lastCommandAt > 2_500
                    ) {
                        lastCommandAt = now
                        if (shellStep == 0) {
                            // List the volumes first so the diagnostics zip records
                            // which one actually holds \EFI\BOOT\BOOTAA64.EFI.
                            sendSerial(output, "map\r")
                        } else {
                            val fs = candidates[shellStep - 1]
                            publishStage(BootStage.SHELL_RESCUE)
                            AgentFeed.event("serial.shell", "path" to path, "typed" to fs)
                            sendSerial(output, "$fs:\\EFI\\BOOT\\BOOTAA64.EFI\r")
                            qemuLog?.let {
                                appendLogMarker(it, "检测到 UEFI Shell，已自动输入 $fs:\\EFI\\BOOT\\BOOTAA64.EFI")
                            }
                        }
                        shellStep++
                    }
                }
            } catch (t: Throwable) {
                // Nothing may escape this coroutine: an unhandled exception here is
                // a FATAL EXCEPTION that takes the app down with it.
                AgentFeed.event("serial.error", "path" to path, "error" to t.toString())
            } finally {
                serialWatcherLive.set(false)
                // 0 bytes means the guest never wrote a single character to the
                // console — the signature of a launch that died before firmware ran.
                AgentFeed.event(
                    "serial.closed",
                    "path" to path,
                    "bytes" to totalBytes,
                    "bootPrompt" to bootPromptSeen,
                    "shellCommands" to shellStep,
                )
                runCatching { log?.close() }
                runCatching { socket.close() }
            }
        }
    }

    /** Writes [text] to the guest's serial input as keystrokes. */
    private fun sendSerial(out: OutputStream, text: String) = runCatching {
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** Appends a one-line marker to the QEMU log so the live console shows recovery. */
    private fun appendLogMarker(logFile: File, line: String) = runCatching {
        File(logFile.absolutePath).appendText("[PocketVM] $line\n")
    }

    /**
     * Periodically reports process health to the debug channel.
     *
     * `isAlive` cannot tell "running" from "wedged": QEMU can block inside its
     * main loop with the process alive, the VNC socket still LISTENing and TCP
     * still completing handshakes — while the guest executes nothing and the
     * listener never accepts. Discovered on a real device only by hand-reading
     * `/proc/<pid>/stat`; the app reported 运行中 the whole time. The kernel's
     * CPU accounting is the one objective signal.
     *
     * Observation only: a stall is recorded, not acted on. Turning it into a UI
     * state or an automatic restart is a separate decision.
     */
    private fun startHealthWatch(process: Process) {
        healthJob?.cancel()
        val pid = pidOf(process)
        if (pid <= 0) return
        sawStall = false
        lastHealth = ProcessHealth.sample(pid)
        healthJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && process.isAlive) {
                    delay(HEALTH_INTERVAL_MS)
                val now = ProcessHealth.sample(pid) ?: run {
                // A null sample means /proc/<pid>/stat became unreadable (the process
                // is being reaped, or the ROM tightened read permissions). Say so
                // instead of letting the heartbeats simply stop.
                AgentFeed.event("health.probe_lost", "pid" to pid)
                return@launch
            }
                val stalled = ProcessHealth.stalled(lastHealth, now)
                if (stalled == true) sawStall = true
                AgentFeed.event(
                    "health",
                    "pid" to pid,
                    "alive" to process.isAlive,
                    "cpuTicks" to now.cpuTicks,
                    "dTicks" to (now.cpuTicks - (lastHealth?.cpuTicks ?: now.cpuTicks)),
                    "stalled" to stalled,
                    "qmpOpen" to (qmp != null),
                )
                lastHealth = now
                }
            } catch (t: Throwable) {
                // Same rule as the serial watcher: never let a monitoring failure
                // become a FATAL EXCEPTION.
                AgentFeed.event("health.error", "pid" to pid, "error" to t.toString())
            }
        }
    }

    fun exportDiagnostics(vmId: String): Result<File> = diagnostics.export(vmId, environmentSummary)

    private suspend fun awaitExit(process: Process, manifest: RunManifest, logFile: File) {
        val code = withContext(Dispatchers.IO) { process.waitFor() }
        // Identity check: a quick stop→start cycle may have installed a *new*
        // process and QMP client already. This coroutine only settles the run
        // it actually watched; the new session owns the shared fields now.
        val ownsSession = qemuProcess === process
        if (ownsSession) {
            runCatching { qmp?.close() }
            runCatching { serialWatcherJob?.cancel() }
            runCatching { healthJob?.cancel() }
            qmp = null
            qemuProcess = null
            activeVmId = null
        }
        val hint = FailureHints.fromLog(
            LogTail.read(logFile, maxBytes = 16 * 1024, maxLines = 200),
            code,
        )
        // The manifest is this run's own record (unique runId), so it is always
        // written; the shared runtime state is only touched when we still own it.
        val stoppedByUser = stopRequested
        manifests.write(
            manifest.copy(
                finishedAtEpochMs = System.currentTimeMillis(),
                exitCode = code,
                outcome = when {
                    stoppedByUser -> RunManifest.Outcome.STOPPED_BY_USER
                    code == 0 -> RunManifest.Outcome.EXITED_OK
                    else -> RunManifest.Outcome.EXITED_ERROR
                },
                failureHint = hint,
            ),
        )
        AgentFeed.event(
            "run.exit",
            "vm" to manifest.vmId,
            "run" to manifest.runId,
            "exitCode" to code,
            "signal" to signalName(code),
            "stoppedByUser" to stoppedByUser,
            "logBytes" to logFile.length(),
            "sawStall" to sawStall,
        )
        if (ownsSession) {
            _state.value = VmRuntimeState.Exited(
                vmId = manifest.vmId,
                exitCode = code,
                hint = if (stoppedByUser) "已由用户停止" else hint,
                logPath = logFile.absolutePath,
            )
        }
    }

    private fun readableProblem(file: File, label: String): String? = try {
        java.io.FileInputStream(file).use { it.read() }
        null
    } catch (t: Throwable) {
        "$label 不可读：${file.absolutePath}（${t.javaClass.simpleName}: ${t.message}）"
    }

    private fun writableProblem(file: File, label: String): String? = try {
        java.io.RandomAccessFile(file, "rw").use { }
        null
    } catch (t: Throwable) {
        "$label 不可写：${file.absolutePath}（${t.javaClass.simpleName}: ${t.message}）"
    }

    /**
     * First line QEMU actually complained about.
     *
     * The program name is the *executable's basename*, which here is
     * `libqemu-system-aarch64.so` — matching on "qemu-system-aarch64:" silently
     * failed for every error. Match on the generic `<prog>: ` shape instead, and
     * fall back to the last non-blank line.
     */
    private fun firstErrorLine(log: String): String? {
        val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val prefixed = lines.firstOrNull { line ->
            val colon = line.indexOf(": ")
            colon in 1..80 && (line.substring(0, colon).endsWith(".so") ||
                line.substring(0, colon).contains("qemu"))
        }
        return (prefixed ?: lines.last()).take(300)
    }

    /**
     * Probes whether [preferred] can be bound, then scans 5900-5999. A busy port
     * would fail *every* profile identically, which is indistinguishable from
     * "the emulator cannot start" — and the user has no way to know why.
     */
    private fun pickVncPort(preferred: Int, allInterfaces: Boolean): Int {
        fun free(port: Int): Boolean = try {
            val address = if (allInterfaces) null else java.net.InetAddress.getLoopbackAddress()
            java.net.ServerSocket(port, 1, address).close()
            true
        } catch (t: Throwable) {
            false
        }

        if (free(preferred)) return preferred
        for (port in preferred + 1..5999) if (free(port)) return port
        for (port in 5900 until preferred) if (free(port)) return port
        return preferred
    }

    /** @return null when a unix socket can be bound at [path], else the reason. */
    private fun probeUnixSocket(path: File): String? {
        var socket: android.net.LocalSocket? = null
        return try {
            path.parentFile?.mkdirs()
            path.delete()
            socket = android.net.LocalSocket().apply {
                // bind() is what creates the socket inode; on FUSE this is the
                // call that fails with EACCES. Listening is not required to prove
                // the path is usable, and LocalSocket has no public listen().
                bind(
                    android.net.LocalSocketAddress(
                        path.absolutePath,
                        android.net.LocalSocketAddress.Namespace.FILESYSTEM,
                    ),
                )
            }
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}（该路径所在文件系统不支持 Unix socket）"
        } finally {
            runCatching { socket?.close() }
            path.delete()
        }
    }

    /**
     * The last run's manifest is the only durable evidence of how it ended.
     * RUNNING/STARTING means the process vanished without [awaitExit] running
     * (app killed, device rebooted); EXITED_ERROR means QEMU died on its own.
     */
    private fun lastRunWasUnclean(vmId: String): Boolean = when (manifests.latest(vmId)?.outcome) {
        RunManifest.Outcome.STARTING,
        RunManifest.Outcome.RUNNING,
        RunManifest.Outcome.EXITED_ERROR,
        -> true
        else -> false
    }

    private fun fail(vmId: String, message: String, hint: String? = null): LaunchOutcome {
        _state.value = VmRuntimeState.Failed(vmId, message, hint)
        return LaunchOutcome.Failed(message, hint)
    }

    private fun deviceMemoryMb(): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem / (1024 * 1024)
    }

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /**
     * Child PID for display only. `Process.pid()` may be missing or hidden on
     * some ART versions, in which case the implementation field is used. A
     * failure here must never affect the launch, hence -1.
     */
    private fun pidOf(process: Process): Int {
        runCatching {
            val method = process.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
            if (method != null) return (method.invoke(process) as Number).toInt()
        }
        var type: Class<*>? = process.javaClass
        while (type != null) {
            runCatching {
                val field = type!!.getDeclaredField("pid").apply { isAccessible = true }
                return field.getInt(process)
            }
            type = type.superclass
        }
        return -1
    }

    private fun exitCodeOf(process: Process): Int = runCatching {
        if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue() else -1
    }.getOrDefault(-1)

    /**
     * A process killed by a signal reports 128 + signo. Printing the name turns
     * "exit=132" into "SIGILL", which is the difference between "a parameter was
     * rejected" and "the emulator executed an illegal instruction".
     */
    private fun signalName(code: Int): String? = when (code) {
        129 -> "SIGHUP"
        130 -> "SIGINT"
        131 -> "SIGQUIT"
        132 -> "SIGILL"
        133 -> "SIGTRAP"
        134 -> "SIGABRT"
        135 -> "SIGBUS"
        136 -> "SIGFPE"
        137 -> "SIGKILL"
        139 -> "SIGSEGV"
        141 -> "SIGPIPE"
        143 -> "SIGTERM"
        else -> null
    }

    private companion object {
        /** Must exceed the floor in [ProcessHealth.stalled] or no verdict is ever reached. */
        const val HEALTH_INTERVAL_MS = 10_000L
    }
}
