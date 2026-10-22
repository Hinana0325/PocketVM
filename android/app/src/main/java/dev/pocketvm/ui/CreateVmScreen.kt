package dev.pocketvm.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.config.Severity
import dev.pocketvm.core.model.StorageController
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.core.model.VmConfig
import java.io.File

@Composable
fun CreateVmScreen(
    state: UiState,
    onUpdate: ((VmConfig) -> VmConfig) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onImportIso: (Uri) -> Unit,
    onImportFirmware: (Uri) -> Unit,
) {
    val draft = state.draft ?: return
    var firmwareMenu by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }

    // Port validity is needed both by the field and by the save button.
    var portText by remember(draft.vncPort) { mutableStateOf(draft.vncPort.toString()) }
    val portValue = portText.toIntOrNull()
    val portInvalid = portValue == null || portValue !in 5900..5999

    val isoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportIso)
    }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportFirmware)
    }

    // Backing out of the wizard used to throw away every edit without asking.
    val requestBack: () -> Unit = {
        if (state.hasUnsavedChanges && state.importing == null) discardDialog = true else onBack()
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
                        onBack()
                    },
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { discardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = requestBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text(
                    if (state.editing) "编辑虚拟机" else "新建虚拟机",
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (state.editing) {
                    Text(
                        "修改配置不会影响已安装的系统；运行中请先停止",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(title = "基本配置") {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { name -> onUpdate { it.copy(name = name.take(60)) } },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SliderRow(
                label = "CPU 核心",
                value = draft.cpuCores.toFloat(),
                valueRange = 1f..8f,
                steps = 6,
                valueText = "${draft.cpuCores} vCPU",
                onChange = { cores -> onUpdate { it.copy(cpuCores = cores) } },
            )
            SliderRow(
                label = "内存",
                value = draft.memoryMb.toFloat(),
                valueRange = 1024f..8192f,
                steps = 13,
                valueText = "${draft.memoryMb} MB",
                onChange = { mem -> onUpdate { it.copy(memoryMb = (mem / 512).toInt() * 512) } },
            )
            SliderRow(
                label = "磁盘容量",
                value = draft.diskSizeGb.toFloat(),
                valueRange = 32f..256f,
                steps = 13,
                valueText = "${draft.diskSizeGb} GB",
                onChange = { gb -> onUpdate { it.copy(diskSizeGb = (gb / 16).toInt() * 16) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(64, 128, 256).forEach { size ->
                    OutlinedButton(onClick = { onUpdate { it.copy(diskSizeGb = size) } }) {
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
                Text(
                    "当前磁盘实际占用 ${used / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "设备内存 ${state.deviceMemoryMb} MB · 剩余存储 ${state.freeSpaceMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "UEFI 固件",
            subtitle = "aarch64 需要 edk2 pflash 镜像；变量存储会按固件大小自动创建",
        ) {
            Box {
                OutlinedButton(onClick = { firmwareMenu = true }, modifier = Modifier.fillMaxWidth()) {
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
                                onUpdate { it.copy(firmwareCodePath = path) }
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { firmwarePicker.launch(arrayOf("*/*")) }) {
                Text("从文件管理器导入 QEMU_EFI.fd")
            }
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
                    Box {
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
                                        onUpdate { it.copy(isoPath = path, bootFromCd = true) }
                                    },
                                )
                            }
                        }
                    }
                }
                if (draft.isoPath != null) {
                    TextButton(
                        onClick = { onUpdate { it.copy(isoPath = null) } },
                        enabled = state.importing == null,
                    ) { Text("移除") }
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

        SectionCard(
            title = "VNC 服务",
            subtitle = "画面由 QEMU 自带的 VNC 服务提供，本 App 只负责启动与监督",
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = { input ->
                    portText = input.filter { it.isDigit() }.take(4)
                    portText.toIntOrNull()?.let { port ->
                        // Only commit a complete, in-range port; showing "58" as
                        // accepted while the config kept 5900 was a lie.
                        if (port in 5900..5999) onUpdate { it.copy(vncPort = port) }
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
                listOf(
                    VncAccess.LOOPBACK to "仅本机",
                    VncAccess.LAN to "局域网",
                ).forEach { (mode, label) ->
                    if (draft.vncAccess == mode) {
                        Button(onClick = { }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onUpdate { it.copy(vncAccess = mode) } }) { Text(label) }
                    }
                }
            }

            OutlinedTextField(
                value = draft.vncPassword.orEmpty(),
                onValueChange = { value -> onUpdate { it.copy(vncPassword = value.take(8).ifBlank { null }) } },
                label = { Text("VNC 密码（最多 8 位，RFB 限制）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                if (draft.vncAccess == VncAccess.LAN) {
                    val ip = state.localIp ?: "设备局域网 IP"
                    "外部客户端直接连 $ip:${draft.vncPort}。密码为空时任何同网设备都能接管画面与键盘。"
                } else {
                    "只监听 127.0.0.1。外部查看先执行 adb forward tcp:${draft.vncPort} tcp:${draft.vncPort}，" +
                        "再连 127.0.0.1:${draft.vncPort}。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "磁盘控制器",
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
                        OutlinedButton(onClick = { onUpdate { it.copy(storageController = mode) } }) {
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

        if (state.issues.isNotEmpty()) {
            SectionCard(title = "启动前检查", subtitle = "错误会阻止启动，警告不会") {
                state.issues.forEach { issue ->
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

        // Saving is always allowed: a config without firmware/ISO is still worth
        // keeping, and VmSession refuses to start it anyway.
        if (state.importing != null) {
            Text(
                "正在复制安装镜像，请等待完成后再保存…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(
            onClick = onSave,
            enabled = state.importing == null && !portInvalid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.editing) "保存修改" else "保存")
        }
    }
}

@Composable
private fun SliderRow(
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
