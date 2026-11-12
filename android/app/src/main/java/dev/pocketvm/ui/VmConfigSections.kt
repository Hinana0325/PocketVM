package dev.pocketvm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.config.Issue
import dev.pocketvm.gsi.GsiAssembler
import dev.pocketvm.core.config.Severity
import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.PortForward
import dev.pocketvm.core.model.StorageController
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.storage.AppleAssets
import dev.pocketvm.ui.theme.MonoTextStyle
import java.io.File

/**
 * 新建向导与配置编辑共用的表单小节（P1）。
 *
 * 从旧 CreateVmScreen 的单页长表单拆出来：每个小节一个 @Composable，
 * 向导步骤与配置分类页按需拼装，同一份代码、同一份校验与导入逻辑。
 * 小节只读两种状态：
 * - draft：CreateVmViewModel.draft（正在编辑的草稿），改动走 createVm.updateDraft；
 * - state：VmViewModel.ui（宿主环境、镜像库、导入进度等设备/虚拟机状态）。
 * 导入 picker（rememberLauncherForActivityResult）由各小节自己创建，
 * 回调进 createVm 的导入函数；导入进度由 VmViewModel.ui.importing 统一显示。
 */

// ---- 1. 宿主硬件检测 ----

@Composable
fun HostSection(state: UiState) {
    SectionCard(
        title = "宿主硬件检测",
        subtitle = "未 Root 的手机只能用 TCG 软件模拟，没有 KVM 加速",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                text = if (state.env?.ready == true) "TCG 软件模拟就绪" else "执行环境未就绪",
                tone = if (state.env?.ready == true) Tone.Ok else Tone.Error,
            )
            Text(
                "QEMU ${state.env?.version ?: "未知版本"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KeyValueRow("处理器", state.env?.soc ?: "未知")
        KeyValueRow("加速方式", "TCG 软件模拟（未 Root，无 KVM）")
        KeyValueRow("设备内存", "${state.deviceMemoryMb} MB")
        KeyValueRow("剩余存储", "${state.freeSpaceMb} MB")
        state.env?.detail?.takeIf { state.env?.ready != true }?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---- 2. 虚拟机标识与平台 ----

@Composable
fun IdentitySection(draft: VmConfig, createVm: CreateVmViewModel) {
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    SectionCard(
        title = "虚拟机标识与平台",
        subtitle = "平台决定用哪个执行层；三种机型的配置项互不影响",
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { name -> createVm.updateDraft { it.copy(name = name.take(60)) } },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // A7：3 个长标签改为整行单选，避免 320dp / 大字号横向裁切。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VmPlatform.values().forEach { platform ->
                val description = when (platform) {
                    VmPlatform.UEFI_ARM64 ->
                        "QEMU 11.1.2 + edk2，跑 Windows / Linux，TCG 软件模拟"
                    VmPlatform.APPLE_T8030 ->
                        "Inferno t8030 机型，模拟 iPhone 11（A13），加载 iOS 还原包资源"
                    VmPlatform.ANDROID_ARM64 ->
                        "UEFI + virtio-gpu，从导入的完整磁盘镜像启动，内存不低于 4 GB"
                }
                if (draft.platform == platform) {
                    Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                        Text(platform.label, textAlign = TextAlign.Center)
                    }
                } else {
                    OutlinedButton(
                        onClick = { createVm.setPlatform(platform) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(platform.label, textAlign = TextAlign.Center)
                    }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.platform == platform) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (isApple) {
            Text(
                "iPhone 11 机型：CPU 不超过 4 核、内存不超过 3072 MB，切换平台时已自动收敛。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAndroid) {
            Text(
                "Android 机型：显示固定为 virtio-gpu、从磁盘启动（不从光盘引导）、内存不低于 4096 MB。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 3a. iOS 资源（仅 Apple 平台） ----

@Composable
fun AppleSection(draft: VmConfig, state: UiState, createVm: CreateVmViewModel) {
    val appleStatus = state.appleAssets[draft.id]
    val applePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(createVm::importAppleBundle)
    }
    SectionCard(
        title = "iOS 资源",
        subtitle = "还原包里的 kernelcache / SEP / nvme.1；Apple 固件不进 APK，只在本机导入",
    ) {
        val totalMb = (appleStatus?.totalBytes ?: 0L) / (1024 * 1024)
        Text(
            when {
                appleStatus?.present == true ->
                    "已导入 ${AppleAssets.specs.size} 个文件，共 $totalMb MB" +
                        "（系统盘 ${appleStatus.rootDiskBytes / (1024 * 1024 * 1024)} GB）"
                appleStatus == null -> "尚未导入"
                else -> "缺少 ${appleStatus.missing.size} 个文件：" +
                    appleStatus.missing.joinToString("、")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (appleStatus?.present == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        state.importing?.let { progress ->
            Text(
                "正在解压：${progress.copiedBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            onClick = { applePicker.launch(arrayOf("*/*")) },
            enabled = state.importing == null,
        ) { Text("导入还原包 zip") }
        Text(
            "已知限制：旧 restore 镜像与最新 Inferno 的 chip-id 补丁不兼容时，" +
                "iOS 会在 data-protection 启动任务处 panic；需要按官方流程重新 restore。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 3b. Android 磁盘镜像（仅 Android 平台） ----

@Composable
fun AndroidDiskSection(draft: VmConfig, state: UiState, createVm: CreateVmViewModel) {
    val androidDiskPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(createVm::importAndroidDisk)
    }
    SectionCard(
        title = "Android 磁盘镜像",
        subtitle = "导入可 UEFI 启动的 arm64 系统盘（.img / .qcow2）",
    ) {
        val sizeMb = (state.androidDisks[draft.id] ?: 0L) / (1024 * 1024)
        val fmt = state.androidFormats[draft.id] ?: "raw"
        Text(
            if (sizeMb > 0) "已导入：$fmt · $sizeMb MB" else "尚未导入",
            style = MaterialTheme.typography.bodySmall,
            color = if (sizeMb > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        state.importing?.let { progress ->
            Text(
                "正在导入：${progress.copiedBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            onClick = { androidDiskPicker.launch(arrayOf("*/*")) },
            enabled = state.importing == null,
        ) { Text("导入磁盘镜像") }
        Text(
            "完整磁盘镜像走 UEFI + virtio-gpu；GSI 组件（system.img + vendor.img + kernel/ramdisk）" +
                "的拼装还在实验阶段，目前请优先使用可直接启动的完整镜像。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GsiAssemblyCard(vmId = draft.id)
    }
}

/**
 * GSI 组件拼装（实验）：导入 kernel/ramdisk/system/vendor，拼一张 GPT 物理分区盘
 * （metadata/system/vendor/userdata）并转换成 qcow2。只做拼装，不承诺启动成功。
 */
@Composable
private fun GsiAssemblyCard(vmId: String, vm: GsiViewModel = hiltViewModel()) {
    LaunchedEffect(vmId) { vm.attach(vmId) }
    val st by vm.state.collectAsState()
    var confirmReplace by remember { mutableStateOf(false) }

    Text(
        "GSI 拼装（实验）：只生成 GPT 物理分区盘（不做 dm-super）。" +
            "启动链（first-stage fstab / boot_devices）与显示（goldfish HAL）尚未打通，结果主要用于实验与导出。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    GsiAssembler.Component.entries.forEach { component ->
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { vm.import(component, it) }
        }
        val size = st.components[component] ?: 0L
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(component.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (size > 0) "${component.hint} · 已导入 ${size / (1024 * 1024)} MB"
                    else "${component.hint} · 未导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !st.busy,
            ) { Text(if (size > 0) "重新导入" else "导入") }
        }
    }

    Button(
        onClick = { if (st.diskExists) confirmReplace = true else vm.assemble(2, false) },
        enabled = !st.busy,
    ) { Text(if (st.busy) "拼装中…" else "拼装系统盘（替换现有磁盘）") }

    st.progress?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    st.notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = vm::clearNotice) { Text("知道了") }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("替换现有磁盘？") },
            text = { Text("这台虚拟机已有磁盘，拼装会覆盖为新的 GSI 分区盘（旧盘不可恢复）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    vm.assemble(2, true)
                }) { Text("替换") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } },
        )
    }
}

// ---- 3c. UEFI 固件（Apple 平台没有 UEFI） ----

@Composable
fun FirmwareSection(draft: VmConfig, state: UiState, createVm: CreateVmViewModel) {
    var firmwareMenu by remember { mutableStateOf(false) }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(createVm::importFirmware)
    }
    SectionCard(
        title = "UEFI 固件",
        subtitle = "aarch64 需要 edk2 pflash 镜像；变量存储会按固件大小自动创建",
    ) {
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { firmwareMenu = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    (draft.firmwareCodePath ?: state.firmwareOptions.firstOrNull())
                        ?.substringAfterLast('/')
                        ?: "未找到固件，点击选择",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = firmwareMenu, onDismissRequest = { firmwareMenu = false }) {
                if (state.firmwareOptions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("firmware/ 目录为空") },
                        onClick = { firmwareMenu = false },
                    )
                }
                state.firmwareOptions.forEach { path ->
                    DropdownMenuItem(
                        text = { Text(File(path).name) },
                        onClick = {
                            firmwareMenu = false
                            createVm.updateDraft { it.copy(firmwareCodePath = path) }
                        },
                    )
                }
            }
        }
        TextButton(onClick = { firmwarePicker.launch(arrayOf("*/*")) }) {
            Text("从文件管理器导入 QEMU_EFI.fd")
        }
    }
}

// ---- 3d. 安装介质与驱动盘（仅 UEFI 平台；Android 自带引导，Apple 没有 ISO） ----

@Composable
fun InstallMediaSection(draft: VmConfig, state: UiState, createVm: CreateVmViewModel) {
    var imageMenu by remember { mutableStateOf(false) }
    val isoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(createVm::importIso)
    }
    SectionCard(title = "安装介质", subtitle = "ISO 会被复制到应用私有目录，QEMU 不支持 content:// 路径") {
        Text(
            draft.isoPath?.substringAfterLast('/') ?: "尚未选择",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { isoPicker.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) },
                enabled = state.importing == null,
            ) { Text("导入新 ISO") }
            if (state.imageOptions.isNotEmpty()) {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { imageMenu = true },
                        enabled = state.importing == null,
                    ) { Text("使用已导入镜像") }
                    DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                        state.imageOptions.forEach { path ->
                            DropdownMenuItem(
                                text = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    imageMenu = false
                                    createVm.updateDraft { d ->
                                        d.copy(
                                            isoPath = path,
                                            bootFromCd = true,
                                            name = if (d.name == VmConfig.DEFAULT_NAME || d.name.isBlank())
                                                VmConfig.nameFromIso(path) ?: d.name
                                            else d.name,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (draft.isoPath != null) {
                TextButton(
                    onClick = { createVm.updateDraft { it.copy(isoPath = null) } },
                    enabled = state.importing == null,
                ) { Text("移除") }
            }
        }
        if (MediaHints.looksLikeWindows(draft.isoPath, draft.name)) {
            MediaHints.classifyIso(draft.isoPath)?.let { info ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        info.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (info.tone) {
                            MediaHintTone.WARNING -> MaterialTheme.colorScheme.error
                            MediaHintTone.OK -> MaterialTheme.colorScheme.primary
                            MediaHintTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        info.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "同一份 ISO 可被多台虚拟机共用，无需重复复制。安装完成后移除介质，" +
                "下次启动就会直接从虚拟磁盘引导。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.importing?.let { progress ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "正在复制${progress.kind.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress.kind == ImportKind.INSTALL_ISO ||
                        progress.kind == ImportKind.DRIVER_ISO
                    ) {
                        TextButton(onClick = createVm::cancelImport) { Text("取消导入") }
                    }
                }
                val fraction = progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    buildString {
                        append("复制中 ")
                        append(progress.copiedBytes / (1024 * 1024))
                        append(" MB")
                        progress.totalBytes?.let { append(" / ${it / (1024 * 1024)} MB") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DriverIsoSection(draft: VmConfig, state: UiState, createVm: CreateVmViewModel) {
    val driverIsoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(createVm::importDriverIso)
    }
    SectionCard(
        title = "驱动盘（VirtIO 驱动）",
        subtitle = "USB 光驱在本机会令 QEMU 崩溃时，挂这张盘让 WinPE 加载 vioscsi 才能装 Windows",
    ) {
        val driverName = draft.driverIsoPath?.substringAfterLast('/')
        Text(
            driverName ?: "未选择（仅当 USB 崩溃、改用 virtio-scsi 装 Windows 时才需要）",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { driverIsoPicker.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) },
                enabled = state.importing == null,
            ) { Text("导入驱动盘 ISO") }
            if (draft.driverIsoPath != null) {
                TextButton(
                    onClick = { createVm.updateDraft { it.copy(driverIsoPath = null) } },
                    enabled = state.importing == null,
                ) { Text("移除") }
            }
        }
        MediaHints.driverAdvice(draft.isoPath, draft.driverIsoPath, draft.name)?.let { advice ->
            Text(
                advice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "用 Fedora 的 virtio-win 驱动盘（含 vioscsi、viostor 等）。安装时 Windows 停在「找不到驱动程序」" +
                "或读不到安装文件时，点「加载驱动程序」指向这张 AHCI 盘里的 vioscsi（如 w11arm64 目录）即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 4. 硬件资源分配（vCPU / 内存 / 磁盘） ----

@Composable
fun HardwareSection(draft: VmConfig, state: UiState, createVm: CreateVmViewModel) {
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    SectionCard(
        title = "硬件资源分配",
        subtitle = "TCG 下每多一个 vCPU / 每多 1 GB 内存，都是实打实的宿主开销",
    ) {
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
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
        if (!isApple && !isAndroid) {
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
            Text(
                "qcow2 精简置备：文件只随实际写入增长，256 GB 的盘初始也只占几百 KB。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.diskUsage[draft.id]?.takeIf { it > 0 }?.let { used ->
                UsageBar(usedBytes = used, totalGb = draft.diskSizeGb)
            }
        }
    }
}

// ---- 存储总线：只提供真实的 StorageController 两个值，没有 SATA ----

@Composable
fun StorageBusSection(draft: VmConfig, createVm: CreateVmViewModel) {
    SectionCard(
        title = "存储总线",
        subtitle = "Windows 安装程序自带 NVMe 与 USB 驱动；virtio 需要额外注入驱动盘",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                StorageController.NVME to "NVMe（推荐）",
                StorageController.VIRTIO to "virtio",
            ).forEach { (mode, label) ->
                if (draft.storageController == mode) {
                    Button(onClick = { }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { createVm.updateDraft { it.copy(storageController = mode) } }) {
                        Text(label)
                    }
                }
            }
        }
        Text(
            if (draft.storageController == StorageController.NVME) {
                "磁盘走 NVMe、安装介质走 USB，两者都有 Windows 原生驱动，ISO 可直接装进虚拟磁盘。"
            } else {
                "virtio 更快且支持 TRIM，但安装界面会看不到磁盘，需要先加载 virtio-win 里的 viostor。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 5. VNC 显示：协议只有 VNC，没有 Spice；所有平台都显示 ----

@Composable
fun VncDisplaySection(
    draft: VmConfig,
    createVm: CreateVmViewModel,
    /** 端口合法性回传：父级（向导「下一步」/ 配置「保存」）据此禁用按钮。 */
    onPortValidChange: (Boolean) -> Unit = {},
) {
    // Port validity is needed both by the field and by the save button.
    var portText by remember(draft.vncPort) { mutableStateOf(draft.vncPort.toString()) }
    val portValue = portText.toIntOrNull()
    val portInvalid = portValue == null || portValue !in 5900..5999
    androidx.compose.runtime.LaunchedEffect(portInvalid) { onPortValidChange(!portInvalid) }

    // hostfwd editor state. Kept as text so a half-typed port never reaches the
    // config as 0; only a complete in-range pair commits.
    var fwdHostText by remember { mutableStateOf("") }
    var fwdGuestText by remember { mutableStateOf("") }
    val fwdHostValue = fwdHostText.toIntOrNull()
    val fwdGuestValue = fwdGuestText.toIntOrNull()
    val fwdValid = fwdHostValue != null && fwdGuestValue != null &&
        fwdHostValue in 1024..65535 && fwdGuestValue in 1..65535 &&
        draft.portForwards.none { it.hostPort == fwdHostValue }

    SectionCard(
        title = "VNC 显示",
        subtitle = "画面由 QEMU 自带的 VNC 服务提供，本 App 只负责启动与监督",
    ) {
        Text(
            "显示协议：VNC（唯一可用；Spice 未启用）",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { input ->
                portText = input.filter { it.isDigit() }.take(4)
                portText.toIntOrNull()?.let { port ->
                    // Only commit a complete, in-range port; showing "58" as
                    // accepted while the config kept 5900 was a lie.
                    if (port in 5900..5999) createVm.updateDraft { it.copy(vncPort = port) }
                }
            },
            label = { Text("端口（5900-5999）") },
            isError = portInvalid,
            supportingText = if (portInvalid) {
                { Text("请输入 5900-5999 之间的端口，否则不会保存") }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // P1 已下线局域网入口：执行层缺 DES 后端，密码认证的 VNC 起不来。
            // 单选项仍按原交互渲染，旧配置（VncAccess.LAN）会显示为可点击的
            // 「仅本机」，点一下即可把草稿迁回唯一可用模式。
            listOf(VncAccess.LOOPBACK to "仅本机").forEach { (mode, label) ->
                if (draft.vncAccess == mode) {
                    Button(onClick = { }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { createVm.updateDraft { it.copy(vncAccess = mode) } }) { Text(label) }
                }
            }
        }

        if (draft.vncAccess == VncAccess.LAN) {
            Text(
                "P1 不支持局域网 VNC，已下线，请用仅本机 + adb forward。" +
                    "这台虚拟机保存的是旧版「局域网」配置，启动会被校验器拦下" +
                    "（VNC_LAN_UNAVAILABLE）；点上方「仅本机」后保存即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = draft.vncPassword.orEmpty(),
            onValueChange = { value -> createVm.updateDraft { it.copy(vncPassword = value.take(8).ifBlank { null }) } },
            label = { Text("VNC 密码（最多 8 位，RFB 限制）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "只监听 127.0.0.1。P1 不支持局域网 VNC，已下线，请用仅本机 + adb forward：" +
                "外部查看先执行 adb forward tcp:${draft.vncPort} tcp:${draft.vncPort}，" +
                "再连 127.0.0.1:${draft.vncPort}。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "端口转发（把客户机的服务暴露到手机上）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )

        draft.portForwards.forEach { forward ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "手机 ${forward.hostPort} → 客户机 ${forward.guestPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = {
                        createVm.updateDraft { it.copy(portForwards = it.portForwards - forward) }
                    },
                ) { Text("删除") }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = fwdHostText,
                onValueChange = { input -> fwdHostText = input.filter { it.isDigit() }.take(5) },
                label = { Text("手机端口") },
                singleLine = true,
                isError = fwdHostText.isNotEmpty() && (fwdHostValue == null || fwdHostValue !in 1024..65535),
                modifier = Modifier.weight(1f),
            )
            Text("→", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = fwdGuestText,
                onValueChange = { input -> fwdGuestText = input.filter { it.isDigit() }.take(5) },
                label = { Text("客户机端口") },
                singleLine = true,
                isError = fwdGuestText.isNotEmpty() && (fwdGuestValue == null || fwdGuestValue !in 1..65535),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    createVm.updateDraft {
                        it.copy(
                            portForwards = it.portForwards +
                                PortForward(fwdHostValue!!, fwdGuestValue!!),
                        )
                    }
                    fwdHostText = ""
                    fwdGuestText = ""
                },
                enabled = fwdValid,
            ) { Text("添加") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Preset host ports sit at guest+2000: memorable, and never in
            // the <1024 range an Android app cannot bind.
            listOf("远程桌面" to 3389, "SSH" to 22, "网页" to 80).forEach { (label, guestPort) ->
                val hostPort = guestPort + 2000
                TextButton(
                    onClick = {
                        if (draft.portForwards.none { it.hostPort == hostPort }) {
                            createVm.updateDraft {
                                it.copy(portForwards = it.portForwards + PortForward(hostPort, guestPort))
                            }
                        }
                    },
                ) { Text("$label $guestPort") }
            }
        }

        Text(
            "转发只监听 127.0.0.1：以远程桌面预设为例，电脑上先 adb forward tcp:5389 tcp:5389，" +
                "再连 127.0.0.1:5389。P1 不支持局域网 VNC，已下线，无法直接连手机 IP。" +
                "Android 应用无法绑定 1024 以下端口，故手机侧从 1024 起。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 6. 显示与高级选项（仅 UEFI 平台；引导相关是兜底面，出问题才需要改） ----

@Composable
fun AdvancedSection(draft: VmConfig, createVm: CreateVmViewModel) {
    var advanced by remember { mutableStateOf(false) }
    CollapsibleSection(
        title = "显示与高级选项",
        subtitle = if (advanced) {
            "出问题才需要改这里"
        } else {
            "光驱 ${if (draft.usbDisabled) "virtio-scsi+AHCI（USB 已禁用）" else draft.cdTransport.label}" +
                (if (draft.driverIsoPath != null) " · 驱动盘已挂" else "") +
                (if (draft.portForwards.isNotEmpty()) " · 转发 ${draft.portForwards.size} 个端口" else "") +
                " · 输入 ${if (draft.usbDisabled || !draft.usbInput) "virtio" else "USB"} · " +
                "显示 ${draft.displayDevice.label} · " +
                "串口恢复 ${if (draft.serialRecovery) "开" else "关"} · " +
                "CPU ${draft.cpuModel ?: "跟随档位"} · 提取镜像 ${if (draft.useBootImage) "开" else "关"}"
        },
        expanded = advanced,
        onToggle = { advanced = !advanced },
    ) {
        Text(
            "启动模式：标准模式需要较大的 TCG 代码缓存；部分芯片/系统上分配不到时会启动即退出",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Two rows: four buttons do not fit on a phone in one line.
        listOf(
            listOf(LaunchMode.AUTO to "自动", LaunchMode.STANDARD to "标准"),
            listOf(LaunchMode.COMPAT to "兼容", LaunchMode.MINIMAL to "最小"),
        ).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (mode, label) ->
                    if (draft.launchMode == mode) {
                        Button(onClick = { }, modifier = Modifier.weight(1f)) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { createVm.updateDraft { it.copy(launchMode = mode) } },
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }
        }
        Text(
            when (draft.launchMode) {
                LaunchMode.AUTO ->
                    "先按标准启动，失败后自动尝试兼容、最小模式；每次尝试都会单独记录日志。" +
                        "这是默认值，建议保持。"
                LaunchMode.STANDARD ->
                    "多线程 TCG、tb-size 512 MB、-cpu max，性能最好。若启动即退出，改回「自动」。"
                LaunchMode.COMPAT ->
                    "单线程 TCG、tb-size 128 MB、cortex-a76、关闭 virtio-rng。" +
                        "用于标准模式分配代码缓存失败或机型兼容性差的设备。"
                LaunchMode.MINIMAL ->
                    "在兼容基础上再关闭网络、tb-size 64 MB。" +
                        "只用于排查，装完系统后建议切回自动。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            label = "USB 总线",
            options = listOf(false to "启用", true to "禁用"),
            selected = draft.usbDisabled,
            onSelect = { value -> createVm.updateDraft { it.copy(usbDisabled = value) } },
        )
        Text(
            "禁用后 QEMU 不创建 xHCI 控制器，USB 键鼠与 USB 光驱都不会出现：光驱固定走 " +
                "virtio-scsi + AHCI、输入固定走 virtio。给「一碰 USB 栈就 SIGILL」的机型用。" +
                "注意：本机 virtio 键盘收不到按键，禁用后只能看画面或跑无人值守安装。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!draft.usbDisabled) {
            ChoiceRow(
                label = "光驱总线",
                options = listOf(
                    CdTransport.AUTO to "自动",
                    CdTransport.USB to "USB",
                    CdTransport.VIRTIO_SCSI to "virtio-scsi+AHCI",
                ),
                selected = draft.cdTransport,
                onSelect = { value -> createVm.updateDraft { it.copy(cdTransport = value) } },
            )
            Text(
                "USB 是 edk2 与 WinPE 都支持的总线（edk2 无 AHCI 驱动，WinPE 无 vioscsi），" +
                    "但部分机型上 QEMU 的 USB 栈会直接崩溃（SIGILL）。「自动」先用 USB，" +
                    "一旦被信号杀死就改为 virtio-scsi + AHCI（键鼠仍走 USB）并记住这个选择；" +
                    "Windows 在 virtio-scsi 下需配合上方「驱动盘」加载 vioscsi 才能读安装文件" +
                    "（挂了驱动盘时安装 ISO 只走 virtio-scsi：两个 ISO 设备同屏会让 Windows 安装冻结）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ChoiceRow(
                label = "输入设备",
                options = listOf(true to "USB", false to "virtio"),
                selected = draft.usbInput,
                onSelect = { value -> createVm.updateDraft { it.copy(usbInput = value) } },
            )
            Text(
                "键鼠默认走 USB（本机 virtio 键盘收不到按键）；只有同一档再次被信号杀死时" +
                    "才会自动把输入降为 virtio。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ChoiceRow(
            label = "显示设备",
            options = listOf(
                DisplayDevice.RAMFB to "ramfb",
                DisplayDevice.VIRTIO_GPU to "virtio-gpu",
            ),
            selected = draft.displayDevice,
            onSelect = { value -> createVm.updateDraft { it.copy(displayDevice = value) } },
        )
        Text(
            "ramfb 是默认值，也是 Windows 唯一能启动的显示设备：virtio-gpu 的 GOP 没有线性" +
                "帧缓冲，Windows 引导管理器清屏时写空指针会直接 Data Abort（本机实测）。" +
                "ramfb 固定 800x600，可在固件界面按 F10 进 Setup 把首选分辨率改成 1024x768" +
                "（写入变量存储，重启保留）。virtio-gpu 只建议给 Linux 客户机用，它没有 ramfb " +
                "驱动，切过去后内核交接会黑屏；Linux 相反，virtio-gpu 有 DRM 驱动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            label = "串口自动恢复",
            options = listOf(true to "开", false to "关"),
            selected = draft.serialRecovery,
            onSelect = { value -> createVm.updateDraft { it.copy(serialRecovery = value) } },
        )
        Text(
            "开启后监听固件串口，检测到 UEFI Shell 会自动输入引导命令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            label = "网卡",
            options = listOf(null to "跟随档位", true to "开", false to "关"),
            selected = draft.enableNet,
            onSelect = { value -> createVm.updateDraft { it.copy(enableNet = value) } },
        )
        ChoiceRow(
            label = "virtio-rng",
            options = listOf(null to "跟随档位", true to "开", false to "关"),
            selected = draft.rng,
            onSelect = { value -> createVm.updateDraft { it.copy(rng = value) } },
        )

        ChoiceRow(
            label = "提取引导镜像",
            options = listOf(false to "关（推荐）", true to "开"),
            selected = draft.useBootImage,
            onSelect = { value -> createVm.updateDraft { it.copy(useBootImage = value) } },
        )
        Text(
            "抠出的 El Torito 镜像只有启动管理器、没有 boot.wim，开启后 Windows 会引导失败；" +
                "仅当固件完全读不出光盘时才值得一试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(
            label = "CPU 模型",
            options = listOf(null to "跟随档位", "max" to "max", "cortex-a76" to "cortex-a76"),
            selected = draft.cpuModel,
            onSelect = { value -> createVm.updateDraft { it.copy(cpuModel = value) } },
        )
    }
}

// ---- 启动前检查 + QEMU 启动参数预览 ----

@Composable
fun IssuesSection(issues: List<Issue>) {
    if (issues.isEmpty()) return
    SectionCard(title = "启动前检查", subtitle = "错误会阻止启动，警告不会") {
        issues.forEach { issue ->
            val color = if (issue.severity == Severity.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (issue.severity == Severity.ERROR) "错误" else "警告",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                Text(
                    issue.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun ArgvSection(argv: List<String>) {
    // QEMU 命令预览：只显示上次启动的真实参数；没有 argv 时整节不出现，
    // 绝不展示编造的参数。
    if (argv.isEmpty()) return
    SectionCard(
        title = "QEMU 启动参数预览",
        subtitle = "上次启动的实际参数（只读），供对照检查",
    ) {
        Text(
            argv.joinToString(" "),
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 草稿摘要 chips（向导汇总页顶部） ----

@Composable
fun DraftSummaryChips(draft: VmConfig) {
    val isApple = draft.platform == VmPlatform.APPLE_T8030
    val isAndroid = draft.platform == VmPlatform.ANDROID_ARM64
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpecChip(draft.platform.label)
        SpecChip("${draft.cpuCores} vCPU")
        SpecChip("${draft.memoryMb} MB")
        if (!isApple && !isAndroid) {
            SpecChip("${draft.diskSizeGb} GB")
            SpecChip(draft.displayDevice.label)
            SpecChip(
                when {
                    draft.usbDisabled -> "USB 已禁用"
                    draft.usbInput -> "USB 输入"
                    else -> "virtio 输入"
                },
            )
            if (draft.driverIsoPath != null) SpecChip("驱动盘已挂")
        }
    }
}

// ---- 表单小件（从旧 CreateVmScreen 搬过来） ----

@Composable
fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            // A config loaded from disk may exceed the slider range (e.g. edited
            // externally); Compose throws on out-of-range values.
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.toInt()) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/**
 * One labelled row of mutually exclusive options.
 *
 * Generic on purpose: the boot-related settings are a mix of enums, Booleans and
 * nullable Booleans (null = "follow the launch profile"), and each of them used
 * to be a hardcoded constant. Keeping one widget means adding a knob is one line.
 */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, text) ->
                if (value == selected) {
                    Button(onClick = { }) { Text(text) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }) { Text(text) }
                }
            }
        }
    }
}
