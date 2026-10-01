package com.daoshu.compass.ui.compass

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.daoshu.compass.data.settings.LayerVisibility
import com.daoshu.compass.ui.theme.DialColors
import com.daoshu.compass.ui.theme.rememberDialColors
import kotlin.math.min

// ---------------------------------------------------------------------------
// 盘面几何：全部以「盘半径的比例」表达，Canvas 尺寸变化时无需重新测量文字
// ---------------------------------------------------------------------------

/**
 * 盘面外缘占「Canvas 最短边一半」的比例。
 * 留出余量，保证顶部固定指针（半径 1.13 倍处）与刻度文字不会被 Canvas 边界裁剪。
 */
private const val OUTER_RING = 0.870f

// 360° 刻度带
private const val TICK_MINOR_INNER = 0.952f
private const val TICK_MEDIUM_INNER = 0.936f
private const val TICK_MAJOR_INNER = 0.910f
private const val DEGREE_LABEL_RADIUS = 0.870f

// 二十八宿带
private const val XIU_BAND_INNER = 0.720f
private const val XIU_BAND_OUTER = 0.835f
private const val XIU_LABEL_RADIUS = 0.778f

// 六十甲子带
private const val JIAZI_BAND_INNER = 0.585f
private const val JIAZI_BAND_OUTER = 0.705f
private const val JIAZI_LABEL_RADIUS = 0.645f

// 二十四山带
private const val MOUNTAIN_BAND_INNER = 0.440f
private const val MOUNTAIN_BAND_OUTER = 0.570f
private const val MOUNTAIN_LABEL_RADIUS = 0.505f

// 八卦带
private const val BAGUA_BAND_INNER = 0.290f
private const val BAGUA_BAND_OUTER = 0.425f
private const val BAGUA_LABEL_RADIUS = 0.358f

// 中心指针与轴
private const val NEEDLE_LENGTH = 0.272f
private const val HUB_RADIUS = 0.055f

// 顶部固定指针（顶点位于盘外缘稍外，指向盘心）
private const val POINTER_TIP_Y = 1.030f
private const val POINTER_BASE_Y = 1.132f
private const val POINTER_HALF_WIDTH = 0.046f

/**
 * 预计算的环形文字：文本布局结果 + 表盘角度（0° = 正北，顺时针为正）。
 * 只在 remember 中创建，绘制过程中仅读取，避免每帧排版与字符串拼接。
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
    // 缓存容量覆盖全部环形文字（12 + 28 + 60 + 24 + 8 = 132）
    val measurer = rememberTextMeasurer(cacheSize = 160)

    // ---------------- 预计算：文字布局、角度、指针路径 ----------------
    val degreeLabels = remember(measurer, dialColors) { buildDegreeLabels(measurer, dialColors) }
    val xiuLabels = remember(measurer, dialColors) {
        buildRingLabels(
            measurer = measurer,
            names = XIU_NAMES,
            color = dialColors.textPrimary,
            fontSizeSp = 9f,
            startAngle = 0f,
            stepAngle = 360f / XIU_NAMES.size,
            radiusFraction = XIU_LABEL_RADIUS
        )
    }
    val jiaziLabels = remember(measurer, dialColors) {
        buildRingLabels(
            measurer = measurer,
            names = JIAZI_NAMES,
            color = dialColors.textSecondary,
            fontSizeSp = 7.5f,
            startAngle = 0f,
            stepAngle = 6f,
            radiusFraction = JIAZI_LABEL_RADIUS
        )
    }
    val mountainLabels = remember(measurer) {
        val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
        MOUNTAIN_NAMES.mapIndexed { index, name ->
            RingLabel(
                layout = measurer.measure(text = name, style = style),
                angleDegrees = index * 15f,
                radiusFraction = MOUNTAIN_LABEL_RADIUS,
                color = BAGUA_COLORS[MOUNTAIN_PALACE_INDEX[index]]
            )
        }
    }
    val baguaLabels = remember(measurer) {
        val style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        BAGUA_NAMES.mapIndexed { index, name ->
            RingLabel(
                layout = measurer.measure(text = name, style = style),
                angleDegrees = BAGUA_BEARINGS[index],
                radiusFraction = BAGUA_LABEL_RADIUS,
                color = BAGUA_COLORS[index]
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

    Canvas(modifier = modifier) {
        val radius = min(size.width, size.height) / 2f * OUTER_RING
        if (radius <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)

        // 盘面底色（径向渐变，随主题切换）
        drawCircle(brush = dialColors.background, radius = radius, center = center)

        // ---------------- 第 1 层：360° 刻度 ----------------
        if (layers.degreeTicks) {
            drawDegreeTicks(dialColors, center, radius)
            drawRingLabels(degreeLabels, center, radius)
        }

        // ---------------- 第 2 层：二十八宿 ----------------
        if (layers.xiu) {
            drawBand(dialColors.ringInner, center, radius, XIU_BAND_INNER, XIU_BAND_OUTER)
            drawSpokes(dialColors.ringInner, center, radius, XIU_NAMES.size, XIU_BAND_INNER, XIU_BAND_OUTER)
            drawRingLabels(xiuLabels, center, radius)
        }

        // ---------------- 第 3 层：六十甲子 ----------------
        if (layers.jiazi) {
            drawBand(dialColors.ringInner, center, radius, JIAZI_BAND_INNER, JIAZI_BAND_OUTER)
            drawSpokes(dialColors.ringInner, center, radius, JIAZI_NAMES.size, JIAZI_BAND_INNER, JIAZI_BAND_OUTER)
            drawRingLabels(jiaziLabels, center, radius)
        }

        // ---------------- 第 4 层：二十四山（含八卦方位色） ----------------
        if (layers.mountains) {
            drawBand(dialColors.ringOuter, center, radius, MOUNTAIN_BAND_INNER, MOUNTAIN_BAND_OUTER)
            drawPalaceSectors(center, radius, MOUNTAIN_BAND_INNER, MOUNTAIN_BAND_OUTER, 0.16f)
            drawSpokes(dialColors.ringInner, center, radius, MOUNTAIN_NAMES.size, MOUNTAIN_BAND_INNER, MOUNTAIN_BAND_OUTER)
            drawRingLabels(mountainLabels, center, radius)
        }

        // ---------------- 第 5 层：八卦 ----------------
        if (layers.bagua) {
            drawBand(dialColors.ringOuter, center, radius, BAGUA_BAND_INNER, BAGUA_BAND_OUTER)
            drawPalaceSectors(center, radius, BAGUA_BAND_INNER, BAGUA_BAND_OUTER, 0.30f)
            drawRingLabels(baguaLabels, center, radius)
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

// ---------------------------------------------------------------------------
// 预计算工具（仅在 remember 中调用）
// ---------------------------------------------------------------------------

private fun buildRingLabels(
    measurer: TextMeasurer,
    names: List<String>,
    color: Color,
    fontSizeSp: Float,
    startAngle: Float,
    stepAngle: Float,
    radiusFraction: Float
): List<RingLabel> {
    val style = TextStyle(fontSize = fontSizeSp.sp)
    return names.mapIndexed { index, name ->
        RingLabel(
            layout = measurer.measure(text = name, style = style),
            angleDegrees = startAngle + index * stepAngle,
            radiusFraction = radiusFraction,
            color = color
        )
    }
}

private fun buildDegreeLabels(measurer: TextMeasurer, dialColors: DialColors): List<RingLabel> {
    val style = TextStyle(fontSize = 9.sp)
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

private fun DrawScope.drawRingLabels(labels: List<RingLabel>, center: Offset, radius: Float) {
    for (index in labels.indices) {
        val label = labels[index]
        val r = radius * label.radiusFraction
        val layout = label.layout
        val halfWidth = layout.size.width / 2f
        val halfHeight = layout.size.height / 2f
        withTransform({ rotate(degrees = label.angleDegrees, pivot = center) }) {
            // 文字沿半径方向朝外，符合罗盘盘面传统排布
            drawText(
                textLayoutResult = layout,
                color = label.color,
                topLeft = Offset(center.x - halfWidth, center.y - r - halfHeight)
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
    val strokeWidth = radius * 0.0025f
    val stepDegrees = 360f / count
    for (index in 0 until count) {
        withTransform({ rotate(degrees = index * stepDegrees, pivot = center) }) {
            drawLine(
                color = color,
                start = Offset(center.x, center.y - inner),
                end = Offset(center.x, center.y - outer),
                strokeWidth = strokeWidth
            )
        }
    }
}

/** 八卦方位色扇区（每宫 45°，以卦位为中线） */
private fun DrawScope.drawPalaceSectors(
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
            color = BAGUA_COLORS[index].copy(alpha = alpha),
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
        color = dialColors.ringOuter,
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
