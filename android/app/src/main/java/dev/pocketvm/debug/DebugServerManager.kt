package dev.pocketvm.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketvm.BuildConfig
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.data.DebugPreferences
import dev.pocketvm.data.VmConfigRepository
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.diagnostics.DiagEvent
import dev.pocketvm.diagnostics.LogTail
import dev.pocketvm.diagnostics.RunManifestStore
import dev.pocketvm.diagnostics.VmMetrics
import dev.pocketvm.runtime.VmSession
import dev.pocketvm.storage.VmStorageLayout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 调试服务器的对外状态，诊断页直接展示。 */
sealed interface DebugServerState {
    data object Stopped : DebugServerState
    data object Starting : DebugServerState
    data class Running(val port: Int) : DebugServerState
    data class Failed(val message: String) : DebugServerState
}

/**
 * 调试接口服务器（docs/debug-api.md）。
 *
 * 生命周期跟着 [DebugPreferences.enabled] 走：开关打开才 bind 127.0.0.1:18080，
 * 关闭立即 stop、不占端口。控制端点（start/stop/restart）额外受
 * `debug_allow_control` 门控——即使 token 泄露，默认也读得到、动不了。
 * 端口被占等失败只记录状态与 AgentFeed，不让 App 崩。
 */
@Singleton
class DebugServerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: DebugPreferences,
    private val session: VmSession,
    private val repo: VmConfigRepository,
    private val layout: VmStorageLayout,
    private val manifests: RunManifestStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private var engine: EmbeddedServer<*, *>? = null
    private var serverStartedAtEpochMs = 0L
    private val appStartedAtMs = System.currentTimeMillis()

    private val _state = MutableStateFlow<DebugServerState>(DebugServerState.Stopped)
    val state: StateFlow<DebugServerState> = _state.asStateFlow()

    /** App 启动时调用一次：服务随偏好开关启停，本身不做任何 IO。 */
    fun start() {
        scope.launch {
            prefs.enabled.collectLatest { on ->
                if (on) startInternal() else stopInternal()
            }
        }
    }

    private suspend fun startInternal() {
        stopInternal()
        _state.value = DebugServerState.Starting
        try {
            prefs.ensureToken()
            val server = embeddedServer(CIO, host = DebugApi.HOST, port = DebugApi.PORT) {
                module()
            }
            server.start(wait = false)
            engine = server
            serverStartedAtEpochMs = System.currentTimeMillis()
            _state.value = DebugServerState.Running(DebugApi.PORT)
            AgentFeed.event("debug.server.started", "addr" to "${DebugApi.HOST}:${DebugApi.PORT}")
        } catch (t: Throwable) {
            engine = null
            val message = t.message ?: t.javaClass.simpleName
            _state.value = DebugServerState.Failed(message)
            AgentFeed.event("debug.server.failed", "error" to message)
        }
    }

    private fun stopInternal() {
        val wasRunning = engine != null
        runCatching { engine?.stop(100, 300) }
        engine = null
        if (_state.value !is DebugServerState.Failed || wasRunning) {
            _state.value = DebugServerState.Stopped
        }
    }

    // ---------- 数据组装（HTTP 与快照导出共用） ----------

    fun buildInfo(): BuildInfo = BuildInfo(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        packageName = context.packageName,
        agpVersion = BuildConfig.AGP_VERSION,
        kotlinVersion = BuildConfig.KOTLIN_VERSION,
    )

    suspend fun status(): StatusResponse {
        val vms = repo.loadAll()
        val runningId = session.runningVmId()
        return StatusResponse(
            app = buildInfo(),
            uptimeMs = System.currentTimeMillis() - appStartedAtMs,
            vmCount = vms.size,
            runningVmId = runningId,
            runningVmName = vms.firstOrNull { it.id == runningId }?.name,
            serverStartedAtEpochMs = serverStartedAtEpochMs,
        )
    }

    suspend fun vmSummaries(): List<VmSummary> {
        val runningId = session.runningVmId()
        return repo.loadAll().map { it.toSummary(running = it.id == runningId) }
    }

    suspend fun vmDetail(id: String): VmDetail? =
        repo.load(id)?.toDetail(running = session.runningVmId() == id)

    suspend fun logs(vmId: String, rawLines: Int?): LogsResponse? {
        val config = repo.load(vmId) ?: return null
        val manifest = session.currentRun(vmId) ?: manifests.latest(vmId)
        val runId = manifest?.runId
        val file = runId?.let { layout.logFile(vmId, it) }
        val lines = if (file != null) {
            LogTail.read(file, maxLines = 1000).lines()
                .takeLast(DebugApi.clampLines(rawLines))
        } else {
            emptyList()
        }
        return LogsResponse(vmId = config.id, runId = runId, file = file?.absolutePath, lines = lines)
    }

    suspend fun diagnostics(): DiagnosticsResponse {
        val runningId = session.runningVmId()
        val manifest = runningId?.let { session.currentRun(it) } ?: runningId?.let { manifests.latest(it) }
        val watchdog: JsonElement? = manifest?.let { m ->
            val file = layout.watchdogLogFile(m.vmId, m.runId)
            runCatching {
                file.takeIf { it.isFile }?.readLines()?.lastOrNull { it.isNotBlank() }
                    ?.let { json.parseToJsonElement(it) }
            }.getOrNull()
        }
        return DiagnosticsResponse(
            vmId = runningId,
            watchdog = watchdog,
            metrics = session.metrics.value?.toDto(),
            diagEvents = session.diagEvents.value.takeLast(20).map { it.toDto() },
            agentFeed = agentFeedTail(50),
        )
    }

    suspend fun snapshot(): SnapshotResponse {
        val vms = vmSummaries()
        val logs = vms.mapNotNull { vm ->
            logs(vm.id, DebugSnapshotBuilder.MAX_LOG_LINES)?.let { vm.id to it.lines }
        }.toMap()
        return DebugSnapshotBuilder.build(
            generatedAtEpochMs = System.currentTimeMillis(),
            status = status(),
            vms = vms,
            diagnostics = diagnostics(),
            logs = logs,
        )
    }

    fun toJson(snapshot: SnapshotResponse): String =
        json.encodeToString(SnapshotResponse.serializer(), snapshot)

    private fun agentFeedTail(limit: Int): List<String> {
        val file = layout.agentFeedFile()
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().takeLast(limit) }.getOrDefault(emptyList())
    }

    private fun VmConfig.toSummary(running: Boolean) = VmSummary(
        id = id,
        name = name,
        platform = platform.name,
        running = running,
        cpuCores = cpuCores,
        memoryMb = memoryMb,
        diskSizeGb = diskSizeGb,
        diskUsedBytes = runCatching { layout.diskFile(id).length() }.getOrDefault(0L),
        displayDevice = displayDevice.name,
        vncPort = vncPort,
    )

    private fun VmConfig.toDetail(running: Boolean) = VmDetail(
        id = id,
        name = name,
        platform = platform.name,
        running = running,
        cpuCores = cpuCores,
        memoryMb = memoryMb,
        diskSizeGb = diskSizeGb,
        diskUsedBytes = runCatching { layout.diskFile(id).length() }.getOrDefault(0L),
        storageController = storageController.name,
        displayDevice = displayDevice.name,
        bootFromCd = bootFromCd,
        isoPath = isoPath,
        driverIsoPath = driverIsoPath,
        firmwareCodePath = firmwareCodePath,
        vncPort = vncPort,
        vncAccess = vncAccess.name,
    )

    private fun VmMetrics.toDto() = MetricsDto(
        epochMs = epochMs,
        cpuPercent = cpuPercent,
        vcpuPercents = vcpuPercents,
        rssMb = rssMb,
        readMBs = readMBs,
        writeMBs = writeMBs,
        uptimeMs = uptimeMs,
    )

    private fun DiagEvent.toDto() = DiagEventDto(
        epochMs = epochMs,
        kind = kind,
        windowMs = windowMs,
        consecutive = consecutive,
        evidence = evidence,
    )

    // ---------- Ktor 模块 ----------

    private fun Application.module() {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        routing {
            get("${DebugApi.PREFIX}/health") {
                call.respond(HealthResponse())
            }
            route(DebugApi.PREFIX) {
                get("/status") {
                    if (!call.requireAuth()) return@get
                    call.respond(status())
                }
                get("/vms") {
                    if (!call.requireAuth()) return@get
                    call.respond(vmSummaries())
                }
                get("/vms/{id}") {
                    if (!call.requireAuth()) return@get
                    val id = call.parameters["id"].orEmpty()
                    val detail = vmDetail(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("vm_not_found"))
                    call.respond(detail)
                }
                get("/vms/{id}/logs") {
                    if (!call.requireAuth()) return@get
                    val id = call.parameters["id"].orEmpty()
                    val lines = call.request.queryParameters["lines"]?.toIntOrNull()
                    val result = logs(id, lines)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("vm_not_found"))
                    call.respond(result)
                }
                get("/diagnostics") {
                    if (!call.requireAuth()) return@get
                    call.respond(diagnostics())
                }
                get("/snapshot") {
                    if (!call.requireAuth()) return@get
                    call.respond(snapshot())
                }
                post("/vms/{id}/start") {
                    if (!call.requireAuth()) return@post
                    if (!controlAllowed()) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("control_disabled"))
                    }
                    val id = call.parameters["id"].orEmpty()
                    val config = repo.load(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("vm_not_found"))
                    val busy = session.runningVmId()
                    if (busy != null && busy != id) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("another_vm_running"))
                    }
                    if (busy == null) scope.launch { session.start(config) }
                    call.respond(HttpStatusCode.Accepted, action(id))
                }
                post("/vms/{id}/stop") {
                    if (!call.requireAuth()) return@post
                    if (!controlAllowed()) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("control_disabled"))
                    }
                    val id = call.parameters["id"].orEmpty()
                    if (session.runningVmId() != id) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("vm_not_running"))
                    }
                    scope.launch { session.stop(id) }
                    call.respond(HttpStatusCode.Accepted, action(id))
                }
                post("/vms/{id}/restart") {
                    if (!call.requireAuth()) return@post
                    if (!controlAllowed()) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("control_disabled"))
                    }
                    val id = call.parameters["id"].orEmpty()
                    val config = repo.load(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("vm_not_found"))
                    if (session.runningVmId() != id) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("vm_not_running"))
                    }
                    scope.launch {
                        session.stop(id)
                        session.start(config)
                    }
                    call.respond(HttpStatusCode.Accepted, action(id))
                }
            }
        }
    }

    private fun action(vmId: String) = ActionResponse(
        accepted = true,
        vmId = vmId,
        runningVmId = session.runningVmId(),
    )

    private suspend fun controlAllowed(): Boolean = prefs.allowControl.first()

    private suspend fun ApplicationCall.requireAuth(): Boolean {
        val expected = runCatching { prefs.token.first() }.getOrNull()
        val provided = request.header(DebugApi.TOKEN_HEADER)
        if (DebugApi.authorized(expected, provided)) return true
        respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        return false
    }
}
