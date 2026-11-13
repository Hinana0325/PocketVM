package dev.pocketvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.storage.DiskConverter
import dev.pocketvm.storage.SnapshotMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快照与备份（roadmap Snapshot 的第一个落地点）：qcow2 内部快照 + UEFI 变量
 * （与 TPM 状态，如存在）。只允许在 VM 停止时操作；恢复前自动建保险快照。
 */
@Composable
fun VmSnapshotsScreen(
    vm: VmConfig,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    snapVm: SnapshotViewModel = hiltViewModel(),
) {
    val st by snapVm.state.collectAsState()
    var label by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<SnapshotMeta?>(null) }
    var pendingDelete by remember { mutableStateOf<SnapshotMeta?>(null) }

    val blocked = st.blockReason != null
    val working = st.busy != null
    // 运行中只允许「运行时快照」：不用关机，但没有 UEFI 变量/TPM 的副本。
    val live = st.runningVmId != null
    val liveBlocked = st.liveBlockReason != null

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = "快照与备份",
            subtitle = vm.name,
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
            SectionCard(title = "说明", subtitle = "qcow2 内部快照 · 停止态或运行态") {
                Text(
                    "快照会记录磁盘当前状态（qcow2 内部快照，秒级、不复制整块盘）。" +
                        "**停机快照**额外另存 UEFI 变量（vars.fd）与 TPM 状态，恢复前会自动为当前" +
                        "状态建一份「自动快照」，误恢复可以再回滚。" +
                        "**运行时快照**不用关机，但只覆盖磁盘与内存，UEFI 变量 / TPM 不随快照回滚。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (blocked && !live) {
                    Text(
                        st.blockReason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (live && liveBlocked) {
                    Text(
                        st.liveBlockReason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // raw 磁盘既不能做内部快照，又会按虚拟容量占存储，给一个转换入口。
            if (st.diskFormat == DiskConverter.Format.RAW) {
                SectionCard(title = "磁盘格式", subtitle = "raw 不支持快照") {
                    Text(
                        "当前是 raw 镜像：在 FUSE 上会按虚拟容量占用存储，也不能创建快照。" +
                            "转换成 qcow2 后占用按实际写入增长，并且能使用下面的快照功能。" +
                            "转换期间原盘会保留一份备份，失败不会丢数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { snapVm.convertToQcow2() },
                        enabled = !live && !blocked && !working,
                    ) { Text("转换为 qcow2") }
                }
            }

            SectionCard(
                title = if (live) "新建运行时快照" else "新建快照",
                subtitle = if (live) "不用关机 · 含内存，可能要等几十秒" else "名称可留空，自动用时间",
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("快照名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (live) snapVm.createLive(label) else snapVm.create(label)
                        label = ""
                    },
                    enabled = !working && if (live) !liveBlocked else !blocked,
                ) { Text(if (live) "创建运行时快照" else "创建快照") }
            }

            st.busy?.let { text ->
                SectionCard(title = "进行中", subtitle = "操作完成前请勿退出") {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionCard(title = "已有快照", subtitle = "共 ${st.snapshots.size} 个") {
                when {
                    st.loading -> Text("读取中…", style = MaterialTheme.typography.bodySmall)
                    st.snapshots.isEmpty() -> Text(
                        "还没有快照。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> st.snapshots.forEach { snap ->
                        SnapshotRow(
                            snap = snap,
                            // 运行时快照要求 VM 在跑；停机快照要求 VM 已停止。
                            enabled = if (snap.live) {
                                !liveBlocked && !working
                            } else {
                                !blocked && !working
                            },
                            onRestore = { pendingRestore = snap },
                            onDelete = { pendingDelete = snap },
                        )
                    }
                }
            }

            st.notice?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = snapVm::clearNotice) { Text("知道了") }
            }
        }
    }

    pendingRestore?.let { snap ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复到「${snap.label}」？") },
            text = {
                Text(
                    if (snap.live) {
                        "磁盘与内存会回到该快照的状态，虚拟机继续运行、不用重启。" +
                            "运行时快照不含 UEFI 变量与 TPM 状态，这两项保持当前值。"
                    } else {
                        "当前磁盘与 UEFI 变量会回到该快照的状态。恢复前会先为当前状态创建自动快照，" +
                            "误操作可以再回滚。恢复要求虚拟机已停止。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (snap.live) snapVm.restoreLive(snap.id) else snapVm.restore(snap.id)
                    pendingRestore = null
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }

    pendingDelete?.let { snap ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除快照「${snap.label}」？") },
            text = { Text("只删除这个快照与它的变量副本，不影响当前磁盘内容。") },
            confirmButton = {
                TextButton(onClick = {
                    snapVm.delete(snap.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SnapshotRow(
    snap: SnapshotMeta,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val stamp = remember(snap.createdAtEpochMs) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(snap.createdAtEpochMs))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(snap.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$stamp · " + when {
                        snap.live -> "运行时快照（不含 UEFI/TPM）"
                        snap.auto -> "自动快照"
                        else -> "手动快照"
                    } + (if (snap.tpmIncluded) " · 含 TPM 状态" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestore, enabled = enabled) { Text("恢复") }
            OutlinedButton(onClick = onDelete, enabled = enabled) { Text("删除") }
        }
    }
}
