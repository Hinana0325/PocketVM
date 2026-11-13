package dev.pocketvm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.navigation.VmSnapshots
import dev.pocketvm.runtime.VmSessionHost
import dev.pocketvm.storage.DiskConverter
import dev.pocketvm.storage.SnapshotManager
import dev.pocketvm.storage.SnapshotMeta
import dev.pocketvm.storage.SnapshotPolicy
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快照页状态：列表 + 创建/恢复/删除。所有写操作都先过 [SnapshotPolicy]
 * （VM 运行/启动中/关机中一律拒绝），失败以 notice 呈现，不崩 UI。
 */
@HiltViewModel
class SnapshotViewModel @Inject constructor(
    private val manager: SnapshotManager,
    private val converter: DiskConverter,
    private val sessions: VmSessionHost,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vmId: String = savedStateHandle.toRoute<VmSnapshots>().vmId

    data class State(
        val snapshots: List<SnapshotMeta> = emptyList(),
        val loading: Boolean = true,
        val busy: String? = null,
        val runningVmId: String? = null,
        val blockReason: String? = null,
        /** 运行时快照的门禁：非 null 表示现在不能做（没在跑 / 正在启动或关机）。 */
        val liveBlockReason: String? = null,
        /** 磁盘当前格式；null = 磁盘还没建。raw 不支持快照，需要先转换。 */
        val diskFormat: DiskConverter.Format? = null,
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
            sessions.states.collect { map ->
                val runtime = map[vmId] ?: VmRuntimeState.Idle
                _state.update {
                    it.copy(
                        runningVmId = if (sessions.isRunning(vmId)) vmId else null,
                        blockReason = SnapshotPolicy.blockReason(
                            if (sessions.isRunning(vmId)) vmId else null,
                            runtime,
                        ),
                        liveBlockReason = SnapshotPolicy.liveBlockReason(
                            if (sessions.isRunning(vmId)) vmId else null,
                            runtime,
                        ),
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val list = manager.list(vmId)
            // 读魔数是文件 IO，别放在主线程。
            val format = withContext(Dispatchers.IO) { converter.currentFormat(vmId) }
            _state.update { it.copy(snapshots = list, loading = false, diskFormat = format) }
        }
    }

    /**
     * raw → qcow2。raw 在 FUSE 上会吃掉整个虚拟容量，而且不支持内部快照，
     * 所以导入 raw 镜像后应该尽早转。
     */
    fun convertToQcow2() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在转换磁盘格式（大磁盘可能要几分钟，请勿退出）…") }
            val result = converter.convert(vmId, DiskConverter.Format.QCOW2)
            _state.update {
                it.copy(
                    busy = null,
                    notice = result.exceptionOrNull()?.let { e -> "转换失败：${e.message}" }
                        ?: "已转换为 qcow2，现在可以创建快照了",
                )
            }
            refresh()
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

    /** 运行中快照：不用关机，但只覆盖磁盘 + 内存（不含 UEFI 变量/TPM）。 */
    fun createLive(label: String) {
        viewModelScope.launch {
            val result = manager.createLive(vmId, label.trim())
            _state.update {
                it.copy(
                    notice = result.exceptionOrNull()?.let { e -> "运行时快照失败：${e.message}" }
                        ?: "运行时快照已创建（不含 UEFI 变量/TPM）",
                )
            }
            refresh()
        }
    }

    fun restoreLive(snapshotId: String) {
        viewModelScope.launch {
            val result = manager.restoreLive(vmId, snapshotId)
            _state.update {
                it.copy(
                    notice = result.exceptionOrNull()?.let { e -> "恢复失败：${e.message}" }
                        ?: "已恢复到运行时快照",
                )
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
