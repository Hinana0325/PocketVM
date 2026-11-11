package dev.pocketvm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform

/**
 * 新建向导 5 步（P1）。
 *
 * 5 步对应 5 个 NavHost 目的地（CreateBasic / CreateHardware / CreateDevices /
 * CreateRuntime / CreateReview），草稿存在 CreateVmViewModel 里，切换步骤不丢
 * 填写内容。退出向导（第一步按返回）时按草稿状态提示：
 * - 正在导入 ISO：先确认是否取消导入再离开；
 * - 有未保存的修改：问是否保留草稿（保留则下次新建时恢复，放弃则清空）；
 * - 没有修改：直接离开。
 * 第 2–5 步按返回只回上一步，不弹框。
 *
 * 步骤内容直接复用 VmConfigSections.kt 的小节；第 5 步是汇总确认页
 * （摘要 chips + 全部配置 + 启动前检查 + 保存）。
 */

// ---- 向导壳：顶栏 + 步骤指示 + 底部按钮 + 退出/导入/已知必坏确认框 ----

@Composable
fun WizardScaffold(
    step: Int,
    title: String,
    subtitle: String,
    state: UiState,
    createVm: CreateVmViewModel,
    contentPadding: PaddingValues,
    onExit: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onSave: (() -> Unit)?,
    nextEnabled: Boolean = true,
    saveEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    val importing = state.importing
    var retainDialog by remember { mutableStateOf(false) }
    var importingLeaveDialog by remember { mutableStateOf(false) }
    // 25226 二次确认：记录触发前的完整草稿，取消时恢复。
    var knownBadRestore by remember(draft?.id) { mutableStateOf<VmConfig?>(null) }
    var previousDraft by remember(draft?.id) { mutableStateOf(draft) }

    // Backing out of the wizard used to throw away every edit without asking.
    val isoImporting = importing?.kind == ImportKind.INSTALL_ISO ||
        importing?.kind == ImportKind.DRIVER_ISO
    val requestExit: () -> Unit = {
        when {
            // A6：ISO 复制中返回必须先确认；取消时清掉 .part，不静默丢弃已完成的文件。
            isoImporting -> importingLeaveDialog = true
            draftState.hasUnsavedChanges && importing == null -> retainDialog = true
            else -> onExit()
        }
    }
    // 第一步按返回 = 退出向导（走确认）；第 2–5 步按返回 = 回上一步。
    val requestBack: () -> Unit = when {
        step == 1 -> requestExit
        onPrev != null -> onPrev
        else -> onExit
    }

    // 导入在确认框打开期间完成时，自动收起确认框，避免用户取消一个已完成的导入。
    LaunchedEffect(importing != null) {
        if (importing == null) importingLeaveDialog = false
    }

    // 25226 二次确认（D-UX-9 B）：只在新选择时弹一次；不硬阻断。
    LaunchedEffect(draft) {
        val current = draft
        if (current != null && previousDraft != null &&
            current.isoPath != previousDraft?.isoPath &&
            MediaHints.requiresKnownBadConfirmation(current.isoPath, previousDraft?.isoPath)
        ) {
            knownBadRestore = previousDraft
        }
        previousDraft = current
    }

    BackHandler(onBack = requestBack)

    knownBadRestore?.let { previous ->
        val restore: () -> Unit = {
            createVm.updateDraft { _ -> previous }
            knownBadRestore = null
        }
        AlertDialog(
            onDismissRequest = restore,
            title = { Text("25226 已知必坏") },
            text = {
                Text("25226 启动即异常，建议改用 22589。仍要使用这份 ISO 吗？取消会恢复之前的选择。")
            },
            confirmButton = {
                TextButton(onClick = { knownBadRestore = null }) { Text("仍要使用") }
            },
            dismissButton = {
                TextButton(onClick = restore) { Text("取消") }
            },
        )
    }

    if (importingLeaveDialog) {
        val progress = importing
        val copiedMb = (progress?.copiedBytes ?: 0L) / (1024 * 1024)
        val totalMb = progress?.totalBytes?.let { it / (1024 * 1024) }
        val amount = if (totalMb != null) "$copiedMb MB / $totalMb MB" else "$copiedMb MB"
        AlertDialog(
            onDismissRequest = { importingLeaveDialog = false },
            title = { Text("正在导入${progress?.kind?.label ?: "镜像"}") },
            text = {
                Text(
                    "已复制 $amount。离开会取消导入并删除未完成的临时文件；" +
                        "若复制已完成，文件会保留在镜像库。未保存的修改也会丢弃。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importingLeaveDialog = false
                        createVm.cancelImport()
                        createVm.discardDraft()
                        onExit()
                    },
                ) {
                    Text("取消导入并离开", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { importingLeaveDialog = false }) { Text("继续等待") }
            },
        )
    }

    if (retainDialog) {
        AlertDialog(
            onDismissRequest = { retainDialog = false },
            title = { Text("保留草稿？") },
            text = { Text("下次点「新建虚拟机」时会恢复这次填写的内容；选「放弃」则全部丢弃。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        retainDialog = false
                        onExit()
                    },
                ) { Text("保留草稿") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        retainDialog = false
                        createVm.discardDraft()
                        onExit()
                    },
                ) { Text("放弃", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = title,
            subtitle = subtitle,
            showBack = true,
            onBack = requestBack,
        )
        // 步骤指示：第 X 步 / 共 5 步。
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "第 $step 步 / 共 5 步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { step / 5f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onPrev != null) {
                OutlinedButton(
                    onClick = onPrev,
                    modifier = Modifier.weight(1f),
                ) { Text("上一步") }
            }
            if (onNext != null) {
                Button(
                    onClick = onNext,
                    enabled = nextEnabled && importing == null,
                    modifier = Modifier.weight(1f),
                ) { Text("下一步") }
            }
            if (onSave != null) {
                Button(
                    onClick = onSave,
                    enabled = saveEnabled && importing == null,
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}

// ---- 第 1 步：基本信息 ----

@Composable
fun CreateBasicScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    contentPadding: PaddingValues,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    if (draft == null) {
        // 进程被系统杀死后恢复到向导页：草稿只在内存里，已经没了，回首页。
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    WizardScaffold(
        step = 1,
        title = "新建虚拟机",
        subtitle = "QEMU 11.1.2 · TCG 软件模拟 · VNC 显示",
        state = state,
        createVm = createVm,
        contentPadding = contentPadding,
        onExit = onExit,
        onPrev = null,
        onNext = onNext,
        onSave = null,
    ) {
        HostSection(state = state)
        IdentitySection(draft = draft, createVm = createVm)
        if (isApple) AppleSection(draft = draft, state = state, createVm = createVm)
        if (isAndroid) AndroidDiskSection(draft = draft, state = state, createVm = createVm)
    }
}

// ---- 第 2 步：硬件资源 ----

@Composable
fun CreateHardwareScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    contentPadding: PaddingValues,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    if (draft == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    WizardScaffold(
        step = 2,
        title = "硬件资源",
        subtitle = "CPU / 内存 / 磁盘",
        state = state,
        createVm = createVm,
        contentPadding = contentPadding,
        onExit = onExit,
        onPrev = onPrev,
        onNext = onNext,
        onSave = null,
    ) {
        HardwareSection(draft = draft, state = state, createVm = createVm)
        if (!isApple && !isAndroid) StorageBusSection(draft = draft, createVm = createVm)
    }
}

// ---- 第 3 步：设备与介质 ----

@Composable
fun CreateDevicesScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    contentPadding: PaddingValues,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    if (draft == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    WizardScaffold(
        step = 3,
        title = "设备与介质",
        subtitle = "固件 / 安装镜像 / 驱动盘",
        state = state,
        createVm = createVm,
        contentPadding = contentPadding,
        onExit = onExit,
        onPrev = onPrev,
        onNext = onNext,
        onSave = null,
    ) {
        if (!isApple) FirmwareSection(draft = draft, state = state, createVm = createVm)
        if (!isApple && !isAndroid) {
            InstallMediaSection(draft = draft, state = state, createVm = createVm)
            DriverIsoSection(draft = draft, state = state, createVm = createVm)
        }
        if (isApple) {
            Text(
                "iPhone 11 机型没有 UEFI 固件与安装介质：资源在第 1 步导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAndroid) {
            Text(
                "Android 机型从导入的磁盘镜像启动，没有安装介质与驱动盘。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 第 4 步：运行环境 ----

@Composable
fun CreateRuntimeScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    contentPadding: PaddingValues,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    if (draft == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    var portValid by remember { mutableStateOf(true) }
    WizardScaffold(
        step = 4,
        title = "运行环境",
        subtitle = "VNC 显示 / 启动模式 / 高级选项",
        state = state,
        createVm = createVm,
        contentPadding = contentPadding,
        onExit = onExit,
        onPrev = onPrev,
        onNext = onNext,
        onSave = null,
        nextEnabled = portValid,
    ) {
        VncDisplaySection(
            draft = draft,
            createVm = createVm,
            onPortValidChange = { portValid = it },
        )
        if (!isApple && !isAndroid) AdvancedSection(draft = draft, createVm = createVm)
        if (isApple || isAndroid) {
            Text(
                "iPhone / Android 机型没有高级启动选项：显示与启动方式由平台固定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 第 5 步：汇总确认 ----

@Composable
fun CreateReviewScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    contentPadding: PaddingValues,
    onPrev: () -> Unit,
    onSave: () -> Unit,
    onExit: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    if (draft == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    WizardScaffold(
        step = 5,
        title = "确认并保存",
        subtitle = "检查一遍，没问题就保存",
        state = state,
        createVm = createVm,
        contentPadding = contentPadding,
        onExit = onExit,
        onPrev = onPrev,
        onNext = null,
        onSave = onSave,
    ) {
        DraftSummaryChips(draft = draft)
        DraftSummarySection(draft = draft, state = state)
        IssuesSection(issues = draftState.issues)
        ArgvSection(argv = state.argv)
        if (state.importing != null) {
            Text(
                "正在复制安装镜像，请等待完成后再保存…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 汇总页：全部配置逐项列出（只读），与第 1–4 步填的内容一一对应。 */
@Composable
fun DraftSummarySection(draft: VmConfig, state: UiState) {
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    SectionCard(title = "配置汇总", subtitle = "保存后可在详情页修改") {
        KeyValueRow("名称", draft.name)
        KeyValueRow("平台", draft.platform.label)
        KeyValueRow("CPU", "${draft.cpuCores} vCPU")
        KeyValueRow("内存", "${draft.memoryMb} MB")
        if (!isApple && !isAndroid) {
            KeyValueRow("磁盘", "${draft.diskSizeGb} GB（${draft.storageController.name}）")
            KeyValueRow("安装介质", draft.isoPath?.substringAfterLast('/') ?: "无")
            KeyValueRow("驱动盘", draft.driverIsoPath?.substringAfterLast('/') ?: "无")
            KeyValueRow("固件", draft.firmwareCodePath?.substringAfterLast('/') ?: "内置")
        }
        if (isApple) {
            val status = state.appleAssets[draft.id]
            KeyValueRow(
                "iOS 资源",
                if (status?.present == true) "已导入" else "未导入 / 不完整",
            )
        }
        if (isAndroid) {
            val sizeMb = (state.androidDisks[draft.id] ?: 0L) / (1024 * 1024)
            KeyValueRow("磁盘镜像", if (sizeMb > 0) "$sizeMb MB" else "未导入")
        }
        KeyValueRow("VNC 端口", "${draft.vncPort}（仅本机）")
        KeyValueRow("显示设备", draft.displayDevice.label)
        KeyValueRow(
            "启动模式",
            when (draft.launchMode) {
                dev.pocketvm.core.model.LaunchMode.AUTO -> "自动"
                dev.pocketvm.core.model.LaunchMode.STANDARD -> "标准"
                dev.pocketvm.core.model.LaunchMode.COMPAT -> "兼容"
                dev.pocketvm.core.model.LaunchMode.MINIMAL -> "最小"
            },
        )
        if (draft.portForwards.isNotEmpty()) {
            KeyValueRow(
                "端口转发",
                draft.portForwards.joinToString("、") { "${it.hostPort}→${it.guestPort}" },
            )
        }
    }
}
