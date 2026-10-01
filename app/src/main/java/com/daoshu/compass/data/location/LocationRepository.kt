package com.daoshu.compass.data.location

import kotlinx.coroutines.flow.StateFlow

/**
 * 定位与磁偏角仓库。
 *
 * 实现由 `FusedLocationRepository` 提供，Hilt 绑定见 `di/NetworkModule.kt`。
 */
interface LocationRepository {

    /** 定位 / 磁偏角状态流 */
    val state: StateFlow<GeoState>

    /** 是否已授予定位权限（不触发请求） */
    fun hasLocationPermission(): Boolean

    /** 主动请求一次定位更新；无权限时直接置 errorMessage */
    fun requestLocationUpdate()

    /** 用最近一次定位（或上次保存值）计算磁偏角 */
    fun computeDeclination(latitude: Double, longitude: Double, altitudeMeters: Double): DeclinationInfo

    /** 停止定位并释放注册的回调 */
    fun stop()
}
