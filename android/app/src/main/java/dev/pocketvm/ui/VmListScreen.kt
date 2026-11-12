package dev.pocketvm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.storage.AppleAssets
import dev.pocketvm.ui.theme.Danger
import dev.pocketvm.ui.theme.ShapeCard
import dev.pocketvm.ui.theme.ShapeChip
import dev.pocketvm.ui.theme.Success
import dev.pocketvm.ui.theme.Warning

/**
 * 虚拟机列表：布局与组件按 Stitch 首页设计（宿主状态卡片、筛选 chips、
 * 每台虚拟机一张卡片），但只用真实状态、不编造数字：
 *
 * - QEMU 版本取 env?.version，缺失时标 QEMU 11.1.2；显示走 VNC，绝不用 Spice；
 * - 加速是 TCG 软件模拟，绝不用 KVM；只跑 ARM64 客户机，绝不用 x86_64；
 * - SoC 取 env?.soc（本机为 Snapdragon 8 Gen 3 / Adreno 750）。
 *
 * Stitch 里没有真实数据源的元素（宿主实时 CPU%、ZRAM 压缩比、「60 FPS」、
 * 终端快捷入口、模板库、重启/快照按钮）一律不画。
 */
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
    // 多机同跑：按 vmId 取各自状态；准备/运行/停止都算「运行中」。
    fun isLive(id: String): Boolean = when (state.runtimes[id]) {
        is VmRuntimeState.Running, is VmRuntimeState.Preparing, is VmRuntimeState.Stopping -> true
        else -> false
    }
    val runningCount = state.vms.count { isLive(it.id) }
    val stoppedCount = state.vms.size - runningCount
    val env = state.env
    var pendingDelete by remember { mutableStateOf<VmConfig?>(null) }
    var envExpanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(VmFilter.ALL) }

    val visibleVms = when (filter) {
        VmFilter.ALL -> state.vms
        VmFilter.RUNNING -> state.vms.filter { isLive(it.id) }
        VmFilter.STOPPED -> state.vms.filter { !isLive(it.id) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
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
                        onDarkSurface = true,
                    )
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeroStat("虚拟机", "${state.vms.size}", modifier = Modifier.weight(1f))
                    HeroStat("运行中", "$runningCount", modifier = Modifier.weight(1f))
                    HeroStat(
                        "执行层",
                        if (env?.ready == true) "就绪" else "缺失",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            HostHardwareCard(
                env = env,
                deviceMemoryMb = state.deviceMemoryMb,
                freeSpaceMb = state.freeSpaceMb,
                allocatedMemoryMb = state.vms.filter { isLive(it.id) }.sumOf { it.memoryMb },
                diskUsedBytes = state.diskUsage.values.sum(),
            )
        }

        item {
            ReadinessBanner(
                env = env,
                expanded = envExpanded,
                onToggle = { envExpanded = !envExpanded },
                onProbe = onProbe,
            )
        }

        val importing = state.importing
        if (importing != null) {
            item {
                ImportingCard(importing = importing)
            }
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
            item {
                FilterChipsRow(
                    selected = filter,
                    totalCount = state.vms.size,
                    runningCount = runningCount,
                    stoppedCount = stoppedCount,
                    onSelect = { filter = it },
                )
            }
            if (visibleVms.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = ShapeCard,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        EmptyState(
                            icon = Icons.Filled.Computer,
                            title = if (filter == VmFilter.RUNNING) "没有正在运行的虚拟机" else "没有已停止的虚拟机",
                            subtitle = if (filter == VmFilter.RUNNING) "启动一台虚拟机后，它会出现在这里。" else "全部虚拟机都在运行中。",
                        )
                    }
                }
            } else {
                items(visibleVms, key = { it.id }) { vm ->
                    VmCard(
                        vm = vm,
                        usedBytes = state.diskUsage[vm.id] ?: 0L,
                        appleStatus = state.appleAssets[vm.id],
                        androidDiskBytes = state.androidDisks[vm.id] ?: 0L,
                        androidDiskFormat = state.androidFormats[vm.id] ?: "raw",
                        runtime = state.runtimes[vm.id] ?: VmRuntimeState.Idle,
                        runningId = if (state.runtimes[vm.id] is VmRuntimeState.Running) vm.id else null,
                        metrics = state.metricsByVm[vm.id],
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
    }

    pendingDelete?.let { vm ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${vm.name}」？") },
            text = {
                Text(
                    if (vm.platform == VmPlatform.APPLE_T8030) {
                        "已导入的 iOS 资源（含 25 GB 系统盘镜像）与全部日志都会被删除，无法恢复。"
                    } else if (vm.platform == VmPlatform.ANDROID_ARM64) {
                        "已导入的 Android 磁盘镜像与全部日志都会被删除，无法恢复。"
                    } else {
                        "虚拟磁盘（已用 ${(state.diskUsage[vm.id] ?: 0L) / (1024 * 1024)} MB）、" +
                            "UEFI 变量存储、已安装的系统与全部日志都会被删除，无法恢复。"
                    },
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

private enum class VmFilter(val label: String) {
    ALL("全部"),
    RUNNING("运行中"),
    STOPPED("已停止"),
}

/**
 * Stitch 的「宿主硬件分配状态」卡片，只用真实数据：
 * 已分配内存 = 正在运行那台 VM 的 memoryMb / 本机总内存；
 * 磁盘已用 = 全部 disk.qcow2 实际占用之和；QEMU 版本取 env.version。
 * 宿主实时 CPU%、ZRAM、本机剩余存储总量没有数据源，不画。
 */
@Composable
private fun HostHardwareCard(
    env: EnvSnapshot?,
    deviceMemoryMb: Long,
    freeSpaceMb: Long,
    allocatedMemoryMb: Int,
    diskUsedBytes: Long,
) {
    SectionCard(
        title = "宿主硬件",
        subtitle = "TCG 软件模拟 · VNC 显示 · 仅本机访问",
        headerEnd = {
            SpecChip(env?.soc ?: "未知芯片")
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HostMetricBox(
                label = "已分配内存",
                value = if (deviceMemoryMb > 0) "$allocatedMemoryMb MB" else "—",
                subtext = if (deviceMemoryMb > 0) "本机共 $deviceMemoryMb MB" else "本机内存未知",
                fraction = if (deviceMemoryMb > 0) {
                    (allocatedMemoryMb.toFloat() / deviceMemoryMb).coerceIn(0f, 1f)
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            HostMetricBox(
                label = "虚拟磁盘已用",
                value = "${diskUsedBytes / (1024 * 1024)} MB",
                subtext = "剩余空间 ${freeSpaceMb / 1024} GB",
                fraction = null,
                modifier = Modifier.weight(1f),
            )
            HostMetricBox(
                label = "QEMU 版本",
                value = env?.version ?: "11.1.2",
                subtext = "TCG · ARM64 客户机",
                fraction = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HostMetricBox(
    label: String,
    value: String,
    subtext: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (fraction != null) {
            MeterBar(fraction = fraction)
        }
        Text(
            subtext,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Stitch 的筛选 chips：全部 / 运行中 / 已停止（PocketVM 没有模板库，不画）。 */
@Composable
private fun FilterChipsRow(
    selected: VmFilter,
    totalCount: Int,
    runningCount: Int,
    stoppedCount: Int,
    onSelect: (VmFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(VmFilter.ALL, VmFilter.RUNNING, VmFilter.STOPPED).forEach { filter ->
            val isSelected = selected == filter
            val count = when (filter) {
                VmFilter.ALL -> totalCount
                VmFilter.RUNNING -> runningCount
                VmFilter.STOPPED -> stoppedCount
            }
            Surface(
                onClick = { onSelect(filter) },
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = ShapeChip,
            ) {
                Text(
                    "${filter.label} ($count)",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Stitch 的「正在写入镜像」卡片：只在真有导入任务时出现，进度取 importing.fraction。 */
@Composable
private fun ImportingCard(importing: ImportProgress) {
    SectionCard(
        title = "正在导入",
        subtitle = importing.kind.label,
    ) {
        val fraction = importing.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
            Text(
                "已复制 ${importing.copiedBytes / (1024 * 1024)} MB / " +
                    "${(importing.totalBytes ?: 0L) / (1024 * 1024)} MB（${(fraction * 100).toInt()}%）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
            Text(
                "已复制 ${importing.copiedBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VmCard(
    vm: VmConfig,
    usedBytes: Long,
    appleStatus: AppleAssets.Status?,
    androidDiskBytes: Long,
    androidDiskFormat: String,
    runtime: VmRuntimeState,
    runningId: String?,
    metrics: dev.pocketvm.diagnostics.VmMetrics?,
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
    val isApple = vm.platform == VmPlatform.APPLE_T8030
    val isAndroid = vm.platform == VmPlatform.ANDROID_ARM64

    val accent: Color = when {
        isRunning -> Success
        isPreparing || isStopping -> Warning
        failure != null || exit != null -> Danger
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = ShapeCard,
        border = if (isRunning) BorderStroke(1.dp, Success.copy(alpha = 0.35f)) else null,
        // 点卡片空白处直接进控制台；内部按钮仍优先处理自己的点击。
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                vm.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f, false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SpecChip(
                                when {
                                    isApple -> "iPhone 11"
                                    else -> "ARM64"
                                },
                            )
                        }
                        Text(
                            when {
                                isApple -> "iPhone 11（t8030）· iOS"
                                isAndroid -> "Android（ARM64）· UEFI 磁盘镜像"
                                else -> vm.isoPath?.substringAfterLast('/') ?: "未挂载安装介质"
                            },
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

                // Stitch 的硬件拓扑参数网格：vCPU / 内存 / 存储 / 显示（VNC，绝不用 Spice）。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SpecCell(
                                label = "虚拟核心",
                                value = "${vm.cpuCores} vCPU",
                                modifier = Modifier.weight(1f),
                            )
                            SpecCell(
                                label = "内存",
                                value = "${vm.memoryMb} MB",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SpecCell(
                                label = "存储",
                                value = when {
                                    isApple -> if (appleStatus?.present == true) "iOS 资源已导入" else "iOS 资源未导入"
                                    isAndroid -> if (androidDiskBytes > 0) {
                                        "$androidDiskFormat · ${androidDiskBytes / (1024 * 1024)} MB"
                                    } else {
                                        "未导入磁盘镜像"
                                    }
                                    else -> "${vm.diskSizeGb} GB ${vm.storageController.name.lowercase()}"
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SpecCell(
                                label = "显示",
                                value = "${vm.displayDevice.label} · VNC",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (!isApple && !isAndroid) {
                    UsageBar(usedBytes = usedBytes, totalGb = vm.diskSizeGb)
                }

                if (isRunning && metrics != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LiveMetric("CPU", "${metrics.cpuPercent.toInt()}%")
                        LiveMetric("内存", metrics.rssMb?.let { "$it MB" } ?: "—")
                        LiveMetric("I/O", liveIoLabel(metrics))
                        Sparkline(
                            history = metrics.cpuHistory,
                            modifier = Modifier.weight(1f).height(30.dp),
                            color = Success,
                        )
                    }
                }

                Text(
                    if (isApple) {
                        when {
                            appleStatus == null -> "iOS 资源：未导入"
                            appleStatus.present ->
                                "iOS 资源：已导入 · 系统盘 ${appleStatus.rootDiskBytes / (1024 * 1024 * 1024)} GB"
                            else -> "iOS 资源缺少 ${appleStatus.missing.size} 项，请在编辑页导入"
                        }
                    } else if (isAndroid) {
                        if (androidDiskBytes > 0) "磁盘镜像：已导入 $androidDiskFormat · ${androidDiskBytes / (1024 * 1024)} MB" else "磁盘镜像：未导入"
                    } else {
                        "光驱 ${vm.cdTransport.label} · 固件 ${vm.firmwareCodePath?.substringAfterLast('/') ?: "内置"}" +
                            " · ${vm.launchMode.name.lowercase()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isApple && appleStatus?.present != true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("打开控制台")
                    }
                    OutlinedButton(
                        onClick = if (isRunning) onStop else onStart,
                        enabled = when {
                            busy -> false
                            isRunning -> !isStopping
                            isPreparing || isStopping -> false
                            otherRunning -> false
                            else -> true
                        },
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

@Composable
private fun SpecCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun liveIoLabel(m: dev.pocketvm.diagnostics.VmMetrics): String {
    val read = m.readMBs
    val write = m.writeMBs
    if (read == null || write == null) return "—"
    return String.format(java.util.Locale.US, "%.1f MB/s", read + write)
}
