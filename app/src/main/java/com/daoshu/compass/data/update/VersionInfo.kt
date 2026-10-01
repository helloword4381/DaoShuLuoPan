package com.daoshu.compass.data.update

import kotlinx.serialization.Serializable

/**
 * 远程版本信息。
 *
 * 数据来源有两处：
 * 1. 仓库 `release/version.json`（主通道 / 备用通道直接反序列化本类）；
 * 2. GitHub Releases API（最终降级通道，由 `DefaultUpdateRepository` 从 tag / assets 组装）。
 *
 * 所有可选字段都带默认值，保证 `version.json` 缺字段时也能解析成功。
 */
@Serializable
data class VersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String = "",
    val forceUpdate: Boolean = false,
    val minSupportedVersion: Int = 1
)
