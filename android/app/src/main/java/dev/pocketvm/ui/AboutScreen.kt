package dev.pocketvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketvm.BuildConfig

@Composable
fun AboutScreen(
    state: UiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroHeader(
                title = "PocketVM",
                subtitle = "在 Android 手机上运行 Windows ARM64 · 无需 Root",
            )
        }
        item {
            SectionCard(title = "当前版本", subtitle = "构建信息") {
                KeyValueRow("版本号", BuildConfig.VERSION_NAME)
                KeyValueRow("运行模式", "TCG 软件模拟 · 单虚拟机")
                KeyValueRow("固件", "edk2 (AAVMF) pflash 64 MiB")
                KeyValueRow("图形", "VNC 服务（外部 RFB 客户端连接）")
            }
        }
        item {
            SectionCard(title = "怎么用", subtitle = "三步跑起 Windows") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "1. 在「环境」页确认执行层已就绪。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "2. 在「虚拟机」页新建一台，导入 Windows 11 ARM64 的 ISO。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "3. 启动后用任意 VNC 客户端连接提示的地址与端口，即可看到安装界面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(title = "Windows 引导要点", subtitle = "从 CD 启动但卡在固件进度条") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "部分机型（如骁龙 8 Gen3）上 QEMU 的 USB 栈会直接崩溃（SIGILL），光驱和 USB 输入都受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "「自动」模式下发生过一次 USB 崩溃后，App 会自动整体回退到 virtio-scsi + virtio 输入并记住该选择；之后 Windows 从 virtio-scsi 读不到安装文件，需挂一张含 VirtIO 驱动（vioscsi）的 AHCI 驱动盘，在 WinPE 里「加载驱动」后再装。Linux 内核自带 virtio 驱动，不受此限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(title = "需要知道", subtitle = "避免踩坑") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "· 不需要 Root，但 QEMU 执行层须作为 lib*.so 打包进应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· 软件模拟，性能远低于原生；装系统耐心等待。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· 同一时间只能运行一台虚拟机；前台切换不会中断运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· 启动失败可在控制台「复制错误」导出诊断包，便于定位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· VNC 客户端若显示「未激活 / Not Activated」水印，是客户端试用遮罩，与系统无关；换免费客户端（aVNC、bVNC Free、RealVNC 免费版）即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
