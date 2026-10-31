package dev.pocketvm.ui

import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.diagnostics.DiagEvent
import dev.pocketvm.diagnostics.DiagSnapshot
import dev.pocketvm.diagnostics.DiagVerdict
import dev.pocketvm.diagnostics.DiagVncBanner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 诊断看板的 UI 状态（T6 §5.1）：T2 的 `diag` / `diagEvents` StateFlow 在
 * ViewModel 里整体替换成这个不可变对象，UI 只读，不解析 jsonl。
 */
data class WatchdogUiState(
    val enabled: Boolean = true,
    val snapshot: DiagSnapshot? = null,
    val events: List<DiagEvent> = emptyList(),
    /** 事件读取失败时的原因；当前 T2 走内存 StateFlow，正常为 null。 */
    val readError: String? = null,
)

/** 与 Components.Tone 解耦，便于在纯 JVM 单测里断言。 */
enum class DiagTone { IDLE, OK, ERROR }

data class VerdictUi(
    val label: String,
    val conclusion: String,
    val tone: DiagTone,
)

data class SignalUi(
    val label: String,
    val state: String,
    val value: String,
    val tone: DiagTone,
)

/**
 * 诊断看板的格式化与判定展示层（T6 §6.3 / §7）。
 *
 * 这里只做 UI 本地允许的两类计算：数字格式化与「样本新鲜度」；停摆判定
 * 一律来自 T2 的 [DiagSnapshot.verdict]，本对象不复制阈值、不重算窗口。
 */
object DiagnosticsFormat {

    const val DEFAULT_WINDOW_MS = 5_000

    /** 事件仅展示最近 20 条（T2 内存上限），默认展示 5 条。 */
    const val EVENT_PREVIEW_LIMIT = 5
    const val EVENT_LIST_LIMIT = 20

    private const val RAW_SNAPSHOT_LIMIT = 8_192

    // ---- 判定 ----

    fun verdictUi(verdict: DiagVerdict?): VerdictUi = when (verdict ?: DiagVerdict.NOT_RUNNING) {
        DiagVerdict.NOT_RUNNING ->
            VerdictUi("未运行", "虚拟机未运行，没有实时信号。", DiagTone.IDLE)
        DiagVerdict.SAMPLING ->
            VerdictUi("采样中", "正在等待第二个采样窗口，暂不下结论。", DiagTone.IDLE)
        DiagVerdict.HEALTHY ->
            VerdictUi("信号正常", "主循环响应正常。", DiagTone.OK)
        DiagVerdict.WATCH ->
            VerdictUi("观察中", "有一个窗口出现异常，继续观察。", DiagTone.IDLE)
        DiagVerdict.SUSPECTED_STALL ->
            VerdictUi("疑似停摆", "关键信号同时异常，主循环可能已停摆。", DiagTone.ERROR)
        DiagVerdict.STALLED ->
            VerdictUi("已停摆", "连续多个异常窗口，主循环已停止响应。", DiagTone.ERROR)
        DiagVerdict.UNKNOWN ->
            VerdictUi("无法判定", "信号不足（部分数据不可读），暂不下结论。", DiagTone.IDLE)
    }

    fun isStallVerdict(verdict: DiagVerdict?): Boolean =
        verdict == DiagVerdict.SUSPECTED_STALL || verdict == DiagVerdict.STALLED

    /** 停摆且进程仍存活时才提供「停止虚拟机」（T6 §7.7）。 */
    fun offersStop(snapshot: DiagSnapshot): Boolean =
        snapshot.verdict == DiagVerdict.STALLED && snapshot.processAlive == true

    fun offersLogs(verdict: DiagVerdict?): Boolean = isStallVerdict(verdict)

    // ---- 样本新鲜度（UI 唯一的本地计算，T6 §5.2） ----

    /** 3 个窗口未更新即视为过期；默认 5 秒窗口 ⇒ 15 秒。 */
    fun staleThresholdMs(windowMs: Int): Long = windowMs.coerceAtLeast(1).toLong() * 3

    fun isStale(snapshot: DiagSnapshot, nowMs: Long): Boolean =
        nowMs - snapshot.sampledAtEpochMs > staleThresholdMs(snapshot.windowMs)

    /** 过期时胶囊降级为「无法判定」，但原始判定仍保留在快照里。 */
    fun displayVerdict(snapshot: DiagSnapshot, nowMs: Long): DiagVerdict =
        if (isStale(snapshot, nowMs)) DiagVerdict.UNKNOWN else snapshot.verdict

    fun staleNote(snapshot: DiagSnapshot, nowMs: Long): String? =
        if (isStale(snapshot, nowMs)) {
            "样本已过期 ${relativeAge(nowMs, snapshot.sampledAtEpochMs)}，最后读数仅供参考。"
        } else {
            null
        }

    fun relativeAge(nowMs: Long, thenMs: Long): String {
        val seconds = (nowMs - thenMs).coerceAtLeast(0L) / 1000
        return when {
            seconds < 60 -> "$seconds 秒前"
            seconds < 3_600 -> "${seconds / 60} 分前"
            else -> "${seconds / 3_600} 小时前"
        }
    }

    // ---- 时间 / 数字格式（Locale.US，T6 §6.3） ----

    fun formatClock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun formatDateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun formatQmpRtt(ms: Int?): String = when {
        ms == null -> "不可用"
        ms < 1_000 -> "$ms ms"
        else -> String.format(Locale.US, "%.1f s", ms / 1_000.0)
    }

    fun formatPercent(value: Float?, decimals: Int = 0): String =
        value?.let { String.format(Locale.US, "%.${decimals}f%%", it) } ?: "不可用"

    fun vcpuAveragePercents(values: List<Float>?): Float? =
        values?.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1_024L -> "$safe B"
            safe < 1_024L * 1_024L -> String.format(Locale.US, "%.1f KB", safe / 1_024.0)
            else -> String.format(Locale.US, "%.1f MB", safe / (1_024.0 * 1_024.0))
        }
    }

    fun formatWindow(windowMs: Int): String =
        if (windowMs > 0 && windowMs % 1_000 == 0) {
            "${windowMs / 1_000} 秒"
        } else {
            String.format(Locale.US, "%.1f 秒", windowMs / 1_000.0)
        }

    fun formatBytesDelta(delta: Long?, windowMs: Int): String =
        delta?.let { "${formatBytes(it)} / ${formatWindow(windowMs)}" } ?: "不可用"

    /** 与 ConsoleScreen 的 formatUptime 保持一致；null 显示「—」而不是 0。 */
    fun formatUptimeMs(ms: Long?): String {
        if (ms == null) return "—"
        val totalSeconds = ms.coerceAtLeast(0L) / 1000
        return if (totalSeconds >= 3_600) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                totalSeconds / 3_600,
                (totalSeconds % 3_600) / 60,
                totalSeconds % 60,
            )
        } else {
            String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }

    // ---- 四个关键信号（T6 §4.2 / §7.3） ----

    fun qmpSignal(snapshot: DiagSnapshot): SignalUi {
        val rtt = snapshot.qmpRttMs
        return when {
            snapshot.qmpTimedOut == true ->
                SignalUi("QMP 查询", "超时", "无响应", DiagTone.ERROR)
            rtt == null ->
                SignalUi("QMP 查询", "不可用", "—", DiagTone.IDLE)
            rtt < 250 ->
                SignalUi("QMP 查询", "正常", formatQmpRtt(rtt), DiagTone.OK)
            rtt <= 2_000 ->
                SignalUi("QMP 查询", "较慢", formatQmpRtt(rtt), DiagTone.IDLE)
            else ->
                SignalUi("QMP 查询", "超时", formatQmpRtt(rtt), DiagTone.ERROR)
        }
    }

    fun vncSignal(snapshot: DiagSnapshot): SignalUi {
        val connect = snapshot.vncConnectMs?.let { "$it ms" } ?: "—"
        return when (snapshot.vncBanner) {
            DiagVncBanner.OK ->
                SignalUi("VNC 握手", "握手正常", connect, DiagTone.OK)
            DiagVncBanner.NO_RESPONSE ->
                SignalUi("VNC 握手", "无响应", "无 banner", DiagTone.ERROR)
            DiagVncBanner.TIMEOUT ->
                SignalUi("VNC 握手", "握手超时", "无 banner", DiagTone.ERROR)
            DiagVncBanner.REFUSED ->
                SignalUi("VNC 握手", "连接被拒", connect, DiagTone.ERROR)
            null ->
                SignalUi("VNC 握手", "不可用", "—", DiagTone.IDLE)
        }
    }

    fun serialSignal(snapshot: DiagSnapshot): SignalUi {
        val delta = snapshot.serialBytesDelta
        return when {
            delta == null ->
                SignalUi("串口输出", "不可用", "—", DiagTone.IDLE)
            delta <= 0L ->
                // 串口静默是正常现象，只能是 Idle，绝不能标红（T6 §4.2）。
                SignalUi("串口输出", "静默", formatBytesDelta(0L, snapshot.windowMs), DiagTone.IDLE)
            else ->
                SignalUi("串口输出", "有输出", formatBytesDelta(delta, snapshot.windowMs), DiagTone.IDLE)
        }
    }

    fun mainThreadSignal(snapshot: DiagSnapshot): SignalUi {
        val main = snapshot.mainThreadCpuPercent
            ?: return SignalUi("主线程推进", "不可用", "—", DiagTone.IDLE)
        val vcpu = vcpuAveragePercents(snapshot.vcpuPercents)
        val vcpuText = vcpu?.let { "vCPU 平均 ${formatPercent(it)}" } ?: "vCPU 不可用"
        val value = "${formatPercent(main, 1)} · $vcpuText"
        return when {
            main <= 0f -> SignalUi("主线程推进", "停滞", value, DiagTone.ERROR)
            main < 0.1f -> SignalUi("主线程推进", "缓慢", value, DiagTone.IDLE)
            else -> SignalUi("主线程推进", "推进中", value, DiagTone.OK)
        }
    }

    fun signals(snapshot: DiagSnapshot): List<SignalUi> = listOf(
        qmpSignal(snapshot),
        vncSignal(snapshot),
        serialSignal(snapshot),
        mainThreadSignal(snapshot),
    )

    /** 判定卡的一行依据：只复述信号状态，不推断。 */
    fun signalBasis(snapshot: DiagSnapshot): String {
        val qmp = qmpSignal(snapshot)
        val vnc = vncSignal(snapshot)
        val main = mainThreadSignal(snapshot)
        return "QMP ${qmp.state} · VNC ${vnc.state} · 主线程 ${main.state}"
    }

    fun badWindowText(snapshot: DiagSnapshot): String? =
        if (snapshot.badWindows > 0) {
            "本次运行累计 ${snapshot.badWindows} 个异常窗口（窗口 ${formatWindow(snapshot.windowMs)}）"
        } else {
            null
        }

    // ---- 事件（T6 §5.3 / §7.5） ----

    fun eventLabel(kind: String?): String = when (kind) {
        "QMP_TIMEOUT" -> "QMP 超时"
        "VNC_BANNER_TIMEOUT" -> "VNC 握手超时"
        "MAIN_THREAD_STALL" -> "主线程停摆"
        "CPU_SPIN" -> "vCPU 空转"
        "SAMPLE_GAP" -> "采样中断"
        "PROC_UNREADABLE" -> "主线程读取失败"
        "QMP_STATUS" -> "QMP 状态异常"
        null, "" -> "未知事件"
        else -> "未知事件（原文：$kind）"
    }

    fun eventHeadline(event: DiagEvent, zone: ZoneId = ZoneId.systemDefault()): String {
        val head = "${formatClock(event.epochMs, zone)}  ${eventLabel(event.kind)}"
        return if (event.consecutive > 0) {
            "$head · 连续第 ${event.consecutive} 个异常窗口"
        } else {
            head
        }
    }

    fun visibleEvents(events: List<DiagEvent>, expanded: Boolean): List<DiagEvent> =
        events.take(if (expanded) EVENT_LIST_LIMIT else EVENT_PREVIEW_LIMIT)

    // ---- 复制摘要 / 原始快照 / 运行清单 ----

    fun summaryText(
        vmName: String,
        vmId: String,
        snapshot: DiagSnapshot,
        latestEvent: DiagEvent?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val qmp = qmpSignal(snapshot)
        val vnc = vncSignal(snapshot)
        val serial = serialSignal(snapshot)
        val main = mainThreadSignal(snapshot)
        val verdict = verdictUi(displayVerdict(snapshot, nowMs))
        return buildString {
            appendLine("PocketVM 诊断摘要")
            appendLine("时间：${formatDateTime(snapshot.sampledAtEpochMs, zone)}")
            appendLine("虚拟机：$vmName（$vmId）")
            appendLine(
                "判定：${verdict.label}（本次运行累计 ${snapshot.badWindows} 个异常窗口，" +
                    "窗口 ${formatWindow(snapshot.windowMs)}）",
            )
            appendLine("QMP：${qmp.state}（${qmp.value}）")
            appendLine("VNC：${vnc.state}（${vnc.value}）")
            appendLine("串口：${serial.value}（${serial.state}）")
            appendLine("主线程：${main.value}")
            appendLine("运行时长：${formatUptimeMs(snapshot.processUptimeMs)}")
            latestEvent?.let { appendLine("最近事件：${eventHeadline(it, zone)}") }
            staleNote(snapshot, nowMs)?.let { appendLine(it) }
        }.trimEnd()
    }

    fun rawSnapshotText(snapshot: DiagSnapshot): String = buildString {
        appendLine("vmId=${snapshot.vmId}")
        appendLine("runId=${snapshot.runId}")
        appendLine("windowMs=${snapshot.windowMs}")
        appendLine("sampledAtEpochMs=${snapshot.sampledAtEpochMs}")
        appendLine("verdict=${snapshot.verdict}")
        appendLine("badWindows=${snapshot.badWindows}")
        appendLine("qmpRttMs=${snapshot.qmpRttMs}")
        appendLine("qmpTimedOut=${snapshot.qmpTimedOut}")
        appendLine("vncBanner=${snapshot.vncBanner}")
        appendLine("vncConnectMs=${snapshot.vncConnectMs}")
        appendLine("serialBytesDelta=${snapshot.serialBytesDelta}")
        appendLine("serialBytesTotal=${snapshot.serialBytesTotal}")
        appendLine("processAlive=${snapshot.processAlive}")
        appendLine("processUptimeMs=${snapshot.processUptimeMs}")
        appendLine("processCpuPercent=${snapshot.processCpuPercent}")
        appendLine("mainThreadCpuPercent=${snapshot.mainThreadCpuPercent}")
        appendLine("vcpuPercents=${snapshot.vcpuPercents?.joinToString(",") ?: "null"}")
        appendLine("procReadable=${snapshot.procReadable}")
        appendLine("unavailableSignals=${snapshot.unavailableSignals.joinToString(",")}")
    }.let { if (it.length > RAW_SNAPSHOT_LIMIT) it.takeLast(RAW_SNAPSHOT_LIMIT) else it }.trimEnd()

    fun outcomeLabel(outcome: RunManifest.Outcome): String = when (outcome) {
        RunManifest.Outcome.STARTING -> "启动中"
        RunManifest.Outcome.RUNNING -> "运行中"
        RunManifest.Outcome.EXITED_OK -> "正常退出"
        RunManifest.Outcome.EXITED_ERROR -> "异常退出"
        RunManifest.Outcome.STOPPED_BY_USER -> "用户停止"
        RunManifest.Outcome.LAUNCH_FAILED -> "启动失败"
    }

    fun manifestDurationMs(manifest: RunManifest): Long? =
        manifest.finishedAtEpochMs?.let { (it - manifest.startedAtEpochMs).coerceAtLeast(0L) }

    fun manifestSummaryText(
        vmName: String,
        vmId: String,
        manifest: RunManifest,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("PocketVM 诊断摘要")
        appendLine("时间：${formatDateTime(manifest.finishedAtEpochMs ?: manifest.startedAtEpochMs, zone)}")
        appendLine("虚拟机：$vmName（$vmId）")
        appendLine("最近一次运行：${outcomeLabel(manifest.outcome)}" +
            manifest.exitCode?.let { "（exit=$it）" }.orEmpty())
        appendLine("启动档位：${manifest.profile ?: "—"}（第 ${manifest.attempt} 次尝试）")
        appendLine("开始时间：${formatDateTime(manifest.startedAtEpochMs, zone)}")
        appendLine("运行时长：${formatUptimeMs(manifestDurationMs(manifest))}")
        manifest.failureHint?.let { appendLine("失败归因：$it") }
        appendLine("说明：本摘要来自运行清单；实时信号与事件只在 App 进程内保留，完整时间线见诊断包 watchdog.jsonl。")
    }.trimEnd()

    /** 启动失败、没有运行清单时的摘要（T6 §8.3）。 */
    fun failureSummaryText(
        vmName: String,
        vmId: String,
        reason: String?,
        hint: String?,
    ): String = buildString {
        appendLine("PocketVM 诊断摘要")
        appendLine("虚拟机：$vmName（$vmId）")
        appendLine("最近一次运行：启动失败")
        reason?.takeIf { it.isNotBlank() }?.let { appendLine("失败原因：$it") }
        hint?.takeIf { it.isNotBlank() }?.let { appendLine("失败归因：$it") }
        appendLine("说明：本次启动没有运行期样本；完整信息见诊断包 watchdog.jsonl 与运行日志。")
    }.trimEnd()
}
