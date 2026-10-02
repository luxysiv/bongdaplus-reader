package vn.bongdaplus.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Xanh lá thương hiệu BongdaPlus
private val Green40 = Color(0xFF1B7A43)
private val Green80 = Color(0xFF4CAF7D)
private val GreenContainerL = Color(0xFFDFF3E6)
private val RedHot = Color(0xFFC62828)

private val LightScheme = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = GreenContainerL,
    onPrimaryContainer = Color(0xFF0B3D22),
    secondary = Color(0xFF8C6D1F),
    tertiary = RedHot,
)

private val DarkScheme = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF06351D),
    primaryContainer = Color(0xFF0B3D22),
    onPrimaryContainer = Color(0xFFDFF3E6),
    tertiary = Color(0xFFFF8A80),
)

@Composable
fun NewsTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content
    )
}

/** Màu nhãn NÓNG dùng chung light/dark */
val hotRed = RedHot
