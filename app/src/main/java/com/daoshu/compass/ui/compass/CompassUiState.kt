package com.daoshu.compass.ui.compass

import com.daoshu.compass.data.location.DeclinationInfo
import com.daoshu.compass.data.location.LocationFix
import com.daoshu.compass.data.settings.CompassSettings
import com.daoshu.compass.data.sensor.SensorReading
import com.daoshu.compass.data.update.UpdateState

/** 罗盘主界面状态（由 CompassViewModel 组装） */
data class CompassUiState(
    val settings: CompassSettings = CompassSettings(),
    val reading: SensorReading? = null,
    val northHeadingDegrees: Float = 0f,   // 已按 真北/磁北 + 校准偏移 修正后的朝向
    val magneticHeadingDegrees: Float = 0f,
    val declination: DeclinationInfo? = null,
    val fix: LocationFix? = null,
    val needsCalibration: Boolean = false,
    val calibrationPercentage: Int = 0,
    val locationPermissionGranted: Boolean = false,
    val updateState: UpdateState = UpdateState.Idle,
    val updateDialogVisible: Boolean = false
)
