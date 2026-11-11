package zai.deva.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 深色优先的主题（设计 §5：深色主题优先，强调色与桌面端一致）。
 * 桌面端底色 #1e1e20（见 apps/desktop 渲染层 tokens），这里对齐为深色 scheme 的 surface；
 * 强调色先用 Deva 品牌蓝，后续 P5 打磨配色时与桌面端 tokens 统一校准。
 */

private val Accent = Color(0xFF4C8DFF)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    background = Color(0xFF1E1E20),
    surface = Color(0xFF1E1E20),
    surfaceVariant = Color(0xFF2A2A2E),
    onBackground = Color(0xFFE4E4E7),
    onSurface = Color(0xFFE4E4E7)
)

private val LightScheme = lightColorScheme(
    primary = Accent
)

@Composable
fun DevaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
}
