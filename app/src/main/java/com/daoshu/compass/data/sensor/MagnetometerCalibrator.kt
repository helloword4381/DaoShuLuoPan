package com.daoshu.compass.data.sensor

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁力计 8 字校准辅助。
 *
 * 原理：把手机在空中画“8”字时，三轴磁力计读数会在球面上移动，
 * 每一轴的跨度（max - min）都接近“2 × 当地地磁总强度”。
 * 因此用三轴平均跨度与目标跨度的比值估计校准进度，
 * 并叠加采样数量因子，避免刚采到两三个样本就报告 100%。
 *
 * 本类不做任何持久化，进程重启后重新统计；线程安全。
 */
@Singleton
class MagnetometerCalibrator @Inject constructor() {

    private val lock = Any()

    /** 是否已经收到过至少一个样本 */
    private var hasSample = false

    /** 累计样本数，用于抑制“样本太少却进度很高”的误报 */
    private var sampleCount = 0

    /** 各轴最小值；未采样时取典型地磁强度的负值作为初值 */
    private val minValues = FloatArray(AXIS_COUNT) { -DEFAULT_FIELD_UT }

    /** 各轴最大值；未采样时取典型地磁强度的正值作为初值 */
    private val maxValues = FloatArray(AXIS_COUNT) { DEFAULT_FIELD_UT }

    /**
     * 记录一次三轴磁力计采样（微特斯拉，设备坐标系）。
     */
    fun onSample(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        synchronized(lock) {
            val values = floatArrayOf(x, y, z)
            for (axis in 0 until AXIS_COUNT) {
                if (!hasSample) {
                    minValues[axis] = values[axis]
                    maxValues[axis] = values[axis]
                } else {
                    if (values[axis] < minValues[axis]) minValues[axis] = values[axis]
                    if (values[axis] > maxValues[axis]) maxValues[axis] = values[axis]
                }
            }
            hasSample = true
            sampleCount++
        }
    }

    /**
     * 校准进度 0..100。
     *
     * = min(1, 三轴平均跨度 / 目标跨度) × min(1, 样本数 / 最少样本数)，取整后裁剪到 0..100。
     */
    fun calibrationPercentage(): Int = synchronized(lock) {
        var spanSum = 0.0
        for (axis in 0 until AXIS_COUNT) {
            spanSum += (maxValues[axis] - minValues[axis]).toDouble()
        }
        val averageSpan = spanSum / AXIS_COUNT
        val spanRatio = (averageSpan / TARGET_SPAN_UT).coerceIn(0.0, 1.0)
        val sampleRatio = (sampleCount.toDouble() / MIN_SAMPLE_COUNT).coerceIn(0.0, 1.0)
        (spanRatio * sampleRatio * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX)
    }

    /** 清空统计，重新开始校准。 */
    fun reset() {
        synchronized(lock) {
            hasSample = false
            sampleCount = 0
            for (axis in 0 until AXIS_COUNT) {
                minValues[axis] = -DEFAULT_FIELD_UT
                maxValues[axis] = DEFAULT_FIELD_UT
            }
        }
    }

    /**
     * 校准点集合，供 UI 提示用（x,y,z 三元组）。
     *
     * 返回当前 min/max 包围盒的 8 个角点：已采样的轴用实测极值，
     * 尚未采样的轴用 ±DEFAULT_FIELD_UT 的典型值，因此任何时刻都返回 8 个点。
     */
    fun calibrationPoints(): List<FloatArray> = synchronized(lock) {
        val points = ArrayList<FloatArray>(CORNER_COUNT)
        for (mask in 0 until CORNER_COUNT) {
            points.add(
                floatArrayOf(
                    if (mask and BIT_X != 0) maxValues[0] else minValues[0],
                    if (mask and BIT_Y != 0) maxValues[1] else minValues[1],
                    if (mask and BIT_Z != 0) maxValues[2] else minValues[2]
                )
            )
        }
        points
    }

    private companion object {
        /** 三轴：x / y / z */
        const val AXIS_COUNT = 3

        /** 包围盒的 8 个角点 */
        const val CORNER_COUNT = 8

        const val BIT_X = 1
        const val BIT_Y = 2
        const val BIT_Z = 4

        /** 未采样时使用的典型地磁强度初值（微特斯拉） */
        const val DEFAULT_FIELD_UT = 50f

        /**
         * 目标平均跨度：完整画“8”字时每轴跨度约为 2 × 地磁总强度（50µT 量级）= 100µT。
         */
        const val TARGET_SPAN_UT = 100.0

        /** 达到满进度所需的最少样本数（20ms 采样下约 1 秒） */
        const val MIN_SAMPLE_COUNT = 50

        const val PERCENT_MAX = 100
    }
}
