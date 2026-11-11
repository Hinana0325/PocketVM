package dev.pocketvm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.diagnostics.DiagEvent
import dev.pocketvm.diagnostics.DiagSnapshot
import dev.pocketvm.diagnostics.DiagVerdict
import dev.pocketvm.diagnostics.DiagVncBanner
import dev.pocketvm.diagnostics.SessionReport
import dev.pocketvm.diagnostics.StallHypothesis
import dev.pocketvm.ui.theme.MonoTextStyle
import dev.pocketvm.ui.theme.PocketVmTheme
import kotlinx.coroutines.delay


// ---- 诊断看板的小卡（P1：诊断子页复用） ----

/** 健康判定卡：判定芯片 + 结论 + 信号依据 + 导出/复制/日志/停止 + 电池提示。 */
@Composable
internal fun HealthVerdictCard(
    vm: VmConfig,
    diag: WatchdogUiState,
    snapshot: DiagSnapshot,
    nowMs: Long,
    copied: Boolean,
    onCopySummary: (String) -> Unit,
    onExport: () -> Unit,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
    batteryWhitelisted: Boolean? = null,
    onRequestBatteryWhitelist: () -> Unit = {},
) {
    val stale = DiagnosticsFormat.isStale(snapshot, nowMs)
    val displayed = DiagnosticsFormat.displayVerdict(snapshot, nowMs)
    val verdict = DiagnosticsFormat.verdictUi(displayed)
    val conclusion = DiagnosticsFormat.staleNote(snapshot, nowMs) ?: verdict.conclusion
    SectionCard(
        title = "健康判定",
        subtitle = if (stale) {
            "样本已过期"
        } else {
            "每 5 秒采样一次 · 最近 ${DiagnosticsFormat.formatClock(snapshot.sampledAtEpochMs)}"
        },
    ) {
        StatusChip(text = verdict.label, tone = verdict.tone.toTone())
        Text(conclusion, style = MaterialTheme.typography.bodyMedium)
        Text(
            DiagnosticsFormat.signalBasis(snapshot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DiagnosticsFormat.badWindowText(snapshot)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "最近采样 ${DiagnosticsFormat.formatClock(snapshot.sampledAtEpochMs)}" +
                "（${DiagnosticsFormat.relativeAge(nowMs, snapshot.sampledAtEpochMs)}）" +
                " · 窗口 ${DiagnosticsFormat.formatWindow(snapshot.windowMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        diag.readError?.let {
            Text(
                "事件读取失败：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text("导出诊断包")
            }
            TextButton(
                onClick = {
                    onCopySummary(
                        DiagnosticsFormat.summaryText(
                            vmName = vm.name,
                            vmId = vm.id,
                            snapshot = snapshot,
                            latestEvent = diag.events.firstOrNull(),
                            nowMs = nowMs,
                        ),
                    )
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(if (copied) "已复制" else "复制判读摘要") }
        }
        val showLogs = DiagnosticsFormat.offersLogs(displayed)
        val showStop = DiagnosticsFormat.offersStop(snapshot)
        if (showLogs || showStop) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showLogs) {
                    TextButton(
                        onClick = onOpenLogs,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("查看日志") }
                }
                if (showStop) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("停止虚拟机") }
                }
            }
        }
        if (batteryWhitelisted == false) {
            Text(
                "电池优化未关闭：系统可能在后台冻结虚拟机（H4），建议设为「无限制」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = onRequestBatteryWhitelist,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("去设置电池无限制") }
        }
    }
}

/** 停摆归因卡（无归因时不画任何东西）。 */
@Composable
internal fun AttributionCard(snapshot: DiagSnapshot) {
    val attribution = DiagnosticsFormat.stallAttribution(snapshot) ?: return
    val freeze = attribution.hypothesis == StallHypothesis.H4_SYSTEM_FREEZE
    SectionCard(
        title = if (freeze) "系统冻结归因" else "停摆归因",
        subtitle = if (freeze) {
            "只读判读 · 来自采样间隔异常"
        } else {
            "只读判读 · 来自本次采样的线程状态"
        },
    ) {
        StatusChip(text = attribution.label, tone = DiagTone.ERROR.toTone())
        Text(attribution.conclusion, style = MaterialTheme.typography.bodyMedium)
        Text(
            "建议：${attribution.suggestion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "主线程状态 ${attribution.mainState ?: "不可读"}" +
                (attribution.mainSyscallName?.let { " · syscall $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 关键信号卡：QMP / VNC / 串口 / 主线程等信号逐行。 */
@Composable
internal fun KeySignalsCard(snapshot: DiagSnapshot, stale: Boolean) {
    SectionCard(title = "关键信号") {
        DiagnosticsFormat.signals(snapshot).forEach { signal ->
            SignalRowUi(signal, dimmed = stale)
        }
    }
}

/** 线程分布卡：主线程与 vCPU 的 CPU 时间占比（可折叠）。 */
@Composable
internal fun ThreadDistributionCard(snapshot: DiagSnapshot, stale: Boolean) {
    var showThreads by remember(snapshot.vmId) { mutableStateOf(false) }
    CollapsibleSection(
        title = "线程分布",
        subtitle = "主线程与 vCPU 的 CPU 时间占比",
        expanded = showThreads,
        onToggle = { showThreads = !showThreads },
    ) {
        NumberRow("主线程", DiagnosticsFormat.formatPercent(snapshot.mainThreadCpuPercent, 1), dimmed = stale)
        NumberRow("进程 CPU", DiagnosticsFormat.formatPercent(snapshot.processCpuPercent, 0), dimmed = stale)
        NumberRow("运行时长", DiagnosticsFormat.formatUptimeMs(snapshot.processUptimeMs), dimmed = stale)
        val vcpus = snapshot.vcpuPercents
        if (vcpus.isNullOrEmpty()) {
            Text(
                "每 vCPU 占用：不可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            vcpus.forEachIndexed { index, percent ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "vCPU $index",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp),
                    )
                    MeterBar(fraction = percent / 100f, modifier = Modifier.weight(1f))
                    Text(
                        DiagnosticsFormat.formatPercent(percent),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp),
                    )
                }
            }
        }
    }
}

/** 「怎么读这张看板」说明卡（可折叠）。 */
@Composable
internal fun DiagnosticsNotesCard() {
    var showNotes by remember { mutableStateOf(false) }
    CollapsibleSection(
        title = "怎么读这张看板",
        expanded = showNotes,
        onToggle = { showNotes = !showNotes },
    ) {
        Text(
            DIAGNOSTICS_NOTES,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 原始快照卡（仅调试用，可折叠）。 */
@Composable
internal fun RawSnapshotCard(snapshot: DiagSnapshot) {
    var showRaw by remember(snapshot.vmId) { mutableStateOf(false) }
    CollapsibleSection(
        title = "原始快照",
        subtitle = "仅调试用，字段以 RuntimeWatchdog 为准",
        expanded = showRaw,
        onToggle = { showRaw = !showRaw },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(
                DiagnosticsFormat.rawSnapshotText(snapshot),
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable

// ---- 启动 / 停止 / 首窗口 ----

@Composable
internal fun PreparingPanel() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        SectionCard(title = "健康判定", subtitle = "启动中") {
            StatusChip(text = "启动中", tone = Tone.Busy)
            Text(
                "QMP 握手后开始采样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun StoppingPanel(diag: WatchdogUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "健康判定", subtitle = "停止中") {
                StatusChip(text = "停止中", tone = Tone.Busy)
                Text(
                    "正在关机，诊断信号已停止更新；保留最近事件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (diag.events.isNotEmpty()) {
            item { EventsCard(events = diag.events, emptyText = "本次运行没有记录到异常窗口。") }
        }
    }
}

@Composable
internal fun WaitingFirstSamplePanel(diag: WatchdogUiState, onExport: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "健康判定",
                subtitle = "每 5 秒采样一次",
            ) {
                StatusChip(text = "采样中", tone = Tone.Idle)
                Text(
                    "正在等待第一个采样窗口（每 5 秒一拍），暂不下结论。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diag.readError?.let {
                    Text(
                        "事件读取失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("导出诊断包")
                }
            }
        }
        item {
            SectionCard(title = "关键信号") {
                listOf("QMP 查询", "VNC 握手", "串口输出", "主线程推进").forEach { label ->
                    SignalRowUi(SignalUi(label = label, state = "检测中", value = "—", tone = DiagTone.IDLE))
                }
            }
        }
    }
}

// ---- 空态 / 历史运行 / 未启用 ----

@Composable
internal fun WatchdogDisabledPanel(onExport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.MonitorHeart,
            title = "诊断采样未启用",
            subtitle = "当前构建没有可用的 RuntimeWatchdog。你仍然可以导出诊断包（含历史日志与运行清单）。",
            actionLabel = "导出诊断包",
            onAction = onExport,
        )
    }
}

@Composable
internal fun EmptyPanel(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.MonitorHeart,
            title = title,
            subtitle = subtitle,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

@Composable
internal fun RecentRunPanel(
    vm: VmConfig,
    runtime: VmRuntimeState?,
    manifest: RunManifest?,
    snapshot: DiagSnapshot?,
    events: List<DiagEvent>,
    report: SessionReport?,
    copied: Boolean,
    onCopySummary: (String) -> Unit,
    onExport: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val result = when (runtime) {
        is VmRuntimeState.Exited -> "已退出（exit=${runtime.exitCode}）"
        is VmRuntimeState.Failed -> "启动失败"
        else -> manifest?.let {
            DiagnosticsFormat.outcomeLabel(it.outcome) +
                (it.exitCode?.let { code -> "（exit=$code）" } ?: "")
        } ?: "—"
    }
    val reason = (runtime as? VmRuntimeState.Failed)?.reason
    val hint = when (runtime) {
        is VmRuntimeState.Exited -> runtime.hint
        is VmRuntimeState.Failed -> runtime.hint
        else -> null
    } ?: manifest?.failureHint
    val copyText = when {
        snapshot != null -> DiagnosticsFormat.summaryText(
            vmName = vm.name,
            vmId = vm.id,
            snapshot = snapshot,
            latestEvent = events.firstOrNull(),
            nowMs = System.currentTimeMillis(),
        )
        manifest != null -> DiagnosticsFormat.manifestSummaryText(vm.name, vm.id, manifest)
        runtime is VmRuntimeState.Failed ->
            DiagnosticsFormat.failureSummaryText(vm.name, vm.id, runtime.reason, runtime.hint)
        else -> null
    }
    val emptyEvents = if (snapshot != null) {
        "本次运行没有记录到异常窗口。"
    } else {
        "未加载到事件：事件仅保留本次 App 进程内最近 20 条，完整时间线见诊断包 watchdog.jsonl。"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "最近一次运行", subtitle = "最近一次运行摘要") {
                KeyValueRow("结果", result)
                reason?.takeIf { it.isNotBlank() }?.let { KeyValueRow("失败原因", it) }
                hint?.takeIf { it.isNotBlank() }?.let { KeyValueRow("失败归因", it) }
                manifest?.let {
                    KeyValueRow("启动档位", "${it.profile ?: "—"}（第 ${it.attempt} 次尝试）")
                    KeyValueRow("开始时间", DiagnosticsFormat.formatDateTime(it.startedAtEpochMs))
                    NumberRow("运行时长", DiagnosticsFormat.formatUptimeMs(DiagnosticsFormat.manifestDurationMs(it)))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                        Text("导出诊断包")
                    }
                    if (copyText != null) {
                        TextButton(
                            onClick = { onCopySummary(copyText) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(if (copied) "已复制" else "复制判读摘要") }
                    }
                }
                TextButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("查看日志") }
            }
        }
        item { EventsCard(events = events, emptyText = emptyEvents) }
        report?.takeIf { it.sessions.isNotEmpty() }?.let { history ->
            item { SessionReportCard(history) }
        }
    }
}

// ---- 共享小件 ----

/** 数字行：tabular numerals + 右对齐（T6 §6.3）。不复用 KeyValueRow 以免改公共组件。 */
@Composable
internal fun NumberRow(key: String, value: String, dimmed: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = if (dimmed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
internal fun SignalRowUi(signal: SignalUi, dimmed: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(text = signal.state, tone = signal.tone.toTone())
        Text(
            signal.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            signal.value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = if (dimmed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
internal fun EventsCard(events: List<DiagEvent>, emptyText: String) {
    var showAll by remember { mutableStateOf(false) }
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
    val visible = DiagnosticsFormat.visibleEvents(events, showAll)
    SectionCard(title = "停摆事件", subtitle = "来自 watchdog.jsonl 的异常窗口") {
        if (events.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visible.forEach { event ->
                val key = "${event.epochMs}:${event.kind}"
                val open = key in expandedKeys
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        DiagnosticsFormat.eventHeadline(event),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (event.evidence.isNotBlank()) {
                        Text(
                            "证据：${event.evidence}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (open) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (event.evidence.length > 40) {
                            TextButton(
                                onClick = {
                                    expandedKeys = if (open) expandedKeys - key else expandedKeys + key
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) { Text(if (open) "收起" else "展开") }
                        }
                    }
                }
            }
            if (events.size > DiagnosticsFormat.EVENT_PREVIEW_LIMIT) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (showAll) "收起" else "展开全部 ${events.size} 条")
                }
            }
        }
    }
}

@Composable
internal fun SessionReportCard(report: SessionReport) {
    var expanded by remember { mutableStateOf(false) }
    val totals = report.totals
    CollapsibleSection(
        title = "启动报表",
        subtitle = "按启动会话聚合 · ${totals.sessionCount} 次启动 / ${totals.attemptCount} 次尝试",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        totals.successRate?.let { rate ->
            NumberRow(
                "成功率",
                "${Math.round(rate * 100)}%（${totals.successCount}/${totals.sessionCount}）",
            )
        }
        NumberRow("QMP 握手中位数", DiagnosticsFormat.formatDurationMs(totals.medianTimeToQmpMs))
        NumberRow("到 RUNNING 中位数", DiagnosticsFormat.formatDurationMs(totals.medianTimeToRunningMs))
        if (totals.profileDistribution.isNotEmpty()) {
            Text(
                "最终档位",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                totals.profileDistribution.entries.joinToString(" · ") {
                    "${DiagnosticsFormat.profileLabel(it.key)}×${it.value}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (totals.exitHistogram.isNotEmpty()) {
            Text(
                "exit 分布",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                totals.exitHistogram.entries.joinToString(" · ") { "${it.key}×${it.value}" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "最近 ${minOf(report.sessions.size, SESSION_REPORT_LIMIT)} 次启动",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report.sessions.take(SESSION_REPORT_LIMIT).forEach { session ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    DiagnosticsFormat.sessionHeadline(session),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    DiagnosticsFormat.sessionDetail(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SESSION_REPORT_LIMIT = 5

@Composable
internal fun rememberNowMs(active: Boolean): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(active) {
        if (!active || inPreview) return@LaunchedEffect
        // 5 秒级刷新，与 T2 采样节拍一致；不做逐秒重绘（T6 §11）。
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun DiagTone.toTone(): Tone = when (this) {
    DiagTone.IDLE -> Tone.Idle
    DiagTone.OK -> Tone.Ok
    DiagTone.ERROR -> Tone.Error
}

private val DIAGNOSTICS_NOTES = """
正常判定要求连续 2 个 5 秒窗口都通过；单次超时不等于停摆。

QMP 和 VNC 的 TCP 握手由内核完成，连接成功不能证明 QEMU 用户态还在跑。
典型停摆是：端口还能连、banner 或 QMP 不再响应，同时 vCPU 线程仍在满核运行。

主线程 0 tick 是核心证据。只看「进程 CPU 很高」会误判为健康——多线程 TCG 下
vCPU 空转本来就很耗 CPU。

串口静默通常是正常的：QEMU 与客户机稳定运行时本来就不输出。串口有输出只说明
客户机在动，不保证主循环健康。

读不到 /proc（部分系统 SELinux 限制）时显示「不可用」，看板不会把缺失数据当成 0。

采样中断（间隔远超 5 秒）说明 App 进程自身被系统挂起过：MIUI/HyperOS 的省电冻结
会把 App 与 QEMU 一起冻住，表现为 VNC/QMP 失联、解冻后自愈。这不是 QEMU 故障；
到系统电池设置把 PocketVM 设为「无限制」可解，看板会给出「系统冻结归因」卡。
""".trimIndent()
