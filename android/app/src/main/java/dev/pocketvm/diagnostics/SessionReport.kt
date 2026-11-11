package dev.pocketvm.diagnostics

import dev.pocketvm.core.model.RunManifest

/**
 * 启动报表：把一台 VM 的全部 run manifest 按「一次用户启动操作」（sessionId）聚合。
 *
 * 纯 Kotlin、无 Android 依赖：输入是磁盘上的清单列表，输出是只读汇总；UI 只展示，
 * 不重算。旧清单（0.10.32 之前）没有 sessionId，此时退回用 runId 分组——每个
 * attempt 各自成组，仍可统计 exit 分布与启动耗时，只是看不出同一次启动的降级阶梯。
 *
 * 启动成功的判据是「任一 attempt 进入 RUNNING」（`runningAtEpochMs != null`；旧清单
 * 回退看 outcome），而不是 exit code：用户主动停止、客户机正常关机都发生在成功启动
 * 之后，而停在引导器阶段的主动停止（exit=132）并不代表启动成功。
 */
data class SessionReport(
    /** 新 → 旧（按会话首个 attempt 的开始时间倒序）。 */
    val sessions: List<SessionSummary>,
    val totals: Totals,
) {
    data class SessionSummary(
        val sessionId: String,
        /** 本会话第一个 attempt 的开始时间。 */
        val startedAtEpochMs: Long,
        val vmName: String,
        /** 本会话的 attempt 数；AUTO 阶梯降级时 > 1。 */
        val attempts: Int,
        /** 本会话最终采用的档位（最后一个 attempt 的 profile 原文）。 */
        val finalProfile: String?,
        /** 是否成功启动过（任一 attempt 进入 RUNNING）。 */
        val succeeded: Boolean,
        /** 最后一个 attempt 的 outcome。 */
        val finalOutcome: RunManifest.Outcome,
        /** 最后一个 attempt 的 exit code（尚未退出为 null）。 */
        val finalExitCode: Int?,
        /** 成功 attempt 的 QMP 握手耗时（startedAt → qmpConfirmed）。 */
        val timeToQmpMs: Long?,
        /** 成功 attempt 的 RUNNING 耗时（startedAt → runningAt）。 */
        val timeToRunningMs: Long?,
        /** 生效的光驱总线（成功 attempt 优先）。 */
        val cdTransport: String?,
        val usbInput: Boolean?,
        val serialEnabled: Boolean?,
        /** 本会话各 attempt 的非空 exit code，按 attempt 顺序。 */
        val exitCodes: List<Int>,
    )

    data class Totals(
        val sessionCount: Int,
        val attemptCount: Int,
        val successCount: Int,
        /** 成功率（0..1）；无会话时为 null，避免把 0/0 显示成 0%。 */
        val successRate: Float?,
        /** 最终档位 → 会话数，按会话数降序。 */
        val profileDistribution: Map<String, Int>,
        /** exit code → attempt 数（只统计已退出的 attempt），按 exit code 升序。 */
        val exitHistogram: Map<Int, Int>,
        /** 有 QMP 时间戳的会话的 time-to-QMP 中位数。 */
        val medianTimeToQmpMs: Long?,
        /** 成功启动的会话的 time-to-RUNNING 中位数。 */
        val medianTimeToRunningMs: Long?,
    )

    companion object {
        fun build(manifests: List<RunManifest>): SessionReport {
            val sessions = manifests
                .groupBy { it.sessionId ?: it.runId }
                .values
                .map { attempts ->
                    // 同一会话的 attempt 按阶梯序号排序；旧清单每个 runId 只有一条。
                    val ordered = attempts.sortedWith(compareBy({ it.attempt }, { it.startedAtEpochMs }))
                    val first = ordered.first()
                    val last = ordered.last()
                    val boot = ordered.firstOrNull(::reachedRunning) ?: last
                    SessionSummary(
                        sessionId = first.sessionId ?: first.runId,
                        startedAtEpochMs = ordered.minOf { it.startedAtEpochMs },
                        vmName = first.vmName,
                        attempts = ordered.size,
                        finalProfile = last.profile,
                        succeeded = ordered.any(::reachedRunning),
                        finalOutcome = last.outcome,
                        finalExitCode = last.exitCode,
                        timeToQmpMs = elapsedMs(boot.startedAtEpochMs, boot.qmpConfirmedAtEpochMs),
                        timeToRunningMs = elapsedMs(boot.startedAtEpochMs, boot.runningAtEpochMs),
                        cdTransport = boot.cdTransport,
                        usbInput = boot.usbInput,
                        serialEnabled = boot.serialEnabled,
                        exitCodes = ordered.mapNotNull { it.exitCode },
                    )
                }
                .sortedByDescending { it.startedAtEpochMs }
            return SessionReport(sessions, totals(manifests, sessions))
        }

        private fun reachedRunning(manifest: RunManifest): Boolean =
            manifest.runningAtEpochMs != null ||
                manifest.outcome == RunManifest.Outcome.RUNNING ||
                manifest.outcome == RunManifest.Outcome.EXITED_OK

        private fun elapsedMs(start: Long, end: Long?): Long? =
            end?.let { it - start }?.takeIf { it >= 0 }

        private fun totals(
            manifests: List<RunManifest>,
            sessions: List<SessionSummary>,
        ): Totals {
            val successCount = sessions.count { it.succeeded }
            val profileDistribution = sessions
                .groupingBy { it.finalProfile ?: "未知" }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .associate { it.key to it.value }
            val exitHistogram = manifests
                .mapNotNull { it.exitCode }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
            return Totals(
                sessionCount = sessions.size,
                attemptCount = manifests.size,
                successCount = successCount,
                successRate = if (sessions.isEmpty()) null else successCount.toFloat() / sessions.size,
                profileDistribution = profileDistribution,
                exitHistogram = exitHistogram,
                medianTimeToQmpMs = median(sessions.mapNotNull { it.timeToQmpMs }),
                medianTimeToRunningMs = median(sessions.mapNotNull { it.timeToRunningMs }),
            )
        }

        private fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
