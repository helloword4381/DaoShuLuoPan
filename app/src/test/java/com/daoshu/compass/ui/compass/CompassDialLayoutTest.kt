package com.daoshu.compass.ui.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 罗盘盘面自适应排版验证（纯 JVM，不需要设备）。
 *
 * 覆盖用户反馈的两个问题：
 *   1. 文字与环圈是否随画布（屏幕）大小同源缩放；
 *   2. 密集环（六十甲子 60 格）的文字是否还会重叠。
 *
 * 说明：这里验证的是 [CompassDialLayout] 的几何判定，它与 [CompassDial] 实际绘制使用的是
 * 同一套「切向内接预算」公式与同一套缩放系数，因此结论对实际绘制成立。
 * 文字尺寸按「中文方块字」建模（宽 = 高 = 字号像素），对 CJK 字形而言这是保守估计
 * （真实中文字形高度通常小于字号，宽度接近字号）。
 */
class CompassDialLayoutTest {

    /** 盘面外缘比例，与 CompassDial.OUTER_RING 保持一致 */
    private val outerRing = 0.870f

    /** 六十甲子文字轨道半径，与 CompassDial.JIAZI_LABEL_RADIUS 保持一致 */
    private val jiaziLabelRadius = 0.629f

    /** 各环带内外半径比例，与 CompassDial 中的常量保持一致 */
    private val xiuBand = 0.716f to 0.826f
    private val jiaziBand = 0.556f to 0.702f
    private val mountainBand = 0.404f to 0.538f

    /**
     * 实际可用画布边长（dp）。
     *
     * 注意：CompassScreen 中表盘是 `Column` 里的 `fillMaxWidth().weight(1f)`，
     * 因此给到 CompassDial 的方框边长 = min(屏宽 - 2*16dp 内边距, 剩余高度)，
     * 通常**小于**屏幕宽度。这里取偏保守（偏小）的估计，覆盖竖屏手机与分屏场景。
     */
    private val canvasCases = listOf(
        CanvasCase("small 5.0in dial 320dp @2.0", 320f, 2.0f),
        CanvasCase("main 6.1in dial 340dp @2.75", 340f, 2.75f),
        CanvasCase("large 6.7in dial 360dp @2.625", 360f, 2.625f),
        CanvasCase("tablet 800dp @2.0", 800f, 2.0f)
    )

    private fun dialRadiusPx(case: CanvasCase): Float = case.sideDp * case.density / 2f * outerRing

    /** 中文方块字近似：文字盒 = 字数 × 字号 × 字号（像素） */
    private fun boxFor(charCount: Int, fontSp: Float, density: Float): Pair<Float, Float> {
        val sizePx = fontSp * density
        return (charCount * sizePx) to sizePx
    }

    @Test
    fun scaleFactor_increasesWithDialRadius() {
        println("=== dial radius -> global scale (fonts and rings share one scale) ===")
        val measured = canvasCases.map { case ->
            val radius = dialRadiusPx(case)
            val scale = CompassDialLayout.scaleForRadius(radius)
            println("  ${case.label}: radius=${"%.1f".format(radius)}px -> scale=${"%.3f".format(scale)}")
            radius to scale
        }
        // 小屏到大屏必须单调不减，且大屏严格更大（未被下限夹住）
        measured.zipWithNext().forEach { (a, b) ->
            assertTrue("缩放系数应随盘半径单调不减：$a -> $b", b.second >= a.second)
        }
        assertTrue(
            "最大屏的缩放系数应严格大于最小屏",
            measured.last().second > measured.first().second
        )
        measured.forEach { (_, s) ->
            assertTrue("缩放系数越界: $s", s >= 0.62f && s <= 1.60f)
        }
    }

    /**
     * 复现线上问题：六十甲子环在固定 7.5sp 下径排两字，必然超出切向预算（= 重叠）。
     */
    @Test
    fun jiaziAtOldFixedFontSize_reproducesOverlap() {
        val case = canvasCases[1] // main 6.1in 393dp @2.75
        val radius = dialRadiusPx(case)
        val labelRadius = radius * jiaziLabelRadius
        val (w, h) = boxFor(charCount = 2, fontSp = 7.5f, density = case.density)
        val budget = CompassDialLayout.tangentialWidthBudgetPx(labelRadius, 60, w)
        val fits = CompassDialLayout.fits(labelRadius, 60, w, h, radial = true)
        println(
            "=== OLD behaviour: jiazi at fixed 7.5sp, radial, 2 chars ===\n" +
                "  labelRadius=${"%.1f".format(labelRadius)}px  textBox=${w}x${h}px  " +
                "tangentialBudget=${"%.2f".format(budget)}px  fits=$fits"
        )
        assertTrue("fixed 7.5sp two-char jiazi must be reported as overlapping", !fits)
    }

    /**
     * 修复后的行为：字号随盘半径缩放，并逐档缩小到满足预算为止；
     * 两字确实放不下时退回单字，且单字必须放得下（否则仍会重叠）。
     */
    @Test
    fun adaptiveFontSize_satisfiesBudgetForEveryCanvas() {
        val rings = listOf(
            RingSpec("xiu-28", 28, xiuBand.first, xiuBand.second, 11f, 1, null, 9.5f),
            RingSpec("jiazi-60", 60, jiaziBand.first, jiaziBand.second, 9.5f, 2, jiaziLabelRadius, 0f),
            RingSpec("mountains-24", 24, mountainBand.first, mountainBand.second, 14f, 2, null, 9.5f)
        )
        for (case in canvasCases) {
            val radius = dialRadiusPx(case)
            val scale = CompassDialLayout.scaleForRadius(radius)
            println("=== ${case.label}  radius=${"%.1f".format(radius)}px  scale=${"%.3f".format(scale)} ===")
            for (ring in rings) {
                val labelRadius = radius * (ring.labelRadius ?: (ring.inner + ring.outer) / 2f)
                val preferred = (ring.baseSp * scale).coerceAtMost(26f)

                // 按实现同样的方式逐档下探，求出能容纳的最大字号
                var chosen = 0f
                var sp = preferred
                while (sp >= CompassDialLayout.minFontSp) {
                    val (w, h) = boxFor(ring.charCount, sp, case.density)
                    if (CompassDialLayout.fits(labelRadius, ring.cellCount, w, h, radial = true)) {
                        chosen = sp
                        break
                    }
                    sp -= 0.25f
                }
                val fallbackToSingle = chosen <= 0f
                val effectiveSp = if (fallbackToSingle) CompassDialLayout.minFontSp else chosen
                val chars = if (fallbackToSingle) 1 else ring.charCount
                val (w, h) = boxFor(chars, effectiveSp, case.density)
                val budget = CompassDialLayout.tangentialWidthBudgetPx(labelRadius, ring.cellCount, w)
                val fits = CompassDialLayout.fits(labelRadius, ring.cellCount, w, h, radial = true)

                println(
                    "  ${ring.name}: preferred=${"%.2f".format(preferred)}sp -> used=${"%.2f".format(effectiveSp)}sp " +
                        "x${chars}char  budget=${"%.2f".format(budget)}px  radialLen=${"%.2f".format(h)}px  fits=$fits"
                )
                assertTrue(
                    "${case.label} ${ring.name} still overlaps after adaptive sizing",
                    fits
                )
                assertTrue("font size must not go below the floor", effectiveSp >= CompassDialLayout.minFontSp)
                // 反向约束：稀疏环不允许被缩得难以辨认（防止预算过度保守）
                if (ring.minAcceptableSp > 0f) {
                    assertTrue(
                        "${case.label} ${ring.name} shrunk to ${"%.2f".format(effectiveSp)}sp, " +
                            "below the acceptable minimum ${ring.minAcceptableSp}sp",
                        effectiveSp >= ring.minAcceptableSp
                    )
                }
            }
        }
    }

    /** 单字兜底几何必须成立：这是「宁少显示一个字，也不重叠」的底线保证。 */
    @Test
    fun singleCharacterFallback_alwaysFits() {
        for (case in canvasCases) {
            val radius = dialRadiusPx(case)
            val labelRadius = radius * jiaziLabelRadius
            val sp = CompassDialLayout.minFontSp
            val (w, h) = boxFor(1, sp, case.density)
            val fits = CompassDialLayout.fits(labelRadius, 60, w, h, radial = true)
            println(
                "${case.label}: jiazi single-char fallback ${"%.1f".format(sp)}sp box=${w}x${h}px fits=$fits"
            )
            assertTrue("jiazi single-char fallback must not overlap on ${case.label}", fits)
        }
    }

    @Test
    fun budgetShrinksWithCellCount() {
        val radius = 300f
        val b24 = CompassDialLayout.tangentialWidthBudgetPx(radius, 24, 20f)
        val b60 = CompassDialLayout.tangentialWidthBudgetPx(radius, 60, 20f)
        println("=== more cells -> smaller tangential budget: 24cells=${"%.2f".format(b24)}px  60cells=${"%.2f".format(b60)}px")
        assertTrue("60-cell budget must be smaller than 24-cell", b60 < b24)
        assertEquals(true, b60 < b24)
    }

    private data class CanvasCase(val label: String, val sideDp: Float, val density: Float)

    private data class RingSpec(
        val name: String,
        val cellCount: Int,
        val inner: Float,
        val outer: Float,
        val baseSp: Float,
        val charCount: Int,
        val labelRadius: Float? = null,
        /** 在小屏上仍应达到的最小可用字号；0 表示不做下限约束（如 60 格密集环） */
        val minAcceptableSp: Float = 0f
    )
}
