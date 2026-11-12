package dev.pocketvm.tpm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.diagnostics.LogTail
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * swtpm 进程的启动参数（纯函数，无 Android、无 I/O，单测直接断言）。
 *
 * 前台模式，绝不用 `--daemon`：PocketVM 必须持有明确的子进程句柄才能管
 * 它的生命周期；daemonize 之后 PID 跟踪和异常回收都不可靠。
 */
internal fun swtpmArgv(executable: String, stateDir: String, socketPath: String): List<String> =
    listOf(
        executable,
        "socket",
        "--tpm2",
        "--tpmstate",
        "dir=$stateDir",
        "--ctrl",
        "type=unixio,path=$socketPath",
    )

/**
 * swtpm 的生命周期管理（阶段 2）。
 *
 * 顺序契约（方案 §3）：起 swtpm → 等 socket 就绪 → 才轮到 QEMU；
 * 停的时候先停 QEMU，再停 swtpm，最后删 socket、保留状态文件。
 * TPM 身份一旦建立就属于这台 VM：任何路径都不重建、不删除状态目录。
 */
interface SoftwareTpmManager {

    /**
     * 启动 swtpm 前台进程，等到控制 socket 出现且进程存活才返回。
     * 失败抛 [IllegalStateException]（带清晰原因），调用方必须让 QEMU
     * 绝不启动。
     */
    suspend fun start(vmId: String): SoftwareTpmSession

    /**
     * 停 swtpm：`destroy()` → 最多等 3 秒 → 超时 `destroyForcibly()`；
     * 最后删 socket 文件、保留状态文件。
     */
    suspend fun stop(session: SoftwareTpmSession)

    /**
     * 只清残留 socket、检查状态目录；绝不重建已有 TPM 身份。
     * 状态目录被占时抛 [IllegalStateException]，不要自动重试。
     */
    suspend fun recover(vmId: String)
}

/**
 * [SoftwareTpmManager] 的唯一实现。
 *
 * 进程解析规则（阶段 1 Native POC 实测）：三个文件都没有 RUNPATH，
 * `swtpm` 的 NEEDED 是 `libswtpm_libtpms.so`、`libtpms.so` 与系统库，
 * `libswtpm_libtpms.so` 还要系统自带的 `libz.so`。所以运行时靠
 * `LD_LIBRARY_PATH=nativeLibraryDir` 解析，两个 `.so` 与可执行文件
 * （打包时改名成 `libswtpm.so`）放在同一目录即可。
 */
@Singleton
class SoftwareTpmManagerImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val store: TpmStateStore,
    private val monitor: TpmProcessMonitor,
) : SoftwareTpmManager {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前活动的会话。VmSession 单例保证同时只跑一台 VM，这里再守一道。 */
    @Volatile
    private var activeSession: SoftwareTpmSession? = null

    override suspend fun start(vmId: String): SoftwareTpmSession = withContext(Dispatchers.IO) {
        val current = activeSession
        if (current != null && current.isAlive) {
            throw IllegalStateException(
                "TPM 已在为虚拟机 ${current.vmId} 运行，不能同时启动第二台虚拟机的 TPM。",
            )
        }
        if (current != null) activeSession = null

        val stateDir = store.stateDir(vmId)
        // 长度守卫在里面：超长直接抛错，不等 swtpm 去报看不懂的错。
        val socketFile = store.socketFile(vmId)
        // 残留 socket 只在「本 VM 无活动会话」时删：正在跑的会话的 socket 绝不能碰。
        store.deleteSocketFile(vmId)

        val nativeLibDir = appContext.applicationInfo?.nativeLibraryDir
            ?: throw IllegalStateException("取不到 nativeLibraryDir，无法定位 swtpm。")
        val executable = File(nativeLibDir, "libswtpm.so")
        if (!executable.isFile) {
            throw IllegalStateException("APK 缺少 swtpm（$executable 不存在），请确认安装包完整。")
        }
        executable.setExecutable(true, false)

        val logFile = store.logFile(vmId)
        val argv = swtpmArgv(executable.absolutePath, stateDir.absolutePath, socketFile.absolutePath)
        val process = try {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeLibDir
                    environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                    environment()["LANG"] = "C"
                }
                .start()
        } catch (t: Throwable) {
            throw IllegalStateException("无法启动 swtpm：${t.message}", t)
        }

        // 等 socket 出现且进程存活；超时约 10 秒则杀进程并报清晰错误。
        // 等待期间进程提前退出（例如状态目录被另一个 swtpm 锁着）同样走失败分支，
        // 日志尾巴会拼进报错里——swtpm 对 dir 后端默认加文件锁（lock=true），
        // Kotlin 侧不猜锁文件名，锁冲突由 swtpm 自己的报错说话。
        val deadline = System.currentTimeMillis() + TPM_START_TIMEOUT_MS
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) break
            if (socketFile.exists()) {
                ready = true
                break
            }
            delay(100)
        }
        if (!ready) {
            runCatching { process.destroyForcibly() }
            val exit = runCatching {
                if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue() else null
            }.getOrNull()
            val tail = LogTail.read(logFile, maxBytes = 4 * 1024, maxLines = 20).trim()
            throw IllegalStateException(
                buildString {
                    append("swtpm 未能在 10 秒内就绪")
                    if (exit != null) append("（进程已退出，exit=$exit）") else append("（进程仍在但控制套接字未出现）")
                    append("：${socketFile.absolutePath}")
                    if (tail.isNotEmpty()) append("\n$tail")
                },
            )
        }

        val session = SoftwareTpmSession(
            process = process,
            vmId = vmId,
            stateDir = stateDir,
            socketPath = socketFile.absolutePath,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        activeSession = session
        monitor.start(scope, session)
        AgentFeed.event(
            "tpm.started",
            "vm" to vmId,
            "socket" to session.socketPath,
            "stateDir" to stateDir.absolutePath,
        )
        session
    }

    override suspend fun stop(session: SoftwareTpmSession) = withContext(Dispatchers.IO) {
        monitor.stop()
        val process = session.process
        if (process.isAlive) {
            process.destroy()
            val exited = runCatching { process.waitFor(TPM_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (!exited && process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor(TPM_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
        }
        if (activeSession === session) activeSession = null
        // 删 socket、保留状态文件：TPM 身份属于 VM，不属于这次会话。
        store.deleteSocketFile(session.vmId)
        AgentFeed.event("tpm.stopped", "vm" to session.vmId, "alive" to process.isAlive)
    }

    override suspend fun recover(vmId: String) = withContext(Dispatchers.IO) {
        // 本进程内还有活动会话占着这台 VM 的 TPM：被占，不自动重试。
        val current = activeSession
        if (current != null && current.vmId == vmId && current.isAlive) {
            throw IllegalStateException("TPM 状态被另一个进程占用（虚拟机 $vmId 的 swtpm 仍在运行），不自动重试。")
        }
        // 没有 TPM 身份可恢复时什么都不做——绝不凭空建一个。
        if (!store.stateDirFile(vmId).isDirectory) {
            AgentFeed.event("tpm.recover", "vm" to vmId, "state" to "absent")
            return@withContext
        }
        // 只清残留 socket；状态目录原样保留。另一个 swtpm（非本进程启动的）
        // 若正占着这个状态目录，接下来 start() 时 swtpm 会因文件锁失败，
        // 报错里会带上它的日志尾巴。
        store.deleteSocketFile(vmId)
        AgentFeed.event("tpm.recover", "vm" to vmId, "state" to "ok")
    }

    private companion object {
        /** 等 swtpm 建出控制 socket 的上限；超过即判启动失败。 */
        const val TPM_START_TIMEOUT_MS = 10_000L

        /** `destroy()` 后等进程退出的上限；超过即 `destroyForcibly()`。 */
        const val TPM_STOP_TIMEOUT_SECONDS = 3L
    }
}
