package dev.pocketvm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VmRuntimeState

/**
 * 虚拟机详情页：管理中心（P1）。
 *
 * 列表点卡片进这里，不再直进控制台。这一页回答「这台虚拟机现在怎么样、
 * 能对它做什么」：
 * - 基本信息：平台、状态、CPU / 内存 / 磁盘；
 * - 快捷操作：启动 / 停止（没有重启：PocketVM 没有重启功能，重启请先停止再启动）；
 * - 运行概览：运行中才有的 CPU / 内存 / 时长（来自 state.metrics，不另外订阅）；
 * - 管理入口：控制台、配置、诊断中心、连接信息（VNC）；
 * - 虚拟机信息：固件 / 介质 / 显示 / 启动模式等完整配置（本页直接展示，
 *   不再单开一页）。
 * 删除也在这一页（二次确认），删完回列表。
 */
@Composable
fun VmDetailScreen(
    state: UiState,
    vm: VmConfig,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenSnapshots: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnection: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val runtime = state.runtimes[vm.id] ?: VmRuntimeState.Idle
    val running = runtime is VmRuntimeState.Running
    val otherRunning = state.runtimes.entries
        .firstOrNull { it.key != vm.id && it.value is VmRuntimeState.Running }
        ?.value as? VmRuntimeState.Running
    val metrics = state.metricsByVm[vm.id]
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
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
                    onClick = { confirmDelete = false; onDelete() },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = vm.name,
            subtitle = vm.platform.label,
            showBack = true,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "基本信息") {
                KeyValueRow("平台", vm.platform.label)
                KeyValueRow(
                    "状态",
                    when (runtime) {
                        is VmRuntimeState.Running -> "运行中 · PID ${runtime.pid}"
                        is VmRuntimeState.Preparing -> "启动中"
                        is VmRuntimeState.Stopping -> "正在关机"
                        is VmRuntimeState.Exited -> "已退出 (exit=${runtime.exitCode})"
                        is VmRuntimeState.Failed -> "启动失败"
                        VmRuntimeState.Idle -> "未运行"
                    },
                )
                KeyValueRow("CPU", "${vm.cpuCores} vCPU")
                KeyValueRow("内存", "${vm.memoryMb} MB")
                if (vm.platform != VmPlatform.APPLE_T8030 && vm.platform != VmPlatform.ANDROID_ARM64) {
                    KeyValueRow("磁盘", "${vm.diskSizeGb} GB（已用 ${(state.diskUsage[vm.id] ?: 0L) / (1024 * 1024)} MB）")
                }
                KeyValueRow("创建时间", DiagnosticsFormat.formatDateTime(vm.createdAtEpochMs))
            }

            SectionCard(title = "快捷操作") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = if (running) onStop else onStart,
                        enabled = !state.busy &&
                            runtime !is VmRuntimeState.Preparing &&
                            runtime !is VmRuntimeState.Stopping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (running) "停止" else "启动") }
                    OutlinedButton(
                        onClick = onOpenConsole,
                        modifier = Modifier.weight(1f),
                    ) { Text("打开控制台") }
                }
                otherRunning?.let {
                    Text(
                        "其他虚拟机也在运行；再启动一台会受设备内存预算约束。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "PocketVM 没有重启功能：重启请先停止再启动；虚拟盘本身就是持久的，不需要快照。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "运行概览", subtitle = "宿主机侧采样（/proc + QMP），不需要客户机装 agent") {
                if (!running || metrics == null) {
                    Text(
                        "虚拟机运行后，这里显示 QEMU 进程的 CPU、内存与运行时长。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    KeyValueRow("vCPU 占用", "${metrics.cpuPercent.toInt()}%")
                    KeyValueRow("内存 RSS", metrics.rssMb?.let { "$it MB" } ?: "不可用")
                    KeyValueRow("已运行", formatUptime(metrics.uptimeMs))
                    KeyValueRow(
                        "磁盘读 / 写",
                        "${metrics.readMBs?.let { String.format(java.util.Locale.US, "%.1f MB/s", it) } ?: "不可用"} / " +
                            "${metrics.writeMBs?.let { String.format(java.util.Locale.US, "%.1f MB/s", it) } ?: "不可用"}",
                    )
                }
            }

            SectionCard(title = "管理", subtitle = "控制台、配置、快照、诊断、连接各是一页") {
                DetailEntry(
                    label = "控制台",
                    summary = "运行状态、日志、性能、SSH、客户机按键",
                    onClick = onOpenConsole,
                )
                DetailEntry(
                    label = "配置",
                    summary = "处理器 / 内存 / 存储 / 设备 / 运行环境",
                    onClick = onOpenConfig,
                )
                DetailEntry(
                    label = "快照与备份",
                    summary = "停止状态下创建/恢复（qcow2 内部快照 + UEFI 变量）",
                    onClick = onOpenSnapshots,
                )
                DetailEntry(
                    label = "诊断中心",
                    summary = "看门狗、性能、卡顿、温控与绑核、会话报告",
                    onClick = onOpenDiagnostics,
                )
                DetailEntry(
                    label = "连接信息",
                    summary = "VNC 地址 127.0.0.1:${vm.vncPort} · 仅本机",
                    onClick = onOpenConnection,
                )
            }

            SectionCard(title = "虚拟机信息", subtitle = "完整配置（只读），改配置请点上方「配置」") {
                if (vm.platform != VmPlatform.APPLE_T8030 && vm.platform != VmPlatform.ANDROID_ARM64) {
                    KeyValueRow("安装介质", vm.isoPath?.substringAfterLast('/') ?: "无")
                    KeyValueRow("驱动盘", vm.driverIsoPath?.substringAfterLast('/') ?: "无")
                    KeyValueRow("固件", vm.firmwareCodePath?.substringAfterLast('/') ?: "内置")
                    KeyValueRow("存储控制器", vm.storageController.name)
                    KeyValueRow("光驱总线", vm.cdTransport.label)
                }
                if (vm.platform == VmPlatform.APPLE_T8030) {
                    val status = state.appleAssets[vm.id]
                    KeyValueRow("iOS 资源", if (status?.present == true) "已导入" else "未导入 / 不完整")
                }
                if (vm.platform == VmPlatform.ANDROID_ARM64) {
                    val sizeMb = (state.androidDisks[vm.id] ?: 0L) / (1024 * 1024)
                    KeyValueRow("磁盘镜像", if (sizeMb > 0) "$sizeMb MB（${state.androidFormats[vm.id] ?: "raw"}）" else "未导入")
                }
                KeyValueRow("显示设备", vm.displayDevice.label)
                KeyValueRow("VNC 端口", "${vm.vncPort}（仅本机）")
                KeyValueRow(
                    "启动模式",
                    when (vm.launchMode) {
                        LaunchMode.AUTO -> "自动（标准 → 兼容 → 最小）"
                        LaunchMode.STANDARD -> "标准"
                        LaunchMode.COMPAT -> "兼容"
                        LaunchMode.MINIMAL -> "最小"
                    },
                )
                if (vm.portForwards.isNotEmpty()) {
                    KeyValueRow(
                        "端口转发",
                        vm.portForwards.joinToString("、") { "${it.hostPort}→${it.guestPort}" },
                    )
                }
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = !running && !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除虚拟机") }
        }
    }
}

/** 详情页的管理入口行：标题 + 一行摘要 + 右箭头。 */
@Composable
private fun DetailEntry(label: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 运行时长：与 ConsoleScreen 的 formatUptime 同款（毫秒 → H:MM:SS / MM:SS）。 */
private fun formatUptime(ms: Long): String {
    val totalSeconds = ms / 1000
    return if (totalSeconds >= 3600) {
        String.format(
            java.util.Locale.US,
            "%d:%02d:%02d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
        )
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
