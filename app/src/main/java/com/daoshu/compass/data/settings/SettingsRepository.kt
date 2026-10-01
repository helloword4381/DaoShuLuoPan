package com.daoshu.compass.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * 设置仓库。
 *
 * 实现由 `DataStoreSettingsRepository` 提供，Hilt 绑定见 `di/NetworkModule.kt`。
 */
interface SettingsRepository {

    /** 设置流；首次订阅即给出带默认值的完整设置 */
    val settings: Flow<CompassSettings>

    /** 以当前设置为入参做原子更新 */
    suspend fun update(transform: (CompassSettings) -> CompassSettings)

    /** 恢复默认设置 */
    suspend fun reset()
}
