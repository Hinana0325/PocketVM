package dev.pocketvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EnvironmentScreen(
    state: UiState,
    modifier: Modifier = Modifier,
    onProbe: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroHeader(
                title = "执行环境",
                subtitle = "QEMU 执行层 · 固件 · 设备能力",
                trailing = {
                    StatusChip(
                        text = if (state.env?.ready == true) "已就绪" else "未就绪",
                        tone = if (state.env?.ready == true) Tone.Ok else Tone.Error,
                        onDarkSurface = true,
                    )
                },
            ) {
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeroStat(
                        "执行层",
                        state.env?.version ?: "未知",
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    )
                    HeroStat(
                        "页大小",
                        state.env?.pageSizeKb?.takeIf { it > 0 }?.let { "$it KB" } ?: "—",
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    )
                    HeroStat(
                        "SoC",
                        state.env?.soc ?: "—",
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            ReadinessBanner(
                env = state.env,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onProbe = onProbe,
            )
        }
        state.env?.takeIf { it.staticExec == false }?.let { env ->
            item {
                SectionCard(title = "本机限制", subtitle = "不是 PocketVM 的 bug，是设备 bionic 的限制") {
                    Text(
                        "你的系统无法自举静态可执行文件，因此 QEMU 必须以动态链接（PIE）方式构建，" +
                            "由 /system/bin/linker64 加载。只要放进正确的 lib*.so 就能跑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(title = "它在测什么", subtitle = "P0 门槛：QEMU 真能执行，而不只是文件存在") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "· 执行层是否能被 linker64 加载（命名、SONAME、依赖闭包）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· 依赖库（glib / pixman / slirp / pcre2 / intl）是否一同就位",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· edk2 UEFI 固件是否为 64 MiB 的 pflash 镜像（2 MiB 的 -bios 文件会被拒绝）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "· SoC 与页大小，用于规避部分机型上的 TCG 分配失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
