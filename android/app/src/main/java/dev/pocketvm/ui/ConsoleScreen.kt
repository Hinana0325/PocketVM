package dev.pocketvm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.runtime.SshDefaults
import dev.pocketvm.ui.theme.Danger
import dev.pocketvm.ui.theme.Success
import dev.pocketvm.runtime.SshState
import dev.pocketvm.ui.theme.MonoTextStyle
import kotlinx.coroutines.delay

private const val LOG_HEIGHT_DP = 360
private const val SERIAL_HEIGHT_DP = 220

/**
 * 运行中虚拟机的控制台（Stitch 布局重写）。
 *
 * 版式顺序照 Stitch 的控制台设计：顶栏（名称 + 状态 + 运行计时）→ 显示通道横幅 →
 * VNC 画面区 → 输入工具栏 → 实时负载 → 控制指令 → 日志速览；SSH 与诊断仍是独立 Tab。
 * 颜色只用 MaterialTheme.colorScheme（灰白主题），不用 Stitch 的深色 Titanium Slate。
 *
 * 几个 Stitch 里的假东西在这里是诚实版，原因写在对应位置：
 * - PocketVM 没有内置 VNC 画面渲染，「桌面预览」只是一个说明占位 + 外部客户端连接信息，
 *   绝不画假的 Windows 桌面或任务管理器。
 * - 显示是 VNC（RFB 3.8，127.0.0.1），不是 Spice；QEMU 11.1.2 TCG，没有分辨率/帧率/延迟数据。
 * - 没有温度、功耗、ZRAM 数据，没有重启与快照功能——都没有画出来或写了说明。
 */
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
    onGuestKey: (String, String) -> Unit,
    onGuestCombo: (List<String>) -> Unit,
    onGuestText: (String) -> Unit,
    onBootFromShell: () -> Unit,
    onSshConnect: (String, Int, String, String) -> Unit,
    onSshDisconnect: () -> Unit,
    onSshRun: (String) -> Unit,
    onSshClear: () -> Unit,
    onSshOpenShell: () -> Unit,
    onSshCloseShell: () -> Unit,
    onSshShellSend: (String) -> Unit,
    onSshShellCtrlC: () -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnection: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit = {},
) {
    BackHandler { onBack() }
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
    // SSH 表单放在这里而不是 SshTab 内部：切到别的 Tab 时 SshTab 会离开组合，
    // 用户名/密码/命令输入会丢；ConsoleScreen 本身在整个页面生命周期内都在。
    val sshForwardPort = remember(vm.id, vm.portForwards) { SshDefaults.hostPort(vm.portForwards) }
    val sshForm = remember(vm.id) { SshFormState(sshForwardPort ?: 2222) }
    val clipboard = LocalClipboardManager.current
    val logScroll = rememberScrollState()
    val serialScroll = rememberScrollState()
    // 运行计时：每秒一拍。计时文本是顶栏里唯一的逐秒变化量。
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val liveRunning = runtime as? VmRuntimeState.Running
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val shortStatus = when (runtime) {
        is VmRuntimeState.Running -> "运行中"
        is VmRuntimeState.Preparing -> "启动中"
        is VmRuntimeState.Stopping -> "停止中"
        is VmRuntimeState.Exited -> "已退出"
        is VmRuntimeState.Failed -> "失败"
        VmRuntimeState.Idle -> "未运行"
    }
    val statusTone = when (runtime) {
        is VmRuntimeState.Running -> Tone.Ok
        is VmRuntimeState.Preparing, is VmRuntimeState.Stopping -> Tone.Busy
        is VmRuntimeState.Exited, is VmRuntimeState.Failed -> Tone.Error
        VmRuntimeState.Idle -> Tone.Idle
    }
    val canToggle = !state.busy &&
        runtime !is VmRuntimeState.Preparing &&
        runtime !is VmRuntimeState.Stopping &&
        (running || otherRunning == null)

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
            subtitle = if (vm.platform == VmPlatform.APPLE_T8030) {
                "${vm.cpuCores} vCPU · ${vm.memoryMb} MB · iPhone 11 · TCG"
            } else {
                "${vm.cpuCores} vCPU · ${vm.memoryMb} MB · ${vm.diskSizeGb} GB · TCG"
            },
            showBack = true,
            onBack = onBack,
            actions = {
                if (running && liveRunning != null) {
                    Text(
                        formatUptime((nowMs - liveRunning.startedAtEpochMs).coerceAtLeast(0L)),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(text = shortStatus, tone = statusTone)
                IconButton(onClick = { onRefresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            },
        )

        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Filled.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("概览") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("日志") },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                icon = { Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("性能") },
            )
            Tab(
                selected = tab == 3,
                onClick = { tab = 3 },
                icon = { Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("SSH") },
            )
        }

        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(initialScale = 0.98f))
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "consoleTab",
            modifier = Modifier.weight(1f),
        ) { activeTab ->
            when (activeTab) {
            0 -> OverviewTab(
                state = state,
                vm = vm,
                runtime = runtime,
                running = running,
                otherRunning = otherRunning,
                copiedError = copiedError,
                copiedVnc = copied,
                onCopyVnc = {
                    clipboard.setText(AnnotatedString("127.0.0.1:${vm.vncPort}"))
                    copied = true
                },
                onStart = onStart,
                onStop = onStop,
                onExport = onExport,
                onEject = onEject,
                onGuestKey = onGuestKey,
                onGuestCombo = onGuestCombo,
                onGuestText = onGuestText,
                onBootFromShell = onBootFromShell,
                onCopyError = {
                    clipboard.setText(AnnotatedString(errorReport(runtime, state.log)))
                    copiedError = true
                },
                onOpenLogs = { tab = 1 },
                onOpenPerformance = { tab = 2 },
                onOpenSsh = { tab = 3 },
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenConnection = onOpenConnection,
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
            3 -> SshTab(
                state = state,
                vm = vm,
                running = running,
                form = sshForm,
                onConnect = onSshConnect,
                onDisconnect = onSshDisconnect,
                onRun = onSshRun,
                onClear = onSshClear,
                onOpenShell = onSshOpenShell,
                onCloseShell = onSshCloseShell,
                onShellSend = onSshShellSend,
                onShellCtrlC = onSshShellCtrlC,
            )
            }
        }

        // Stitch 的底部吸底操作栏：主操作是启动/停止，次操作是导出诊断包。
        // 「停止」就是真关机（QEMU 进程退出），不是 Stitch 写的「断开连接」——
        // 虚拟机不会在后台继续跑；「全屏沉浸视窗」没有对应物，本应用不内置
        // VNC 画面，无画面可全屏，所以不画这个按钮。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = if (running) onStop else onStart,
                enabled = canToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Text(if (running) "停止" else "启动", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("导出诊断包")
            }
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
                    Sparkline(
                        history = metrics.cpuHistory,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
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

/** 日志速览用的原文尾部：只截行，不改内容。 */
private fun takeLastLines(text: String, count: Int): String =
    text.lines().takeLast(count).joinToString("\n").trimEnd()

/**
 * 概览 Tab：Stitch 控制台的版式顺序，内容全部是真数据。
 *
 * 顶栏的状态与计时在 [ConsoleScreen]；这里依次是安装介质提示、运行状态、
 * 连接信息入口、诊断中心入口、客户机按键、实时负载、控制指令、日志速览，
 * 再加两张条件卡（卸载安装介质 / 引导卡在固件）。
 * VNC 画面区已拆到独立的连接信息页（VmConnectionScreen），诊断已拆到诊断中心。
 */
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
    onGuestKey: (String, String) -> Unit,
    onGuestCombo: (List<String>) -> Unit,
    onGuestText: (String) -> Unit,
    onBootFromShell: () -> Unit,
    onCopyError: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPerformance: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val usedBytes = state.diskUsage[vm.id] ?: 0L
    var guestText by remember { mutableStateOf("") }
    // 输入工具栏的模式：0 方向键 / 1 输入文本。只切换面板显示，按键本身都走 QMP 回调。
    var inputMode by remember { mutableStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val liveRuntime = runtime as? VmRuntimeState.Running
    val cdRunStart = liveRuntime?.startedAtEpochMs ?: 0L
    // A3a 修复：卡片覆盖 FIRMWARE/LOADING/BOOT_PROMPT，阶段前进不自动消失；
    // 只有发送成功（短暂显示 ✓）、手动关闭或 3 分钟安全窗口到期才收起。
    var cdDismissed by remember(vm.id, cdRunStart) { mutableStateOf(false) }
    var cdTappedAt by remember(vm.id, cdRunStart) { mutableStateOf(0L) }
    var cdSuccessSeen by remember(vm.id, cdRunStart) { mutableStateOf(false) }
    var cdNow by remember(vm.id, cdRunStart) { mutableStateOf(System.currentTimeMillis()) }
    val cdFeedbackFresh = cdTappedAt > 0L &&
        state.lastGuestKeyAtEpochMs >= cdTappedAt &&
        state.lastGuestKeyLabel == "Enter"
    LaunchedEffect(running, cdRunStart) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(5_000)
            cdNow = System.currentTimeMillis()
        }
    }
    LaunchedEffect(cdFeedbackFresh, state.lastGuestKeyOk) {
        if (cdFeedbackFresh && state.lastGuestKeyOk == true && !cdSuccessSeen) {
            cdSuccessSeen = true
            delay(1_500)
            cdDismissed = true
        }
    }
    val showCdPrompt = CdPrompt.shouldShow(
        running = running,
        bootFromCd = vm.bootFromCd,
        stage = liveRuntime?.stage,
        startedAtEpochMs = cdRunStart,
        nowMs = cdNow,
        dismissed = cdDismissed,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showCdPrompt) {
            item {
                SectionCard(
                    title = "安装介质提示",
                    subtitle = "CD 启动提示只在 VNC 画面出现",
                    headerEnd = {
                        TextButton(onClick = { cdDismissed = true }) { Text("关闭") }
                    },
                ) {
                    Text(
                        "VNC 画面出现「Press any key to boot from CD or DVD…」时，" +
                            "请在 5 秒内点下面的按钮发送 Enter。卡片会保留到发送成功、" +
                            "手动关闭或 3 分钟后自动收起。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            cdTappedAt = System.currentTimeMillis()
                            onGuestKey("ret", "Enter")
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("发送 Enter", style = MaterialTheme.typography.titleSmall)
                    }
                    if (cdFeedbackFresh) {
                        state.lastGuestKeyOk?.let { ok ->
                            Text(
                                if (ok) "已发送 Enter ✓" else "发送失败 ✗（QMP 通道不可用）",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) Success else Danger,
                            )
                        }
                    }
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
                        modifier = Modifier.weight(1f, false),
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
                    "显示设备",
                    vm.displayDevice.label +
                        if (vm.displayDevice == DisplayDevice.RAMFB) "（Windows 引导必需）"
                        else "（Linux / Android 用）",
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
                        enabled = !state.busy &&
                            runtime !is VmRuntimeState.Preparing &&
                            runtime !is VmRuntimeState.Stopping &&
                            otherRunning == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (running) "停止" else "启动", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = onExport) { Text("导出诊断包") }
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

        item {
            SectionCard(title = "连接信息", subtitle = "VNC 地址、Display、密码、adb forward") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "127.0.0.1:${vm.vncPort}",
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "VNC · RFB 3.8 · 仅本机 · ${vm.displayDevice.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(
                        text = if (running) "监听中" else "未启动",
                        tone = if (running) Tone.Ok else Tone.Idle,
                    )
                }
                OutlinedButton(
                    onClick = onOpenConnection,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("查看连接信息") }
            }
        }

        item {
            SectionCard(title = "诊断中心", subtitle = "看门狗、性能、卡顿、温控与绑核、会话报告") {
                Text(
                    "诊断已从控制台拆出去：健康判定、停摆归因、线程分布、会话报告都在诊断中心。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开诊断中心") }
            }
        }

        if (vm.platform != VmPlatform.APPLE_T8030) {
        item {
            SectionCard(
                title = "客户机按键",
                subtitle = "手机 VNC 客户端没有方向键/功能键时，用这里发给客户机",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Stitch 的输入工具栏：触控板模式、软键盘、Ctrl+Alt+Del、缩放。
                    // 触控板没有真实对应物（没有内置画面可触控），改成方向键盘；
                    // 软键盘对应文本输入（手机键盘键入后经 typeText 发给客户机）；
                    // Ctrl+Alt+Del 直接走组合键回调；缩放没有内置画面可缩放，已去掉。
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (inputMode == 0) {
                            Button(
                                onClick = { inputMode = 0 },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp),
                            ) { Text("方向键", style = MaterialTheme.typography.labelSmall) }
                        } else {
                            OutlinedButton(
                                onClick = { inputMode = 0 },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp),
                            ) { Text("方向键", style = MaterialTheme.typography.labelSmall) }
                        }
                        if (inputMode == 1) {
                            Button(
                                onClick = { inputMode = 1 },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp),
                            ) { Text("输入文本", style = MaterialTheme.typography.labelSmall) }
                        } else {
                            OutlinedButton(
                                onClick = { inputMode = 1 },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp),
                            ) { Text("输入文本", style = MaterialTheme.typography.labelSmall) }
                        }
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onGuestCombo(listOf("ctrl", "alt", "delete"))
                            },
                            enabled = running,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp),
                        ) { Text("Ctrl+Alt+Del", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (inputMode == 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("↑" to "up", "↓" to "down", "←" to "left", "→" to "right").forEach { (label, code) ->
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onGuestKey(code, label)
                                    },
                                    enabled = running,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                ) { Text(label) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Enter" to "ret", "Esc" to "esc", "Tab" to "tab", "空格" to "spc").forEach { (label, code) ->
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onGuestKey(code, label)
                                    },
                                    enabled = running,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("F2" to "f2", "F10" to "f10", "Del" to "delete").forEach { (label, code) ->
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onGuestKey(code, label)
                                    },
                                    enabled = running,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = guestText,
                                onValueChange = { guestText = it.take(200) },
                                label = { Text("键入文本") },
                                singleLine = true,
                                enabled = running,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    onGuestText(guestText)
                                    guestText = ""
                                },
                                enabled = running && guestText.isNotEmpty(),
                            ) { Text("输入") }
                            Button(
                                onClick = {
                                    onGuestText(guestText + "\r")
                                    guestText = ""
                                },
                                enabled = running && guestText.isNotEmpty(),
                            ) { Text("输入+⏎") }
                        }
                    }
                    state.lastGuestKeyLabel?.let { label ->
                        val ok = state.lastGuestKeyOk == true
                        Text(
                            "上次发送：$label " + if (ok) "✓" else "✗（QMP 未连接）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) Success else Danger,
                        )
                    }
                    Text(
                        "按键经 QMP 直接注入客户机，和 VNC 客户端里的键盘等价；" +
                            "方向键用于固件启动菜单，Enter 用于「按任意键从 CD 启动」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }

        item {
            TelemetrySection(
                state = state,
                vm = vm,
                running = running,
                usedBytes = usedBytes,
                onOpenPerformance = onOpenPerformance,
            )
        }

        item {
            SectionCard(title = "控制指令", subtitle = "关机即停止 QEMU 进程") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = if (running) onStop else onStart,
                        enabled = !state.busy &&
                            runtime !is VmRuntimeState.Preparing &&
                            runtime !is VmRuntimeState.Stopping &&
                            otherRunning == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                        Text(if (running) "停止" else "启动", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = onExport) { Text("导出诊断包") }
                }
                OutlinedButton(
                    onClick = onOpenSsh,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开 SSH 终端") }
                if (running) {
                    OutlinedButton(
                        onClick = onBootFromShell,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("在 Shell 中引导 Windows") }
                    Text(
                        "固件若停在 UEFI Shell，App 会通过串口自动输入引导命令，" +
                            "Shell 的回应见「日志」页的「固件输出」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "PocketVM 没有重启与快照功能：重启请先停止再启动；" +
                        "虚拟盘本身就是持久的，不需要快照。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            SectionCard(
                title = "日志速览",
                subtitle = "串口与 QEMU 输出原文 · 完整内容见「日志」页",
                headerEnd = {
                    TextButton(onClick = onOpenLogs) { Text("完整日志") }
                },
            ) {
                // Stitch 的日志行（spice-vdagent、virgl3d）是假的：这里只放
                // state.serial / state.log 的原文尾部，一字不改。
                Text("固件输出（串口）", style = MaterialTheme.typography.titleSmall)
                Text(
                    takeLastLines(state.serial, 8).ifBlank {
                        if (running) {
                            "（客户机还没有任何串口输出：固件仍在初始化，或启动项里没有可引导介质）"
                        } else {
                            "（未运行）"
                        }
                    },
                    style = MonoTextStyle,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
                Text("QEMU 输出", style = MaterialTheme.typography.titleSmall)
                Text(
                    takeLastLines(state.log, 8).ifBlank {
                        if (running) "（QEMU 正常运行时不会输出内容，画面请用 VNC 客户端连接）"
                        else "（暂无输出）"
                    },
                    style = MonoTextStyle,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }
    }
}



/** Stitch 遥测区的 2×2 网格：vCPU、内存、磁盘 I/O、虚拟盘，全部来自 state.metrics 与磁盘用量。 */
@Composable
private fun TelemetrySection(
    state: UiState,
    vm: VmConfig,
    running: Boolean,
    usedBytes: Long,
    onOpenPerformance: () -> Unit,
) {
    val metrics = state.metrics
    SectionCard(
        title = "实时负载",
        subtitle = "宿主机侧采样（/proc + QMP）· 1 秒一拍 · 不需要客户机装 agent",
        headerEnd = {
            TextButton(onClick = onOpenPerformance) { Text("完整性能") }
        },
    ) {
        if (!running || metrics == null) {
            Text(
                "虚拟机运行后，这里显示 QEMU 进程的 CPU、内存与磁盘 I/O；" +
                    "数据来自 /proc 与 QMP，不需要客户机里装 agent。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniCard(
                icon = Icons.Filled.Speed,
                label = "vCPU 占用",
                value = "${metrics.cpuPercent.toInt()}%",
                modifier = Modifier.weight(1f),
            ) {
                Sparkline(
                    history = metrics.cpuHistory,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
                Text(
                    "${metrics.vcpuPercents.size.coerceAtLeast(1)} vCPU · " +
                        (state.env?.soc ?: "宿主 ARM64"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val phoneMb = state.deviceMemoryMb
            val rss = metrics.rssMb
            MiniCard(
                icon = Icons.Filled.Memory,
                label = "内存 RSS",
                value = rss?.let { "$it MB" } ?: "不可用",
                modifier = Modifier.weight(1f),
            ) {
                if (rss != null && phoneMb > 0) {
                    MeterBar(fraction = rss.toFloat() / phoneMb.toFloat())
                    Text(
                        "占手机内存 ${rss * 100 / phoneMb}% · 客户机设定 ${vm.memoryMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        "客户机设定 ${vm.memoryMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniCard(
                icon = Icons.Filled.Storage,
                label = "磁盘读",
                value = metrics.readMBs?.let(::formatRate) ?: "不可用",
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    "写 ${metrics.writeMBs?.let(::formatRate) ?: "不可用"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "块层速率",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniCard(
                icon = Icons.Filled.Folder,
                label = "虚拟盘",
                value = "${usedBytes / (1024 * 1024)} MB",
                modifier = Modifier.weight(1f),
            ) {
                UsageBar(usedBytes = usedBytes, totalGb = vm.diskSizeGb)
            }
        }
        Text(
            "CPU 超过 100% 是正常的：多线程 TCG 让 vCPU 线程并行翻译，各自都能吃满一个核。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 遥测网格里的小卡片：图标 + 名称 + 大数字 + 自定义内容。 */
@Composable
private fun MiniCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        content()
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

/**
 * SSH 控制台：连接客户机里的 sshd 执行命令。
 *
 * 只走 exec 通道，不做终端仿真——目标是「不用 VNC 键盘也能控制 Linux 客户机」，
 * 不是把 App 做成终端模拟器。密码只留在输入框与 SshConsole 的内存里，不落盘。
 */
@Composable
private fun SshTab(
    state: UiState,
    vm: VmConfig,
    running: Boolean,
    form: SshFormState,
    onConnect: (String, Int, String, String) -> Unit,
    onDisconnect: () -> Unit,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
    onOpenShell: () -> Unit,
    onCloseShell: () -> Unit,
    onShellSend: (String) -> Unit,
    onShellCtrlC: () -> Unit,
) {
    if (!running) {
        EmptyState(
            icon = Icons.Filled.Terminal,
            title = "虚拟机未运行",
            subtitle = "启动虚拟机后，这里可以通过 SSH 连接客户机执行命令。需要客户机里已启动 sshd，" +
                "并在编辑页添加「主机端口 → 客户机 22」的端口转发。",
        )
        return
    }

    val forwardPort = SshDefaults.hostPort(vm.portForwards)
    val connected = state.sshState is SshState.Connected
    val outputScroll = rememberScrollState()

    LaunchedEffect(state.sshOutput) {
        if (state.sshOutput.isNotEmpty()) outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "SSH 连接", subtitle = "经 QEMU hostfwd 连接客户机 22") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = form.host,
                            onValueChange = { form.host = it },
                            label = { Text("主机") },
                            singleLine = true,
                            enabled = !connected && !state.sshRunning,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.port,
                            onValueChange = { input -> form.port = input.filter(Char::isDigit).take(5) },
                            label = { Text("端口") },
                            singleLine = true,
                            enabled = !connected && !state.sshRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(104.dp),
                        )
                    }
                    OutlinedTextField(
                        value = form.user,
                        onValueChange = { form.user = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        enabled = !connected && !state.sshRunning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = { form.password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = !connected && !state.sshRunning,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (connected) {
                            OutlinedButton(onClick = onDisconnect) {
                                Text("断开")
                            }
                        } else {
                            Button(
                                onClick = {
                                    onConnect(
                                        form.host.trim(),
                                        form.port.toIntOrNull() ?: 0,
                                        form.user.trim(),
                                        form.password,
                                    )
                                },
                                enabled = !state.sshRunning,
                            ) {
                                Text(if (state.sshRunning) "连接中…" else "连接")
                            }
                        }
                        Text(
                            when (val ssh = state.sshState) {
                                is SshState.Connected -> "已连接 ${ssh.user}@${ssh.host}:${ssh.port}"
                                SshState.Connecting -> "连接中…"
                                SshState.Disconnected -> "未连接"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (forwardPort == null) {
                            "编辑页还没有「主机端口 → 客户机 22」的端口转发，SSH 连不进来。"
                        } else {
                            "客户机需已安装并启动 sshd；端口转发 $forwardPort → 22。" +
                                "密码只保存在内存里，不写入配置。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (forwardPort == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        item {
            SectionCard(title = "交互终端（PTY）", subtitle = "sudo 提示、需要真实终端的命令") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.sshShellOpen) {
                            OutlinedButton(onClick = onCloseShell, enabled = !state.sshRunning) {
                                Text("关闭终端")
                            }
                            OutlinedButton(onClick = onShellCtrlC, enabled = !state.sshRunning) {
                                Text("^C")
                            }
                        } else {
                            Button(onClick = onOpenShell, enabled = connected && !state.sshRunning) {
                                Text("打开交互终端")
                            }
                        }
                        Text(
                            if (state.sshShellOpen) {
                                "已打开：输入发给这个 shell，输出与命令模式共用下方「输出」"
                            } else {
                                "未打开；命令模式下用下方 exec 通道"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, false),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.sshShellOpen) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = form.shellLine,
                                onValueChange = { form.shellLine = it },
                                label = { Text("输入 / 密码") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    onShellSend(form.shellLine)
                                    form.shellLine = ""
                                },
                                enabled = form.shellLine.isNotEmpty(),
                            ) {
                                Text("发送")
                            }
                        }
                        Text(
                            "sudo 的密码提示也在这里输入；密码不会被回显到输出里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "执行命令", subtitle = "每条命令单独开一个 exec 通道") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = form.command,
                            onValueChange = { form.command = it },
                            label = { Text("命令") },
                            singleLine = true,
                            enabled = connected && !state.sshRunning,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                onRun(form.command)
                                form.command = ""
                            },
                            enabled = connected && !state.sshRunning && form.command.isNotBlank(),
                        ) {
                            Text(if (state.sshRunning) "执行中…" else "执行")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("uname -a", "ip a", "df -h", "dmesg | tail -20", "sudo poweroff").forEach { quick ->
                            TextButton(onClick = { onRun(quick) }, enabled = connected && !state.sshRunning) {
                                Text(quick)
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "输出", subtitle = "stdout + stderr") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            text = state.sshOutput.ifEmpty { "尚无输出" },
                            style = MonoTextStyle.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxSize().verticalScroll(outputScroll),
                        )
                    }
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
        }
    }
}

/** SSH 表单的跨 Tab 状态。密码只在内存里，不写入配置。 */
private class SshFormState(initialPort: Int) {
    var host by mutableStateOf("127.0.0.1")
    var port by mutableStateOf(initialPort.toString())
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var command by mutableStateOf("")
    var shellLine by mutableStateOf("")
}
