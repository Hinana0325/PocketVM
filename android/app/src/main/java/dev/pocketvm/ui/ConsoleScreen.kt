package dev.pocketvm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.core.model.VncAccess
import dev.pocketvm.ui.theme.MonoTextStyle
import kotlinx.coroutines.delay

private const val LOG_HEIGHT_DP = 360
private const val SERIAL_HEIGHT_DP = 220

@Composable
fun ConsoleScreen(
    state: UiState,
    vm: VmConfig,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onEject: () -> Unit,
    onSendKey: () -> Unit,
    onBootFromShell: () -> Unit,
    onBack: () -> Unit,
) {
    val sessionRuntime = state.runtime
    val runtime = when (val r = sessionRuntime) {
        is VmRuntimeState.Running -> if (r.vmId == vm.id) r else VmRuntimeState.Idle
        is VmRuntimeState.Preparing -> if (r.vmId == vm.id) r else VmRuntimeState.Idle
        is VmRuntimeState.Stopping -> if (r.vmId == vm.id) r else VmRuntimeState.Idle
        is VmRuntimeState.Exited -> if (r.vmId == vm.id) r else VmRuntimeState.Idle
        is VmRuntimeState.Failed -> if (r.vmId == null || r.vmId == vm.id) r else VmRuntimeState.Idle
        VmRuntimeState.Idle -> VmRuntimeState.Idle
    }
    val running = runtime is VmRuntimeState.Running
    val otherRunning = (sessionRuntime as? VmRuntimeState.Running)?.takeIf { it.vmId != vm.id }
    var showArgv by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var copiedError by remember { mutableStateOf(false) }
    var followLog by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val logScroll = rememberScrollState()
    val serialScroll = rememberScrollState()
    val usedBytes = state.diskUsage[vm.id] ?: 0L

    LaunchedEffect(copied, copiedError) {
        if (copied || copiedError) {
            delay(1500)
            copied = false
            copiedError = false
        }
    }
    LaunchedEffect(state.log, followLog) {
        if (followLog && state.log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }
    LaunchedEffect(state.serial) {
        if (state.serial.isNotEmpty()) serialScroll.scrollTo(serialScroll.maxValue)
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = vm.name,
            subtitle = "${vm.cpuCores} vCPU · ${vm.memoryMb} MB · ${vm.diskSizeGb} GB · TCG",
            showBack = true,
            onBack = onBack,
            actions = {
                TextButton(onClick = { onRefresh() }) { Text("刷新") }
            },
        )

        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("概览") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("日志") })
        }

        when (tab) {
            0 -> OverviewTab(
                state = state,
                vm = vm,
                runtime = runtime,
                running = running,
                otherRunning = otherRunning,
                copiedError = copiedError,
                copiedVnc = copied,
                onCopyVnc = {
                    val host = if (vm.vncAccess == VncAccess.LAN) (state.localIp ?: "0.0.0.0") else "127.0.0.1"
                    clipboard.setText(AnnotatedString("$host:${vm.vncPort}"))
                    copied = true
                },
                onStart = onStart,
                onStop = onStop,
                onExport = onExport,
                onEject = onEject,
                onSendKey = onSendKey,
                onBootFromShell = onBootFromShell,
                onCopyError = {
                    clipboard.setText(AnnotatedString(errorReport(runtime, state.log)))
                    copiedError = true
                },
            )
            else -> LogsTab(
                state = state,
                vm = vm,
                running = running,
                showArgv = showArgv,
                followLog = followLog,
                logScroll = logScroll,
                serialScroll = serialScroll,
                onToggleArgv = { showArgv = !showArgv },
                onToggleFollow = { followLog = !followLog },
            )
        }
    }
}

@Composable
private fun OverviewTab(
    state: UiState,
    vm: VmConfig,
    runtime: VmRuntimeState,
    running: Boolean,
    otherRunning: VmRuntimeState.Running?,
    copiedError: Boolean,
    copiedVnc: Boolean,
    onCopyVnc: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onEject: () -> Unit,
    onSendKey: () -> Unit,
    onBootFromShell: () -> Unit,
    onCopyError: () -> Unit,
) {
    val usedBytes = state.diskUsage[vm.id] ?: 0L
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "运行状态") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        text = when (runtime) {
                            is VmRuntimeState.Running ->
                                if (runtime.pid > 0) "运行中 · PID ${runtime.pid}" else "运行中"
                            is VmRuntimeState.Preparing -> runtime.step
                            is VmRuntimeState.Stopping -> "正在关机"
                            is VmRuntimeState.Exited -> "已退出 (exit=${runtime.exitCode})"
                            is VmRuntimeState.Failed -> "启动失败"
                            VmRuntimeState.Idle -> "未运行"
                        },
                        tone = when (runtime) {
                            is VmRuntimeState.Running -> Tone.Ok
                            is VmRuntimeState.Preparing, is VmRuntimeState.Stopping -> Tone.Busy
                            is VmRuntimeState.Exited, is VmRuntimeState.Failed -> Tone.Error
                            VmRuntimeState.Idle -> Tone.Idle
                        },
                    )
                    TextButton(onClick = onCopyError) { Text(if (copiedError) "已复制" else "复制错误") }
                }

                KeyValueRow("虚拟盘", "已用 ${usedBytes / (1024 * 1024)} MB / ${vm.diskSizeGb} GB")
                KeyValueRow(
                    "光驱 / 固件",
                    "${vm.cdTransport.label} · ${vm.firmwareCodePath?.substringAfterLast('/') ?: "内置"}" +
                        if (vm.driverIsoPath != null) " · 驱动盘已挂" else "",
                )
                KeyValueRow(
                    "控制器",
                    if (vm.storageController.name == "NVME") "虚拟盘 NVMe · 光驱 AHCI"
                    else "虚拟盘 virtio-blk · 光驱 AHCI",
                )
                KeyValueRow(
                    "启动模式",
                    when (vm.launchMode) {
                        LaunchMode.AUTO -> "自动（标准 → 兼容 → 最小）"
                        LaunchMode.STANDARD -> "标准（多线程 TCG）"
                        LaunchMode.COMPAT -> "兼容（单线程 TCG / cortex-a76）"
                        LaunchMode.MINIMAL -> "最小（无网络 / 无 rng）"
                    },
                )

                (runtime as? VmRuntimeState.Running)?.let { live ->
                    KeyValueRow("引导阶段", live.stage.label)
                    Text(
                        live.stage.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                (runtime as? VmRuntimeState.Failed)?.let { failed ->
                    Text(failed.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    failed.hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                (runtime as? VmRuntimeState.Exited)?.hint?.let { hint ->
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = if (running) onStop else onStart,
                        enabled = runtime !is VmRuntimeState.Preparing &&
                            runtime !is VmRuntimeState.Stopping &&
                            otherRunning == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            if (running) Icons.Filled.Clear else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (running) "停止" else "启动", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = onExport) { Text("导出诊断包") }
                    TextButton(onClick = onSendKey) { Text("应答引导提示") }
                }
                otherRunning?.let {
                    Text(
                        "另一台虚拟机正在运行，需先停止才能启动这一台。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (vm.isoPath != null && usedBytes > 4L * 1024 * 1024 * 1024) {
            item {
                SectionCard(
                    title = "安装完成了吗？",
                    subtitle = "虚拟盘已写入 ${usedBytes / (1024 * 1024 * 1024)} GB，系统看起来已经装好",
                ) {
                    Text(
                        "卸载 ${vm.isoPath.substringAfterLast('/')} 后，之后每次都直接从虚拟磁盘启动，" +
                            "不会再看到安装程序。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onEject, enabled = !state.busy && otherRunning == null) {
                            Text(if (running) "卸载并重启" else "卸载安装介质")
                        }
                    }
                }
            }
        }

        if (vm.isoPath != null && usedBytes <= 4L * 1024 * 1024 * 1024) {
            item {
                SectionCard(
                    title = "引导卡在固件？",
                    subtitle = "选完启动项后黑屏 / 固件进度条不动 / Display output is not active",
                ) {
                    Text(
                        "选完启动项后长时间黑屏、或屏幕出现「Display output is not active.」" +
                            "（这是 QEMU 的占位画面：客户机把显示输出关掉后没有恢复），排查顺序：\n" +
                            "① 串口日志里 starting Boot0001 之后是否仍有输出——有就是内核还在启动，TCG 下先等 3-5 分钟；\n" +
                            "② USB 在本机必然崩溃：保持「光驱总线=自动」启动一次，App 会自动记住" +
                            "virtio-scsi + virtio 输入（光驱和输入一起回退），之后不再走 USB；\n" +
                            "③ Windows 在 virtio-scsi 下读不到安装文件——挂一张含 VirtIO 驱动（vioscsi）的" +
                            "AHCI 驱动盘，在 WinPE 里「加载驱动」后再装；\n" +
                            "④ 仍黑屏则把固件换回内置 2024.02 原版（QEMU_EFI.fd）重试，隔离固件回归。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            val vncHost = if (vm.vncAccess == VncAccess.LAN) (state.localIp ?: "0.0.0.0") else "127.0.0.1"
            SectionCard(
                title = "VNC 服务",
                subtitle = "App 不渲染画面，用外部 RFB 客户端连接",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        text = if (running) "监听中" else "未启动",
                        tone = if (running) Tone.Ok else Tone.Idle,
                    )
                    Text(
                        if (vm.vncAccess == VncAccess.LAN) "0.0.0.0" else "127.0.0.1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("地址  $vncHost:${vm.vncPort}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCopyVnc) { Text(if (copiedVnc) "已复制" else "复制") }
                }
                KeyValueRow("Display", ":${vm.vncPort - 5900}")
                KeyValueRow("密码", vm.vncPassword?.takeIf { it.isNotBlank() } ?: "未设置")
                if (vm.vncAccess == VncAccess.LOOPBACK) {
                    Text(
                        "adb forward tcp:${vm.vncPort} tcp:${vm.vncPort}",
                        style = MonoTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
                Text(
                    "协议 RFB 3.8。局域网模式下 VNC 认证是 DES（8 字符密钥），只适合可信网络；" +
                        "需要更强隔离时用「仅本机」+ 端口转发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "两种「未激活」别混淆：① 屏幕正中出现固件消息「Display output is not active.」" +
                        "——这是引导交接后的显示输出问题，与激活无关，见上方排查卡；" +
                        "② 部分 VNC 客户端（尤其商业版）会在画面角落叠加试用遮罩——" +
                        "那是客户端自身水印，与目标系统无关。换用免费客户端即可去除：" +
                        "aVNC（开源）、bVNC Free、RealVNC Viewer 免费版。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running) {
                    Text(
                        "固件若停在 UEFI Shell，App 会通过串口自动输入引导命令，" +
                            "Shell 的回应见「日志」页的「固件输出」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onBootFromShell,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("在 Shell 中引导 Windows") }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(
    state: UiState,
    vm: VmConfig,
    running: Boolean,
    showArgv: Boolean,
    followLog: Boolean,
    logScroll: androidx.compose.foundation.ScrollState,
    serialScroll: androidx.compose.foundation.ScrollState,
    onToggleArgv: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "固件输出（串口）",
                subtitle = if (running) "UEFI 与引导程序的控制台，实时追加" else "未运行",
            ) {
                Text(
                    state.serial.ifBlank {
                        if (running) {
                            "（客户机还没有任何串口输出：固件仍在初始化，或启动项里没有可引导介质）"
                        } else {
                            "（未运行）"
                        }
                    },
                    style = MonoTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SERIAL_HEIGHT_DP.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(serialScroll),
                )
            }
        }

        item {
            SectionCard(title = "QEMU 输出", subtitle = "stdout + stderr 合并，实时追加") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onToggleArgv) {
                        Text(if (showArgv) "隐藏启动参数" else "查看启动参数")
                    }
                    TextButton(onClick = onToggleFollow) {
                        Text(if (followLog) "自动跟随：开" else "自动跟随：关")
                    }
                }
                if (showArgv) {
                    Text(
                        state.argv.joinToString(" \\\n  "),
                        style = MonoTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                Text(
                    state.log.ifBlank {
                        if (running) "（QEMU 正常运行时不会输出内容，画面请用 VNC 客户端连接）"
                        else "（暂无输出）"
                    },
                    style = MonoTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LOG_HEIGHT_DP.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(logScroll),
                )
            }
        }
    }
}

/**
 * Everything needed to diagnose a failed launch, in one clipboard paste:
 * the aggregated per-profile failure reasons plus the raw QEMU output.
 */
private fun errorReport(runtime: VmRuntimeState, log: String): String = buildString {
    appendLine("PocketVM 启动诊断")
    when (runtime) {
        is VmRuntimeState.Failed -> {
            appendLine(runtime.reason)
            runtime.hint?.let { appendLine("提示：$it") }
        }
        is VmRuntimeState.Exited ->
            appendLine("已退出 exit=${runtime.exitCode} ${runtime.hint.orEmpty()}")
        else -> appendLine("状态：$runtime")
    }
    appendLine("--- QEMU 输出尾部 ---")
    append(
        if (log.isNotBlank()) {
            log.takeLast(4000)
        } else if (runtime is VmRuntimeState.Running) {
            "（运行中；QEMU 正常时不输出任何内容，画面请用 VNC 客户端连接）"
        } else {
            "（无输出：进程在产生任何日志之前就退出了）"
        },
    )
}
