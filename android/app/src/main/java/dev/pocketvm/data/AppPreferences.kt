package dev.pocketvm.data

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 应用偏好（P2 数据层）：DataStore Preferences。
 *
 * 实例名 `app_prefs`，与导航状态的 `nav_state`（[dev.pocketvm.navigation.LastRouteStore]）
 * 是两个独立的 DataStore，别混在一起：导航状态是「上次停在哪页」，这里是
 * 「用户选好的偏好」，生命周期和清理策略都不同。
 *
 * 现在只有两组偏好——以前根本没有持久化偏好可「迁」：
 * - 主题：以前只跟系统（[dev.pocketvm.ui.theme.PocketVmTheme] 默认参数），
 *   现在用户可以固定浅色 / 深色。
 * - 新建虚拟机的默认配置：以前每次新建都用写死的 4 核 / 建议内存 / 64 GB；
 *   现在记住上一次新建时用的 CPU / 内存 / 磁盘，下次新建直接沿用。
 *   只有「新建」成功时才记，编辑已有虚拟机不改默认值。
 * 电池白名单提示仍是运行时状态（每次读系统设置），不是持久化偏好，
 * 不放这里。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

data class NewVmDefaults(
    val cpuCores: Int,
    val memoryMb: Int,
    val diskSizeGb: Int,
)

private val Context.appPrefsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "app_prefs")

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val DEFAULT_CPU_CORES_KEY = intPreferencesKey("default_cpu_cores")
private val DEFAULT_MEMORY_MB_KEY = intPreferencesKey("default_memory_mb")
private val DEFAULT_DISK_GB_KEY = intPreferencesKey("default_disk_gb")

class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    val themeMode: Flow<ThemeMode> =
        context.appPrefsDataStore.data.map { prefs ->
            prefs[THEME_MODE_KEY]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = mode.name
        }
    }

    /** 没记过返回 null，调用方用写死的默认值。 */
    suspend fun newVmDefaults(): NewVmDefaults? {
        val prefs = context.appPrefsDataStore.data.first()
        val cpuCores = prefs[DEFAULT_CPU_CORES_KEY] ?: return null
        val memoryMb = prefs[DEFAULT_MEMORY_MB_KEY] ?: return null
        val diskSizeGb = prefs[DEFAULT_DISK_GB_KEY] ?: return null
        return NewVmDefaults(cpuCores, memoryMb, diskSizeGb)
    }

    suspend fun setNewVmDefaults(cpuCores: Int, memoryMb: Int, diskSizeGb: Int) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[DEFAULT_CPU_CORES_KEY] = cpuCores
            prefs[DEFAULT_MEMORY_MB_KEY] = memoryMb
            prefs[DEFAULT_DISK_GB_KEY] = diskSizeGb
        }
    }
}
