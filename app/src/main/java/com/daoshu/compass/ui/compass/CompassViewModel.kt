package com.daoshu.compass.ui.compass

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daoshu.compass.data.install.ApkInstaller
import com.daoshu.compass.data.location.DeclinationInfo
import com.daoshu.compass.data.location.GeoState
import com.daoshu.compass.data.location.LocationRepository
import com.daoshu.compass.data.sensor.CompassSensorManager
import com.daoshu.compass.data.settings.NorthType
import com.daoshu.compass.data.settings.SettingsRepository
import com.daoshu.compass.data.update.UpdateRepository
import com.daoshu.compass.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 罗盘主界面 ViewModel：把四个 data 层数据源合并为单一的 [CompassUiState]。
 *
 * 注入的四个仓库严格按 CONTRACT 第 3/4/5/6 节；额外的 ApplicationContext 仅用于
 * [ApkInstaller]（安装 APK 必须有 Context），不会泄漏 Activity。
 */
@HiltViewModel
class CompassViewModel @Inject constructor(
    private val sensorManager: CompassSensorManager,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** 更新对话框显隐：由运行时事件（启动检查 / 手动检查）驱动，不落设置 */
    private val dialogVisible = MutableStateFlow(false)

    /** 本次进程内是否已做过启动检查，避免每次 onResume 都请求网络 */
    private var autoCheckDone = false

    /** 磁偏角缓存：GeomagneticField 构造代价较高，按定位时间戳缓存 */
    private var cachedDeclinationFixTime = Long.MIN_VALUE
    private var cachedDeclination: DeclinationInfo? = null

    val uiState: StateFlow<CompassUiState> = combine(
        settingsRepository.settings,
        sensorManager.state,
        locationRepository.state,
        updateRepository.state,
        dialogVisible
    ) { settings, sensor, geo, update, dialog ->
        val magneticHeading = normalizeDegrees(
            (sensor.reading?.azimuthDegrees ?: 0f) + settings.calibrationOffsetDegrees
        )
        val declination = resolveDeclination(geo)
        val northCorrection = if (settings.northType == NorthType.TRUE) {
            declination?.declinationDegrees ?: 0f
        } else {
            0f
        }
        CompassUiState(
            settings = settings,
            reading = sensor.reading,
            northHeadingDegrees = normalizeDegrees(magneticHeading + northCorrection),
            magneticHeadingDegrees = magneticHeading,
            declination = declination,
            fix = geo.fix,
            needsCalibration = sensor.needsCalibration,
            calibrationPercentage = sensor.calibrationPercentage,
            locationPermissionGranted = geo.permissionGranted || locationRepository.hasLocationPermission(),
            updateState = update,
            updateDialogVisible = dialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CompassUiState()
    )

    init {
        // 平滑系数随设置变化实时下发给传感器层
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                sensorManager.setSmoothing(settings.smoothing)
            }
        }
    }

    /** 进入前台：启动传感器与定位，并在需要时执行一次启动更新检查 */
    fun onResume() {
        sensorManager.start()
        if (locationRepository.hasLocationPermission()) {
            locationRepository.requestLocationUpdate()
        }
        if (!autoCheckDone) {
            autoCheckDone = true
            viewModelScope.launch {
                val settings = settingsRepository.settings.first()
                if (!settings.checkUpdateOnStart) return@launch
                val result = updateRepository.checkForUpdate()
                if (result is UpdateState.Available && settings.showUpdateDialogOnStart) {
                    dialogVisible.value = true
                }
            }
        }
    }

    /** 退到后台：停止传感器与定位，省电 */
    fun onPause() {
        sensorManager.stop()
        locationRepository.stop()
    }

    /** 切换某一绘制层的显隐 */
    fun toggleLayer(layer: CompassLayer) {
        viewModelScope.launch {
            settingsRepository.update { current ->
                val layers = current.layers
                current.copy(
                    layers = when (layer) {
                        CompassLayer.BAGUA -> layers.copy(bagua = !layers.bagua)
                        CompassLayer.MOUNTAINS -> layers.copy(mountains = !layers.mountains)
                        CompassLayer.JIAZI -> layers.copy(jiazi = !layers.jiazi)
                        CompassLayer.XIU -> layers.copy(xiu = !layers.xiu)
                        CompassLayer.DEGREE_TICKS -> layers.copy(degreeTicks = !layers.degreeTicks)
                    }
                )
            }
        }
    }

    /** 磁北 / 真北基准切换 */
    fun switchNorthType() {
        viewModelScope.launch {
            settingsRepository.update { current ->
                current.copy(
                    northType = if (current.northType == NorthType.TRUE) {
                        NorthType.MAGNETIC
                    } else {
                        NorthType.TRUE
                    }
                )
            }
        }
    }

    /** 清除手动校准偏移（八字校准由传感器层持续统计） */
    fun resetCalibration() {
        viewModelScope.launch {
            settingsRepository.update { current ->
                current.copy(calibrationOffsetDegrees = 0f)
            }
        }
    }

    fun dismissUpdateDialog() {
        dialogVisible.value = false
    }

    /** 下载并调起系统安装器；未获「安装未知应用」授权时先跳转系统设置 */
    fun startUpdate() {
        val info = (updateRepository.state.value as? UpdateState.Available)?.info ?: return
        viewModelScope.launch {
            if (!ApkInstaller.canRequestPackageInstalls(appContext)) {
                ApkInstaller.openInstallPermissionSettings(appContext)
                return@launch
            }
            val apkFile = updateRepository.downloadApk(info) ?: return@launch
            ApkInstaller.ensureNotificationChannel(appContext)
            ApkInstaller.notifyDownloadComplete(appContext, apkFile)
            if (!ApkInstaller.install(appContext, apkFile)) {
                // 安装被系统拦截时回到授权页，用户授权后可再次点击「立即更新」
                ApkInstaller.openInstallPermissionSettings(appContext)
            }
        }
    }

    /** 手动检查更新：发现新版本才弹对话框，失败仅在设置页/横幅显示状态 */
    fun checkUpdateManually() {
        viewModelScope.launch {
            val result = updateRepository.checkForUpdate()
            dialogVisible.value = result is UpdateState.Available
        }
    }

    /** 重新请求一次定位（无权限时不触发系统弹窗，由 UI 层负责申请） */
    fun refreshLocation() {
        if (locationRepository.hasLocationPermission()) {
            locationRepository.requestLocationUpdate()
        }
    }

    override fun onCleared() {
        sensorManager.stop()
        locationRepository.stop()
        super.onCleared()
    }

    /** 优先使用定位仓库给出的磁偏角，其次用最近一次定位结果本地计算（按时间戳缓存） */
    private fun resolveDeclination(geo: GeoState): DeclinationInfo? {
        geo.declination?.let { return it }
        val fix = geo.fix ?: return null
        if (fix.timeMillis != cachedDeclinationFixTime) {
            cachedDeclinationFixTime = fix.timeMillis
            cachedDeclination = runCatching {
                locationRepository.computeDeclination(fix.latitude, fix.longitude, fix.altitudeMeters)
            }.getOrNull()
        }
        return cachedDeclination
    }
}
