package dev.pocketvm.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 日志清理任务的排队（P3）。
 *
 * 每天一次；只要求电量不低——清理是纯本机文件 IO，不用网、不用充电，
 * 电量低时系统会推迟，任务本身可延后，不着急。KEEP：重复排队不重置
 * 周期（App 每次启动都调一次 [enqueue]，已排好的任务不受影响）。
 *
 * WorkManager 最小周期 15 分钟，每天一次远在限制之上；任务在系统
 * 杀进程、重启后由 WorkManager 自己恢复，不用 App 操心。
 */
object LogCleanupScheduler {

    const val UNIQUE_NAME = "log-cleanup"

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<LogCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
