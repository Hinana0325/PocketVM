package dev.pocketvm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.navigation.VmSnapshots
import dev.pocketvm.runtime.VmSession
import dev.pocketvm.storage.SnapshotManager
import dev.pocketvm.storage.SnapshotMeta
import dev.pocketvm.storage.SnapshotPolicy
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 快照页状态：列表 + 创建/恢复/删除。所有写操作都先过 [SnapshotPolicy]
 * （VM 运行/启动中/关机中一律拒绝），失败以 notice 呈现，不崩 UI。
 */
@HiltViewModel
class SnapshotViewModel @Inject constructor(
    private val manager: SnapshotManager,
    private val session: VmSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vmId: String = savedStateHandle.toRoute<VmSnapshots>().vmId

    data class State(
        val snapshots: List<SnapshotMeta> = emptyList(),
        val loading: Boolean = true,
        val busy: String? = null,
        val runningVmId: String? = null,
        val blockReason: String? = null,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            manager.progress.collect { text -> _state.update { it.copy(busy = text) } }
        }
        viewModelScope.launch {
            session.state.collect { runtime ->
                val runningId = session.runningVmId()
                _state.update {
                    it.copy(
                        runningVmId = runningId,
                        blockReason = SnapshotPolicy.blockReason(runningId, runtime),
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val list = manager.list(vmId)
            _state.update { it.copy(snapshots = list, loading = false) }
        }
    }

    fun create(label: String) {
        viewModelScope.launch {
            val result = manager.create(vmId, label.trim())
            _state.update {
                it.copy(notice = result.exceptionOrNull()?.let { e -> "创建失败：${e.message}" } ?: "快照已创建")
            }
            refresh()
        }
    }

    fun restore(snapshotId: String) {
        viewModelScope.launch {
            val result = manager.restore(vmId, snapshotId)
            _state.update {
                it.copy(notice = result.exceptionOrNull()?.let { e -> "恢复失败：${e.message}" } ?: "已恢复到所选快照")
            }
            refresh()
        }
    }

    fun delete(snapshotId: String) {
        viewModelScope.launch {
            val result = manager.delete(vmId, snapshotId)
            _state.update {
                it.copy(notice = result.exceptionOrNull()?.let { e -> "删除失败：${e.message}" } ?: "快照已删除")
            }
            refresh()
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }
}
