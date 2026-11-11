package dev.pocketvm.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pocketvm.data.VmDatabase
import dev.pocketvm.runtime.NetworkMonitor
import javax.inject.Singleton

/**
 * Hilt 模块（P3）：只放「构造函数注入不够用」的绑定。
 *
 * 绝大多数依赖直接在类上写 `@Inject constructor`（见各类的构造函数），
 * 这里只留两处例外：
 * - Room 数据库：要走 `Room.databaseBuilder`，不能直接 new；
 * - [NetworkMonitor]：建好后必须调一次 `start()` 注册 NetworkCallback，
 *   否则只建不启、永远收不到网络变化。
 *
 * 其余的（VmStorageLayout / VmConfigRepository / AppPreferences /
 * LastRouteStore / QemuEnvironment / FirmwareManager / IsoImporter /
 * RunManifestStore / DiagnosticsExporter / ConfigValidator /
 * DiskProvisioner / VmSession / QemuCommandBuilder / AppleCommandBuilder）
 * 全部是 @Inject 构造函数 + @Singleton（VmSession 是单例，其余默认
 * 未加作用域、每次注入新建——它们都是无状态的工具类，只有 VmSession
 * 持有 QEMU 进程，必须全局唯一）。
 *
 * WorkManager 的 Configuration 不在这里提供：PocketVmApp 直接注入
 * HiltWorkerFactory 再组装（见 [dev.pocketvm.PocketVmApp]），少一层转发。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VmDatabase =
        VmDatabase.create(context)

    /**
     * 网络状态跟 App 走：创建即 start，注册 ConnectivityManager 回调。
     * 不跟 ViewModel/Activity——旋转屏幕、切页面都不应重注册。
     */
    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor =
        NetworkMonitor(context).also { it.start() }
}
