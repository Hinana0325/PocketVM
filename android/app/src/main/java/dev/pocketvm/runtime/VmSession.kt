package dev.pocketvm.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.pocketvm.core.config.ConfigValidator
import dev.pocketvm.core.config.ValidationInput
import dev.pocketvm.core.config.ValidationReport
import dev.pocketvm.core.model.LaunchProfile
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.diagnostics.DiagnosticsExporter
import dev.pocketvm.diagnostics.LogTail
import dev.pocketvm.diagnostics.RunManifestStore
import dev.pocketvm.storage.CheckOutcome
import dev.pocketvm.storage.DiskProvisioner
import dev.pocketvm.storage.DiskResult
import dev.pocketvm.storage.FirmwareManager
import dev.pocketvm.storage.FirmwareResult
import dev.pocketvm.storage.QemuAssets
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface LaunchOutcome {
    /**
     * [profile] is the one that actually worked; [failed] lists earlier attempts
     * in the fallback ladder, so the UI can say "标准模式失败，已用兼容模式启动".
     */
    data class Started(
        val profile: LaunchProfile = LaunchProfile.STANDARD,
        val failed: List<String> = emptyList(),
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

    suspend fun start(config: VmConfig): LaunchOutcome = mutex.withLock {
        if (isRunning()) return@withLock LaunchOutcome.Failed("已有虚拟机在运行，请先停止。")

        stopRequested = false
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
        if (validation.hasErrors) {
            val first = validation.errors.first()
            _state.value = VmRuntimeState.Failed(config.id, first.message, probe.abortReason)
            return@withLock LaunchOutcome.Rejected(validation)
        }

        val binary = probe.usable
            ?: return@withLock LaunchOutcome.Failed("未找到可执行的 QEMU。", probe.abortReason)

        _state.value = VmRuntimeState.Preparing(config.id, "准备 UEFI 固件")
        val firmware = when (val result = withContext(Dispatchers.IO) { firmwareManager.prepare(config, vars) }) {
            is FirmwareResult.Failure -> return@withLock fail(config.id, result.message)
            is FirmwareResult.Ready -> result.files
        }

        _state.value = VmRuntimeState.Preparing(config.id, "准备虚拟磁盘")
        val diskReady = when (val result = withContext(Dispatchers.IO) { diskProvisioner.ensure(config, disk) }) {
            is DiskResult.Failure -> return@withLock fail(config.id, result.message)
            is DiskResult.Ready -> result
        }

        // A previous session that died (killed by Android, crash, power loss) can
        // leave a qcow2 with leaked or inconsistent metadata. Booting on top of
        // that is how a repairable image becomes an unrecoverable one.
        preflightNote = null
        val unclean = withContext(Dispatchers.IO) {
            !diskReady.created && lastRunWasUnclean(config.id)
        }
        if (unclean) {
            _state.value = VmRuntimeState.Preparing(config.id, "检查虚拟磁盘一致性")
            val check = withContext(Dispatchers.IO) { diskProvisioner.check(diskReady.file) }
            when (check.outcome) {
                CheckOutcome.CLEAN -> preflightNote = "上次异常退出，磁盘检查通过"
                CheckOutcome.REPAIRED -> preflightNote = "上次异常退出，已修复磁盘元数据：${check.detail.orEmpty()}"
                CheckOutcome.SKIPPED -> preflightNote = "上次异常退出，${check.detail ?: "未做磁盘检查"}"
                CheckOutcome.BROKEN -> return@withLock fail(
                    config.id,
                    "虚拟磁盘一致性检查失败，已阻止启动以免进一步损坏。",
                    check.detail,
                )
            }
        }

        // QEMU needs its runtime data (keymaps) or VNC keystrokes are dropped.
        val dataDir = withContext(Dispatchers.IO) { QemuAssets.ensureSharedData(appContext, layout) }
            ?.absolutePath
            ?: environment.dataDir(layout)

        // Control channel and file access are profile-independent: check once.
        val socket = layout.qmpSocket(config.id).also { it.delete() }

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

        // Fallback ladder: 标准 → 兼容 → 最小 (or a single profile when the user
        // pinned one). Each attempt gets its own log and manifest, so a failed
        // profile does not erase the evidence of why it failed.
        val ladder = LaunchProfile.ladder(config.launchMode)
        val failedAttempts = mutableListOf<String>()
        var lastHint: String? = null

        for ((index, profile) in ladder.withIndex()) {
            _state.value = VmRuntimeState.Preparing(
                config.id,
                if (index == 0) "启动 QEMU（${profile.label}）" else "尝试${profile.label}模式",
            )

            val runId = UUID.randomUUID().toString().take(8)
            val logFile = layout.logFile(config.id, runId).also { it.parentFile?.mkdirs() }
            socket.delete()
            currentLog = logFile

            val argv = commandBuilder.build(
                LaunchRequest(
                    executable = binary.file.absolutePath,
                    firmware = firmware,
                    diskPath = diskReady.file.absolutePath,
                    diskFormat = diskReady.format,
                    isoPath = iso?.absolutePath,
                    cpuCores = config.cpuCores,
                    memoryMb = config.memoryMb,
                    vncPort = config.vncPort,
                    vncBindAddress = if (config.vncAccess == VncAccess.LAN) "0.0.0.0" else "127.0.0.1",
                    vncPasswordProtected = !config.vncPassword.isNullOrBlank(),
                    qmpSocketPath = socket.absolutePath,
                    bootFromCd = config.bootFromCd,
                    vmName = config.name,
                    storageController = config.storageController,
                    profile = profile,
                    dataDir = dataDir,
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
                vncPort = config.vncPort,
                vncBindAddress = if (config.vncAccess == VncAccess.LAN) "0.0.0.0" else "127.0.0.1",
                vncPasswordProtected = !config.vncPassword.isNullOrBlank(),
                qmpSocketPath = socket.absolutePath,
                logPath = logFile.absolutePath,
                profile = profile.name,
                attempt = index + 1,
            )
            currentManifest = manifest
            manifests.write(manifest)

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
                    vncPort = config.vncPort,
                    logPath = logFile.absolutePath,
                )
                monitorJob = scope.launch { awaitExit(launched, manifest, logFile) }
                return@withLock LaunchOutcome.Started(profile, failedAttempts.toList())
            }

            // This profile failed. Keep the log, clean up, try the next one.
            val code = exitCodeOf(launched)
            runCatching { launched.destroyForcibly() }
            qemuProcess = null
            activeVmId = null
            client.close()
            socket.delete()
            val hint = FailureHints.fromLog(
                LogTail.read(logFile, maxBytes = 16 * 1024, maxLines = 200),
                code,
            )
            manifests.write(
                manifest.copy(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    exitCode = code,
                    outcome = RunManifest.Outcome.LAUNCH_FAILED,
                    failureHint = hint,
                ),
            )
            lastHint = hint
            failedAttempts += "${profile.label}（exit=$code）"
        }

        val summary = if (failedAttempts.size == 1) {
            "QEMU 启动后立即退出（${failedAttempts.first()}）。"
        } else {
            "所有启动模式都失败：${failedAttempts.joinToString("；")}"
        }
        _state.value = VmRuntimeState.Failed(config.id, summary, lastHint)
        return@withLock LaunchOutcome.Failed(summary, lastHint)
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
            // Settle the state transition before releasing the lock so a
            // start() right after stop() sees a clean slate.
            val monitor = monitorJob
            if (monitor != null) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) { monitor.join() }
            }
        }
    }

    fun readLog(maxLines: Int = 400): String = currentLog?.let { LogTail.read(it, maxLines = maxLines) } ?: ""

    fun exportDiagnostics(vmId: String): Result<File> = diagnostics.export(vmId, environmentSummary)

    private suspend fun awaitExit(process: Process, manifest: RunManifest, logFile: File) {
        val code = withContext(Dispatchers.IO) { process.waitFor() }
        // Identity check: a quick stop→start cycle may have installed a *new*
        // process and QMP client already. This coroutine only settles the run
        // it actually watched; the new session owns the shared fields now.
        val ownsSession = qemuProcess === process
        if (ownsSession) {
            runCatching { qmp?.close() }
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

    private fun fail(vmId: String, message: String, hint: String? = null): LaunchOutcome {        _state.value = VmRuntimeState.Failed(vmId, message, hint)
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

    /** `Process.pid()` is API 26+ on Android but returns int, not long. */
    private fun pidOf(process: Process): Int = runCatching {
        val method = process.javaClass.getMethod("pid")
        (method.invoke(process) as Number).toInt()
    }.getOrDefault(-1)

    private fun exitCodeOf(process: Process): Int = runCatching {
        if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue() else -1
    }.getOrDefault(-1)
}
