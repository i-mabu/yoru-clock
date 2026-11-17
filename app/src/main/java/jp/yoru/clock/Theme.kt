package jp.yoru.clock

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// ===== 1. テーマ (従来View版と同一の夜色パレット) =====
internal val NightBg = Color(0xFF0B1026)
internal val NightPanel = Color(0xFF161D38)
internal val NightText = Color(0xFFF2F3FA)
internal val NightSub = Color(0xFF9AA3C7)
internal val NightAccent = Color(0xFF8FA8FF)
internal val NightGold = Color(0xFFFFC46B)
internal val NightTabOn = Color(0xFF2A3560)
internal val NightHint = Color(0xFF5A648C)

// 数値表示用フォント (太字は各 Text 側で fontWeight = Bold を併用する)
internal val MonoFamily: FontFamily = FontFamily.Monospace

private val NightScheme = darkColorScheme(
    primary = NightAccent,
    onPrimary = NightBg,
    secondary = NightGold,
    onSecondary = NightBg,
    background = NightBg,
    onBackground = NightText,
    surface = NightPanel,
    onSurface = NightText,
    surfaceVariant = NightPanel,
    onSurfaceVariant = NightSub,
)

@Composable
internal fun YoruClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightScheme, content = content)
}
