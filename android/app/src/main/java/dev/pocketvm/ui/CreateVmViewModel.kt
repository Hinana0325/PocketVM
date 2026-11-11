package dev.pocketvm.ui

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketvm.core.config.ConfigValidator
import dev.pocketvm.core.config.Issue
import dev.pocketvm.core.config.Severity
import dev.pocketvm.core.config.ValidationInput
import dev.pocketvm.data.AppPreferences
import dev.pocketvm.data.VmConfigRepository
import dev.pocketvm.storage.FirmwareManager
import dev.pocketvm.storage.IsoImporter
import dev.pocketvm.storage.VmStorageLayout
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.storage.AppleAssets
import dev.pocketvm.storage.Qcow2Creator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 导入任务来源：只有 ISO / 驱动盘导入支持离页取消。 */
enum class ImportKind(val label: String) {
    INSTALL_ISO("安装镜像"),
    DRIVER_ISO("驱动盘镜像"),
    APPLE_BUNDLE("iOS 资源"),
    ANDROID_DISK("Android 磁盘镜像"),
}

data class ImportProgress(val copiedBytes: Long, val totalBytes: Long?, val kind: ImportKind) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (copiedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** 草稿编辑会话：新建向导与配置编辑共用，独立于 VmViewModel 的业务状态。 */
data class CreateDraftState(
    val draft: VmConfig? = null,
    /** True 表示正在编辑已有虚拟机（配置编辑器），false 表示新建向导。 */
    val editing: Boolean = false,
    /** 打开草稿时的快照，用于判断是否有未保存的修改。 */
    val baseline: VmConfig? = null,
    val issues: List<Issue> = emptyList(),
) {
    val hasUnsavedChanges: Boolean get() = draft != null && draft != baseline
    val errorCount: Int get() = issues.count { it.severity == Severity.ERROR }
}

/** 保存结果：保存成功的配置 + 校验错误数（调用方据此刷新列表、弹提示、跳转）。 */
data class SaveResult(val config: VmConfig, val errorCount: Int)

/**
 * 新建向导 / 配置编辑的草稿 ViewModel（P1）。
 *
 * 草稿（draft / editing / baseline / issues）从 VmViewModel.UiState 搬到这里，
 * 原因：
 * - 向导 5 步是 5 个 NavHost 目的地，切换步骤时草稿必须不丢；
 *   草稿放在独立的、Activity 级的 ViewModel 里，导航切换不会重置它。
 * - 配置编辑与新建向导共用同一份草稿逻辑，但两个编辑会话互斥：
 *   打开一个会替换另一个（startNew / openConfig 的恢复规则见下）。
 *
 * 设备与虚拟机状态（env、firmwareOptions、imageOptions、appleAssets、
 * androidDisks、androidFormats、deviceMemoryMb、freeSpaceMb）仍在
 * VmViewModel.UiState 里（列表页也要用），这里只通过 vm 读，不重复持有。
 * 导入完成时通过 vm 的 setImporting / addImportedImage / setAppleStatus /
 * setAndroidDisk 同步回 VmViewModel；提示走 vm.postNotice（统一进 snackbar）。
 *
 * QEMU 运行逻辑（VmSession、QemuCommandBuilder、看门狗、性能调度器）一律
 * 不碰；这里只读写 configRepository（Room）与 layout 下的导入目录。
 *
 * P3 起由 Hilt 辅助注入：Hilt 不能直接注入另一个 ViewModel（VmViewModel
 * 不在依赖图里），所以 vm 用 @Assisted，在调用处（PocketVmRoot）由
 * hiltViewModel 的 creationCallback 传进来；其余依赖（仓库、布局、导入器、
 * 校验器、偏好）全部由 Hilt 注入。手写的 ViewModelProvider.Factory 已删，
 * 由 Hilt 根据下面的 @AssistedFactory 接口生成实现。
 */
@HiltViewModel(assistedFactory = CreateVmViewModel.Factory::class)
class CreateVmViewModel @AssistedInject constructor(
    @ApplicationContext private val appContext: Context,
    @Assisted private val vm: VmViewModel,
    private val configRepository: VmConfigRepository,
    private val layout: VmStorageLayout,
    private val isoImporter: IsoImporter,
    private val firmwareManager: FirmwareManager,
    private val validator: ConfigValidator,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(vm: VmViewModel): CreateVmViewModel
    }

    /** 当前 ISO/驱动盘导入任务；用于离页保护与「取消导入」。 */
    private var importJob: Job? = null

    private val _draft = MutableStateFlow(CreateDraftState())
    val draft: StateFlow<CreateDraftState> = _draft.asStateFlow()

    init {
        // 镜像库（已导入的 ISO）是 devices 步「使用已导入镜像」下拉的数据源；
        // 草稿 ViewModel 自己读一份，列表页那份仍由 VmViewModel.refresh() 管。
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) {
                layout.imagesDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".iso", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.map { it.absolutePath }
                    ?: emptyList()
            }
            vm.addImportedImages(images)
        }
    }

    // ---------- 打开编辑会话 ----------

    /**
     * 新建向导入口。
     *
     * 保留草稿恢复：如果上次退出向导时选了「保留草稿」，草稿还留在内存里
     * （editing == false），这次直接恢复，不新建——否则保留就没意义。
     * 正在编辑已有虚拟机时（editing == true）一定新建，避免把配置草稿冲掉。
     */
    fun startNew() {
        val current = _draft.value
        if (current.draft != null && !current.editing) return
        viewModelScope.launch {
            val firmware = withContext(Dispatchers.IO) {
                firmwareManager.available().firstOrNull()?.absolutePath
            }
            // P2：新建默认值先读偏好（上一次新建时用的 CPU/内存/磁盘），
            // 没记过再用写死的默认值/建议内存。
            val defaults = appPreferences.newVmDefaults()
            val draft = VmConfig.create("Windows 11 ARM64").let { base ->
                base.copy(
                    cpuCores = defaults?.cpuCores ?: base.cpuCores,
                    memoryMb = defaults?.memoryMb ?: suggestedMemoryMb(),
                    diskSizeGb = defaults?.diskSizeGb ?: base.diskSizeGb,
                    firmwareCodePath = firmware,
                )
            }
            _draft.update {
                it.copy(draft = draft, editing = false, baseline = draft, issues = emptyList())
            }
            refreshIssues(draft)
        }
    }

    /**
     * 配置编辑入口。
     *
     * 同一台虚拟机的草稿已在编辑中时直接恢复（杀进程恢复到配置页也走这里）；
     * 否则从仓库重读（丢弃上一次未保存的修改——配置编辑没有保留草稿，
     * 退出时只有「放弃 / 继续编辑」）。
     *
     * P2：读配置是 suspend（Room），这里起协程异步读；草稿到之前页面显示
     * 空态（配置页的 draft == null 分支会再调一次 openConfig，是 no-op）。
     */
    fun openConfig(vmId: String) {
        val current = _draft.value
        if (current.draft?.id == vmId && current.editing) return
        viewModelScope.launch {
            val target = configRepository.load(vmId) ?: return@launch
            _draft.update {
                it.copy(draft = target, editing = true, baseline = target, issues = emptyList())
            }
            refreshIssues(target)
        }
    }

    /** 放弃草稿（向导退出时选「放弃」，或配置编辑退出时选「放弃修改」）。 */
    fun discardDraft() {
        _draft.update { it.copy(draft = null, editing = false, baseline = null, issues = emptyList()) }
    }

    // ---------- 草稿编辑 ----------

    fun updateDraft(transform: (VmConfig) -> VmConfig) {
        val current = _draft.value.draft ?: return
        val next = transform(current)
        _draft.update { it.copy(draft = next) }
        refreshIssues(next)
    }

    fun setPlatform(platform: VmPlatform) = updateDraft { draft ->
        if (draft.platform == platform) return@updateDraft draft
        val apple = platform == VmPlatform.APPLE_T8030
        val android = platform == VmPlatform.ANDROID_ARM64
        val defaultName = draft.name.isBlank() || draft.name == VmConfig.DEFAULT_NAME
        val name = when {
            apple && defaultName -> "iPhone 11"
            android && defaultName -> "Android"
            else -> draft.name
        }
        // t8030 在 TCG 下每多一个 vCPU/GB 都是实打实的宿主内存与线程开销；
        // 现有脚本用 7 vCPU / 6 GB，手机端先按 4 / 3 GB 起步，用户仍可再调。
        // Android 走 virtio-gpu（GKI 自带 DRM 驱动），并且总是从磁盘启动。
        draft.copy(
            platform = platform,
            name = name,
            cpuCores = if (apple) draft.cpuCores.coerceAtMost(4) else draft.cpuCores,
            memoryMb = when {
                apple -> draft.memoryMb.coerceAtMost(3072)
                android -> draft.memoryMb.coerceAtLeast(4096)
                else -> draft.memoryMb
            },
            displayDevice = if (android) DisplayDevice.VIRTIO_GPU else draft.displayDevice,
            bootFromCd = if (android) false else draft.bootFromCd,
            isoPath = if (android) null else draft.isoPath,
            driverIsoPath = if (android) null else draft.driverIsoPath,
        )
    }

    /**
     * 保存草稿到仓库并清空编辑会话。
     *
     * P2：写 Room 是 suspend，调用方（AppNavHost）起协程调，写完再刷新列表、
     * 弹提示、跳转。新建成功时把这次的 CPU/内存/磁盘记成下次新建的默认值
     *（偏好）；编辑已有虚拟机不改默认值。
     *
     * 不自己跳转、不自己弹提示：调用方（AppNavHost）拿返回的 SaveResult，
     * 再调 vm.refreshVms()、vm.postNotice(...)、navController  popBackStack。
     */
    suspend fun saveDraft(): SaveResult? {
        val draft = _draft.value.draft ?: return null
        configRepository.save(draft)
        if (!_draft.value.editing) {
            appPreferences.setNewVmDefaults(draft.cpuCores, draft.memoryMb, draft.diskSizeGb)
        }
        val errors = _draft.value.issues.count { it.severity == Severity.ERROR }
        _draft.update { it.copy(draft = null, editing = false, baseline = null, issues = emptyList()) }
        return SaveResult(draft, errors)
    }

    // ---------- 校验 ----------

    fun refreshIssues(config: VmConfig) {
        if (config.platform == VmPlatform.ANDROID_ARM64) {
            viewModelScope.launch {
                val size = withContext(Dispatchers.IO) {
                    layout.diskFile(config.id).takeIf { it.isFile }?.length() ?: 0L
                }
                val issues = buildList {
                    if (size == 0L) {
                        add(
                            Issue(
                                Severity.ERROR,
                                "ANDROID_DISK_MISSING",
                                "还没有导入 Android 磁盘镜像。请点「导入磁盘镜像」选择 .img/.qcow2；" +
                                    "镜像需要是可 UEFI 启动的 arm64 系统盘。",
                            ),
                        )
                    } else if (size < 1024L * 1024 * 1024) {
                        add(
                            Issue(
                                Severity.WARNING,
                                "ANDROID_DISK_SMALL",
                                "镜像只有 ${size / (1024 * 1024)} MB，可能不是完整的 Android 系统盘。",
                            ),
                        )
                    }
                    if (config.vncAccess == VncAccess.LAN) {
                        add(
                            Issue(
                                Severity.ERROR,
                                "VNC_LAN_UNAVAILABLE",
                                "P1 不支持局域网 VNC，已下线，请用仅本机 + adb forward。" +
                                    "这台虚拟机保存的是旧版「局域网」配置，请改回「仅本机」后再启动。",
                            ),
                        )
                    }
                }
                if (_draft.value.draft == config || _draft.value.draft == null) {
                    _draft.update { it.copy(issues = issues) }
                    vm.setAndroidDiskSize(config.id, size)
                }
            }
            return
        }
        // iOS 机型的校验和 UEFI 完全不同：没有固件/ISO/qcow2，只有「资源是否导入」。
        if (config.platform == VmPlatform.APPLE_T8030) {
            viewModelScope.launch {
                val status = withContext(Dispatchers.IO) {
                    AppleAssets.status(layout, config.id)
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
                                "P1 不支持局域网 VNC，已下线，请用仅本机 + adb forward。" +
                                    "这台虚拟机保存的是旧版「局域网」配置，请改回「仅本机」后再启动。",
                            ),
                        )
                    }
                }
                if (_draft.value.draft == config || _draft.value.draft == null) {
                    _draft.update { it.copy(issues = issues) }
                    vm.setAppleStatus(config.id, status)
                }
            }
            return
        }
        // resolve() may trigger the bundled-firmware decompress; keep it off the
        // main thread. Draft edits arrive rapidly, so latest-write-wins via the
        // config identity check after the IO hop.
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val vmDir = layout.vmDir(config.id)
                validator.validate(
                    ValidationInput(
                        config = config,
                        firmwareCode = firmwareManager.resolve(config),
                        firmwareVars = layout.varsFile(config.id),
                        isoFile = config.isoPath?.let(::File),
                        diskParentWritable = vmDir.exists() && vmDir.canWrite(),
                        deviceMemoryMb = deviceMemoryMb(),
                        freeSpaceBytes = layout.freeSpaceBytes(),
                        qemuAvailable = vm.ui.value.env?.ready == true,
                        otherVms = vm.ui.value.vms.filter { it.id != config.id },
                        qemuDetail = vm.ui.value.env?.detail,
                    ),
                )
            }
            // Drop stale results if the user kept editing while we were on IO.
            if (_draft.value.draft == config || _draft.value.draft == null) {
                _draft.update { it.copy(issues = report.issues) }
            }
        }
    }

    // ---------- 导入 ----------

    fun importIso(uri: Uri) {
        launchIsoImport(uri, ImportKind.INSTALL_ISO)
    }

    fun importDriverIso(uri: Uri) {
        launchIsoImport(uri, ImportKind.DRIVER_ISO)
    }

    /**
     * ISO / 驱动盘导入：单任务、可取消。
     *
     * 离页确认由向导壳（WizardScaffold）负责；副本先落 `.part`，取消或失败由
     * IsoImporter 清理。若用户已离开编辑页但复制恰好完成，文件会保留并
     * 加入镜像库——不再走「复制完成后再删除」的静默丢弃路径。
     */
    private fun launchIsoImport(uri: Uri, kind: ImportKind) {
        if (importJob?.isActive == true) {
            vm.postNotice("已有镜像正在导入，请等待完成或取消")
            return
        }
        importJob = viewModelScope.launch {
            vm.setImporting(ImportProgress(0, null, kind))
            try {
                val result = withContext(Dispatchers.IO) {
                    isoImporter.import(uri) { copied, total ->
                        vm.setImporting(ImportProgress(copied, total, kind))
                    }
                }
                result.fold(
                    onSuccess = { file ->
                        val draft = _draft.value.draft
                        vm.addImportedImage(file.absolutePath)
                        if (draft == null) {
                            vm.setImporting(null)
                            vm.postNotice("${kind.label}已导入，文件已保留在镜像库：${file.name}")
                        } else {
                            val nextDraft = if (kind == ImportKind.INSTALL_ISO) {
                                val name = if (draft.name == VmConfig.DEFAULT_NAME || draft.name.isBlank()) {
                                    VmConfig.nameFromIso(file.absolutePath) ?: draft.name
                                } else {
                                    draft.name
                                }
                                draft.copy(isoPath = file.absolutePath, bootFromCd = true, name = name)
                            } else {
                                draft.copy(driverIsoPath = file.absolutePath)
                            }
                            _draft.update { it.copy(draft = nextDraft) }
                            vm.setImporting(null)
                            vm.postNotice(
                                if (kind == ImportKind.INSTALL_ISO) "已导入 ${file.name}"
                                else "已导入驱动盘 ${file.name}",
                            )
                        }
                        if (kind == ImportKind.INSTALL_ISO) {
                            _draft.value.draft?.let { refreshIssues(it) }
                        }
                    },
                    onFailure = { error ->
                        vm.setImporting(null)
                        vm.postNotice("${kind.label}导入失败：${error.message}")
                    },
                )
            } catch (c: CancellationException) {
                vm.setImporting(null)
                vm.postNotice("已取消导入，未完成的临时文件已清理")
                // 取消恰好发生在重命名之后时，文件已经在镜像库；刷新一次让用户还能选到。
                vm.refreshVms()
                throw c
            } finally {
                importJob = null
            }
        }
    }

    /** 取消当前 ISO / 驱动盘导入；未完成的 `.part` 由 IsoImporter 在取消时删除。 */
    fun cancelImport() {
        importJob?.cancel()
    }

    fun importFirmware(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { isoImporter.importFirmware(uri) }
            result.fold(
                onSuccess = { file ->
                    vm.postNotice("已导入固件 ${file.name}")
                    vm.probeEnvironment()
                    updateDraft { it.copy(firmwareCodePath = file.absolutePath) }
                },
                onFailure = { error ->
                    vm.postNotice("固件导入失败：${error.message}")
                },
            )
        }
    }

    /**
     * 导入一份 Android arm64 磁盘镜像（.img / .qcow2），直接放进 VM 的 disk 路径。
     * qcow2/raw 的格式由 DiskProvisioner 按魔数判断，这里只负责完整落盘。
     */
    fun importAndroidDisk(uri: Uri) {
        val draft = _draft.value.draft ?: return
        val target = layout.diskFile(draft.id)
        viewModelScope.launch {
            vm.setImporting(ImportProgress(0, null, ImportKind.ANDROID_DISK))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = appContext.contentResolver.openInputStream(uri)
                        ?: error("无法打开所选文件")
                    val tmp = java.io.File(target.parentFile, target.name + ".part")
                    tmp.parentFile?.mkdirs()
                    input.use { stream ->
                        tmp.outputStream().buffered(1 shl 20).use { out ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            while (true) {
                                val n = stream.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                copied += n
                                vm.setImporting(ImportProgress(copied, null, ImportKind.ANDROID_DISK))
                            }
                        }
                    }
                    if (tmp.length() < 64L * 1024 * 1024) {
                        tmp.delete()
                        error("镜像只有 ${tmp.length() / (1024 * 1024)} MB，不像是完整的系统盘")
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) error("无法写入目标文件")
                    target.length()
                }
            }
            result.fold(
                onSuccess = { size ->
                    vm.setImporting(null)
                    vm.postNotice("已导入 Android 磁盘镜像（${size / (1024 * 1024)} MB）")
                    vm.setAndroidDisk(
                        draft.id,
                        size,
                        if (Qcow2Creator.isQcow2(target)) "qcow2" else "raw",
                    )
                    refreshIssues(draft)
                },
                onFailure = { t ->
                    vm.setImporting(null)
                    vm.postNotice("Android 镜像导入失败：${t.message}")
                },
            )
        }
    }

    /**
     * 导入 iOS 还原包 zip。一个 25 GB 的 nvme.1 解压要几分钟，所以进度按
     * 「已写字节数」推进；中途离开页面也继续写完，避免留下半个镜像。
     */
    fun importAppleBundle(uri: Uri) {
        val draft = _draft.value.draft ?: return
        val target = AppleAssets.dir(layout, draft.id)
        viewModelScope.launch {
            vm.setImporting(ImportProgress(0, null, ImportKind.APPLE_BUNDLE))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = appContext.contentResolver.openInputStream(uri)
                        ?: error("无法打开所选文件")
                    input.use { stream ->
                        AppleAssets.importZip(stream, target) { _, total ->
                            vm.setImporting(ImportProgress(total, null, ImportKind.APPLE_BUNDLE))
                        }
                    }
                }
            }
            result.fold(
                onSuccess = { names ->
                    val status = withContext(Dispatchers.IO) {
                        AppleAssets.status(layout, draft.id)
                    }
                    vm.setImporting(null)
                    vm.postNotice("已导入 ${names.size} 个 iOS 资源文件（共 ${status.totalBytes / (1024 * 1024)} MB）")
                    vm.setAppleStatus(draft.id, status)
                    refreshIssues(draft)
                },
                onFailure = { t ->
                    vm.setImporting(null)
                    vm.postNotice("iOS 资源导入失败：${t.message}")
                },
            )
        }
    }

    // ---------- internals ----------

    private fun deviceMemoryMb(): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem / (1024 * 1024)
    }

    private fun suggestedMemoryMb(): Int {
        val total = deviceMemoryMb()
        val candidate = ((total * 0.5).toInt() / 512) * 512
        return candidate.coerceIn(2048, 6144)
    }
}
