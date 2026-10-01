package com.daoshu.compass.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore 单例扩展属性，只在本文件内可见（契约固定文件名 `daoshu_compass_settings`）。
 *
 * `preferencesDataStore` 保证同一进程内同名文件只有一个 DataStore 实例，
 * 因此由 `DataStoreSettingsRepository` 单例持有即可，不会出现多实例并发写。
 */
private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(name = "daoshu_compass_settings")

/**
 * DataStore Preferences 实现。
 *
 * 键名统一为下划线小写；读取任何键都带默认值，首次启动（键不存在）也不会崩溃。
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    override val settings: Flow<CompassSettings> = context.dataStore.data
        .catch { throwable ->
            // 文件损坏或磁盘读取异常时回退到全默认配置，避免首屏崩溃
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences -> preferences.toCompassSettings() }

    override suspend fun update(transform: (CompassSettings) -> CompassSettings) {
        context.dataStore.edit { preferences ->
            val updated = transform(preferences.toCompassSettings())
            preferences.writeAll(updated)
        }
    }

    override suspend fun reset() {
        context.dataStore.edit { preferences -> preferences.clear() }
    }

    /**
     * 读取完整设置：每个键都使用 [CompassSettings] 的默认值兜底，并做范围归一化。
     */
    private fun Preferences.toCompassSettings(): CompassSettings {
        val defaults = CompassSettings()

        val themeMode = this[SettingsKeys.THEME_MODE]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: defaults.themeMode
        val northType = this[SettingsKeys.NORTH_TYPE]
            ?.let { stored -> NorthType.entries.firstOrNull { it.name == stored } }
            ?: defaults.northType

        val layers = LayerVisibility(
            bagua = this[SettingsKeys.LAYER_BAGUA] ?: defaults.layers.bagua,
            mountains = this[SettingsKeys.LAYER_MOUNTAINS] ?: defaults.layers.mountains,
            jiazi = this[SettingsKeys.LAYER_JIAZI] ?: defaults.layers.jiazi,
            xiu = this[SettingsKeys.LAYER_XIU] ?: defaults.layers.xiu,
            degreeTicks = this[SettingsKeys.LAYER_DEGREE_TICKS] ?: defaults.layers.degreeTicks
        )

        val smoothing = (this[SettingsKeys.SMOOTHING] ?: defaults.smoothing)
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_SMOOTHING, MAX_SMOOTHING)
            ?: defaults.smoothing

        val calibrationOffset = (this[SettingsKeys.CALIBRATION_OFFSET] ?: defaults.calibrationOffsetDegrees)
            .takeIf { it.isFinite() }
            ?.let { normalizeOffset(it) }
            ?: defaults.calibrationOffsetDegrees

        return CompassSettings(
            themeMode = themeMode,
            northType = northType,
            layers = layers,
            smoothing = smoothing,
            calibrationOffsetDegrees = calibrationOffset,
            checkUpdateOnStart = this[SettingsKeys.CHECK_UPDATE_ON_START] ?: defaults.checkUpdateOnStart,
            showUpdateDialogOnStart = this[SettingsKeys.SHOW_UPDATE_DIALOG_ON_START]
                ?: defaults.showUpdateDialogOnStart
        )
    }

    /**
     * 全量写回：保证一次 update 后磁盘上的键是自洽的完整快照。
     */
    private fun MutablePreferences.writeAll(settings: CompassSettings) {
        this[SettingsKeys.THEME_MODE] = settings.themeMode.name
        this[SettingsKeys.NORTH_TYPE] = settings.northType.name
        this[SettingsKeys.LAYER_BAGUA] = settings.layers.bagua
        this[SettingsKeys.LAYER_MOUNTAINS] = settings.layers.mountains
        this[SettingsKeys.LAYER_JIAZI] = settings.layers.jiazi
        this[SettingsKeys.LAYER_XIU] = settings.layers.xiu
        this[SettingsKeys.LAYER_DEGREE_TICKS] = settings.layers.degreeTicks
        this[SettingsKeys.SMOOTHING] = settings.smoothing
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_SMOOTHING, MAX_SMOOTHING)
            ?: CompassSettings().smoothing
        this[SettingsKeys.CALIBRATION_OFFSET] = settings.calibrationOffsetDegrees
            .takeIf { it.isFinite() }
            ?.let { normalizeOffset(it) }
            ?: CompassSettings().calibrationOffsetDegrees
        this[SettingsKeys.CHECK_UPDATE_ON_START] = settings.checkUpdateOnStart
        this[SettingsKeys.SHOW_UPDATE_DIALOG_ON_START] = settings.showUpdateDialogOnStart
    }

    /** 手动偏移归一到 -180..180（与设置页滑块范围一致） */
    private fun normalizeOffset(value: Float): Float {
        var result = value % DEGREES_FULL_CIRCLE
        if (result > DEGREES_HALF_CIRCLE) result -= DEGREES_FULL_CIRCLE
        if (result < -DEGREES_HALF_CIRCLE) result += DEGREES_FULL_CIRCLE
        return result
    }

    /** DataStore 键定义：全部为下划线小写 */
    private object SettingsKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NORTH_TYPE = stringPreferencesKey("north_type")
        val LAYER_BAGUA = booleanPreferencesKey("layer_bagua")
        val LAYER_MOUNTAINS = booleanPreferencesKey("layer_mountains")
        val LAYER_JIAZI = booleanPreferencesKey("layer_jiazi")
        val LAYER_XIU = booleanPreferencesKey("layer_xiu")
        val LAYER_DEGREE_TICKS = booleanPreferencesKey("layer_degree_ticks")
        val SMOOTHING = floatPreferencesKey("smoothing")
        val CALIBRATION_OFFSET = floatPreferencesKey("calibration_offset_degrees")
        val CHECK_UPDATE_ON_START = booleanPreferencesKey("check_update_on_start")
        val SHOW_UPDATE_DIALOG_ON_START = booleanPreferencesKey("show_update_dialog_on_start")
    }

    private companion object {
        const val MIN_SMOOTHING = 0.02f
        const val MAX_SMOOTHING = 1.0f
        const val DEGREES_FULL_CIRCLE = 360f
        const val DEGREES_HALF_CIRCLE = 180f
    }
}
