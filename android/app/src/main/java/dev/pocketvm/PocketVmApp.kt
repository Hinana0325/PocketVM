package dev.pocketvm

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.runtime.DeviceInfo
import dev.pocketvm.storage.VmStorageLayout
import dev.pocketvm.work.LogCleanupScheduler
import javax.inject.Inject

/**
 * Hilt 入口（P3）：`@HiltAndroidApp` 生成依赖图，Application 级的依赖
 * （VmSession、Room 仓库、偏好、网络监听等）全部走 Hilt 注入。
 *
 * 手写的 AppContainer 已删：它原来在 PocketVmApp 里 new 出整张依赖图，
 * 现在图由 Hilt 在编译期生成（模块见 [dev.pocketvm.di.AppModule]，
 * 其余依赖直接在各自的类上写 `@Inject constructor`）。
 *
 * 注入时机：生成的 Hilt_PocketVmApp 在 `super.onCreate()` 之前先调
 * `hiltInternalInject()`，所以下面的 `@Inject lateinit var` 在
 * `onCreate()` 里已经可用——AgentFeed 的 attach 与 app.start 事件
 * （原来在 AppContainer.init 里）直接搬到这里，不用改时序。
 *
 * WorkManager：本类同时是 [Configuration.Provider]——默认的
 * androidx.startup 初始化已在 manifest 里关掉（否则它会用默认配置
 * 建一个没有 HiltWorkerFactory 的 WorkManager），这里把 Hilt 的
 * [HiltWorkerFactory] 交给 WorkManager，@HiltWorker 才能拿到注入的依赖。
 * 定时任务在 onCreate 里排队一次（KEEP，重复启动不重复排队）。
 */
@HiltAndroidApp
class PocketVmApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var layout: VmStorageLayout

    override fun onCreate() {
        super.onCreate()
        // The channel is opened here, not at the first launch, so that "app is up
        // but no VM was ever started" is visible from outside — that is precisely
        // when the paths, version and device facts are worth having. The probe is
        // deliberately not run: it executes QEMU, which is a launch-time concern.
        AgentFeed.attach(layout.agentFeedFile())
        AgentFeed.event(
            "app.start",
            "version" to BuildConfig.VERSION_NAME,
            "package" to packageName,
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "abi" to DeviceInfo.abi(),
            "soc" to DeviceInfo.socLabel(),
            "pageSizeKb" to DeviceInfo.pageSizeKb(),
            "nativeLibDir" to applicationInfo.nativeLibraryDir,
            "root" to layout.root.absolutePath,
        )
        // P3：日志清理定时任务（WorkManager）。每天跑一次，删 90 天前的旧运行
        // 日志；首次启动排队，之后 KEEP 不重复排队。
        LogCleanupScheduler.enqueue(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
