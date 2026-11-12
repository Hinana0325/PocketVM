package dev.pocketvm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 调试接口偏好（docs/debug-api.md）：独立 DataStore `debug_prefs`。
 *
 * 与 `app_prefs`、`nav_state` 分开：这三组数据的生命周期与清理策略都不同，
 * 调试开关不应该跟着主题/新建默认值一起被清。token 也不进日志、不进快照。
 */
class DebugPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    val enabled: Flow<Boolean> =
        context.debugPrefsDataStore.data.map { it[ENABLED_KEY] ?: false }

    val allowControl: Flow<Boolean> =
        context.debugPrefsDataStore.data.map { it[ALLOW_CONTROL_KEY] ?: false }

    val token: Flow<String?> =
        context.debugPrefsDataStore.data.map { it[TOKEN_KEY]?.takeIf { t -> t.isNotBlank() } }

    suspend fun setEnabled(value: Boolean) {
        context.debugPrefsDataStore.edit { it[ENABLED_KEY] = value }
        if (value) ensureToken()
    }

    suspend fun setAllowControl(value: Boolean) {
        context.debugPrefsDataStore.edit { it[ALLOW_CONTROL_KEY] = value }
    }

    /** 首次开启接口时生成并落盘；已存在则原样返回。 */
    suspend fun ensureToken(): String {
        val prefs = context.debugPrefsDataStore.data.first()
        prefs[TOKEN_KEY]?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = newToken()
        context.debugPrefsDataStore.edit { it[TOKEN_KEY] = generated }
        return generated
    }

    /** 重新生成：旧 token 立即失效（服务端每次请求都读当前值）。 */
    suspend fun regenerateToken(): String {
        val generated = newToken()
        context.debugPrefsDataStore.edit { it[TOKEN_KEY] = generated }
        return generated
    }

    companion object {
        private val ENABLED_KEY = booleanPreferencesKey("debug_enabled")
        private val ALLOW_CONTROL_KEY = booleanPreferencesKey("debug_allow_control")
        private val TOKEN_KEY = stringPreferencesKey("debug_token")

        /** 32 字节 SecureRandom → 64 位小写 hex。纯函数，便于单测。 */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

private val Context.debugPrefsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "debug_prefs")
