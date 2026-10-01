package com.daoshu.compass.data.location

/**
 * 定位结果。
 */
data class LocationFix(
    /** 纬度（度，北正） */
    val latitude: Double,
    /** 经度（度，东正） */
    val longitude: Double,
    /** 海拔（米）；平台未提供时为 0.0 */
    val altitudeMeters: Double,
    /** 水平精度（米）；平台未提供时为 -1 */
    val accuracyMeters: Float,
    /** 定位来源：fused / gps / network / passive 等 */
    val provider: String,
    /** 定位时间（Unix 毫秒） */
    val timeMillis: Long
)

/**
 * 磁偏角结果，由 Android 平台 [android.hardware.GeomagneticField] 给出。
 */
data class DeclinationInfo(
    /** 磁偏角，东偏为正 */
    val declinationDegrees: Float,
    /** 地磁总强度（微特斯拉） */
    val fieldStrengthUt: Float,
    /** 磁倾角（度） */
    val inclinationDegrees: Float,
    /** 地磁模型年份（取当前公历年份） */
    val modelYear: Double
)

/**
 * 定位与磁偏角对外状态。
 */
data class GeoState(
    /** 最近一次定位结果 */
    val fix: LocationFix? = null,
    /** 由最近一次定位计算出的磁偏角 */
    val declination: DeclinationInfo? = null,
    /** 是否已授予定位权限 */
    val permissionGranted: Boolean = false,
    /** 错误提示（无权限、定位服务不可用等） */
    val errorMessage: String? = null
)
