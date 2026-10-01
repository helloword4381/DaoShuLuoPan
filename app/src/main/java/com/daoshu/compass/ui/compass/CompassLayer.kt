package com.daoshu.compass.ui.compass

import com.daoshu.compass.data.settings.LayerVisibility

/**
 * 罗盘可切换的绘制层。
 *
 * 顺序与 CONTRACT 第 7 节一致，且与 [LayerVisibility] 的字段一一对应；
 * 每一层都真正控制 [CompassDial] 中对应环带的绘制与文字。
 */
enum class CompassLayer {
    BAGUA,
    MOUNTAINS,
    JIAZI,
    XIU,
    DEGREE_TICKS;

    /** 中文名称（UI 文案） */
    val label: String
        get() = when (this) {
            BAGUA -> "八卦"
            MOUNTAINS -> "二十四山"
            JIAZI -> "六十甲子"
            XIU -> "二十八宿"
            DEGREE_TICKS -> "360° 刻度"
        }

    /** 副标题，用于设置页与快捷开关提示 */
    val subtitle: String
        get() = when (this) {
            BAGUA -> "八等分方位"
            MOUNTAINS -> "每山 15°"
            JIAZI -> "干支六十循环"
            XIU -> "二十八宿"
            DEGREE_TICKS -> "外圈度数"
        }

    /** 在当前层可见性配置下，本层是否显示 */
    fun isVisibleIn(layers: LayerVisibility): Boolean = when (this) {
        BAGUA -> layers.bagua
        MOUNTAINS -> layers.mountains
        JIAZI -> layers.jiazi
        XIU -> layers.xiu
        DEGREE_TICKS -> layers.degreeTicks
    }
}
