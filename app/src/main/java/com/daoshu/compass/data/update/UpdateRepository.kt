package com.daoshu.compass.data.update

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 自动更新仓库。
 *
 * 实现类由 DI 绑定（`di/NetworkModule.kt` 中 `DefaultUpdateRepository -> UpdateRepository`）。
 */
interface UpdateRepository {

    /** 当前更新状态，UI 直接 collect */
    val state: StateFlow<UpdateState>

    /** 依次尝试三级通道，任一成功即返回；全部失败置 [UpdateState.Failed] */
    suspend fun checkForUpdate(): UpdateState

    /** 是否强制更新（远程 minSupportedVersion 大于本地 versionCode 时也必须强制） */
    fun isForceUpdate(info: VersionInfo): Boolean

    /** 下载 APK 到 cacheDir，返回文件；通过 [state] 上报进度 */
    suspend fun downloadApk(info: VersionInfo): File?

    /** 复位到 [UpdateState.Idle] */
    fun reset()
}
