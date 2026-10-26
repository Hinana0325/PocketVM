package dev.pocketvm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- brand palette (amber identity, kept across the redesign) ----
private val Amber = Color(0xFFFFB74D)
private val AmberDark = Color(0xFFC8871E)
private val Ink = Color(0xFF0E0E12)
private val Surface = Color(0xFF17171D)
private val SurfaceHigh = Color(0xFF22222B)
private val SurfaceHighest = Color(0xFF2A2A34)
private val Muted = Color(0xFF9A9AA6)

/** Success green, shared with StatusChip(Tone.Ok) so chips and text agree. */
val Success = Color(0xFF5DD39E)
/** Warning amber, for staged/transient states. */
val Warning = Amber
/** Destructive red, for failures and delete confirmations. */
val Danger = Color(0xFFFF6B6B)

/**
 * The hero gradient: a warm amber sweep that reads as "PocketVM" at a glance
 * while staying dark-mode friendly. Used on the list header and empty states.
 */
val HeroGradient = Brush.verticalGradient(
    listOf(
        Color(0xFFFFC163),
        Color(0xFFE08A1E),
        Color(0xFF8A530C),
    ),
)

/** Larger radius for hero/cards so surfaces feel layered rather than flat. */
val ShapeHero = RoundedCornerShape(24.dp)
val ShapeCard = RoundedCornerShape(16.dp)
val ShapeChip = RoundedCornerShape(50.dp)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = AmberDark,
    onPrimaryContainer = Color(0xFF1A1200),
    secondary = Color(0xFF8FB8FF),
    onSecondary = Color(0xFF08203F),
    secondaryContainer = Color(0xFF233A57),
    onSecondaryContainer = Color(0xFFD9E2FF),
    background = Ink,
    onBackground = Color(0xFFECECF1),
    surface = Surface,
    onSurface = Color(0xFFECECF1),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    error = Danger,
    onError = Color(0xFF2A0505),
    outline = Color(0xFF3A3A46),
    outlineVariant = Color(0xFF2B2B35),
    scrim = Color(0xFF000000),
)

/**
 * 浅色主题用「暖纸」基调：背景比纯白柔和，卡片是更浅的奶油色，
 * 主色改成更深的琥珀以保证文字对比度（#B8730E on #FAF6EE ≈ 4.9:1）。
 */
private val LightScheme = lightColorScheme(
    // 深一档的琥珀：在暖纸背景上 4.58:1，按钮白字 4.93:1（WCAG AA）。
    primary = Color(0xFFA65F00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE1B0),
    onPrimaryContainer = Color(0xFF3A2400),
    secondary = Color(0xFF3A5FA8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E2FF),
    onSecondaryContainer = Color(0xFF001945),
    background = Color(0xFFFAF6EE),
    onBackground = Color(0xFF1C1A16),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1C1A16),
    surfaceVariant = Color(0xFFEDE5D8),
    onSurfaceVariant = Color(0xFF6B6455),
    surfaceContainer = Color(0xFFF6F0E5),
    surfaceContainerHigh = Color(0xFFF1EADC),
    surfaceContainerHighest = Color(0xFFEAE1D2),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFD5C9B6),
    outlineVariant = Color(0xFFE5DCCB),
    scrim = Color(0xFF000000),
)

val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

@Composable
fun PocketVmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(
            displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.5.sp),
        ),
        content = content,
    )
}
