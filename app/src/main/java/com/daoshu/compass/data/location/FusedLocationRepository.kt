package com.daoshu.compass.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.daoshu.compass.di.ApplicationScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Year
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 定位与磁偏角实现。
 *
 * 主路径：`FusedLocationProviderClient`（先取上次已知位置，再请求一次新定位，
 * `LocationRequest.Builder` + `PRIORITY_BALANCED_POWER_ACCURACY`）。
 * 降级路径：Google Play 服务不可用、抛异常或取不到结果时改用
 * `android.location.LocationManager`（NETWORK_PROVIDER 优先，其次 GPS_PROVIDER）。
 *
 * 磁偏角一律使用平台自带的 [GeomagneticField]（内部为 WMM 模型），不自行实现球谐系数。
 */
@Singleton
class FusedLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) : LocationRepository {

    /** Google Play 服务不可用或初始化失败时为 null */
    private val fusedClient: FusedLocationProviderClient? = createFusedClient()

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _state = MutableStateFlow(
        GeoState(permissionGranted = hasLocationPermission())
    )

    override val state: StateFlow<GeoState> = _state.asStateFlow()

    private val lock = Any()

    private var requestJob: Job? = null

    @Volatile
    private var activeFusedCallback: LocationCallback? = null

    private fun createFusedClient(): FusedLocationProviderClient? {
        val availability = GoogleApiAvailability.getInstance()
        val status = runCatching { availability.isGooglePlayServicesAvailable(context) }
            .getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (status != ConnectionResult.SUCCESS) return null
        return runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull()
    }

    override fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    override fun requestLocationUpdate() {
        if (!hasLocationPermission()) {
            _state.update {
                it.copy(
                    permissionGranted = false,
                    errorMessage = ERROR_NO_PERMISSION
                )
            }
            return
        }
        _state.update { it.copy(permissionGranted = true, errorMessage = null) }
        synchronized(lock) {
            requestJob?.cancel()
            requestJob = scope.launch { acquireFix() }
        }
    }

    override fun computeDeclination(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double
    ): DeclinationInfo {
        // 防御性裁剪：GeomagneticField 对越界纬度会抛 IllegalArgumentException
        val safeLatitude = latitude.coerceIn(MIN_LATITUDE, MAX_LATITUDE).toFloat()
        val safeLongitude = normalizeLongitude(longitude).toFloat()
        val nowMillis = System.currentTimeMillis()
        val field = GeomagneticField(
            safeLatitude,
            safeLongitude,
            altitudeMeters.toFloat(),
            nowMillis
        )
        // Kotlin 属性语法即平台 getDeclination() / getFieldStrength() / getInclination()
        return DeclinationInfo(
            declinationDegrees = field.declination,
            fieldStrengthUt = field.fieldStrength,
            inclinationDegrees = field.inclination,
            modelYear = Year.now().value.toDouble()
        )
    }

    override fun stop() {
        synchronized(lock) {
            requestJob?.cancel()
            requestJob = null
        }
        val callback = activeFusedCallback
        if (callback != null) {
            runCatching { fusedClient?.removeLocationUpdates(callback) }
            activeFusedCallback = null
        }
    }

    // ------------------------------------------------------------------
    // 定位获取
    // ------------------------------------------------------------------

    private suspend fun acquireFix() {
        val client = fusedClient
        if (client != null) {
            // 主路径：Google Play 定位。异常（含 SecurityException）时走降级路径
            val fusedFix = runCatching { requestFusedFix(client) }.getOrNull()
            if (fusedFix != null) {
                publishFix(fusedFix)
                return
            }
        }
        // 降级路径：系统 LocationManager
        val systemFix = runCatching { requestSystemFix() }.getOrNull()
        if (systemFix != null) {
            publishFix(systemFix)
        } else {
            _state.update {
                it.copy(
                    permissionGranted = true,
                    errorMessage = if (client == null) {
                        ERROR_FUSED_UNAVAILABLE
                    } else {
                        ERROR_LOCATION_FAILED
                    }
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFusedFix(client: FusedLocationProviderClient): Location? {
        val lastKnown: Location? = withTimeoutOrNull(LAST_KNOWN_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                client.lastLocation
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        }

        // 已缓存上次位置时只等较短时间拿新定位，避免界面长时间无反馈
        val freshTimeout = if (lastKnown != null) REFRESH_TIMEOUT_MILLIS else COLD_TIMEOUT_MILLIS
        val fresh: Location? = withTimeoutOrNull(freshTimeout) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val location = result.lastLocation ?: return
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
                activeFusedCallback = callback
                val request = LocationRequest.Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    UPDATE_INTERVAL_MILLIS
                )
                    .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
                    .setMaxUpdates(MAX_UPDATES)
                    .setWaitForAccurateLocation(false)
                    .build()
                val started = runCatching {
                    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                }.isSuccess
                if (!started) {
                    activeFusedCallback = null
                    if (continuation.isActive) continuation.resume(null)
                }
                continuation.invokeOnCancellation {
                    runCatching { client.removeLocationUpdates(callback) }
                    activeFusedCallback = null
                }
            }
        }

        activeFusedCallback?.let { callback ->
            runCatching { client.removeLocationUpdates(callback) }
        }
        activeFusedCallback = null
        return fresh ?: lastKnown
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSystemFix(): Location? {
        val manager = locationManager ?: return null
        val provider = pickSystemProvider(manager) ?: return null
        val lastKnown = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()

        val fresh: Location? = withTimeoutOrNull(COLD_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellationSignal = CancellationSignal()
                val executor = ContextCompat.getMainExecutor(context)
                val consumer = Consumer<Location> { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                val started = runCatching {
                    manager.getCurrentLocation(provider, cancellationSignal, executor, consumer)
                }.isSuccess
                if (!started && continuation.isActive) continuation.resume(null)
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
            }
        }
        return fresh ?: lastKnown
    }

    /** 磁偏角对定位精度要求很低（随地理位置缓慢变化），故优先省电的网络定位。 */
    private fun pickSystemProvider(manager: LocationManager): String? {
        val candidates = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        for (provider in candidates) {
            val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (enabled) return provider
        }
        return null
    }

    private fun publishFix(location: Location) {
        val fix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else UNKNOWN_ACCURACY_METERS,
            provider = location.provider ?: PROVIDER_UNKNOWN,
            timeMillis = location.time
        )
        val declination = computeDeclination(fix.latitude, fix.longitude, fix.altitudeMeters)
        _state.update {
            it.copy(
                fix = fix,
                declination = declination,
                permissionGranted = true,
                errorMessage = null
            )
        }
    }

    private fun normalizeLongitude(longitude: Double): Double {
        if (!longitude.isFinite()) return 0.0
        var result = longitude % DEGREES_FULL_CIRCLE
        if (result > DEGREES_HALF_CIRCLE) result -= DEGREES_FULL_CIRCLE
        if (result < -DEGREES_HALF_CIRCLE) result += DEGREES_FULL_CIRCLE
        return result
    }

    private companion object {
        const val ERROR_NO_PERMISSION = "未授予定位权限，无法计算磁偏角；请在系统设置中允许本应用使用定位"
        const val ERROR_FUSED_UNAVAILABLE = "本机 Google Play 定位服务不可用，且系统定位未返回结果"
        const val ERROR_LOCATION_FAILED = "定位失败，请确认系统定位开关已开启并授予定位权限"

        const val PROVIDER_UNKNOWN = "unknown"
        const val UNKNOWN_ACCURACY_METERS = -1f

        /** 一次新定位的请求间隔与最小间隔（毫秒） */
        const val UPDATE_INTERVAL_MILLIS = 5_000L
        const val MIN_UPDATE_INTERVAL_MILLIS = 2_000L

        /** 只取一次定位结果 */
        const val MAX_UPDATES = 1

        const val LAST_KNOWN_TIMEOUT_MILLIS = 3_000L
        const val REFRESH_TIMEOUT_MILLIS = 4_000L
        const val COLD_TIMEOUT_MILLIS = 10_000L

        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val DEGREES_FULL_CIRCLE = 360.0
        const val DEGREES_HALF_CIRCLE = 180.0
    }
}
