package dev.pocketvm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.RunManifest
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.ui.theme.Danger
import dev.pocketvm.ui.theme.ShapeCard
import dev.pocketvm.ui.theme.Success
import dev.pocketvm.ui.theme.Warning

@Composable
fun VmListScreen(
    state: UiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
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
    var pendingDelete by remember { mutableStateOf<VmConfig?>(null) }
    var envExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroHeader(
                title = "PocketVM",
                subtitle = "ARM64 虚拟机 · TCG 软件模拟 · 无需 Root",
                trailing = {
                    StatusChip(
                        text = if (env?.ready == true) "环境就绪" else "环境未就绪",
                        tone = if (env?.ready == true) Tone.Ok else Tone.Error,
                    )
                },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatPill("虚拟机", "${state.vms.size}", modifier = Modifier.weight(1f))
                StatPill("运行中", if (runningId != null) "1" else "0", modifier = Modifier.weight(1f))
                StatPill(
                    "执行层",
                    if (env?.ready == true) "就绪" else "缺失",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            ReadinessBanner(
                env = env,
                expanded = envExpanded,
                onToggle = { envExpanded = !envExpanded },
                onProbe = onProbe,
            )
        }

        if (state.vms.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = ShapeCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EmptyState(
                        icon = Icons.Filled.Computer,
                        title = "还没有虚拟机",
                        subtitle = "新建后会自动创建 64 GB 精简置备磁盘与独立 UEFI 变量存储。",
                        actionLabel = "新建虚拟机",
                        onAction = onNew,
                    )
                }
            }
        } else {
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
    val otherRunning = runningId != null && !isRunning

    val accent: Color = when {
        isRunning -> Success
        isPreparing || isStopping -> Warning
        failure != null || exit != null -> Danger
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = ShapeCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.Computer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            vm.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            vm.isoPath?.substringAfterLast('/') ?: "未挂载安装介质",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpecChip("${vm.cpuCores} vCPU")
                    SpecChip("${vm.memoryMb} MB")
                    SpecChip("${vm.diskSizeGb} GB")
                    SpecChip(vm.storageController.name.lowercase())
                }

                UsageBar(usedBytes = usedBytes, totalGb = vm.diskSizeGb)

                Text(
                    "光驱 ${vm.cdTransport.label} · 固件 ${vm.firmwareCodePath?.substringAfterLast('/') ?: "内置"}" +
                        " · ${vm.launchMode.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                failure?.let {
                    Text(
                        it.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

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
                        Icon(
                            if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                        Text(
                            if (isRunning) "停止" else "启动",
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    OutlinedButton(onClick = onOpen, enabled = !busy || isRunning) {
                        Text("控制台")
                    }
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
    }
}
