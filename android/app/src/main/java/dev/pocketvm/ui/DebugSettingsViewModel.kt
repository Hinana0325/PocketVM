package dev.pocketvm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketvm.data.DebugPreferences
import dev.pocketvm.debug.DebugServerManager
import dev.pocketvm.debug.DebugServerState
import dev.pocketvm.debug.DebugSnapshotExporter
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 诊断中心「开发者调试」区的状态（docs/debug-api.md §6）。
 * 只做开关、token 展示/重生成与快照导出；服务器本体在 [DebugServerManager]。
 */
@HiltViewModel
class DebugSettingsViewModel @Inject constructor(
    private val prefs: DebugPreferences,
    private val manager: DebugServerManager,
    private val exporter: DebugSnapshotExporter,
) : ViewModel() {

    data class State(
        val enabled: Boolean = false,
        val allowControl: Boolean = false,
        val token: String = "",
        val server: DebugServerState = DebugServerState.Stopped,
        val exporting: Boolean = false,
        val shareFile: File? = null,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.enabled.collect { on -> _state.update { it.copy(enabled = on) } }
        }
        viewModelScope.launch {
            prefs.allowControl.collect { on -> _state.update { it.copy(allowControl = on) } }
        }
        viewModelScope.launch {
            prefs.token.collect { token -> _state.update { it.copy(token = token.orEmpty()) } }
        }
        viewModelScope.launch {
            manager.state.collect { server -> _state.update { it.copy(server = server) } }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setEnabled(value) }
    }

    fun setAllowControl(value: Boolean) {
        viewModelScope.launch { prefs.setAllowControl(value) }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            prefs.regenerateToken()
            _state.update { it.copy(notice = "已生成新 token，旧 token 立即失效") }
        }
    }

    fun exportSnapshot() {
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, notice = null) }
            val result = exporter.export()
            _state.update {
                it.copy(
                    exporting = false,
                    shareFile = result.getOrNull(),
                    notice = result.exceptionOrNull()?.let { e -> "快照导出失败：${e.message}" },
                )
            }
        }
    }

    fun consumeShare() = _state.update { it.copy(shareFile = null) }

    fun clearNotice() = _state.update { it.copy(notice = null) }
}
