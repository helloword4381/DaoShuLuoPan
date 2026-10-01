package com.daoshu.compass.data.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.daoshu.compass.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * 指南针传感器融合。
 *
 * 姿态来源优先级：
 * 1. `TYPE_ROTATION_VECTOR`（含磁北，直接给出完整姿态）；
 * 2. `TYPE_GAME_ROTATION_VECTOR`（不含磁北，只用于俯仰角兜底）；
 * 3. `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` 走
 *    `SensorManager.getRotationMatrix` / `getOrientation`。
 *
 * 无论哪条路径，都注册磁力计以获得磁场强度、精度状态与 8 字校准样本。
 * 传感器回调只做数组复制并投递到 [ioDispatcher] 上的处理协程，
 * 角度解算、最短路径平滑与校准统计全部在该协程内完成，主线程零计算。
 */
@Singleton
class CompassSensorManager @Inject constructor(
    private val sensorManager: SensorManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val gameRotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val accelerometerSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val magneticFieldSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** 8 字校准统计；契约要求校准进度经 [SensorState.calibrationPercentage] 暴露，故由本类持有。 */
    private val calibrator = MagnetometerCalibrator()

    /** 契约定义：设备是否有 ROTATION_VECTOR 或 MAGNETIC_FIELD */
    private val isAvailable: Boolean = rotationVectorSensor != null || magneticFieldSensor != null

    private val _state = MutableStateFlow(SensorState(isAvailable = isAvailable))

    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var sampleChannel: Channel<SensorSample>? = null

    /** 低通滤波系数 0..1，越大越平滑（越大则单次插值权重越小） */
    @Volatile
    private var smoothingFactor = DEFAULT_SMOOTHING

    private var processingJob: Job? = null
    private var pipelineScope: CoroutineScope? = null

    // ------------------------------------------------------------------
    // 以下状态只由处理协程读写，无需加锁
    // ------------------------------------------------------------------

    /** 平滑后的方位角，null 表示还没有可用姿态 */
    private var filteredAzimuth: Float? = null

    /** 本帧解算出的原始方位角 */
    private var currentAzimuth: Float? = null

    /** 本帧俯仰角（度） */
    private var currentTilt = 0f

    /** 本帧磁场强度（微特斯拉） */
    private var currentStrength = 0f

    private var hasGravity = false
    private var hasMagnetic = false

    /** 旋转矢量是否已经给出过有效方位角 */
    private var hasRotationHeading = false

    private val gravityValues = FloatArray(AXIS_COUNT)
    private val magneticValues = FloatArray(AXIS_COUNT)

    /** 旋转矢量事件的精度兜底值 */
    private var lastRotationAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

    /** 磁力计事件精度（存在时优先，因为它直接反映磁力计校准状态） */
    private var lastMagneticAccuracy: Int? = null

    /**
     * 幂等启动：重复调用不会重复注册。
     */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val channel = Channel<SensorSample>(
                capacity = SAMPLE_BUFFER_SIZE,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            sampleChannel = channel
            val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
            pipelineScope = scope
            processingJob = scope.launch {
                for (sample in channel) {
                    processSample(sample)
                }
            }
            registerSensors()
        }
    }

    /**
     * 幂等停止：注销监听、结束处理协程。
     */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            runCatching { sensorManager.unregisterListener(sensorListener) }
            sampleChannel?.close()
            sampleChannel = null
            processingJob?.cancel()
            processingJob = null
            pipelineScope?.cancel()
            pipelineScope = null
        }
    }

    fun isRunning(): Boolean = running

    /**
     * 低通滤波系数 0..1，越大越平滑。
     *
     * 取值 1.0 时仍保留 [MIN_INTERPOLATION_WEIGHT] 的最小插值权重，
     * 避免罗盘彻底冻结不再跟随设备转动。
     */
    fun setSmoothing(factor: Float) {
        if (!factor.isFinite()) return
        smoothingFactor = factor.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // 传感器注册
    // ------------------------------------------------------------------

    private fun registerSensors() {
        // 优先旋转矢量；缺失时才启用游戏旋转矢量作为俯仰角兜底
        register(rotationVectorSensor)
        if (rotationVectorSensor == null) {
            register(gameRotationVectorSensor)
        }
        // 磁力计：磁场强度、精度状态、8 字校准样本均依赖它
        register(magneticFieldSensor)
        // 加速度计：旋转矢量缺失时参与姿态解算；存在时用于姿态校验与俯仰角兜底
        register(accelerometerSensor)
    }

    private fun register(sensor: Sensor?) {
        if (sensor == null) return
        runCatching { sensorManager.registerListener(sensorListener, sensor, SENSOR_INTERVAL_MICROS) }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val channel = sampleChannel ?: return
            // SensorEvent.values 由框架复用，跨线程投递前必须复制
            channel.trySend(
                SensorSample(
                    type = event.sensor.type,
                    values = event.values.copyOf(),
                    accuracy = event.accuracy,
                    timestampNanos = event.timestamp
                )
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ------------------------------------------------------------------
    // 处理协程
    // ------------------------------------------------------------------

    private fun processSample(sample: SensorSample) {
        when (sample.type) {
            Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(sample)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> processGameRotationVector(sample)
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(sample)
            Sensor.TYPE_MAGNETIC_FIELD -> processMagneticField(sample)
            else -> return
        }
        publish(sample.timestampNanos)
    }

    private fun processRotationVector(sample: SensorSample) {
        if (sample.values.size < MIN_ROTATION_VECTOR_SIZE) {
            // 数据异常时退回加速度计 + 磁力计解算
            updateFromGravityAndMagnetic()
            return
        }
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, sample.values)
        currentTilt = pitchDegrees(rotationMatrix)
        headingDegrees(rotationMatrix)?.let { heading ->
            currentAzimuth = heading
            hasRotationHeading = true
        }
        lastRotationAccuracy = sample.accuracy
    }

    private fun processGameRotationVector(sample: SensorSample) {
        if (sample.values.size < MIN_ROTATION_VECTOR_SIZE) return
        if (hasRotationHeading) return
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, sample.values)
        // 游戏旋转矢量不含磁北，只用于俯仰角；朝向仍由加速度计 + 磁力计给出
        currentTilt = pitchDegrees(rotationMatrix)
        lastRotationAccuracy = sample.accuracy
    }

    private fun processAccelerometer(sample: SensorSample) {
        if (sample.values.size < AXIS_COUNT) return
        for (axis in 0 until AXIS_COUNT) {
            gravityValues[axis] = sample.values[axis]
        }
        hasGravity = true
        if (rotationVectorSensor == null) {
            // 无旋转矢量：用加速度计 + 磁力计解算姿态
            updateFromGravityAndMagnetic()
            if (gameRotationVectorSensor == null) {
                // 连游戏旋转矢量都没有时，直接用重力方向得到俯仰角
                val norm = vectorNorm(gravityValues)
                if (norm > MIN_GRAVITY_NORM) {
                    currentTilt = -Math.toDegrees(
                        asin((gravityValues[1] / norm).coerceIn(-1f, 1f)).toDouble()
                    ).toFloat()
                }
            }
        }
    }

    private fun processMagneticField(sample: SensorSample) {
        if (sample.values.size < AXIS_COUNT) return
        for (axis in 0 until AXIS_COUNT) {
            magneticValues[axis] = sample.values[axis]
        }
        hasMagnetic = true
        currentStrength = vectorNorm(magneticValues)
        lastMagneticAccuracy = sample.accuracy
        // 8 字校准统计
        calibrator.onSample(magneticValues[0], magneticValues[1], magneticValues[2])
        if (rotationVectorSensor == null) {
            updateFromGravityAndMagnetic()
        }
    }

    /**
     * 加速度计 + 磁力计姿态解算（`getRotationMatrix` + `getOrientation`）。
     */
    private fun updateFromGravityAndMagnetic() {
        if (!hasGravity || !hasMagnetic) return
        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)
        val ok = SensorManager.getRotationMatrix(
            rotationMatrix,
            inclinationMatrix,
            gravityValues,
            magneticValues
        )
        if (!ok) return
        if (gameRotationVectorSensor == null) {
            currentTilt = pitchDegrees(rotationMatrix)
        }
        headingDegrees(rotationMatrix)?.let { heading -> currentAzimuth = heading }
    }

    // ------------------------------------------------------------------
    // 角度解算
    // ------------------------------------------------------------------

    /**
     * 由设备 -> 世界的旋转矩阵求方位角（0..360，磁北为 0，顺时针增大）。
     *
     * 旋转矩阵 R 满足 `世界向量 = R × 设备向量`，世界系为 X=东、Y=北、Z=天顶，
     * 因此 R 的第三行即“天顶方向在设备坐标系中的分量”。
     *
     * - 平放（天顶接近设备 Z 轴）：设备 Y 轴（屏幕上方）即朝向轴，直接用 R；
     * - 竖持（天顶接近设备 Y 轴）：设备 Y 轴接近竖直，方位角会退化抖动，
     *   此时用 `remapCoordinateSystem` 把屏幕背面（-Z 轴，即“举起手机瞄准”的方向）
     *   重映射为朝向轴，让竖屏握持也有稳定读数。
     */
    private fun headingDegrees(rotationMatrix: FloatArray): Float? {
        val upAlongY = abs(rotationMatrix[7])
        val upAlongZ = abs(rotationMatrix[8])
        val effectiveMatrix = if (upAlongZ >= upAlongY) {
            rotationMatrix
        } else {
            val remapped = FloatArray(9)
            val remappedOk = SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_MINUS_Z,
                remapped
            )
            if (remappedOk) remapped else rotationMatrix
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(effectiveMatrix, orientation)
        val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
        return if (degrees.isFinite()) normalizeDegrees(degrees) else null
    }

    /**
     * 俯仰角：`getOrientation` 的 pitch 约定（平放 0，顶部朝天 -90）。
     */
    private fun pitchDegrees(rotationMatrix: FloatArray): Float =
        Math.toDegrees(asin((-rotationMatrix[7]).coerceIn(-1f, 1f)).toDouble()).toFloat()

    /**
     * 最短路径低通平滑：沿 -180..180 的角差插值，杜绝 359°→1° 时反向跨越整圈。
     */
    private fun smoothAzimuth(target: Float): Float {
        val previous = filteredAzimuth
        if (previous == null) {
            filteredAzimuth = target
            return target
        }
        val weight = (1f - smoothingFactor).coerceAtLeast(MIN_INTERPOLATION_WEIGHT)
        val next = normalizeDegrees(previous + shortestAngleDelta(previous, target) * weight)
        filteredAzimuth = next
        return next
    }

    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = (to - from) % DEGREES_FULL_CIRCLE
        if (delta > DEGREES_HALF_CIRCLE) delta -= DEGREES_FULL_CIRCLE
        if (delta < -DEGREES_HALF_CIRCLE) delta += DEGREES_FULL_CIRCLE
        return delta
    }

    private fun normalizeDegrees(value: Float): Float {
        var result = value % DEGREES_FULL_CIRCLE
        if (result < 0f) result += DEGREES_FULL_CIRCLE
        return result
    }

    private fun vectorNorm(values: FloatArray): Float =
        sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])

    private fun effectiveAccuracy(): Int = lastMagneticAccuracy ?: lastRotationAccuracy

    /**
     * 组装并发布状态；解算不出方位角时保留上一帧读数。
     */
    private fun publish(timestampNanos: Long) {
        val rawAzimuth = currentAzimuth
        val smoothedAzimuth = rawAzimuth?.let { smoothAzimuth(it) }
        val accuracy = effectiveAccuracy()
        val strength = currentStrength
        val tilt = currentTilt

        _state.update { previous ->
            val reading = if (smoothedAzimuth != null) {
                SensorReading(
                    azimuthDegrees = smoothedAzimuth,
                    accuracy = accuracy,
                    magneticStrengthUt = strength,
                    tiltDegrees = tilt,
                    timestampNanos = timestampNanos
                )
            } else {
                previous.reading
            }
            SensorState(
                reading = reading,
                isAvailable = isAvailable,
                needsCalibration = accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                calibrationPercentage = calibrator.calibrationPercentage()
            )
        }
    }

    /** 传感器原始样本（values 已复制，可安全跨线程传递） */
    private class SensorSample(
        val type: Int,
        val values: FloatArray,
        val accuracy: Int,
        val timestampNanos: Long
    )

    private companion object {
        const val AXIS_COUNT = 3

        /** 旋转矢量至少需要 3 个元素 */
        const val MIN_ROTATION_VECTOR_SIZE = 3

        /** 采样周期 20ms（SENSOR_DELAY_GAME 量级），兼顾流畅与省电 */
        const val SENSOR_INTERVAL_MICROS = 20_000

        /** 样本缓冲：只保留最新数据，防止处理慢于采样时积压 */
        const val SAMPLE_BUFFER_SIZE = 64

        /** 重力模长下限，避免除以接近 0 的值 */
        const val MIN_GRAVITY_NORM = 0.5f

        /** 与 CompassSettings.smoothing 默认值保持一致 */
        const val DEFAULT_SMOOTHING = 0.15f

        /** 平滑系数取 1.0 时的最小插值权重，保证罗盘仍会缓慢跟随 */
        const val MIN_INTERPOLATION_WEIGHT = 0.02f

        const val DEGREES_FULL_CIRCLE = 360f
        const val DEGREES_HALF_CIRCLE = 180f
    }
}
