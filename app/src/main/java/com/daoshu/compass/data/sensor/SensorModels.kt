package com.daoshu.compass.data.sensor

/**
 * 单次传感器采样结果。
 *
 * 角度单位为度，方位角为相对手机顶部指向的磁方位角（0 = 磁北，顺时针增大）。
 */
data class SensorReading(
    /** 0..360，磁北为 0 */
    val azimuthDegrees: Float,
    /** SensorManager.SENSOR_STATUS_* */
    val accuracy: Int,
    /** 磁场强度微特斯拉 */
    val magneticStrengthUt: Float,
    /** 手机俯仰角（平放为 0，顶部朝天为 -90，顶部朝地为 +90） */
    val tiltDegrees: Float,
    /** 采样时间戳（纳秒，取自 SensorEvent.timestamp） */
    val timestampNanos: Long
)

/**
 * 对外暴露的传感器状态。
 */
data class SensorState(
    /** 最近一次可用读数；解算不出方位角时为 null */
    val reading: SensorReading? = null,
    /** 设备是否有 ROTATION_VECTOR 或 MAGNETIC_FIELD */
    val isAvailable: Boolean = false,
    /** 精度低于 SENSOR_STATUS_ACCURACY_MEDIUM 时为 true */
    val needsCalibration: Boolean = false,
    /** 0..100 校准进度（由 MagnetometerCalibrator 给出） */
    val calibrationPercentage: Int = 0
)
