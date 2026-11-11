package dev.pocketvm.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.diagnostics.DiagSnapshot
import dev.pocketvm.diagnostics.SessionReport
import dev.pocketvm.navigation.DiagnosticType

/**
 * 诊断中心（P1）：入口 + 5 个子页。
 *
 * 入口（VmDiagnostics）只做两件事：健康判定摘要 + 5 个子页入口。
 * 详细内容全在子页里（DiagnosticDetail），每个子页复用 DiagnosticsTab.kt
 * 拆出来的小卡（HealthVerdictCard / AttributionCard / KeySignalsCard /
 * ThreadDistributionCard / EventsCard / SessionReportCard / RecentRunPanel）。
 * 子页的状态分发与旧诊断 Tab 完全一致（运行中看快照、退出看最近一次运行、
 * 准备中/停止中/采样中各有面板、未运行是空态），只是内容按分类切片。
 * 控制台的「诊断」Tab 已删：诊断只能从详情页 → 诊断中心进。
 */

// ---- 诊断入口：健康摘要 + 5 个子页入口 ----

@Composable
fun VmDiagnosticsScreen(
    state: UiState,
    vm: VmConfig,
    contentPadding: PaddingValues,
    onOpenDetail: (DiagnosticType) -> Unit,
    onBack: () -> Unit,
) {
    val diag = state.diagnostics
    val snapshot = diag.snapshot
    val nowMs = rememberNowMs(active = state.runtime is VmRuntimeState.Running)
    val report = state.sessionReports[vm.id]

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = "诊断中心",
            subtitle = vm.name,
            showBack = true,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "健康判定", subtitle = "每 5 秒采样一次") {
                if (snapshot == null) {
                    Text(
                        when (state.runtime) {
                            is VmRuntimeState.Running -> "正在等待第一个采样窗口，暂不下结论。"
                            is VmRuntimeState.Preparing -> "启动中，QMP 握手后开始采样。"
                            is VmRuntimeState.Stopping -> "正在关机，诊断信号已停止更新。"
                            else -> "虚拟机未运行：诊断看板在运行后开始采样；进程退出后仍保留最近一次运行的事件与日志。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val displayed = DiagnosticsFormat.displayVerdict(snapshot, nowMs)
                    val verdict = DiagnosticsFormat.verdictUi(displayed)
                    StatusChip(text = verdict.label, tone = verdict.tone.toTone())
                    Text(
                        DiagnosticsFormat.staleNote(snapshot, nowMs) ?: verdict.conclusion,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "最近采样 ${DiagnosticsFormat.formatClock(snapshot.sampledAtEpochMs)}" +
                            "（${DiagnosticsFormat.relativeAge(nowMs, snapshot.sampledAtEpochMs)}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = "诊断分类", subtitle = "点进分类看详细数据") {
                DiagnosticType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetail(type) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(type.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                diagnosticEntrySummary(type, snapshot, diag, report),
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
        }
    }
}

/** 入口列表里每个分类的一行摘要。 */
private fun diagnosticEntrySummary(
    type: DiagnosticType,
    snapshot: DiagSnapshot?,
    diag: WatchdogUiState,
    report: SessionReport?,
): String {
    if (snapshot == null) return "暂无采样数据"
    return when (type) {
        DiagnosticType.WATCHDOG -> DiagnosticsFormat.verdictUi(
            DiagnosticsFormat.displayVerdict(snapshot, System.currentTimeMillis()),
        ).label
        DiagnosticType.PERFORMANCE ->
            "进程 CPU ${DiagnosticsFormat.formatPercent(snapshot.processCpuPercent, 0)}" +
                " · 主线程 ${DiagnosticsFormat.formatPercent(snapshot.mainThreadCpuPercent, 1)}"
        DiagnosticType.STUTTER -> if (diag.events.isEmpty()) {
            "本次运行没有记录到异常窗口"
        } else {
            "${diag.events.size} 条异常窗口"
        }
        DiagnosticType.THERMAL -> listOfNotNull(
            thermalStatusLabel(snapshot.thermalStatusCode),
            snapshot.affinityMode,
        ).joinToString(" · ").ifBlank { "温控与绑核未启用" }
        DiagnosticType.REPORT -> if (report == null || report.sessions.isEmpty()) {
            "暂无启动记录"
        } else {
            "${report.totals.sessionCount} 次启动 / ${report.totals.attemptCount} 次尝试"
        }
    }
}

/** PowerManager 温控状态码 → 中文（THERMAL_STATUS_*，见 android.os.PowerManager）。 */
internal fun thermalStatusLabel(code: Int?): String? = when (code) {
    null -> null
    0 -> "温控正常"
    1 -> "温控轻微"
    2 -> "温控中等"
    3 -> "温控严重"
    4 -> "温控危急"
    5 -> "温控紧急"
    6 -> "温控关机"
    else -> "温控未知（$code）"
}

// ---- 诊断子页：按分类切片 ----

@Composable
fun DiagnosticDetailScreen(
    state: UiState,
    vm: VmConfig,
    type: DiagnosticType,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onOpenLogs: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    val runtime = state.runtime
    val otherRunning = (runtime as? VmRuntimeState.Running)?.takeIf { it.vmId != vm.id }
    val lastManifest = state.lastRuns[vm.id]
    val report = state.sessionReports[vm.id]
    val diag = state.diagnostics
    val canStart = !state.busy && otherRunning == null &&
        (runtime is VmRuntimeState.Idle ||
            runtime is VmRuntimeState.Exited ||
            runtime is VmRuntimeState.Failed)
    val nowMs = rememberNowMs(active = runtime is VmRuntimeState.Running)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val copySummary: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        copied = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = type.label,
            subtitle = vm.name,
            showBack = true,
            onBack = onBack,
        )
        DiagnosticSubPage(
            vm = vm,
            runtime = runtime,
            otherRunning = otherRunning,
            lastManifest = lastManifest,
            report = report,
            diag = diag,
            canStart = canStart,
            onStart = onStart,
            onStop = onStop,
            onExport = onExport,
            onOpenLogs = onOpenLogs,
            batteryWhitelisted = state.batteryWhitelisted,
            onRequestBatteryWhitelist = onRequestBatteryWhitelist,
            nowMs = nowMs,
            copied = copied,
            onCopySummary = copySummary,
        ) { snapshot ->
            val stale = DiagnosticsFormat.isStale(snapshot, nowMs)
            when (type) {
                DiagnosticType.WATCHDOG -> {
                    item {
                        HealthVerdictCard(
                            vm = vm,
                            diag = diag,
                            snapshot = snapshot,
                            nowMs = nowMs,
                            copied = copied,
                            onCopySummary = copySummary,
                            onExport = onExport,
                            onOpenLogs = onOpenLogs,
                            onStop = onStop,
                            batteryWhitelisted = state.batteryWhitelisted,
                            onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                        )
                    }
                    item { KeySignalsCard(snapshot = snapshot, stale = stale) }
                    item { DiagnosticsNotesCard() }
                }
                DiagnosticType.PERFORMANCE -> {
                    item { ThreadDistributionCard(snapshot = snapshot, stale = stale) }
                    item { KeySignalsCard(snapshot = snapshot, stale = stale) }
                }
                DiagnosticType.STUTTER -> {
                    item { AttributionCard(snapshot = snapshot) }
                    item { EventsCard(events = diag.events, emptyText = "本次运行没有记录到异常窗口。") }
                }
                DiagnosticType.THERMAL -> {
                    item {
                        ThermalCard(
                            snapshot = snapshot,
                            batteryWhitelisted = state.batteryWhitelisted,
                            onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                        )
                    }
                    item { KeySignalsCard(snapshot = snapshot, stale = stale) }
                }
                DiagnosticType.REPORT -> {
                    if (report != null && report.sessions.isNotEmpty()) {
                        item { SessionReportCard(report) }
                    } else {
                        item {
                            Text(
                                "暂无启动记录：启动一次虚拟机后，这里会按会话聚合启动报表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    item { RawSnapshotCard(snapshot = snapshot) }
                }
            }
        }
    }
}

/**
 * 子页的状态分发：与旧 DiagnosticsTab 的 when 完全一致。
 * 运行中有快照 → 各子页自己的内容；退出/失败 → 最近一次运行；
 * 准备中/停止中/采样中 → 对应面板；未运行 → 空态。
 */
@Composable
private fun DiagnosticSubPage(
    vm: VmConfig,
    runtime: VmRuntimeState?,
    otherRunning: VmRuntimeState.Running?,
    lastManifest: RunManifest?,
    report: SessionReport?,
    diag: WatchdogUiState,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onOpenLogs: () -> Unit,
    batteryWhitelisted: Boolean?,
    onRequestBatteryWhitelist: () -> Unit,
    nowMs: Long,
    copied: Boolean,
    onCopySummary: (String) -> Unit,
    /** 运行中有快照时各子页的内容：以 LazyListScope 方式逐项加入（每张卡一个 item）。 */
    runningContent: androidx.compose.foundation.lazy.LazyListScope.(snapshot: DiagSnapshot) -> Unit,
) {
    val snapshot = diag.snapshot
    when {
        !diag.enabled -> WatchdogDisabledPanel(onExport = onExport)
        runtime is VmRuntimeState.Preparing -> PreparingPanel()
        runtime is VmRuntimeState.Stopping -> StoppingPanel(diag = diag)
        runtime is VmRuntimeState.Running -> {
            if (snapshot != null) {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    runningContent(snapshot)
                }
            } else {
                WaitingFirstSamplePanel(diag = diag, onExport = onExport)
            }
        }
        runtime is VmRuntimeState.Exited || runtime is VmRuntimeState.Failed -> RecentRunPanel(
            vm = vm,
            runtime = runtime,
            manifest = lastManifest,
            snapshot = snapshot,
            events = if (snapshot != null) diag.events else emptyList(),
            report = report,
            copied = copied,
            onCopySummary = onCopySummary,
            onExport = onExport,
            onOpenLogs = onOpenLogs,
        )
        lastManifest != null -> RecentRunPanel(
            vm = vm,
            runtime = null,
            manifest = lastManifest,
            snapshot = null,
            events = emptyList(),
            report = report,
            copied = copied,
            onCopySummary = onCopySummary,
            onExport = onExport,
            onOpenLogs = onOpenLogs,
        )
        otherRunning != null -> EmptyPanel(
            title = "当前虚拟机未运行",
            subtitle = "另一台虚拟机正在运行，诊断数据属于那台；切过去查看，或先停止再启动这一台。",
        )
        else -> EmptyPanel(
            title = "还没有可诊断的运行",
            subtitle = "诊断看板在虚拟机运行后开始每 5 秒采样；进程退出后仍保留最近一次运行的事件与日志。",
            actionLabel = if (canStart) "启动虚拟机" else null,
            onAction = if (canStart) onStart else null,
        )
    }
}

/** 温控与绑核卡：PowerManager 温控状态 + vCPU 亲和性档位与掩码 + 电池提示。 */
@Composable
private fun ThermalCard(
    snapshot: DiagSnapshot,
    batteryWhitelisted: Boolean?,
    onRequestBatteryWhitelist: () -> Unit,
) {
    SectionCard(title = "温控与绑核", subtitle = "温控降档会自动把 vCPU 切到能效核，降温后恢复") {
        KeyValueRow("温控状态", thermalStatusLabel(snapshot.thermalStatusCode) ?: "未启用（调度器未采样）")
        KeyValueRow("绑核档位", snapshot.affinityMode ?: "未绑核")
        snapshot.affinityMaskHex?.let {
            KeyValueRow("taskset 掩码", "0x$it")
        }
        Text(
            "MODERATE 及以上把 vCPU 切到能效核、优先级回 0，降温后恢复；" +
                "SEVERE 及以上发警告。绝不自动暂停 VM（中途停 Windows 有损坏磁盘的风险，" +
                "停不停由用户决定）。没有可区分的能效核时降无可降，只警告。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (batteryWhitelisted == false) {
            Text(
                "电池优化未关闭：系统可能在后台冻结虚拟机（H4），建议设为「无限制」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onRequestBatteryWhitelist,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("去设置电池无限制") }
        }
    }
}
