package com.daoshu.compass.data.update

import java.io.File

/**
 * 自动更新的全部状态（UI 只读消费，不允许在 UI 层修改）。
 */
sealed interface UpdateState {

    /** 尚未检查过更新 */
    data object Idle : UpdateState

    /** 正在依次探测三级通道 */
    data object Checking : UpdateState

    /** 已是最新 */
    data class UpToDate(val localVersionName: String, val channel: UpdateChannel) : UpdateState

    /**
     * 发现新版本。
     *
     * @param apkSizeBytes 安装包体积，来自最终降级通道的 `assets[].size`；
     *                     主通道 / 备用通道（version.json）无法获知时填 0。
     */
    data class Available(
        val info: VersionInfo,
        val channel: UpdateChannel,
        val apkSizeBytes: Long = 0L
    ) : UpdateState

    /** 下载中，进度由 [progressPercent] 与字节数共同表达 */
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : UpdateState

    /** 下载完成，等待 UI 调起系统安装器 */
    data class Downloaded(val apkFile: File) : UpdateState

    /** 失败；[channel] 为最后一次尝试的通道，非通道相关失败（如下载）时为 null */
    data class Failed(val message: String, val channel: UpdateChannel?) : UpdateState
}

/**
 * 三级降级通道，枚举顺序即为探测顺序。
 */
enum class UpdateChannel(val label: String) {
    GHFAST_PROXY("主通道·加速代理"),
    RAW_GITHUB("备用通道·raw.githubusercontent"),
    GITHUB_API("最终降级·GitHub Releases API")
}
