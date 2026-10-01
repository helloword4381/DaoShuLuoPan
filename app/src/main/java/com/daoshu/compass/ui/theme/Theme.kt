package com.daoshu.compass.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.daoshu.compass.data.settings.ThemeMode

// ---------------------------------------------------------------------------
// 《道枢罗盘》配色：墨黑基调 + 罗盘金，兼顾夜间观星与白天户外可读性
// ---------------------------------------------------------------------------

/** 墨色：主背景 */
val Ink = Color(0xFF0E1116)

/** 玄青：次级面板 */
val InkSurface = Color(0xFF161B23)

/** 罗盘金：主强调色 */
val CompassGold = Color(0xFFC8A96A)

/** 亮金：高亮与指针 */
val BrightGold = Color(0xFFE8CE93)

/** 朱砂：南向与告警 */
val Cinnabar = Color(0xFFB4553F)

/** 青碧：北向与真北参考线 */
val Azure = Color(0xFF4E8FA8)

/** 宣纸：浅色主题背景 */
val Paper = Color(0xFFF6F2E9)

/** 浅色主题面板 */
val PaperSurface = Color(0xFFFFFBF3)

private val DarkColors = darkColorScheme(
    primary = CompassGold,
    onPrimary = Ink,
    primaryContainer = Color(0xFF3A3018),
    onPrimaryContainer = BrightGold,
    secondary = Azure,
    onSecondary = Ink,
    tertiary = Cinnabar,
    onTertiary = Color.White,
    background = Ink,
    onBackground = Color(0xFFE9E4D8),
    surface = InkSurface,
    onSurface = Color(0xFFE9E4D8),
    surfaceVariant = Color(0xFF232A34),
    onSurfaceVariant = Color(0xFFC3BBA8),
    outline = Color(0xFF6E6350)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A6D2F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E2C2),
    onPrimaryContainer = Color(0xFF3A2C0C),
    secondary = Color(0xFF2F6B84),
    onSecondary = Color.White,
    tertiary = Cinnabar,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Color(0xFF1E1B14),
    surface = PaperSurface,
    onSurface = Color(0xFF1E1B14),
    surfaceVariant = Color(0xFFECE4D4),
    onSurfaceVariant = Color(0xFF4C4535),
    outline = Color(0xFF8C8069)
)

/** 罗盘盘面专用色板，供 Canvas 绘制层使用（与 Material 主题解耦） */
data class DialColors(
    val tickMajor: Color,
    val tickMinor: Color,
    val needleNorth: Color,
    val needleSouth: Color,
    val centerHub: Color,
    val trueNorthLine: Color,
    val magneticNorthLine: Color,
    val background: Brush,
    // ---------------- 环带底色与环上文字 ----------------
    // 每个环带使用**不透明实色**，并为环上文字配一个对比明确的前景色：
    // 此前的做法是用 alpha 把 ringOuter 淡涂在米色盘面上，得到的金绿色与深金文字
    // 明度接近（真机截图实测正上方 r=272..316 处文字色 (112,95,49) 与底色 (163,142,99)
    // 几乎同级），导致「文字看不清」。这里改为显式配对的实色 + 高对比文字色。
    /** 环带底色（实色，不依赖 alpha 叠加） */
    val bandColor: Color,
    /** 环带上的文字颜色（与 [bandColor] 保证足够对比） */
    val bandTextColor: Color,
    /** 主刻度/外环/分格线等装饰线颜色（画在盘面底色上，需与 background 对比） */
    val onDialColor: Color,
    /**
     * 八卦方位色（坎艮震巽离坤兑乾），已按本主题的 [bandColor] 校准对比度：
     * 浅色主题用朝黑压暗版（≥4.6:1），深色主题用朝白提亮版（≥4.6:1）。
     */
    val baguaColors: List<Color>
)

/** 深色盘面（夜间观星）：深墨金环带 + 米金文字，对比充足 */
val DarkDialColors = DialColors(
    tickMajor = CompassGold,
    tickMinor = Color(0xFF6E6350),
    needleNorth = BrightGold,
    needleSouth = Color(0xFF6B5B33),
    centerHub = Color(0xFFF2E3BC),
    trueNorthLine = Azure,
    magneticNorthLine = Cinnabar,
    background = Brush.radialGradient(
        colors = listOf(Color(0xFF1B212B), Ink),
        center = Offset.Unspecified,
        radius = Float.POSITIVE_INFINITY
    ),
    bandColor = Color(0xFF2A2416),
    bandTextColor = Color(0xFFF5E6C0),
    onDialColor = CompassGold,
    baguaColors = listOf(
        Color(0xFF5D93B6), // 坎 · 玄蓝（北）
        Color(0xFF9A8D5F), // 艮 · 土黄（东北）
        Color(0xFF609A6A), // 震 · 青绿（东）
        Color(0xFF3FA08A), // 巽 · 碧（东南）
        Color(0xFFCC7664), // 离 · 朱（南）
        Color(0xFFAB8840), // 坤 · 土金（西南）
        Color(0xFF8E97A8), // 兑 · 素银（西）
        Color(0xFF9A86C4)  // 乾 · 紫（西北）
    )
)

/** 浅色盘面（白天户外）：暖金实色环带 + 近黑褐文字，户外强光下依然清晰 */
val LightDialColors = DialColors(
    tickMajor = Color(0xFF6B5626),
    tickMinor = Color(0xFF9A8C70),
    needleNorth = Cinnabar,
    needleSouth = Color(0xFF4A4232),
    centerHub = Color(0xFF2A2415),
    trueNorthLine = Color(0xFF2F6B84),
    magneticNorthLine = Cinnabar,
    background = Brush.radialGradient(
        colors = listOf(Color(0xFFFFFDF7), Paper),
        center = Offset.Unspecified,
        radius = Float.POSITIVE_INFINITY
    ),
    bandColor = Color(0xFFD8C79C),
    bandTextColor = Color(0xFF3A2E12),
    onDialColor = Color(0xFF8A6D2F),
    baguaColors = listOf(
        Color(0xFF2A5672), // 坎 · 玄蓝（北）
        Color(0xFF5C522E), // 艮 · 土黄（东北）
        Color(0xFF325C3A), // 震 · 青绿（东）
        Color(0xFF245B4F), // 巽 · 碧（东南）
        Color(0xFF843D2E), // 离 · 朱（南）
        Color(0xFF655024), // 坤 · 土金（西南）
        Color(0xFF4E535C), // 兑 · 素银（西）
        Color(0xFF584C70)  // 乾 · 紫（西北）
    )
)

/** 当前盘面色板（@Composable 内使用） */
@Composable
fun rememberDialColors(darkTheme: Boolean = isSystemInDarkTheme()): DialColors =
    if (darkTheme) DarkDialColors else LightDialColors

/** 应用主题入口：支持跟随系统 / 强制浅色 / 强制深色 */
@Composable
fun DaoShuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Android 12+ 动态取色仅在“跟随系统”时启用，避免破坏罗盘的传统配色
    val context = LocalContext.current
    val colorScheme = when {
        themeMode == ThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 系统栏图标明暗随主题切换（edge-to-edge 下由 Compose 控制）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DaoShuTypography,
        content = content
    )
}
