package dev.pocketvm.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketvm.gsi.GsiAssembler
import dev.pocketvm.storage.VmStorageLayout
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GSI 组件拼装页状态：导入 kernel/ramdisk/system/vendor → 拼 GPT 物理分区盘。
 * 只做拼装；启动链（first-stage / 显示）未打通的限制在 UI 里如实标注。
 */
@HiltViewModel
class GsiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assembler: GsiAssembler,
    private val layout: VmStorageLayout,
) : ViewModel() {

    data class State(
        val vmId: String? = null,
        val components: Map<GsiAssembler.Component, Long> = emptyMap(),
        val busy: Boolean = false,
        val progress: String? = null,
        val notice: String? = null,
        val diskExists: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun attach(vmId: String) {
        if (_state.value.vmId == vmId) {
            refresh()
            return
        }
        _state.value = State(vmId = vmId)
        refresh()
    }

    fun refresh() {
        val vmId = _state.value.vmId ?: return
        viewModelScope.launch {
            val components = withContext(Dispatchers.IO) {
                assembler.importedComponents(vmId).mapValues { it.value.length() }
            }
            val disk = withContext(Dispatchers.IO) {
                layout.diskFile(vmId).takeIf { it.isFile && it.length() > 0 }?.length() ?: 0L
            }
            _state.update { it.copy(components = components, diskExists = disk > 0) }
        }
    }

    fun import(component: GsiAssembler.Component, uri: Uri) {
        val vmId = _state.value.vmId ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "导入 ${component.label}…", notice = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val target = assembler.componentFile(vmId, component)
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "${target.name}.part")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().buffered(1 shl 20).use { output ->
                            val buffer = ByteArray(1 shl 20)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                            }
                        }
                    } ?: error("无法读取所选文件")
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    target
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    progress = null,
                    notice = result.exceptionOrNull()?.let { e -> "导入失败：${e.message}" }
                        ?: "已导入 ${component.label}",
                )
            }
            refresh()
        }
    }

    fun assemble(userdataGb: Int, replaceExisting: Boolean) {
        val vmId = _state.value.vmId ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "准备拼装…", notice = null) }
            val result = assembler.assemble(
                vmId = vmId,
                userdataGb = userdataGb,
                replaceExisting = replaceExisting,
                onProgress = { text -> _state.update { it.copy(progress = text) } },
            )
            _state.update {
                it.copy(
                    busy = false,
                    progress = null,
                    notice = result.exceptionOrNull()?.let { e -> "拼装失败：${e.message}" }
                        ?: "拼装完成：磁盘已生成，可直接启动（实验特性，启动链未打通）",
                )
            }
            refresh()
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }
}
