package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/**
 * One record per QEMU launch. Written *before* the process starts, so that a
 * crash still leaves a trace of what was executed, and rewritten after exit with
 * the exit code and the matched failure hint.
 */
@Serializable
data class RunManifest(
    val runId: String,
    val vmId: String,
    val vmName: String,
    val appVersion: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val executablePath: String,
    val executableVersion: String? = null,
    val argv: List<String> = emptyList(),
    val firmwareCodePath: String? = null,
    val firmwareVarsPath: String? = null,
    val diskPath: String? = null,
    val diskFormat: String? = null,
    val isoPath: String? = null,
    val vncPort: Int = 0,
    val vncBindAddress: String? = null,
    /** Never record the password itself; only whether auth was armed. */
    val vncPasswordProtected: Boolean = false,
    val qmpSocketPath: String? = null,
    val logPath: String? = null,
    /** Which acceleration profile produced this attempt (see LaunchProfile). */
    val profile: String? = null,
    /** 1-based index of this attempt in the fallback ladder. */
    val attempt: Int = 1,
    val exitCode: Int? = null,
    val outcome: Outcome = Outcome.STARTING,
    val failureHint: String? = null,
    /** QEMU's own first error line, verbatim. */
    val logExcerpt: String? = null,
    /**
     * 一次用户「启动」操作的稳定标识，降级阶梯里的每个 attempt 共用同一个
     * sessionId；runId 仍标识单次 attempt。报表按 session 聚合启动耗时、
     * 阶梯命中与 exit 分布时用它切分。
     */
    val sessionId: String? = null,
    /** QMP 握手成功（控制面可用）的时刻；未握手为 null。 */
    val qmpConfirmedAtEpochMs: Long? = null,
    /** 该 attempt 进入 RUNNING（UI 宣布运行中）的时刻；启动失败为 null。 */
    val runningAtEpochMs: Long? = null,
    /** 该 attempt 实际生效的光驱总线（含 AUTO 解析与降级后）。 */
    val cdTransport: String? = null,
    /** 该 attempt 实际生效的 USB 输入开关（含降级后）。 */
    val usbInput: Boolean? = null,
    /** 该 attempt 是否启用了串口采集（受启动档与介质影响）。 */
    val serialEnabled: Boolean? = null,
) {
    enum class Outcome {
        STARTING,
        RUNNING,
        EXITED_OK,
        EXITED_ERROR,
        STOPPED_BY_USER,
        LAUNCH_FAILED,
    }
}
