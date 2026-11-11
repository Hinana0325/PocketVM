package dev.pocketvm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.navigation.ConfigCategory

/**
 * 虚拟机配置（P1）：入口列表 + 5 个分类页。
 *
 * 入口（VmConfigHome）只列分类，不直接改值；点进分类页再改。
 * 分类页复用 VmConfigSections.kt 的小节，与新建向导同一份代码。
 * 草稿由 CreateVmViewModel 管（openConfig 打开，saveDraft 保存）：
 * - 分类页之间切换不丢草稿（草稿在 ViewModel 里）；
 * - 从入口页返回（回详情页）时有未保存的修改才弹「放弃」确认；
 * - 进程被杀后恢复到配置页时，草稿可从磁盘重读（openConfig），不丢。
 * 保存后回详情页（管理中心），不是回列表——用户是在管理这台虚拟机。
 */

// ---- 配置入口：5 个分类 ----

@Composable
fun VmConfigScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    vm: VmConfig,
    contentPadding: PaddingValues,
    onOpenCategory: (ConfigCategory) -> Unit,
    onBack: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    var discardDialog by remember { mutableStateOf(false) }

    // 入口页返回 = 回详情页；有未保存的修改先确认，放弃才清草稿。
    val requestBack: () -> Unit = {
        if (draftState.hasUnsavedChanges) discardDialog = true
        else {
            createVm.discardDraft()
            onBack()
        }
    }
    BackHandler(onBack = requestBack)

    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("名称、内存、端口等改动都会丢失，已在运行的虚拟机不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardDialog = false
                        createVm.discardDraft()
                        onBack()
                    },
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { discardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = "配置",
            subtitle = vm.name,
            showBack = true,
            onBack = requestBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IssuesSection(issues = draftState.issues)
            SectionCard(title = "配置分类", subtitle = "点进分类修改，改完点右上角保存") {
                ConfigCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCategory(category) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(category.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                configCategorySummary(vm, category),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "配置改动只影响下次启动；已安装的系统与虚拟盘不受影响。" +
                    "运行中的虚拟机请先停止再改配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 入口列表里每个分类当前值的摘要（一行）。 */
private fun configCategorySummary(vm: VmConfig, category: ConfigCategory): String {
    val isApple = vm.platform == VmPlatform.APPLE_T8030
    val isAndroid = vm.platform == VmPlatform.ANDROID_ARM64
    return when (category) {
        ConfigCategory.PROCESSOR -> "${vm.cpuCores} vCPU"
        ConfigCategory.MEMORY -> "${vm.memoryMb} MB"
        ConfigCategory.STORAGE -> when {
            isApple || isAndroid -> "平台固定，不可改"
            else -> "${vm.diskSizeGb} GB · ${vm.storageController.name}"
        }
        ConfigCategory.DEVICE -> when {
            isApple -> "iOS 资源"
            isAndroid -> "Android 磁盘镜像"
            else -> listOfNotNull(
                vm.firmwareCodePath?.substringAfterLast('/'),
                vm.isoPath?.substringAfterLast('/'),
                vm.driverIsoPath?.substringAfterLast('/'),
            ).joinToString(" · ").ifBlank { "固件 / 介质未选" }
        }
        ConfigCategory.RUNTIME -> "VNC ${vm.vncPort} · " +
            when (vm.launchMode) {
                LaunchMode.AUTO -> "自动"
                LaunchMode.STANDARD -> "标准"
                LaunchMode.COMPAT -> "兼容"
                LaunchMode.MINIMAL -> "最小"
            }
    }
}

// ---- 配置分类页：一个分类一页 ----

@Composable
fun VmConfigCategoryScreen(
    state: UiState,
    createVm: CreateVmViewModel,
    vmId: String,
    category: ConfigCategory,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val draftState by createVm.draft.collectAsState()
    val draft = draftState.draft
    if (draft == null) {
        // 进程恢复时草稿不在内存里：配置已存磁盘，直接重读。
        LaunchedEffect(vmId) { createVm.openConfig(vmId) }
        return
    }
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    // 运行环境分类含 VNC 端口输入：端口没输完时禁用保存（与向导一致）。
    var portValid by remember { mutableStateOf(true) }
    val saveEnabled = state.importing == null && (category != ConfigCategory.RUNTIME || portValid)

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = category.label,
            subtitle = draft.name,
            showBack = true,
            onBack = onBack,
            actions = {
                TextButton(onClick = onSave, enabled = saveEnabled) { Text("保存") }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (category) {
                ConfigCategory.PROCESSOR -> {
                    SectionCard(title = "处理器", subtitle = "TCG 下每多一个 vCPU 都是实打实的宿主开销") {
                        SliderRow(
                            label = "CPU 核心",
                            value = draft.cpuCores.toFloat(),
                            valueRange = if (isApple) 1f..4f else 1f..8f,
                            steps = if (isApple) 3 else 6,
                            valueText = "${draft.cpuCores} vCPU",
                            onChange = { cores -> createVm.updateDraft { it.copy(cpuCores = cores) } },
                        )
                        if (isApple) {
                            Text(
                                "iPhone 11 机型最多 4 核。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ConfigCategory.MEMORY -> {
                    SectionCard(title = "内存", subtitle = "TCG 下每多 1 GB 内存都是实打实的宿主开销") {
                        SliderRow(
                            label = "内存",
                            value = draft.memoryMb.toFloat(),
                            valueRange = when {
                                isApple -> 1024f..3072f
                                isAndroid -> 4096f..8192f
                                else -> 1024f..8192f
                            },
                            steps = when {
                                isApple -> 3
                                isAndroid -> 7
                                else -> 13
                            },
                            valueText = "${draft.memoryMb} MB",
                            onChange = { mem -> createVm.updateDraft { it.copy(memoryMb = (mem / 512).toInt() * 512) } },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(2048, 4096, 6144, 8192)
                                .filter { mb ->
                                    when {
                                        isApple -> mb <= 3072
                                        isAndroid -> mb >= 4096
                                        else -> true
                                    }
                                }
                                .forEach { mb ->
                                    val label = "${mb / 1024} GB"
                                    if (draft.memoryMb == mb) {
                                        Button(onClick = { }) { Text(label) }
                                    } else {
                                        OutlinedButton(onClick = { createVm.updateDraft { it.copy(memoryMb = mb) } }) {
                                            Text(label)
                                        }
                                    }
                                }
                        }
                        Text(
                            "设备内存 ${state.deviceMemoryMb} MB · 剩余存储 ${state.freeSpaceMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ConfigCategory.STORAGE -> {
                    if (isApple || isAndroid) {
                        SectionCard(title = "存储", subtitle = "该平台的存储由导入的镜像决定") {
                            Text(
                                if (isApple) "iPhone 11 的系统盘来自还原包里的 nvme 镜像，不在这里改大小。"
                                else "Android 的系统盘就是导入的磁盘镜像本身，不在这里改大小。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        SectionCard(title = "磁盘容量", subtitle = "qcow2 精简置备：文件只随实际写入增长") {
                            SliderRow(
                                label = "磁盘容量",
                                value = draft.diskSizeGb.toFloat(),
                                valueRange = 32f..256f,
                                steps = 13,
                                valueText = "${draft.diskSizeGb} GB",
                                onChange = { gb -> createVm.updateDraft { it.copy(diskSizeGb = (gb / 16).toInt() * 16) } },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(64, 128, 256).forEach { size ->
                                    OutlinedButton(onClick = { createVm.updateDraft { it.copy(diskSizeGb = size) } }) {
                                        Text("$size GB")
                                    }
                                }
                            }
                            state.diskUsage[draft.id]?.takeIf { it > 0 }?.let { used ->
                                UsageBar(usedBytes = used, totalGb = draft.diskSizeGb)
                            }
                        }
                        StorageBusSection(draft = draft, createVm = createVm)
                    }
                }
                ConfigCategory.DEVICE -> {
                    if (isApple) {
                        AppleSection(draft = draft, state = state, createVm = createVm)
                    } else if (isAndroid) {
                        AndroidDiskSection(draft = draft, state = state, createVm = createVm)
                    } else {
                        FirmwareSection(draft = draft, state = state, createVm = createVm)
                        InstallMediaSection(draft = draft, state = state, createVm = createVm)
                        DriverIsoSection(draft = draft, state = state, createVm = createVm)
                    }
                }
                ConfigCategory.RUNTIME -> {
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
            IssuesSection(issues = draftState.issues)
            if (state.importing != null) {
                Text(
                    "正在复制安装镜像，请等待完成后再保存…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
