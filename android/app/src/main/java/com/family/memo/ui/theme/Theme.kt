package com.family.memo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 视觉语言参考 Miuix / HyperOS：
// 中性浅灰页面底 + 纯白分组卡片 + 淡分隔线，琥珀色只作为强调色。
val Amber = Color(0xFFF59E0B)
val AmberDeep = Color(0xFFB45309)
val Ink = Color(0xFF0F0F11)

val PageLight = Color(0xFFF5F5F7)
val CardWhite = Color(0xFFFFFFFF)
val DividerLight = Color(0xFFEDEDEF)
val TextSecondary = Color(0xFF8A8A90)
val TextTertiary = Color(0xFFB0B0B6)

// 便签卡片 8 色（降饱和、扁平）
val MemoCardColors = mapOf(
    "default" to Color(0xFFFFFFFF),
    "lemon" to Color(0xFFFFF8D9),
    "mint" to Color(0xFFE3F7EC),
    "sky" to Color(0xFFE6F1FF),
    "sakura" to Color(0xFFFFEDF1),
    "apricot" to Color(0xFFFFF0E0),
    "lav" to Color(0xFFF0EAFF),
    "gray" to Color(0xFFF2F2F4),
)

private val LightScheme = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF3DC),
    onPrimaryContainer = AmberDeep,
    inversePrimary = Color(0xFFFFD9A0),

    secondary = Color(0xFF6E6E74),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEFF1),
    onSecondaryContainer = Color(0xFF3A3A3E),

    tertiary = AmberDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF0E0),
    onTertiaryContainer = Color(0xFF4A2E05),

    background = PageLight,
    onBackground = Ink,
    surface = PageLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = TextSecondary,
    surfaceTint = Color.Transparent,

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEDEDEF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F7F9),
    surfaceContainerHighest = Color(0xFFEFEFF1),

    outline = Color(0xFFE4E4E8),
    outlineVariant = DividerLight,
    inverseSurface = Color(0xFF26262A),
    inverseOnSurface = Color(0xFFF5F5F7),
    error = Color(0xFFD1455F),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E8),
    onErrorContainer = Color(0xFF41000F),
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF4A3208),
    onPrimaryContainer = Color(0xFFFFD9A0),
    inversePrimary = Color(0xFF8A5A00),

    secondary = Color(0xFFB8B8BE),
    onSecondary = Color(0xFF232326),
    secondaryContainer = Color(0xFF2A2A2E),
    onSecondaryContainer = Color(0xFFE8E8EC),

    tertiary = Color(0xFFE0B27A),
    onTertiary = Color(0xFF2E2010),
    tertiaryContainer = Color(0xFF3B2A16),
    onTertiaryContainer = Color(0xFFFFE3C7),

    background = Color(0xFF0F0F11),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF0F0F11),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFF9A9AA0),
    surfaceTint = Color.Transparent,

    surfaceBright = Color(0xFF2C2C31),
    surfaceDim = Color(0xFF0B0B0D),
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xFF17171A),
    surfaceContainer = Color(0xFF1C1C20),
    surfaceContainerHigh = Color(0xFF242428),
    surfaceContainerHighest = Color(0xFF2C2C31),

    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF26262A),
    inverseSurface = Color(0xFFECECEF),
    inverseOnSurface = Color(0xFF26262A),
    error = Color(0xFFFFB2C1),
    onError = Color(0xFF5C0A22),
    errorContainer = Color(0xFF7A1B36),
    onErrorContainer = Color(0xFFFFDADF),
    scrim = Color.Black,
)

val MemoTypography = Typography(
    // 页面大标题（HyperOS 风格：大号、左对齐）
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

/** theme: system / light / dark */
@Composable
fun MemoTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MemoTypography,
        content = content,
    )
}
