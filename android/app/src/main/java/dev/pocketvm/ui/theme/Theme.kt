package dev.pocketvm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFFFB74D)
private val AmberDark = Color(0xFFC8871E)
private val Ink = Color(0xFF0E0E12)
private val Surface = Color(0xFF17171D)
private val SurfaceHigh = Color(0xFF22222B)
private val Muted = Color(0xFF9A9AA6)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = AmberDark,
    onPrimaryContainer = Color(0xFF1A1200),
    secondary = Color(0xFF8FB8FF),
    background = Ink,
    onBackground = Color(0xFFECECF1),
    surface = Surface,
    onSurface = Color(0xFFECECF1),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF3A3A46),
)

private val LightScheme = lightColorScheme(
    primary = AmberDark,
    secondary = Color(0xFF3A5FA8),
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
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
        ),
        content = content,
    )
}
