package dev.pocketvm.debug

import kotlinx.serialization.Serializable

/**
 * 调试接口的契约（docs/debug-api.md）。
 *
 * 红线：只绑 127.0.0.1；除 health 外都要 `X-PocketVM-Token`；不提供 QMP 透传、
 * shell/任意命令执行、任意文件读取；快照里不含 token/密钥/密码。
 */
object DebugApi {
    const val HOST = "127.0.0.1"
    const val PORT = 18080
    const val PREFIX = "/api/v1"
    const val TOKEN_HEADER = "X-PocketVM-Token"

    /** `lines` 查询参数：缺省 200，钳制在 1..1000（纯函数，便于单测）。 */
    fun clampLines(raw: Int?): Int = (raw ?: 200).coerceIn(1, 1000)

    /**
     * 鉴权判据：预期 token 必须存在且长度一致才比内容；比较走常量时间，
     * 不做短路返回，避免按字节差异泄露信息（纯函数，便于单测）。
     */
    fun authorized(expected: String?, provided: String?): Boolean {
        if (expected.isNullOrBlank() || provided == null) return false
        if (expected.length != provided.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor provided[i].code)
        return diff == 0
    }
}

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class BuildInfo(
    val appVersion: String,
    val versionCode: Int,
    val packageName: String,
    val agpVersion: String,
    val kotlinVersion: String,
)

@Serializable
data class HealthResponse(val ok: Boolean = true)

@Serializable
data class StatusResponse(
    val ok: Boolean = true,
    val app: BuildInfo,
    val uptimeMs: Long,
    val vmCount: Int,
    val runningVmId: String? = null,
    val runningVmName: String? = null,
    val serverStartedAtEpochMs: Long,
)

@Serializable
data class VmSummary(
    val id: String,
    val name: String,
    val platform: String,
    val running: Boolean,
    val cpuCores: Int,
    val memoryMb: Int,
    val diskSizeGb: Int,
    val diskUsedBytes: Long,
    val displayDevice: String,
    val vncPort: Int,
)

/** 单台配置详情：只给路径与大类型字段，不含 VNC 密码等凭据。 */
@Serializable
data class VmDetail(
    val id: String,
    val name: String,
    val platform: String,
    val running: Boolean,
    val cpuCores: Int,
    val memoryMb: Int,
    val diskSizeGb: Int,
    val diskUsedBytes: Long,
    val storageController: String,
    val displayDevice: String,
    val bootFromCd: Boolean,
    val isoPath: String? = null,
    val driverIsoPath: String? = null,
    val firmwareCodePath: String? = null,
    val vncPort: Int,
    val vncAccess: String,
)

@Serializable
data class LogsResponse(
    val vmId: String,
    val runId: String? = null,
    val file: String? = null,
    val lines: List<String> = emptyList(),
)

@Serializable
data class MetricsDto(
    val epochMs: Long,
    val cpuPercent: Float,
    val vcpuPercents: List<Float>,
    val rssMb: Long? = null,
    val readMBs: Float? = null,
    val writeMBs: Float? = null,
    val uptimeMs: Long,
)

@Serializable
data class DiagEventDto(
    val epochMs: Long,
    val kind: String,
    val windowMs: Int,
    val consecutive: Int,
    val evidence: String,
)

/**
 * 诊断概览：看门狗最近样本（原始 JSON 对象）、性能快照、最近的看门狗事件、
 * AgentFeed 事件尾巴。都是只读数据，不重算任何判据。
 */
@Serializable
data class AccelerationDto(
    val verdict: String,
    val summary: String,
    val kvmExists: Boolean,
    val kvmReadable: Boolean,
    val kvmWritable: Boolean,
    val openError: String? = null,
    val runningUnderHypervisor: Boolean,
    val kernelRelease: String,
)

@Serializable
data class DiagnosticsResponse(
    val ok: Boolean = true,
    val vmId: String? = null,
    val watchdog: kotlinx.serialization.json.JsonElement? = null,
    val metrics: MetricsDto? = null,
    val diagEvents: List<DiagEventDto> = emptyList(),
    val agentFeed: List<String> = emptyList(),
    /** 硬件加速能力（只读探测，docs/roadmap.md §5）。 */
    val acceleration: AccelerationDto? = null,
)

@Serializable
data class ActionResponse(
    val accepted: Boolean,
    val vmId: String,
    val runningVmId: String? = null,
)

/** 快照 = status + vms + diagnostics + 各 VM 日志尾巴；与导出文件同一模型。 */
@Serializable
data class SnapshotResponse(
    val ok: Boolean = true,
    val generatedAtEpochMs: Long,
    val status: StatusResponse,
    val vms: List<VmSummary>,
    val diagnostics: DiagnosticsResponse,
    /** vmId -> 最近一次运行日志尾巴（最多 200 行）。 */
    val logs: Map<String, List<String>>,
)

/** 快照组装：纯函数，便于单测断言字段齐全且不含敏感项。 */
object DebugSnapshotBuilder {
    const val MAX_LOG_LINES = 200

    fun build(
        generatedAtEpochMs: Long,
        status: StatusResponse,
        vms: List<VmSummary>,
        diagnostics: DiagnosticsResponse,
        logs: Map<String, List<String>>,
    ): SnapshotResponse = SnapshotResponse(
        generatedAtEpochMs = generatedAtEpochMs,
        status = status,
        vms = vms,
        diagnostics = diagnostics,
        logs = logs.mapValues { (_, lines) -> lines.takeLast(MAX_LOG_LINES) },
    )
}
