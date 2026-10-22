package dev.pocketvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState

@Composable
fun VmListScreen(
    state: UiState,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onDelete: (String) -> Unit,
    onProbe: () -> Unit,
) {
    val runningId = (state.runtime as? VmRuntimeState.Running)?.vmId
    val env = state.env
    // Deleting a VM removes its disk *and* the installed Windows; never do that
    // on a single tap.
    var pendingDelete by remember { mutableStateOf<VmConfig?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建虚拟机") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("PocketVM", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "ARM64 虚拟机 · TCG 软件模拟 · 无 Root",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(
                    title = "执行环境",
                    subtitle = "P0 门槛：QEMU 能被真正执行，而不只是文件存在",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(
                            text = if (env?.ready == true) "已就绪" else "未就绪",
                            tone = if (env?.ready == true) Tone.Ok else Tone.Error,
                        )
                        TextButton(onClick = onProbe) { Text("重新检测") }
                    }
                    if (env != null) {
                        KeyValueRow("版本", env.version ?: "未知")
                        KeyValueRow("来源", env.origin ?: "—")
                        KeyValueRow("qemu-img", env.imageTool ?: "未打包（回退 Kotlin 写盘）")
                        KeyValueRow("数据目录 -L", env.dataDir ?: "使用内置路径")
                        KeyValueRow(
                            "静态执行自检",
                            when (env.staticExec) {
                                true -> "通过"
                                false -> "失败：本机无法运行静态二进制"
                                null -> "未打包探针"
                            },
                        )
                        if (env.staticExec == false) {
                            Text(
                                "本机 bionic 不支持静态可执行文件的自举，QEMU 必须以动态链接（PIE）方式构建，" +
                                    "由 /system/bin/linker64 加载。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        KeyValueRow(
                            "路径",
                            env.binaryPath ?: env.detail ?: "—",
                        )
                        if (!env.ready) {
                            Text(
                                "把 qemu-system-aarch64 重命名为 libqemu-system-aarch64.so，" +
                                    "放入 app/src/main/jniLibs/arm64-v8a/ 后重新构建。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "虚拟机",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.vms.isEmpty()) {
                item {
                    SectionCard(title = "还没有虚拟机", subtitle = "新建后会自动创建 64 GB 精简置备磁盘与独立 UEFI 变量存储") {
                        Text(
                            "先确认执行环境就绪，再准备 QEMU_EFI.fd 与 Windows ARM64 ISO。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(state.vms, key = { it.id }) { vm ->
                VmCard(
                    vm = vm,
                    usedBytes = state.diskUsage[vm.id] ?: 0L,
                    runtime = state.runtime,
                    runningId = runningId,
                    busy = state.busy,
                    lastRun = state.lastRuns[vm.id],
                    onOpen = { onOpen(vm.id) },
                    onEdit = { onEdit(vm.id) },
                    onStart = { onStart(vm.id) },
                    onStop = { onStop(vm.id) },
                    onDelete = { pendingDelete = vm },
                )
            }
        }
    }

    pendingDelete?.let { vm ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${vm.name}」？") },
            text = {
                Text(
                    "虚拟磁盘（已用 ${(state.diskUsage[vm.id] ?: 0L) / (1024 * 1024)} MB）、" +
                        "UEFI 变量存储、已安装的系统与全部日志都会被删除，无法恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(vm.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun VmCard(
    vm: VmConfig,
    usedBytes: Long,
    runtime: VmRuntimeState,
    runningId: String?,
    busy: Boolean,
    lastRun: RunManifest?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val isRunning = runningId == vm.id
    val isPreparing = (runtime as? VmRuntimeState.Preparing)?.vmId == vm.id
    val isStopping = (runtime as? VmRuntimeState.Stopping)?.vmId == vm.id
    val failure = (runtime as? VmRuntimeState.Failed)?.takeIf { it.vmId == vm.id }
    val exit = (runtime as? VmRuntimeState.Exited)?.takeIf { it.vmId == vm.id }
    // One QEMU at a time: starting a second VM would only fail after the tap.
    val otherRunning = runningId != null && !isRunning

    SectionCard(title = vm.name) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                text = when {
                    isRunning -> "运行中 :${vm.vncPort}"
                    isPreparing -> (runtime as VmRuntimeState.Preparing).step
                    isStopping -> "停止中"
                    failure != null -> "启动失败"
                    exit != null -> "已退出 (exit=${exit.exitCode})"
                    else -> "已停止"
                },
                tone = when {
                    isRunning -> Tone.Ok
                    isPreparing || isStopping -> Tone.Busy
                    failure != null || exit != null -> Tone.Error
                    else -> Tone.Idle
                },
            )
            Text(
                "${vm.cpuCores} vCPU · ${vm.memoryMb} MB · ${vm.diskSizeGb} GB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            vm.isoPath?.substringAfterLast('/') ?: "未挂载安装介质",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            "虚拟盘已用 ${usedBytes / (1024 * 1024)} MB / ${vm.diskSizeGb} GB · ${vm.storageController.name.lowercase()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // A failed launch used to look identical to a clean stop: the user had
        // to open the console to discover why nothing happened.
        failure?.let {
            Text(
                it.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Evidence from a previous run that never ended cleanly (app killed,
        // process crash, device reboot). Nothing in the UI used to mention it.
        if (failure == null && !isRunning && !isPreparing && !isStopping) {
            when (lastRun?.outcome) {
                RunManifest.Outcome.EXITED_ERROR -> Text(
                    "上次运行异常退出（exit=${lastRun.exitCode ?: "?"}），下次启动会自动检查磁盘",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RunManifest.Outcome.RUNNING, RunManifest.Outcome.STARTING -> Text(
                    "上次会话未正常结束（应用被系统终止），下次启动会自动检查磁盘",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Unit
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = if (isRunning) onStop else onStart,
                enabled = when {
                    busy -> false
                    isRunning -> !isStopping
                    isPreparing || isStopping -> false
                    otherRunning -> false
                    else -> true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(if (isRunning) "停止" else "启动", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = onOpen,
                enabled = !busy || isRunning,
            ) { Text("控制台") }
            TextButton(
                onClick = onEdit,
                enabled = !isRunning && !isPreparing && !isStopping && !busy,
            ) { Text("编辑") }
            TextButton(
                onClick = onDelete,
                enabled = !isRunning && !isPreparing && !isStopping && !busy,
            ) { Text("删除") }
        }

        if (otherRunning) {
            Text(
                "同一时间只能运行一台虚拟机，请先停止正在运行的那台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
