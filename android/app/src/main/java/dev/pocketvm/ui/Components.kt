package dev.pocketvm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.pocketvm.ui.theme.Danger
import dev.pocketvm.ui.theme.ShapeChip
import dev.pocketvm.ui.theme.ShapeHero
import dev.pocketvm.ui.theme.Success

enum class Tone { Idle, Busy, Ok, Error }

/**
 * A surface card used for every grouped block. Now uses a slightly raised
 * container tone and a larger radius so stacked cards read as layered rather
 * than a flat list of identical boxes.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerEnd: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f, false),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            headerEnd()
        }
        content()
    }
}

@Composable
fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionCard(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        headerEnd = {
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (expanded) content()
    }
}

@Composable
fun StatusChip(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    onDarkSurface: Boolean = false,
) {
    // Hero 那张深色玻璃在浅色主题下也存在，所以不能只看系统明暗。
    val dark = onDarkSurface || isSystemInDarkTheme()
    // 浅色主题下用加深的语义色，16% 透明底上的 11sp 文本也能过 AA（≥4.5:1）。
    val color = when (tone) {
        Tone.Idle -> if (dark) Color(0xFF8A8A8A) else Color(0xFF6B7280)
        Tone.Busy -> if (dark) Color(0xFFC9CDD3) else Color(0xFF3F4756)
        Tone.Ok -> if (dark) Success else Color(0xFF24614A)
        Tone.Error -> if (dark) Danger else Color(0xFFA02020)
    }
    val animated by animateColorAsState(color, animationSpec = tween(400), label = "statusTone")
    // 启动中/停止中时让圆点呼吸；其余状态不创建无限动画，避免常驻重绘。
    val dotAlpha = if (tone == Tone.Busy) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val pulse by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "statusPulseValue",
        )
        pulse
    } else {
        1f
    }
    Row(
        modifier = modifier
            .background(animated.copy(alpha = 0.16f), ShapeChip)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(animated.copy(alpha = dotAlpha), CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = animated,
        )
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(0.65f),
        )
    }
}

// ---- layered / hierarchy primitives added in the redesign ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketTopBar(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        title = {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (showBack && onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = actions,
    )
}

data class NavDestination(val screen: Screen, val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketNavBar(current: Screen, onSelect: (Screen) -> Unit) {
    val items = listOf(
        NavDestination(Screen.List, "虚拟机", Icons.Filled.Computer),
        NavDestination(Screen.Environment, "环境", Icons.Filled.Memory),
        NavDestination(Screen.About, "关于", Icons.Filled.Info),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { dest ->
                    val selected = current == dest.screen
                    val indicator by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else Color.Transparent,
                        animationSpec = tween(300),
                        label = "navIndicator",
                    )
                    val content by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(300),
                        label = "navContent",
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(indicator)
                            .clickable { if (!selected) onSelect(dest.screen) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(dest.icon, contentDescription = dest.label, tint = content, modifier = Modifier.size(20.dp))
                        if (selected) {
                            Text(dest.label, color = content, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 品牌头图：深色灰面 + 灰色光晕 + 渐变字。比纯色渐变更有层次，
 * 也顺手把「运行中」等实时信息收进头图，首页不再是一排孤立的小卡片。
 */
@Composable
fun HeroHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeHero)
            .background(Brush.linearGradient(listOf(Color(0xFF262626), Color(0xFF121212))))
            .drawWithCache {
                val glowCenter = Offset(size.width * 0.92f, size.height * 0.02f)
                val glowRadius = size.width * 0.85f
                val glowBrush = Brush.radialGradient(
                    listOf(Color(0xFFC9CDD3).copy(alpha = 0.22f), Color.Transparent),
                    center = glowCenter,
                    radius = glowRadius,
                )
                val greyCenter = Offset(size.width * 0.02f, size.height * 1.02f)
                val greyRadius = size.width * 0.7f
                val greyBrush = Brush.radialGradient(
                    listOf(Color(0xFF9AA0A6).copy(alpha = 0.14f), Color.Transparent),
                    center = greyCenter,
                    radius = greyRadius,
                )
                onDrawBehind {
                    drawCircle(brush = glowBrush, radius = glowRadius, center = glowCenter)
                    drawCircle(brush = greyBrush, radius = greyRadius, center = greyCenter)
                }
            }
            .border(1.dp, Color(0xFFC9CDD3).copy(alpha = 0.24f), ShapeHero)
            .padding(20.dp),
    ) {
        Text(
            ">_",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
            ),
            color = Color.White.copy(alpha = 0.05f),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = Brush.linearGradient(listOf(Color(0xFFF2F2F2), Color(0xFFC9CDD3))),
                    letterSpacing = 0.4.sp,
                ),
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB9B9C6))
            content?.invoke(this)
        }
        if (trailing != null) {
            Box(Modifier.align(Alignment.TopEnd)) { trailing() }
        }
    }
}

/** 头图里的迷你统计块，半透明白底让它在深色玻璃上自然分层。 */
@Composable
fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = Color(0xFFF3F3F7),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
    }
}

/** 迷你折线：性能页和 VM 卡片共用。 */
@Composable
fun Sparkline(
    history: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    fill: Boolean = true,
    showEndDot: Boolean = true,
) {
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas
        val max = (history.max() ?: 100f).coerceAtLeast(100f)
        val stepX = size.width / (history.size - 1).toFloat()
        fun y(value: Float) = size.height - (value / max * size.height).coerceIn(0f, size.height)
        val line = Path().apply {
            history.forEachIndexed { index, value ->
                val x = index * stepX
                if (index == 0) moveTo(x, y(value)) else lineTo(x, y(value))
            }
        }
        if (fill) {
            val area = Path().apply {
                moveTo(0f, size.height)
                history.forEachIndexed { index, value -> lineTo(index * stepX, y(value)) }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)),
                ),
            )
        }
        drawPath(line, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        if (showEndDot) {
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, y(history.last())))
        }
    }
}

@Composable
fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, ShapeChip)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SpecChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = ShapeChip,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Generic horizontal meter, 0..1. The performance dashboard reuses this for
 * vCPU and memory; [UsageBar] predates it and keeps its own copy.
 */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "meterBar",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surface,
        strokeCap = StrokeCap.Round,
    )
}

@Composable
fun UsageBar(usedBytes: Long, totalGb: Int, modifier: Modifier = Modifier) {
    val totalBytes = totalGb.toLong() * 1024 * 1024 * 1024L
    val fraction = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(600),
        label = "usageBar",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (fraction > 0.9f) Danger else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface,
            strokeCap = StrokeCap.Round,
        )
        Text(
            "虚拟盘已用 ${usedBytes / (1024 * 1024)} MB / $totalGb GB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, ShapeHero),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ReadinessBanner(
    env: EnvSnapshot?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onProbe: () -> Unit,
) {
    val ready = env?.ready == true
    SectionCard(
        title = "执行环境",
        subtitle = if (ready) "已就绪，可以启动虚拟机" else "尚未就绪，先看看下面的说明",
        headerEnd = {
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(text = if (ready) "已就绪" else "未就绪", tone = if (ready) Tone.Ok else Tone.Error)
            TextButton(onClick = onProbe) { Text("重新检测") }
        }
        if (expanded) {
            env?.let { e ->
                KeyValueRow("版本", e.version ?: "未知")
                KeyValueRow("来源", e.origin ?: "—")
                KeyValueRow("qemu-img", e.imageTool ?: "未打包（回退 Kotlin 写盘）")
                KeyValueRow("数据目录 -L", e.dataDir ?: "使用内置路径")
                e.soc?.let { KeyValueRow("SoC", it) }
                e.pageSizeKb?.takeIf { it > 0 }?.let { KeyValueRow("页大小", "$it KB") }
                KeyValueRow(
                    "静态执行自检",
                    when (e.staticExec) {
                        true -> "通过"
                        false -> "失败：本机无法运行静态二进制"
                        null -> "未打包探针"
                    },
                )
                KeyValueRow("路径", e.binaryPath ?: e.detail ?: "—")
                if (e.staticExec == false || !ready) {
                    Text(
                        "把 qemu-system-aarch64 重命名为 libqemu-system-aarch64.so，放入 " +
                            "app/src/main/jniLibs/arm64-v8a/ 后重新构建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } ?: Text(
                "尚未检测，点击「重新检测」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
