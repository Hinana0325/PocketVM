package dev.pocketvm.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Insights
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("性能") })
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
            1 -> LogsTab(
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
            2 -> PerformanceTab(state = state, running = running)
        }
    }
}

/**
 * 性能占用仪表：QEMU 进程在手机上实际占多少。
 *
 * 全部数据来自宿主机侧（`/proc/<pid>` + QMP），不需要客户机里装任何 agent，
 * 因此 Windows 安装过程中也能看。CPU 以「单核的百分比」计——多线程 TCG 下
 * 多个 vCPU 线程并行翻译，总值超过 100% 是常态而非异常。
 */
@Composable
private fun PerformanceTab(
    state: UiState,
    running: Boolean,
) {
    val metrics = state.metrics
    if (!running || metrics == null) {
        EmptyState(
            icon = Icons.Filled.Insights,
            title = "暂无性能数据",
            subtitle = "虚拟机运行中后，这里以 1 秒间隔采样 QEMU 进程的 CPU、每 vCPU 占用、" +
                "内存与磁盘 I/O；数据来自 /proc 与 QMP，不需要客户机里装 agent。",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "CPU 占用", subtitle = "QEMU 进程 · 单核 = 100%") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${metrics.cpuPercent.toInt()}%",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    CpuSparkline(history = metrics.cpuHistory)
                    Text(
                        "超过 100% 是正常的：多线程 TCG 让 ${metrics.vcpuPercents.size.coerceAtLeast(1)} 个" +
                            "vCPU 线程并行翻译代码，各自都能吃满一个核。上图是最近 60 秒的进程总占用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (metrics.vcpuPercents.isNotEmpty()) {
            item {
                SectionCard(title = "每 vCPU 占用", subtitle = "来自 QMP 线程信息") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        metrics.vcpuPercents.forEachIndexed { index, percent ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "vCPU $index",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "${percent.toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                MeterBar(fraction = percent / 100f)
                            }
                        }
                    }
                }
            }
        }

        item {
            val phoneMb = state.deviceMemoryMb
            val rss = metrics.rssMb
            SectionCard(title = "内存", subtitle = "QEMU 进程常驻内存（RSS）") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValueRow("进程 RSS", rss?.let { "$it MB" } ?: "不可用")
                    if (rss != null && phoneMb > 0) {
                        MeterBar(fraction = rss.toFloat() / phoneMb.toFloat())
                        Text(
                            "占手机物理内存（${phoneMb} MB）的 " +
                                "${(rss * 100 / phoneMb)}%。" +
                                "RSS 含 TCG 翻译缓存，未触及的客户机内存不计入，所以可能低于客户机设定值。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (rss == null) {
                        Text(
                            "系统没有提供 /proc 里的 statm/status，读不到 RSS；CPU 与 vCPU 数据不受影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            val read = metrics.readMBs
            val write = metrics.writeMBs
            SectionCard(title = "磁盘 I/O", subtitle = "块层读到/写出的速率") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValueRow("读", read?.let(::formatRate) ?: "不可用")
                    KeyValueRow("写", write?.let(::formatRate) ?: "不可用")
                    Text(
                        if (read == null || write == null) {
                            "这一秒没有可用的 I/O 数据：/proc/<pid>/io 可能被系统禁用，QMP 也没给出块统计。" +
                                "首秒与数据源切换时也会显示「不可用」，下一拍会恢复。"
                        } else {
                            "Windows 安装阶段读 ISO 会持续拉高「读」；写速率突然变大通常是客户机在落盘。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "运行时长", subtitle = "本次启动至今") {
                KeyValueRow("已运行", formatUptime(metrics.uptimeMs))
            }
        }
    }
}

/** 60-second CPU sparkline; flat until the second sample lands. */
@Composable
private fun CpuSparkline(history: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
        if (history.size < 2) return@Canvas
        val max = (history.max() ?: 100f).coerceAtLeast(100f)
        val stepX = size.width / (history.size - 1).toFloat()
        val path = Path().apply {
            history.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - (value / max * size.height).coerceIn(0f, size.height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun formatRate(mbPerSecond: Float): String =
    String.format(java.util.Locale.US, "%.1f MB/s", mbPerSecond)

private fun formatUptime(ms: Long): String {
    val totalSeconds = ms / 1000
    return if (totalSeconds >= 3600) {
        String.format(
            java.util.Locale.US,
            "%d:%02d:%02d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
        )
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
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
                            "（QEMU 占位画面：客户机关闭显示输出后没恢复；社区确认这是引导器→登录界面间的正常过渡，不是故障），" +
                            "排查顺序：\n" +
                            "① 串口日志里 starting Boot0001 之后是否仍有输出——有就是系统还在启动，TCG 下先等 5-10 分钟；\n" +
                            "② 黑屏时先在 VNC 客户端里按 Ctrl+Alt+F1 强制切 TTY，画面通常会回来；\n" +
                            "③ USB 光驱在本机会崩：保持「光驱总线=自动」启动一次，App 会自动改成" +
                            "virtio-scsi + AHCI，键鼠仍走 USB（实测方向键可用）；只有同一档再次崩溃" +
                            "才会把输入降为 virtio，而本机 virtio 键盘收不到按键——需要键盘时请在编辑页" +
                            "把输入设备改回 USB；\n" +
                            "④ Windows 在 virtio-scsi 下读不到安装文件：挂 VirtIO 驱动盘（virtio-win），" +
                            "在 WinPE 的「加载驱动」里进 驱动盘盘符 → vioscsi → w11 → ARM64" +
                            "（认准含 vioscsi.inf 的目录）；挂了驱动盘时光盘只走 virtio-scsi，" +
                            "两个 ISO 设备同屏会让 Windows 安装冻结；\n" +
                            "⑤ 仍黑屏就确认编辑页「显示设备」是 ramfb（默认值）：virtio-gpu 的 GOP " +
                            "没有线性帧缓冲，Windows 引导管理器会写空指针 Data Abort；ramfb 固定 " +
                            "800x600，可在固件界面按 F10 进 Setup 调分辨率（会写入变量存储，重启保留）；\n" +
                            "⑥ 以上无效再把固件换回内置 2024.02 原版（QEMU_EFI.fd）重试，隔离固件回归。",
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
                if (vm.portForwards.isNotEmpty()) {
                    Text(
                        "端口转发（手机 → 客户机）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    vm.portForwards.forEach { forward ->
                        KeyValueRow(
                            if (vm.vncAccess == VncAccess.LAN) {
                                "${state.localIp ?: "0.0.0.0"}:${forward.hostPort}"
                            } else {
                                "127.0.0.1:${forward.hostPort}"
                            },
                            "→ 客户机 :${forward.guestPort}",
                        )
                    }
                    Text(
                        if (vm.vncAccess == VncAccess.LOOPBACK) {
                            "只监听 127.0.0.1：电脑上执行 adb forward tcp:<手机端口> tcp:<手机端口> 后，" +
                                "连接 127.0.0.1:<手机端口>。"
                        } else {
                            "监听 0.0.0.0：同一局域网内直接连 手机IP:<手机端口>。" +
                                "转发的服务仍需各自的认证（RDP/SSH 密码等）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "两种「未激活」别混淆：① 屏幕正中出现「Display output is not active.」" +
                        "——这是 QEMU 的占位画面（引导交接后显示输出没恢复），与激活无关；" +
                        "先按 Ctrl+Alt+F1 强制切 TTY，多半能恢复，详见上方排查卡；" +
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
