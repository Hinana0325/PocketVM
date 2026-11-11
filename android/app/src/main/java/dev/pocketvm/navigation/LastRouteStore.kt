package dev.pocketvm.navigation

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.first

/**
 * 杀进程后重启恢复当前页面（P1 第四步）。
 *
 * Navigation Compose 自带的 saved-state 恢复只覆盖「系统因内存不足杀进程」
 * 的情况；用户从最近任务划掉、或在设置里强行停止，saved-state 不会保留。
 * 所以把「最后一次停留的页面」另外写进 DataStore（Preferences），冷启动时
 * 读出来当 NavHost 的 startDestination。
 *
 * 只存路由 key（字符串），不存 VM 对象；恢复时按 key 重建路由。
 * 向导步骤不持久化：草稿只在 CreateVmViewModel 的内存里，进程死后草稿
 * 本来就没了，恢复到向导页只会看到空表单，所以向导一律回到首页。
 * 恢复时不校验 vmId 是否存在：启动时 VM 列表还没从磁盘读完，校验会误判；
 * 各目标页自己处理「虚拟机不存在」（详情页显示未找到，控制台显示已删除）。
 */

private val Context.lastRouteDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "nav_state")

private val LAST_ROUTE_KEY = stringPreferencesKey("last_route")

class LastRouteStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** 冷启动时读一次；没有记录返回 null（回首页）。 */
    suspend fun readLastRouteKey(): String? =
        context.lastRouteDataStore.data.first()[LAST_ROUTE_KEY]

    /** 每次目的地变化时写一次；key 为 null 时清除记录。 */
    suspend fun saveLastRouteKey(key: String?) {
        context.lastRouteDataStore.edit { prefs ->
            if (key == null) prefs.remove(LAST_ROUTE_KEY) else prefs[LAST_ROUTE_KEY] = key
        }
    }
}

/** 当前目的地 → 可持久化的 key；向导步骤与未知目的地返回 null（不存）。 */
fun NavBackStackEntry?.routeKey(): String? {
    val entry = this ?: return null
    return when {
        entry.destination.hasRoute<Home>() -> "home"
        entry.destination.hasRoute<Environment>() -> "environment"
        entry.destination.hasRoute<About>() -> "about"
        entry.destination.hasRoute<VmDetail>() -> "detail:${entry.toRoute<VmDetail>().vmId}"
        entry.destination.hasRoute<VmConsole>() -> "console:${entry.toRoute<VmConsole>().vmId}"
        entry.destination.hasRoute<VmConnection>() -> "connection:${entry.toRoute<VmConnection>().vmId}"
        entry.destination.hasRoute<VmConfigHome>() -> "config:${entry.toRoute<VmConfigHome>().vmId}"
        entry.destination.hasRoute<VmConfigDetail>() -> {
            val route = entry.toRoute<VmConfigDetail>()
            "configDetail:${route.vmId}:${route.category.name}"
        }
        entry.destination.hasRoute<VmDiagnostics>() -> "diagnostics:${entry.toRoute<VmDiagnostics>().vmId}"
        entry.destination.hasRoute<DiagnosticDetail>() -> {
            val route = entry.toRoute<DiagnosticDetail>()
            "diagDetail:${route.vmId}:${route.type.name}"
        }
        // 向导步骤（CreateBasic/Hardware/Devices/Runtime/Review）不持久化。
        else -> null
    }
}

/** 持久化的 key → 路由对象；解析失败返回 null（调用方回首页）。 */
fun routeForKey(key: String?): Any? {
    if (key.isNullOrBlank()) return null
    return when {
        key == "home" -> Home
        key == "environment" -> Environment
        key == "about" -> About
        key.startsWith("detail:") -> VmDetail(key.removePrefix("detail:"))
        key.startsWith("console:") -> VmConsole(key.removePrefix("console:"))
        key.startsWith("connection:") -> VmConnection(key.removePrefix("connection:"))
        key.startsWith("configDetail:") -> {
            val rest = key.removePrefix("configDetail:")
            val vmId = rest.substringBefore(':')
            val category = ConfigCategory.entries.firstOrNull { it.name == rest.substringAfter(':') }
                ?: return null
            VmConfigDetail(vmId, category)
        }
        key.startsWith("config:") -> VmConfigHome(key.removePrefix("config:"))
        key.startsWith("diagDetail:") -> {
            val rest = key.removePrefix("diagDetail:")
            val vmId = rest.substringBefore(':')
            val type = DiagnosticType.entries.firstOrNull { it.name == rest.substringAfter(':') }
                ?: return null
            DiagnosticDetail(vmId, type)
        }
        key.startsWith("diagnostics:") -> VmDiagnostics(key.removePrefix("diagnostics:"))
        else -> null
    }
}
