package dev.pocketvm.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.storage.VmStorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 日志清理 Worker（P3，WorkManager + @HiltWorker）。
 *
 * 每天跑一次 [LogCleanup.run]：删 90 天前、且不在最近 20 次里的旧运行
 * 日志组，顺带清掉中断导入留下的 `*.part` / `*.tmp`。只删文件，不碰
 * Room、不碰正在跑的 VM、不碰磁盘与配置（安全规则见 [LogCleanup]）。
 *
 * 依赖（[VmStorageLayout]）由 Hilt 注入：@HiltWorker + @AssistedInject，
 * Context 与 WorkerParameters 是 @Assisted（WorkManager 运行时给）。
 * 失败返回 [Result.retry]（下次周期还会再跑一次，不会丢任务）；
 * 每次结果记一笔 AgentFeed（`work.log_cleanup` / `work.log_cleanup_failed`），
 * 诊断包里能看到清理有没有跑。
 */
@HiltWorker
class LogCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val layout: VmStorageLayout,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { LogCleanup.run(layout) }
            .onSuccess { report ->
                AgentFeed.event(
                    "work.log_cleanup",
                    "deletedFiles" to report.deletedFiles,
                    "freedBytes" to report.freedBytes,
                    "deletedRunSets" to report.deletedRunSets,
                    "deletedTempFiles" to report.deletedTempFiles,
                )
            }
            .onFailure { error ->
                AgentFeed.event("work.log_cleanup_failed", "error" to error.toString())
            }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }
}
