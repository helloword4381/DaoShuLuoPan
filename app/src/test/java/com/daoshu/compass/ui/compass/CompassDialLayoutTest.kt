package com.daoshu.compass.ui.compass

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 罗盘盘面自适应排版验证（纯 JVM，不需要设备）。
 *
 * 覆盖真机反馈的三个问题：
 *   1. 文字与环圈是否随画布（屏幕）大小同源缩放；
 *   2. 密集环（六十甲子 60 格）的文字是否还会重叠；
 *   3. 环上文字是否越出本环带（跨到相邻环）——跨环会造成「糊成一片」的观感。
 *
 * 说明：这里验证的是 [CompassDialLayout] 的几何判定，它与 [CompassDial] 实际绘制使用的是
 * 同一套「切向内接预算 / 环带宽度预算」公式与同一套缩放系数，因此结论对实际绘制成立。
 * 文字尺寸按「中文方块字」建模（宽 = 高 = 字号像素），对 CJK 字形而言这是保守估计。
 */
class CompassDialLayoutTest {

    /** 盘面外缘比例，与 CompassDial.OUTER_RING 保持一致 */
    private val outerRing = 0.900f

    /** 各环带内外半径比例，与 CompassDial 中的常量保持一致 */
    private val xiuBand = 0.736f to 0.852f
    private val jiaziBand = 0.568f to 0.716f
    private val mountainBand = 0.414f to 0.548f
    private val baguaBand = 0.262f to 0.386f

    /** 环带宽度预算比例，与 CompassDial.BAND_FIT_RATIO 保持一致 */
    private val bandFitRatio = 0.86f

    /**
     * 实际可用画布边长（dp）。
     *
     * 注意：CompassScreen 中表盘是 `Column` 里的 `fillMaxWidth().weight(1f)`，
     * 因此给到 CompassDial 的方框边长 = min(屏宽 - 2*16dp 内边距, 剩余高度)，
     * 通常**小于**屏幕宽度；真机截图实测盘半径约 450px / 密度 2.75 ≈ 164dp，
     * 对应方框边长约 364dp。这里覆盖更小与更大的情形。
     */
    private val canvasCases = listOf(
        CanvasCase("small 5.0in dial 320dp @2.0", 320f, 2.0f),
        CanvasCase("main 6.1in dial 364dp @2.75 (真机实测)", 364f, 2.75f),
        CanvasCase("large 6.7in dial 400dp @2.625", 400f, 2.625f),
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
        measured.zipWithNext().forEach { (a, b) ->
            assertTrue("scale must not decrease with radius: $a -> $b", b.second >= a.second)
        }
        assertTrue(
            "largest canvas must get a strictly larger scale",
            measured.last().second > measured.first().second
        )
        measured.forEach { (_, s) ->
            assertTrue("scale out of range: $s", s >= 0.60f && s <= 1.80f)
        }
    }

    /**
     * 复现线上问题：六十甲子环在旧实现的固定 7.5sp 下径排两字，必然超出切向预算（= 重叠）。
     */
    @Test
    fun jiaziAtOldFixedFontSize_reproducesOverlap() {
        val case = canvasCases[0] // small dial, worst case
        val radius = dialRadiusPx(case)
        val labelRadius = radius * (jiaziBand.first + jiaziBand.second) / 2f
        val (w, h) = boxFor(charCount = 2, fontSp = 7.5f, density = case.density)
        val budget = CompassDialLayout.tangentialWidthBudgetPx(labelRadius, 60, w)
        val fits = CompassDialLayout.fits(labelRadius, 60, w, h, radial = true)
        println(
            "=== OLD behaviour: jiazi at fixed 7.5sp, radial, 2 chars (${case.label}) ===\n" +
                "  labelRadius=${"%.1f".format(labelRadius)}px  textBox=${w}x${h}px  " +
                "tangentialBudget=${"%.2f".format(budget)}px  fits=$fits"
        )
        assertTrue("fixed 7.5sp two-char jiazi must be reported as overlapping", !fits)
    }

    /**
     * 修复后的行为：字号随盘半径缩放，并逐档缩小到**同时**满足切向预算与环带宽度；
     * 两字放不下时退回单字，且单字必须同时满足两个约束。
     */
    @Test
    fun adaptiveFontSize_satisfiesBothBudgetsForEveryCanvas() {
        val rings = listOf(
            RingSpec("xiu-28", 28, xiuBand.first, xiuBand.second, 11f, 1, 8.0f),
            RingSpec("jiazi-60", 60, jiaziBand.first, jiaziBand.second, 9.5f, 2, 0f),
            RingSpec("mountains-24", 24, mountainBand.first, mountainBand.second, 14f, 2, 8.0f),
            RingSpec("bagua-8", 8, baguaBand.first, baguaBand.second, 18f, 1, 12.0f)
        )
        for (case in canvasCases) {
            val radius = dialRadiusPx(case)
            val scale = CompassDialLayout.scaleForRadius(radius)
            println("=== ${case.label}  radius=${"%.1f".format(radius)}px  scale=${"%.3f".format(scale)} ===")
            for (ring in rings) {
                val labelRadius = radius * (ring.inner + ring.outer) / 2f
                val bandWidth = radius * (ring.outer - ring.inner)
                val preferred = (ring.baseSp * scale).coerceAtMost(30f)

                var chosen = 0f
                var sp = preferred
                while (sp >= CompassDialLayout.minFontSp) {
                    val (w, h) = boxFor(ring.charCount, sp, case.density)
                    if (CompassDialLayout.fits(labelRadius, ring.cellCount, w, h, radial = true) &&
                        CompassDialLayout.fitsBand(w, h, bandWidth, radial = true)
                    ) {
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
                val bandBudget = CompassDialLayout.bandWidthBudgetPx(bandWidth)
                val fits = CompassDialLayout.fits(labelRadius, ring.cellCount, w, h, radial = true)
                val bandOk = CompassDialLayout.fitsBand(w, h, bandWidth, radial = true)

                println(
                    "  ${ring.name}: preferred=${"%.2f".format(preferred)}sp -> used=${"%.2f".format(effectiveSp)}sp " +
                        "x${chars}char  tangential=${"%.2f".format(budget)}px  band=${"%.2f".format(bandBudget)}px  " +
                        "radialLen=${"%.2f".format(h)}px  fits=$fits/$bandOk"
                )
                assertTrue("${case.label} ${ring.name} overlaps a neighbour", fits)
                assertTrue("${case.label} ${ring.name} escapes its band", bandOk)
                assertTrue("font below floor", effectiveSp >= CompassDialLayout.minFontSp)
                if (ring.minAcceptableSp > 0f) {
                    assertTrue(
                        "${case.label} ${ring.name} shrunk to ${"%.2f".format(effectiveSp)}sp, " +
                            "below acceptable ${ring.minAcceptableSp}sp",
                        effectiveSp >= ring.minAcceptableSp
                    )
                }
            }
        }
    }

    /** 单字兜底几何必须成立：这是「宁少显示一个字，也不重叠/不跨环」的底线保证。 */
    @Test
    fun singleCharacterFallback_alwaysFits() {
        for (case in canvasCases) {
            val radius = dialRadiusPx(case)
            val labelRadius = radius * (jiaziBand.first + jiaziBand.second) / 2f
            val bandWidth = radius * (jiaziBand.second - jiaziBand.first)
            val sp = CompassDialLayout.minFontSp
            val (w, h) = boxFor(1, sp, case.density)
            val fits = CompassDialLayout.fits(labelRadius, 60, w, h, radial = true)
            val bandOk = CompassDialLayout.fitsBand(w, h, bandWidth, radial = true)
            println(
                "${case.label}: jiazi single-char fallback ${"%.1f".format(sp)}sp box=${w}x${h}px " +
                    "fits=$fits band=$bandOk (bandWidth=${"%.1f".format(bandWidth)}px)"
            )
            assertTrue("jiazi single-char fallback must not overlap on ${case.label}", fits)
            assertTrue("jiazi single-char fallback must stay in band on ${case.label}", bandOk)
        }
    }

    /**
     * 环带宽度必须至少能容下字号下限：否则该环的字号会被环带预算压到下限以下，
     * 退化成「不可读也不合规」。同时验证求解后的字号确实落在环带内。
     */
    @Test
    fun bandWidthCanAccommodateMinimumFontSize() {
        val minSp = CompassDialLayout.minFontSp
        val rings = listOf(
            RingSpec("xiu-28", 28, xiuBand.first, xiuBand.second, 11f, 1, 0f),
            RingSpec("jiazi-60", 60, jiaziBand.first, jiaziBand.second, 9.5f, 2, 0f),
            RingSpec("mountains-24", 24, mountainBand.first, mountainBand.second, 14f, 2, 0f),
            RingSpec("bagua-8", 8, baguaBand.first, baguaBand.second, 18f, 1, 0f)
        )
        for (case in canvasCases) {
            val radius = dialRadiusPx(case)
            println("=== band width vs font floor (${case.label}, radius=${"%.1f".format(radius)}px) ===")
            for (ring in rings) {
                val bandWidth = radius * (ring.outer - ring.inner)
                val minTextPx = minSp * case.density
                println(
                    "  ${ring.name}: band=${"%.1f".format(bandWidth)}px  " +
                        "floorText=${"%.1f".format(minTextPx)}px  budget=${"%.1f".format(bandWidth * bandFitRatio)}px"
                )
                assertTrue(
                    "${ring.name} band too narrow even for the ${minSp}sp floor on ${case.label}: " +
                        "${"%.1f".format(minTextPx)}px > ${"%.1f".format(bandWidth * bandFitRatio)}px",
                    minTextPx <= bandWidth * bandFitRatio
                )
            }
        }
    }

    @Test
    fun budgetShrinksWithCellCount() {
        val radius = 450f
        val b24 = CompassDialLayout.tangentialWidthBudgetPx(radius, 24, 34f)
        val b60 = CompassDialLayout.tangentialWidthBudgetPx(radius, 60, 34f)
        println("=== more cells -> smaller tangential budget: 24cells=${"%.2f".format(b24)}px  60cells=${"%.2f".format(b60)}px")
        assertTrue("60-cell budget must be smaller than 24-cell", b60 < b24)
    }

    private data class CanvasCase(val label: String, val sideDp: Float, val density: Float)

    private data class RingSpec(
        val name: String,
        val cellCount: Int,
        val inner: Float,
        val outer: Float,
        val baseSp: Float,
        val charCount: Int,
        /** 在小屏上仍应达到的最小可用字号；0 表示不做下限约束（如 60 格密集环） */
        val minAcceptableSp: Float = 0f
    )
}
