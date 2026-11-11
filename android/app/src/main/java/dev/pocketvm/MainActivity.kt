package dev.pocketvm

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.data.AppPreferences
import dev.pocketvm.data.ThemeMode
import dev.pocketvm.diagnostics.AgentFeed
import dev.pocketvm.navigation.LastRouteStore
import dev.pocketvm.ui.PocketVmRoot
import dev.pocketvm.ui.VmViewModel
import dev.pocketvm.ui.theme.PocketVmTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt 入口 Activity（P3）。
 *
 * `@AndroidEntryPoint` 让 Hilt 接管默认的 ViewModelProvider.Factory，
 * 所以 `ViewModelProvider(this)[VmViewModel::class.java]` 拿到的就是
 * Hilt 注入的实例，调用方式不用改（P1 加这行时漏了 VmViewModel 的
 * import，这里顺手补上——之前那版是编不过的）。
 *
 * 偏好与上次路由直接从图里注入，再传给 PocketVmRoot；不再经过
 * `(application as PocketVmApp).container`。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var lastRouteStore: LastRouteStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this)[VmViewModel::class.java]
        // VM 运行状态一变就同步持续性能模式；回到前台后 collector 重放
        // 当前状态重新设置，Activity 不可见期间窗口提示自然失效。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { state ->
                    setSustainedPerformance(state.runtime is VmRuntimeState.Running)
                }
            }
        }
        setContent {
            // P2：主题跟应用偏好（DataStore app_prefs）走；没选过就是跟随系统，
            // 与 P2 之前的默认行为一致。偏好是 Hilt 注入的字段，不再从 container 取。
            val themeMode by appPreferences.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PocketVmTheme(darkTheme = darkTheme) {
                PocketVmRoot(
                    appPreferences = appPreferences,
                    lastRouteStore = lastRouteStore,
                )
            }
        }
    }

    /**
     * 持续性能模式：VM 运行时请系统把 CPU/GPU 最高频率锁定在可长期维持的
     * 水平，避免「先冲高、再过热降频」的锯齿。只在系统声明支持时生效
     * （isSustainedPerformanceModeSupported），不支持的设备上是空操作。
     * 峰值频率可能比不开启时略低，换来的是长时间稳定——对一跑几小时的
     * VM 是正确的取舍；短时间跑分式的峰值不是这个 App 的目标。
     */
    private fun setSustainedPerformance(enabled: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isSustainedPerformanceModeSupported()) return
        runCatching { window.setSustainedPerformanceMode(enabled) }
            .onFailure { AgentFeed.event("perf.sustained_failed", "error" to it.toString()) }
    }
}
