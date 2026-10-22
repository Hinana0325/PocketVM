package dev.pocketvm.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun ConsoleScreen(
    state: UiState,
    vm: VmConfig,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onEject: () -> Unit,
    onSendKey: () -> Unit,
    onBack: () -> Unit,
) {
    val sessionRuntime = state.runtime
    // The session is global, but this screen shows one VM. Without this filter,
    // opening VM B's console while A is running showed A's PID and called B's
    // VNC port "listening".
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
    val clipboard = LocalClipboardManager.current
    val logScroll = rememberScrollState()
    val usedBytes = state.diskUsage[vm.id] ?: 0L

    LaunchedEffect(copied, copiedError) {
        if (copied || copiedError) {
            delay(1500)
            copied = false
            copiedError = false
        }
    }
    // A console that does not follow its own output is useless during setup.
    LaunchedEffect(state.log, followLog) {
        if (followLog && state.log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    BackHandler(onBack = onBack)

    // LazyColumn instead of a scrolling Column: the log pane scrolls on its own,
    // and nesting two same-axis scroll containers made the page fight the log.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column {
                    Text(vm.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${vm.cpuCores} vCPU · ${vm.memoryMb} MB · ${vm.diskSizeGb} GB · TCG",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

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
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(errorReport(runtime, state.log)))
                            copiedError = true
                        },
                    ) { Text(if (copiedError) "已复制" else "复制错误") }
                    TextButton(onClick = onRefresh) { Text("刷新日志") }
                }

                KeyValueRow("虚拟盘", "已用 ${usedBytes / (1024 * 1024)} MB / ${vm.diskSizeGb} GB")
                KeyValueRow(
                    "控制器",
                    if (vm.storageController.name == "NVME") "NVMe + USB CD" else "virtio-blk + virtio-scsi",
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
                    TextButton(onClick = onSendKey) { Text("发送回车") }
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

        // Offered once the disk has clearly been written to: at that point the
        // ISO is only a boot-order obstacle, and leaving it attached is how
        // people end up booting the installer again.
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
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("$vncHost:${vm.vncPort}"))
                            copied = true
                        },
                    ) { Text(if (copied) "已复制" else "复制") }
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
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
                Text(
                    "协议 RFB 3.8。局域网模式下 VNC 认证是 DES（8 字符密钥），只适合可信网络；" +
                        "需要更强隔离时用「仅本机」+ 端口转发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    TextButton(onClick = { showArgv = !showArgv }) {
                        Text(if (showArgv) "隐藏启动参数" else "查看启动参数")
                    }
                    TextButton(onClick = { followLog = !followLog }) {
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
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                Text(
                    state.log.ifBlank {
                        // QEMU is silent on success; an empty pane is normal and
                        // used to look like "it died immediately".
                        if (running) "（QEMU 正常运行时不会输出内容，画面请用 VNC 客户端连接）"
                        else "（暂无输出）"
                    },
                    style = MonoTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LOG_HEIGHT_DP.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
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
