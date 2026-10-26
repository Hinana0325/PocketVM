package dev.pocketvm.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.config.Severity
import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.PortForward
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
    onImportDriverIso: (Uri) -> Unit,
    onImportFirmware: (Uri) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val draft = state.draft ?: return
    var firmwareMenu by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    // The boot knobs are a fallback surface, not something to wade through on
    // every edit, so they stay folded until something actually goes wrong.
    var advanced by remember { mutableStateOf(false) }

    // Port validity is needed both by the field and by the save button.
    var portText by remember(draft.vncPort) { mutableStateOf(draft.vncPort.toString()) }
    val portValue = portText.toIntOrNull()
    val portInvalid = portValue == null || portValue !in 5900..5999

    // hostfwd editor state. Kept as text so a half-typed port never reaches the
    // config as 0; only a complete in-range pair commits.
    var fwdHostText by remember { mutableStateOf("") }
    var fwdGuestText by remember { mutableStateOf("") }
    val fwdHostValue = fwdHostText.toIntOrNull()
    val fwdGuestValue = fwdGuestText.toIntOrNull()
    val fwdValid = fwdHostValue != null && fwdGuestValue != null &&
        fwdHostValue in 1024..65535 && fwdGuestValue in 1..65535 &&
        draft.portForwards.none { it.hostPort == fwdHostValue }

    val isoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportIso)
    }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportFirmware)
    }
    val driverIsoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportDriverIso)
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

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = if (state.editing) "编辑虚拟机" else "新建虚拟机",
            subtitle = if (state.editing) "修改配置不会影响已安装的系统；运行中请先停止" else null,
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
                                    onUpdate { draft ->
                                        draft.copy(
                                            isoPath = path,
                                            bootFromCd = true,
                                            name = if (draft.name == VmConfig.DEFAULT_NAME || draft.name.isBlank())
                                                VmConfig.nameFromIso(path) ?: draft.name
                                            else draft.name,
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
                            onClick = { onUpdate { it.copy(driverIsoPath = null) } },
                            enabled = state.importing == null,
                        ) { Text("移除") }
                    }
                }
                Text(
                    "用 Fedora 的 virtio-win 驱动盘（含 vioscsi、viostor 等）。安装时 Windows 停在「找不到驱动程序」" +
                        "或读不到安装文件时，点「加载驱动程序」指向这张 AHCI 盘里的 vioscsi（如 w11arm64 目录）即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        )
                        TextButton(
                            onClick = {
                                onUpdate { it.copy(portForwards = it.portForwards - forward) }
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
                            onUpdate {
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
                                    onUpdate {
                                        it.copy(portForwards = it.portForwards + PortForward(hostPort, guestPort))
                                    }
                                }
                            },
                        ) { Text("$label $guestPort") }
                    }
                }

                Text(
                    if (draft.vncAccess == VncAccess.LAN) {
                        "「仅本机」模式下转发只监听 127.0.0.1：以远程桌面预设为例，" +
                            "电脑上先 adb forward tcp:5389 tcp:5389，再连 127.0.0.1:5389。" +
                            "「局域网」模式下直接连 手机IP:手机端口（与 VNC 同一敞口策略，服务自身的认证仍是第一道门）。" +
                            "Android 应用无法绑定 1024 以下端口，故手机侧从 1024 起。"
                    } else {
                        "转发只监听 127.0.0.1：以远程桌面预设为例，电脑上先 adb forward tcp:5389 tcp:5389，" +
                            "再连 127.0.0.1:5389。需要真正局域网可达时，把上方访问方式切到「局域网」。" +
                            "Android 应用无法绑定 1024 以下端口，故手机侧从 1024 起。"
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

            SectionCard(
                title = "启动模式",
                subtitle = "标准模式需要较大的 TCG 代码缓存；部分芯片/系统上分配不到时会启动即退出",
            ) {
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
                                    onClick = { onUpdate { it.copy(launchMode = mode) } },
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
            }

            // Everything that decides whether Windows can be booted at all, exposed
            // instead of hardcoded: these are the knobs a device-specific failure
            // usually comes down to.
            SectionCard(
                title = "引导相关（可选）",
                subtitle = if (advanced) "出问题才需要改这里" else "默认组合已适配多数机型",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "收起" else "展开引导选项")
                    }
                }
                if (!advanced) {
                    // Collapsed summary: enough to confirm what a VM is actually
                    // booting with, without reading seven separate controls.
                    Text(
                        "光驱 ${if (draft.usbDisabled) "virtio-scsi+AHCI（USB 已禁用）" else draft.cdTransport.label}" +
                            if (draft.driverIsoPath != null) " · 驱动盘已挂" else "" +
                            if (draft.portForwards.isNotEmpty()) " · 转发 ${draft.portForwards.size} 个端口" else "" +
                            " · 输入 ${if (draft.usbDisabled || !draft.usbInput) "virtio" else "USB"} · " +
                            "显示 ${draft.displayDevice.label} · " +
                            "串口恢复 ${if (draft.serialRecovery) "开" else "关"} · " +
                            "CPU ${draft.cpuModel ?: "跟随档位"} · 提取镜像 ${if (draft.useBootImage) "开" else "关"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (advanced) {
                    ChoiceRow(
                        label = "USB 总线",
                        options = listOf(false to "启用", true to "禁用"),
                        selected = draft.usbDisabled,
                        onSelect = { value -> onUpdate { it.copy(usbDisabled = value) } },
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
                            onSelect = { value -> onUpdate { it.copy(cdTransport = value) } },
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
                            onSelect = { value -> onUpdate { it.copy(usbInput = value) } },
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
                        onSelect = { value -> onUpdate { it.copy(displayDevice = value) } },
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
                        onSelect = { value -> onUpdate { it.copy(serialRecovery = value) } },
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
                        onSelect = { value -> onUpdate { it.copy(enableNet = value) } },
                    )
                    ChoiceRow(
                        label = "virtio-rng",
                        options = listOf(null to "跟随档位", true to "开", false to "关"),
                        selected = draft.rng,
                        onSelect = { value -> onUpdate { it.copy(rng = value) } },
                    )

                    ChoiceRow(
                        label = "提取引导镜像",
                        options = listOf(false to "关（推荐）", true to "开"),
                        selected = draft.useBootImage,
                        onSelect = { value -> onUpdate { it.copy(useBootImage = value) } },
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
                        onSelect = { value -> onUpdate { it.copy(cpuModel = value) } },
                    )
                }
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

/**
 * One labelled row of mutually exclusive options.
 *
 * Generic on purpose: the boot-related settings are a mix of enums, Booleans and
 * nullable Booleans (null = "follow the launch profile"), and each of them used
 * to be a hardcoded constant. Keeping one widget means adding a knob is one line.
 */
@Composable
private fun <T> ChoiceRow(
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
