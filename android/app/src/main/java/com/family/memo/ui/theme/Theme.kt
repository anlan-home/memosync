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

// 暖米黄便签主题（对应 ui/prototype.html）
val Amber = Color(0xFFF59E0B)
val AmberDeep = Color(0xFFB45309)
val Ink = Color(0xFF1F1B16)
val Cream = Color(0xFFFDF8EF)
val CardCream = Color(0xFFFFFDF8)
val Line = Color(0xFFEDE6D9)

val MemoCardColors = mapOf(
    "default" to Color(0xFFFFFDF5),
    "lemon" to Color(0xFFFFF3B0),
    "mint" to Color(0xFFCFF3E0),
    "sky" to Color(0xFFD6E9FF),
    "sakura" to Color(0xFFFFE0E6),
    "apricot" to Color(0xFFFFE3C7),
    "lav" to Color(0xFFE8E0FF),
    "gray" to Color(0xFFEEEEEC),
)

private val LightScheme = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDE9C8),
    onPrimaryContainer = AmberDeep,
    secondary = Color(0xFF6E655A),
    background = Cream,
    onBackground = Ink,
    surface = CardCream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EADA),
    onSurfaceVariant = Color(0xFF6E655A),
    outline = Line,
    error = Color(0xFFC0395D),
)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF5C3D00),
    onPrimaryContainer = Color(0xFFFFD9A0),
    secondary = Color(0xFFC4B8A5),
    background = Color(0xFF17140F),
    onBackground = Color(0xFFEDE6D9),
    surface = Color(0xFF201C16),
    onSurface = Color(0xFFEDE6D9),
    surfaceVariant = Color(0xFF2C261E),
    onSurfaceVariant = Color(0xFFB4A995),
    outline = Color(0xFF4A4237),
    error = Color(0xFFFFB2C1),
)

val MemoTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 12.sp),
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
