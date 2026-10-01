package com.daoshu.compass.data.settings

/**
 * 主题模式。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 罗盘基准北。
 */
enum class NorthType { MAGNETIC, TRUE }

/**
 * 罗盘显示层开关。
 */
data class LayerVisibility(
    /** 八卦 */
    val bagua: Boolean = true,
    /** 二十四山 */
    val mountains: Boolean = true,
    /** 六十甲子 */
    val jiazi: Boolean = true,
    /** 二十八宿 */
    val xiu: Boolean = true,
    /** 360 度刻度 */
    val degreeTicks: Boolean = true
)

/**
 * 应用设置。
 *
 * 默认值为契约固定值，也是 DataStore 读取任何键时的兜底值。
 */
data class CompassSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val northType: NorthType = NorthType.MAGNETIC,
    val layers: LayerVisibility = LayerVisibility(),
    /** 低通滤波系数 0.02..1.0，越大越平滑 */
    val smoothing: Float = 0.15f,
    /** 用户手动校准偏移（-180..180） */
    val calibrationOffsetDegrees: Float = 0f,
    val checkUpdateOnStart: Boolean = true,
    val showUpdateDialogOnStart: Boolean = true
)
