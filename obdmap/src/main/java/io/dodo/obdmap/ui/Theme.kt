package io.dodo.obdmap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра приложения. Тема только тёмная и не зависит от системной: это
 * приборная панель, её смотрят в машине, и светлый вариант тут не нужен.
 *
 * Цвета намеренно холодные и приглушённые, кроме акцента — чтобы цифры и
 * раскраска трека читались, а фон не спорил с картой.
 */
object Palette {
    /** Фон приложения — почти чёрный, но с синевой, а не серый. */
    val Background = Color(0xFF0B0E13)

    /** Панель поверх фона. */
    val Surface = Color(0xFF141A22)

    /** Приподнятая панель: плитки показаний, диалоги. */
    val SurfaceHigh = Color(0xFF1C242F)

    /** Обводка панелей — вместо теней, которых в тёмной теме не видно. */
    val Outline = Color(0xFF2A3542)

    /** Акцент: активные элементы, основной график. */
    val Accent = Color(0xFF2ED3B7)
    val AccentDim = Color(0xFF17796A)

    /** Второй акцент для расхода. */
    val Amber = Color(0xFFF2B33D)

    /** Тревожный цвет: ошибки, резкое ускорение. */
    val Coral = Color(0xFFFF6B6B)

    val TextPrimary = Color(0xFFE8EEF5)
    val TextSecondary = Color(0xFF8A9AAC)
    val TextMuted = Color(0xFF5A6b7C)
}

private val Scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Background,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.Amber,
    onSecondary = Palette.Background,
    tertiary = Palette.Amber,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Outline,
    error = Palette.Coral,
    onError = Palette.Background,
    errorContainer = Color(0xFF3A1D1D),
    onErrorContainer = Palette.TextPrimary,
)

/**
 * Типографика: узкие плотные заголовки и крупные моноширинные цифры.
 * Показания читаются боковым зрением, поэтому у них свой стиль.
 */
private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        color = Palette.TextPrimary,
    ),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        color = Palette.TextSecondary,
    ),
)

/** Стиль крупных показаний: моноширинный, чтобы цифры не прыгали при смене. */
val ReadoutStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-1).sp,
)

/** Скругление панелей. Одно на всё приложение. */
val PanelCorner = 14.dp

/** Системную тему не спрашиваем: приложение всегда тёмное. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
