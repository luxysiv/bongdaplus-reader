package vn.bongdaplus.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import vn.bongdaplus.reader.R

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

/**
 * Be Vietnam Pro — font Việt chuẩn cho toàn bộ giao diện app
 * (thiết kế riêng cho tiếng Việt, hiện đại hơn Roboto hệ thống).
 * Nội dung bài đọc vẫn tôn trọng font serif/sans do user chọn.
 */
val AppFont = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
)

private val baseTypography = Typography()

private val AppTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = AppFont),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = AppFont),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = AppFont),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = AppFont),
    headlineMedium = baseTypography.headlineMedium.copy(fontFamily = AppFont),
    headlineSmall = baseTypography.headlineSmall.copy(fontFamily = AppFont),
    titleLarge = baseTypography.titleLarge.copy(fontFamily = AppFont),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = AppFont, fontWeight = FontWeight.SemiBold),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = AppFont, fontWeight = FontWeight.SemiBold),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = AppFont),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = AppFont),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = AppFont),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = AppFont, fontWeight = FontWeight.SemiBold),
    labelMedium = baseTypography.labelMedium.copy(fontFamily = AppFont, fontWeight = FontWeight.SemiBold),
    labelSmall = baseTypography.labelSmall.copy(fontFamily = AppFont, fontWeight = FontWeight.SemiBold),
)

@Composable
fun NewsTheme(
    mode: String = "system",
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    // Màu động Material You theo hình nền (Android 12+); máy cũ dùng xanh thương hiệu
    val useDynamic = dynamic && android.os.Build.VERSION.SDK_INT >= 31
    val scheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(LocalContext.current)
        useDynamic -> dynamicLightColorScheme(LocalContext.current)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** Màu nhãn NÓNG dùng chung light/dark */
val hotRed = RedHot
