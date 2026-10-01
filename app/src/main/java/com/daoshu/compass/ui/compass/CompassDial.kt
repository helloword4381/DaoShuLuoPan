package com.daoshu.compass.ui.compass

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.daoshu.compass.data.settings.LayerVisibility
import com.daoshu.compass.ui.theme.DialColors
import com.daoshu.compass.ui.theme.rememberDialColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// 盘面几何
// ---------------------------------------------------------------------------
// 设计约束（三轮修正后定型）：
//   1. 环带半径与文字字号都以「盘半径」为唯一基准，屏幕/画布变化时整体等比缩放；
//   2. 每个环的字号同时受两个硬约束限制，二者任一不满足就下调字号：
//        a) 切向内接预算 —— 保证相邻格文字不重叠（密集环的关键）；
//        b) 环带宽度预算 —— 保证文字不越出本环带到相邻环（清晰度的关键）；
//      字号仍不够时退回单字；再不够取下限。绝不重叠、绝不跨环。
//   3. 环带使用不透明实色 + 高对比文字色，避免「金底金字」看不清。
// ---------------------------------------------------------------------------

/** 盘面外缘占「Canvas 最短边一半」的比例。 */
private const val OUTER_RING = 0.900f

// 360° 刻度带
private const val TICK_MINOR_INNER = 0.964f
private const val TICK_MEDIUM_INNER = 0.949f
private const val TICK_MAJOR_INNER = 0.924f
private const val DEGREE_LABEL_RADIUS = 0.876f

// 二十八宿带
private const val XIU_BAND_INNER = 0.736f
private const val XIU_BAND_OUTER = 0.852f

// 六十甲子带（60 格，最密集）
private const val JIAZI_BAND_INNER = 0.568f
private const val JIAZI_BAND_OUTER = 0.716f

// 二十四山带
private const val MOUNTAIN_BAND_INNER = 0.414f
private const val MOUNTAIN_BAND_OUTER = 0.548f

// 八卦带
private const val BAGUA_BAND_INNER = 0.262f
private const val BAGUA_BAND_OUTER = 0.386f

// 中心指针与轴
private const val NEEDLE_LENGTH = 0.268f
private const val HUB_RADIUS = 0.055f

// 顶部固定指针（顶点位于盘外缘稍外，指向盘心）
private const val POINTER_TIP_Y = 1.022f
private const val POINTER_BASE_Y = 1.126f
private const val POINTER_HALF_WIDTH = 0.046f

/** 各环文字轨道半径：取环带几何中心，文字随之居中，不偏向内外边缘。 */
private val XIU_LABEL_RADIUS = (XIU_BAND_INNER + XIU_BAND_OUTER) / 2f
private val JIAZI_LABEL_RADIUS = (JIAZI_BAND_INNER + JIAZI_BAND_OUTER) / 2f
private val MOUNTAIN_LABEL_RADIUS = (MOUNTAIN_BAND_INNER + MOUNTAIN_BAND_OUTER) / 2f
private val BAGUA_LABEL_RADIUS = (BAGUA_BAND_INNER + BAGUA_BAND_OUTER) / 2f

// 字号基准：以「盘半径 = 200px」为 1.0 基准等比缩放，并夹在下限/上限之间
private const val REFERENCE_RADIUS_PX = 200f
private const val MIN_SCALE = 0.60f
private const val MAX_SCALE = 1.80f

// 字号下限/上限（sp = 与半径等比缩放后的 sp）
// 下限取 6sp：这是手机上的可读底线；六十甲子 60 格若在 6sp 仍放不下，
// 由「逐档缩小 → 退回单字」机制处理，而不是继续压小字号。
private const val MIN_FONT_SP = 6.0f
private const val MAX_FONT_SP = 30f
private const val FONT_STEP_SP = 0.25f

/** 切向预算的安全系数：兼作真实字体度量（中文方块字含侧边距略大于字号）的余量。 */
private const val FIT_SAFETY = 0.85f

/** 环带宽度预算的占比（文字径向长度不得超过环带宽度的该比例，其余留给环线间隙）。 */
private const val BAND_FIT_RATIO = 0.86f

/**
 * 盘面排版参数：绘制流程与单元测试共用同一份几何，保证「测试验证的几何」与「实际绘制的几何」一致。
 */
internal object CompassDialLayout {
    /** 盘半径 -> 全局缩放系数（字号与环带同源缩放） */
    fun scaleForRadius(radiusPx: Float): Float =
        (radiusPx / REFERENCE_RADIUS_PX).coerceIn(MIN_SCALE, MAX_SCALE)

    /** 字号下限（sp，未乘缩放） */
    const val minFontSp: Float = MIN_FONT_SP

    /** 相邻文字之间的切向可用宽度预算（像素），即 [tangentialWidthBudgetPx] */
    fun tangentialWidthBudgetPx(radiusPx: Float, cellCount: Int, crossExtentPx: Float): Float =
        com.daoshu.compass.ui.compass.tangentialWidthBudgetPx(radiusPx, cellCount, crossExtentPx)

    /** 环带宽度预算（像素）：文字径向长度不得超过该值 */
    fun bandWidthBudgetPx(bandWidthPx: Float): Float = bandWidthPx * BAND_FIT_RATIO

    /**
     * 判定某条文字是否不与相邻格文字重叠（几何判定，不含字体度量）。
     * [widthPx]/[heightPx] 为文字排版盒尺寸；[radial] 为 true 表示文字沿半径方向排布，
     * 此时「宽度」为切向跨距、「高度」为径向长度。
     */
    fun fits(
        radiusPx: Float,
        cellCount: Int,
        widthPx: Float,
        heightPx: Float,
        radial: Boolean
    ): Boolean {
        val crossExtent = if (radial) widthPx else heightPx
        val lengthExtent = if (radial) heightPx else widthPx
        return lengthExtent <= tangentialWidthBudgetPx(radiusPx, cellCount, crossExtent)
    }

    /** 判定某条文字是否落在环带宽度之内（不跨到相邻环） */
    fun fitsBand(widthPx: Float, heightPx: Float, bandWidthPx: Float, radial: Boolean): Boolean {
        val lengthExtent = if (radial) heightPx else widthPx
        return lengthExtent <= bandWidthBudgetPx(bandWidthPx)
    }
}

/** 文字排布方向 */
private enum class RingTextOrientation {
    /** 字头朝盘心、沿半径向外读（罗盘传统排布，多字与密集环使用） */
    RADIAL,

    /** 文字沿切向站立（单字或极短标签使用） */
    TANGENTIAL
}

/**
 * 单个环上的一条文字：预排版结果 + 位置 + 颜色。
 * 只在 remember 中创建，绘制过程仅读取，避免每帧排版与字符串拼接。
 */
private class RingLabel(
    val layout: TextLayoutResult,
    val angleDegrees: Float,
    val radiusFraction: Float,
    val color: Color
)

/**
 * 罗盘表盘：无状态、纯 Canvas 绘制。
 *
 * 多层同心圆自外向内：360° 刻度 → 二十八宿（28 等分）→ 六十甲子（60 等分）
 * → 二十四山（24 等分，含八卦方位配色）→ 八卦（8 等分）→ 中心指针。
 * 盘面按 -heading 反向旋转表示设备转动，顶部指针固定不动指示当前朝向；
 * 真北模式下额外绘制磁北参考线（与真北相差 [declinationDegrees]）。
 *
 * 文字与环带尺寸均随画布（屏幕）大小等比自适应，且字号同时满足「不重叠」与「不跨环」。
 */
@Composable
fun CompassDial(
    headingDegrees: Float,
    layers: LayerVisibility,
    showTrueNorth: Boolean,
    declinationDegrees: Float,
    modifier: Modifier = Modifier
) {
    val dialColors = rememberDialColors()
    val density = LocalDensity.current
    // 缓存容量覆盖全部环形文字（12 + 28 + 60 + 24 + 8 = 132）
    val measurer = rememberTextMeasurer(cacheSize = 160)

    // 用 BoxWithConstraints 取到画布尺寸，从而在测量文字之前就能算出盘半径与字号；
    // 尺寸变化（横竖屏、分屏、折叠屏）会触发重新测量。
    BoxWithConstraints(modifier = modifier) {
        val sidePx = with(density) { min(maxWidth.toPx(), maxHeight.toPx()) }
        val dialRadiusPx = sidePx / 2f * OUTER_RING
        val scale = CompassDialLayout.scaleForRadius(dialRadiusPx)

        val degreeLabels = remember(measurer, dialColors, dialRadiusPx, scale) {
            with(density) { buildDegreeLabels(density, measurer, dialColors, dialRadiusPx, scale) }
        }
        val xiuLabels = remember(measurer, dialColors, dialRadiusPx, scale) {
            with(density) {
                buildRingLabels(
                    density = density,
                    measurer = measurer,
                    names = XIU_NAMES,
                    color = dialColors.bandTextColor,
                    radiusFraction = XIU_LABEL_RADIUS,
                    dialRadiusPx = dialRadiusPx,
                    scale = scale,
                    preferredFontSp = 11f,
                    orientation = RingTextOrientation.RADIAL,
                    bandWidthPx = dialRadiusPx * (XIU_BAND_OUTER - XIU_BAND_INNER)
                )
            }
        }
        val jiaziLabels = remember(measurer, dialColors, dialRadiusPx, scale) {
            with(density) {
                buildRingLabels(
                    density = density,
                    measurer = measurer,
                    names = JIAZI_NAMES,
                    color = dialColors.bandTextColor,
                    radiusFraction = JIAZI_LABEL_RADIUS,
                    dialRadiusPx = dialRadiusPx,
                    scale = scale,
                    preferredFontSp = 9.5f,
                    orientation = RingTextOrientation.RADIAL,
                    bandWidthPx = dialRadiusPx * (JIAZI_BAND_OUTER - JIAZI_BAND_INNER)
                )
            }
        }
        val mountainLabels = remember(measurer, dialColors, dialRadiusPx, scale) {
            with(density) {
                buildRingLabels(
                    density = density,
                    measurer = measurer,
                    names = MOUNTAIN_NAMES,
                    color = dialColors.bandTextColor,
                    radiusFraction = MOUNTAIN_LABEL_RADIUS,
                    dialRadiusPx = dialRadiusPx,
                    scale = scale,
                    preferredFontSp = 14f,
                    orientation = RingTextOrientation.RADIAL,
                    bandWidthPx = dialRadiusPx * (MOUNTAIN_BAND_OUTER - MOUNTAIN_BAND_INNER),
                    // 二十四山按卦位分色（用本主题校准过对比度的调色板），覆盖上面的统一颜色
                    colorOf = { index -> dialColors.baguaColors[MOUNTAIN_PALACE_INDEX[index]] }
                )
            }
        }
        val baguaLabels = remember(measurer, dialColors, dialRadiusPx, scale) {
            with(density) {
                buildRingLabels(
                    density = density,
                    measurer = measurer,
                    names = BAGUA_NAMES,
                    color = dialColors.bandTextColor,
                    radiusFraction = BAGUA_LABEL_RADIUS,
                    dialRadiusPx = dialRadiusPx,
                    scale = scale,
                    preferredFontSp = 18f,
                    orientation = RingTextOrientation.RADIAL,
                    bandWidthPx = dialRadiusPx * (BAGUA_BAND_OUTER - BAGUA_BAND_INNER),
                    angles = BAGUA_BEARINGS,
                    colorOf = { index -> dialColors.baguaColors[index] }
                )
            }
        }
        val pointerPath = remember {
            Path().apply {
                moveTo(0f, -POINTER_TIP_Y)
                lineTo(-POINTER_HALF_WIDTH, -POINTER_BASE_Y)
                lineTo(POINTER_HALF_WIDTH, -POINTER_BASE_Y)
                close()
            }
        }

        // 自适应字号诊断：仅在实际设备上、每次尺寸变化时记录一次，便于核对字号是否随屏幕缩放。
        if (!LocalInspectionMode.current) {
            remember(dialRadiusPx, scale) {
                fun box(layout: TextLayoutResult) = "${layout.size.width}x${layout.size.height}"
                Log.d(
                    "CompassDial",
                    "dialRadiusPx=${"%.1f".format(dialRadiusPx)} scale=${"%.3f".format(scale)} " +
                        "textPx(degree=${box(degreeLabels.first().layout)} " +
                        "xiu=${box(xiuLabels.first().layout)} " +
                        "jiazi=${box(jiaziLabels.first().layout)} " +
                        "mountain=${box(mountainLabels.first().layout)} " +
                        "bagua=${box(baguaLabels.first().layout)})"
                )
                true
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f * OUTER_RING
            if (radius <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)

            // 盘面底色（径向渐变，随主题切换）
            drawCircle(brush = dialColors.background, radius = radius, center = center)

            // ---------------- 第 1 层：360° 刻度 ----------------
            if (layers.degreeTicks) {
                drawDegreeTicks(dialColors, center, radius)
                drawRingLabels(degreeLabels, center, radius, RingTextOrientation.TANGENTIAL)
            }

            // ---------------- 第 2 层：二十八宿 ----------------
            if (layers.xiu) {
                drawBand(dialColors.bandColor, center, radius, XIU_BAND_INNER, XIU_BAND_OUTER)
                drawSpokes(dialColors.onDialColor, center, radius, XIU_NAMES.size, XIU_BAND_INNER, XIU_BAND_OUTER)
                drawRingLabels(xiuLabels, center, radius, RingTextOrientation.RADIAL)
            }

            // ---------------- 第 3 层：六十甲子 ----------------
            if (layers.jiazi) {
                drawBand(dialColors.bandColor, center, radius, JIAZI_BAND_INNER, JIAZI_BAND_OUTER)
                drawSpokes(dialColors.onDialColor, center, radius, JIAZI_NAMES.size, JIAZI_BAND_INNER, JIAZI_BAND_OUTER)
                drawRingLabels(jiaziLabels, center, radius, RingTextOrientation.RADIAL)
            }

            // ---------------- 第 4 层：二十四山（含八卦方位色） ----------------
            if (layers.mountains) {
                drawBand(dialColors.bandColor, center, radius, MOUNTAIN_BAND_INNER, MOUNTAIN_BAND_OUTER)
                drawPalaceSectors(dialColors, center, radius, MOUNTAIN_BAND_INNER, MOUNTAIN_BAND_OUTER, 0.14f)
                drawSpokes(dialColors.onDialColor, center, radius, MOUNTAIN_NAMES.size, MOUNTAIN_BAND_INNER, MOUNTAIN_BAND_OUTER)
                drawRingLabels(mountainLabels, center, radius, RingTextOrientation.RADIAL)
            }

            // ---------------- 第 5 层：八卦 ----------------
            if (layers.bagua) {
                drawBand(dialColors.bandColor, center, radius, BAGUA_BAND_INNER, BAGUA_BAND_OUTER)
                drawPalaceSectors(dialColors, center, radius, BAGUA_BAND_INNER, BAGUA_BAND_OUTER, 0.26f)
                drawRingLabels(baguaLabels, center, radius, RingTextOrientation.RADIAL)
            }

            // ---------------- 旋转盘面：反向旋转 heading ----------------
            rotate(degrees = -normalizeDegrees(headingDegrees), pivot = center) {
                drawNeedle(dialColors, center, radius)
                if (showTrueNorth) {
                    drawNorthReferenceLines(dialColors, center, radius, declinationDegrees)
                }
            }

            // ---------------- 中心轴 ----------------
            drawCircle(color = dialColors.centerHub, radius = radius * HUB_RADIUS, center = center)

            // ---------------- 顶部固定指针（不随盘面旋转） ----------------
            drawFixedPointer(pointerPath, dialColors, center, radius)
        }
    }
}

// ---------------------------------------------------------------------------
// 自适应字号求解（仅在 remember 中调用）
// ---------------------------------------------------------------------------

/**
 * 相邻两格文字之间的切向可用宽度预算（像素）——盘面几何的硬约束。
 *
 * 推导（精确版）：每格圆心角 Δ = 2π/n，格中心切向间距 d = r·Δ·cos(Δ/2)。
 * 两块相邻文字各是一块「沿半径 w/2、沿切向 h/2」的矩形，整体分别旋转 ±Δ/2；
 * 二者在切向方向的最大伸展之和为 2·sin(Δ/2)·H（H 为切向跨度）加上宽度分量，
 * 令其不超过格间距，解出可用的文字宽度预算：
 *     W ≤ (r·Δ·cos(Δ/2) − 2·sin(Δ/2)·H) / cos(Δ/2)
 *
 * 该式同时适用于径排（W=文字盒宽，H=文字盒高）与切排（W=文字盒高，H=文字盒宽）。
 * 再乘 FIT_SAFETY 留出字体度量误差余量。
 */
private fun tangentialWidthBudgetPx(radiusPx: Float, cellCount: Int, crossExtentPx: Float): Float {
    val delta = 2.0 * PI / cellCount
    val halfDeltaSin = sin(delta / 2.0)
    val halfDeltaCos = cos(delta / 2.0)
    val centerSpacing = radiusPx * delta * halfDeltaCos
    val available = (centerSpacing - 2.0 * halfDeltaSin * crossExtentPx) / halfDeltaCos
    return available.toFloat() * FIT_SAFETY
}

/**
 * 求解某一个环的字号：从 preferred 起逐档下调，取第一个**同时**满足
 * 「不与相邻格重叠」与「不超出环带宽度」的字号。
 * 返回 null 表示即使下限字号也放不下（调用方退回单字）。
 */
private fun fitRingText(
    density: Density,
    measurer: TextMeasurer,
    text: String,
    baseSp: Float,
    scale: Float,
    radiusPx: Float,
    cellCount: Int,
    bandWidthPx: Float,
    orientation: RingTextOrientation
): TextLayoutResult? {
    var sp = (baseSp * scale).coerceAtMost(MAX_FONT_SP)
    while (sp >= MIN_FONT_SP) {
        val layout = measurer.measure(text = text, style = TextStyle(fontSize = with(density) { sp.sp }))
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        val crossExtent = if (orientation == RingTextOrientation.RADIAL) w else h
        val lengthExtent = if (orientation == RingTextOrientation.RADIAL) h else w
        val tangentialOk = lengthExtent <= tangentialWidthBudgetPx(radiusPx, cellCount, crossExtent)
        val bandOk = lengthExtent <= bandWidthPx * BAND_FIT_RATIO
        if (tangentialOk && bandOk) return layout
        sp -= FONT_STEP_SP
    }
    return null
}

private fun buildRingLabels(
    density: Density,
    measurer: TextMeasurer,
    names: List<String>,
    color: Color,
    radiusFraction: Float,
    dialRadiusPx: Float,
    scale: Float,
    preferredFontSp: Float,
    orientation: RingTextOrientation,
    bandWidthPx: Float,
    angles: FloatArray? = null,
    colorOf: ((Int) -> Color)? = null
): List<RingLabel> {
    val cellCount = names.size
    val stepAngle = 360f / cellCount
    val labelRadiusPx = dialRadiusPx * radiusFraction
    return names.mapIndexed { index, name ->
        // 先尝试完整名称；放不下时退回单字，优先保证「不重叠、不跨环」
        val layout = fitRingText(
            density, measurer, name, preferredFontSp, scale,
            labelRadiusPx, cellCount, bandWidthPx, orientation
        ) ?: fitRingText(
            density, measurer, name.substring(0, 1), preferredFontSp, scale,
            labelRadiusPx, cellCount, bandWidthPx, orientation
        ) ?: measurer.measure(
            text = name.substring(0, 1),
            style = TextStyle(fontSize = with(density) { MIN_FONT_SP.sp })
        )
        RingLabel(
            layout = layout,
            angleDegrees = angles?.get(index) ?: (index * stepAngle),
            radiusFraction = radiusFraction,
            color = colorOf?.invoke(index) ?: color
        )
    }
}

private fun buildDegreeLabels(
    density: Density,
    measurer: TextMeasurer,
    dialColors: DialColors,
    dialRadiusPx: Float,
    scale: Float
): List<RingLabel> {
    val fontSizeSp = (9f * scale).coerceIn(6.5f, 16f)
    val style = TextStyle(fontSize = with(density) { fontSizeSp.sp })
    return List(12) { index ->
        val degree = index * 30
        RingLabel(
            layout = measurer.measure(text = "$degree°", style = style),
            angleDegrees = degree.toFloat(),
            radiusFraction = DEGREE_LABEL_RADIUS,
            color = dialColors.tickMajor
        )
    }
}

// ---------------------------------------------------------------------------
// 绘制工具（绘制过程内不做字符串拼接，仅使用值类型与已缓存的布局结果）
// ---------------------------------------------------------------------------

private fun DrawScope.drawRingLabels(
    labels: List<RingLabel>,
    center: Offset,
    radius: Float,
    orientation: RingTextOrientation
) {
    for (index in labels.indices) {
        val label = labels[index]
        val r = radius * label.radiusFraction
        val layout = label.layout
        val halfWidth = layout.size.width / 2f
        val halfHeight = layout.size.height / 2f
        // 旋转后，文字盒中心位于盘心沿「格角」方向 r 处：
        //   切排：盒中心在旋转前坐标系中位于 (0, -r)（盒内 y 向上＝朝外）
        //   径排：整体再多转 -90°，使文字基线指向盘外、字头朝盘心（罗盘传统排布），
        //         此时盒中心在旋转前坐标系中改为位于 (r, 0)
        val isRadial = orientation == RingTextOrientation.RADIAL
        val rotation = if (isRadial) label.angleDegrees - 90f else label.angleDegrees
        val centerOffset = if (isRadial) {
            Offset(center.x + r, center.y)
        } else {
            Offset(center.x, center.y - r)
        }
        withTransform({ rotate(degrees = rotation, pivot = center) }) {
            drawText(
                textLayoutResult = layout,
                color = label.color,
                topLeft = Offset(centerOffset.x - halfWidth, centerOffset.y - halfHeight)
            )
        }
    }
}

private fun DrawScope.drawBand(
    color: Color,
    center: Offset,
    radius: Float,
    innerFraction: Float,
    outerFraction: Float
) {
    val bandRadius = radius * (innerFraction + outerFraction) / 2f
    val bandWidth = radius * (outerFraction - innerFraction)
    drawCircle(color = color, radius = bandRadius, center = center, style = Stroke(width = bandWidth))
}

private fun DrawScope.drawSpokes(
    color: Color,
    center: Offset,
    radius: Float,
    count: Int,
    innerFraction: Float,
    outerFraction: Float
) {
    val inner = radius * innerFraction
    val outer = radius * outerFraction
    val strokeWidth = radius * 0.0022f
    val stepDegrees = 360f / count
    for (index in 0 until count) {
        withTransform({ rotate(degrees = index * stepDegrees, pivot = center) }) {
            drawLine(
                color = color.copy(alpha = 0.45f),
                start = Offset(center.x, center.y - inner),
                end = Offset(center.x, center.y - outer),
                strokeWidth = strokeWidth
            )
        }
    }
}

/** 八卦方位色扇区（每宫 45°，以卦位为中线）；颜色取自当前主题校准过的调色板 */
private fun DrawScope.drawPalaceSectors(
    dialColors: DialColors,
    center: Offset,
    radius: Float,
    innerFraction: Float,
    outerFraction: Float,
    alpha: Float
) {
    val bandRadius = radius * (innerFraction + outerFraction) / 2f
    val topLeft = Offset(center.x - bandRadius, center.y - bandRadius)
    val sectorSize = Size(bandRadius * 2f, bandRadius * 2f)
    val stroke = Stroke(width = radius * (outerFraction - innerFraction))
    for (index in BAGUA_BEARINGS.indices) {
        // drawArc 的 0° 位于三点钟方向，故减去 90°；再回退半格使卦位居中
        drawArc(
            color = dialColors.baguaColors[index].copy(alpha = alpha),
            startAngle = BAGUA_BEARINGS[index] - 90f - 22.5f,
            sweepAngle = 45f,
            useCenter = false,
            topLeft = topLeft,
            size = sectorSize,
            style = stroke
        )
    }
}

private fun DrawScope.drawDegreeTicks(dialColors: DialColors, center: Offset, radius: Float) {
    // 外缘圆
    drawCircle(
        color = dialColors.onDialColor,
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.004f)
    )
    for (degree in 0 until 360) {
        val isMajor = degree % 30 == 0
        val isMedium = degree % 10 == 0
        val innerFraction = when {
            isMajor -> TICK_MAJOR_INNER
            isMedium -> TICK_MEDIUM_INNER
            else -> TICK_MINOR_INNER
        }
        val color = if (isMedium) dialColors.tickMajor else dialColors.tickMinor
        val strokeWidth = when {
            isMajor -> radius * 0.006f
            isMedium -> radius * 0.0035f
            else -> radius * 0.0018f
        }
        val cos = cosDegree(degree)
        val sin = sinDegree(degree)
        // 表盘角度自正北顺时针：x = cx + r·sinθ，y = cy − r·cosθ
        drawLine(
            color = color,
            start = Offset(
                center.x + sin * radius * innerFraction,
                center.y - cos * radius * innerFraction
            ),
            end = Offset(
                center.x + sin * radius,
                center.y - cos * radius
            ),
            strokeWidth = strokeWidth
        )
    }
}

private fun DrawScope.drawNeedle(dialColors: DialColors, center: Offset, radius: Float) {
    val length = radius * NEEDLE_LENGTH
    val strokeWidth = radius * 0.022f
    drawLine(
        color = dialColors.needleNorth,
        start = center,
        end = Offset(center.x, center.y - length),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = dialColors.needleSouth,
        start = center,
        end = Offset(center.x, center.y + length),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

/**
 * 真北模式下的参考线：盘面 0° 为真北，磁北位于真北东侧 declination 度处。
 */
private fun DrawScope.drawNorthReferenceLines(
    dialColors: DialColors,
    center: Offset,
    radius: Float,
    declinationDegrees: Float
) {
    val inner = radius * BAGUA_BAND_INNER
    val outer = radius * DEGREE_LABEL_RADIUS
    val strokeWidth = radius * 0.006f
    drawLine(
        color = dialColors.trueNorthLine,
        start = Offset(center.x, center.y - inner),
        end = Offset(center.x, center.y - outer),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    withTransform({ rotate(degrees = declinationDegrees, pivot = center) }) {
        drawLine(
            color = dialColors.magneticNorthLine,
            start = Offset(center.x, center.y - inner),
            end = Offset(center.x, center.y - outer),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawFixedPointer(
    pointerPath: Path,
    dialColors: DialColors,
    center: Offset,
    radius: Float
) {
    withTransform({
        translate(left = center.x, top = center.y)
        scale(scaleX = radius, scaleY = radius, pivot = Offset.Zero)
    }) {
        drawPath(path = pointerPath, color = dialColors.tickMajor)
    }
}
