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
                        "「自动」模式下发生过一次 USB 崩溃后，App 会自动整体回退到 virtio-scsi + virtio 输入并记住该选择；" +
                            "之后 Windows 从 virtio-scsi 读不到安装文件，需挂一张含 VirtIO 驱动（vioscsi）的驱动盘，" +
                            "在 WinPE 里「加载驱动」后再装。Linux 内核自带 virtio 驱动，不受此限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(title = "Windows 安装配方", subtitle = "社区多份独立教程验证过的组合") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "1. 镜像选 Windows 11 ARM64 Insider 22589 或更新的可用版本（25226 已知必坏：启动即未处理异常）；" +
                            "Win11 的 TPM 检查用 Shift+F10 打开命令提示符，以注册表 LabConfig 绕过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "2. 显示设备保持 ramfb（默认值）：virtio-gpu 的 GOP 没有线性帧缓冲，Windows " +
                            "引导管理器清屏时会写空指针并 Data Abort，安装程序根本起不来；ramfb 是实测唯一" +
                            "能进 Windows 安装界面的设备。ramfb 固定 800x600，固件界面按 F10 可调高" +
                            "（写入变量存储，重启保留）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "3. 挂 VirtIO 驱动盘（virtio-win ISO）后，在 WinPE「加载驱动」里进 驱动盘盘符 → " +
                            "vioscsi → w11 → ARM64（认准含 vioscsi.inf 的目录）。挂驱动盘时安装光盘只走 virtio-scsi：" +
                            "两个 ISO 设备同屏会让 Windows 安装冻结。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "4. 安装假死（Getting ready 超过 10 分钟不动 / 看门狗 BSOD）就重启，TCG 下这是常态；" +
                            "提示 Windows installation cannot proceed 时：Shift+F10 → regedit → " +
                            "HKLM\\SYSTEM\\Setup\\Status\\ChildCompletion\\Setup 改成 3 → 重启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "5. 首次设置（国家/键盘）界面按 Shift+F10 开命令提示符，执行 bcdedit /set TESTSIGNING ON，" +
                            "否则 virtio 驱动装不上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "6. 进桌面后从驱动盘把 NetKVM、viostor、vioscsi、viogpudo 的 .inf（w11\\ARM64 目录）" +
                            "右键安装；遇签名提示就先把 cert 目录里的证书装进受信任的发布者。之后可把显示切回 virtio-gpu。",
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
