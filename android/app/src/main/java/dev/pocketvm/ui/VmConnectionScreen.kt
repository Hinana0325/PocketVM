package dev.pocketvm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.ui.theme.MonoTextStyle

/**
 * VNC 连接信息页（P1）。
 *
 * 从控制台概览页拆出来：控制台只留「运行状态 / 按键 / 负载 / 控制」，
 * 连接相关的地址、Display、密码、adb forward、端口转发全放这里。
 * 内容与原概览页的 VNC 卡一字不改，只是换了个位置；「在 Shell 中引导
 * Windows」是控制台操作，不属于连接信息，仍留在概览页的控制指令卡里。
 *
 * 画面本身不在 App 里渲染（PocketVM 没有内置 VNC 视图），这里只给连接信息。
 */
@Composable
fun VmConnectionScreen(
    vm: VmConfig,
    running: Boolean,
    localIp: String? = null,
    networkAvailable: Boolean = true,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        PocketTopBar(
            title = "连接信息",
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
            DisplayProtocolBanner(vm = vm, running = running)

            // P2：本机网络状态（ConnectivityManager 实时推）。VNC 只监听本机，
            // 这里的局域网地址只是参考——电脑仍走 adb forward，不走局域网直连。
            SectionCard(
                title = "本机网络",
                subtitle = "网络一变就更新，不用手动刷新",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        text = if (networkAvailable) "已连接" else "未连接",
                        tone = if (networkAvailable) Tone.Ok else Tone.Error,
                    )
                    Text(
                        localIp ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                KeyValueRow("局域网地址", localIp ?: "无默认网络")
            }

            SectionCard(
                title = "虚拟机画面（VNC）",
                subtitle = "App 不渲染画面，用外部 RFB 客户端连接",
            ) {
                // Stitch 的「Windows 桌面预览 + 任务管理器」是假图：PocketVM 没有
                // 内置 VNC 画面渲染，这里不画假桌面，只放一个诚实的占位说明。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(
                            Icons.Filled.DesktopWindows,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            "画面在外部 VNC 客户端中查看",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "本应用不内置 VNC 画面渲染",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "127.0.0.1:${vm.vncPort}",
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        text = if (running) "监听中" else "未启动",
                        tone = if (running) Tone.Ok else Tone.Idle,
                    )
                    Text(
                        "127.0.0.1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "地址  127.0.0.1:${vm.vncPort}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("127.0.0.1:${vm.vncPort}"))
                            copied = true
                        },
                    ) { Text(if (copied) "已复制" else "复制") }
                }
                KeyValueRow("Display", ":${vm.vncPort - 5900}")
                KeyValueRow("密码", vm.vncPassword?.takeIf { it.isNotBlank() } ?: "未设置")
                Text(
                    "adb forward tcp:${vm.vncPort} tcp:${vm.vncPort}",
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
                Text(
                    "协议 RFB 3.8。P1 不支持局域网 VNC，已下线，请用仅本机 + adb forward：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (vm.portForwards.isNotEmpty()) {
                    Text(
                        "端口转发（手机 → 客户机）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    vm.portForwards.forEach { forward ->
                        KeyValueRow(
                            "127.0.0.1:${forward.hostPort}",
                            "→ 客户机 :${forward.guestPort}",
                        )
                    }
                    Text(
                        "只监听 127.0.0.1：电脑上执行 adb forward tcp:<手机端口> tcp:<手机端口> 后，" +
                            "连接 127.0.0.1:<手机端口>。P1 不支持局域网 VNC，已下线，转发无法改绑 0.0.0.0。" +
                            "转发的服务仍需各自的认证（RDP/SSH 密码等）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "两种「未激活」别混淆：① 屏幕正中出现「Display output is not active.」" +
                        "——这是 QEMU 的占位画面（引导交接后显示输出没恢复），与激活无关；" +
                        "先按 Ctrl+Alt+F1 强制切 TTY，多半能恢复，详见下方排查卡；" +
                        "② 部分 VNC 客户端（尤其商业版）会在画面角落叠加试用遮罩——" +
                        "那是客户端自身水印，与目标系统无关。换用免费客户端即可去除：" +
                        "aVNC（开源）、bVNC Free、RealVNC Viewer 免费版。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running) {
                    Text(
                        "固件若停在 UEFI Shell，App 会通过串口自动输入引导命令，" +
                            "Shell 的回应见控制台「日志」页的「固件输出」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Stitch 的协议横幅：只写真实值——VNC/RFB 3.8、仅本机、显示设备、QEMU 11.1.2 TCG。 */
@Composable
fun DisplayProtocolBanner(vm: VmConfig, running: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Tv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                "VNC · RFB 3.8 · 仅本机",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "显示 ${vm.displayDevice.label} · QEMU 11.1.2 · TCG 软件模拟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusChip(
            text = if (running) "监听中" else "未启动",
            tone = if (running) Tone.Ok else Tone.Idle,
        )
    }
}
