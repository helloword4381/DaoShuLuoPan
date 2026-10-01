package com.daoshu.compass.data.update

import android.content.Context
import com.daoshu.compass.BuildConfig
import com.daoshu.compass.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 自动更新仓库默认实现：三级通道降级检查 + 带进度回调的 APK 下载。
 *
 * 三级通道（顺序即降级顺序，URL 全部由 BuildConfig 拼接，不硬编码 owner/repo）：
 * 1. `https://ghfast.top/https://raw.githubusercontent.com/{owner}/{repo}/{branch}/release/version.json`
 * 2. `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/release/version.json`
 * 3. `https://api.github.com/repos/{owner}/{repo}/releases/latest`
 *
 * 降级规则：
 * - 403 / 429（限流）→ 降级下一通道
 * - 404 → 视为“暂无更新”（[UpdateState.UpToDate]）
 * - 其他非 2xx、超时 / IO 异常、JSON 解析失败、缺少 downloadUrl → 降级下一通道
 * - 三级全部失败 → [UpdateState.Failed]
 *
 * OkHttpClient 与 Json 均由 `di/NetworkModule.kt` 单例注入，本类不新建客户端、不写 @Module。
 */
@Singleton
class DefaultUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val json: Json
) : UpdateRepository {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 最近一次成功响应的通道，用于归属下载阶段的失败信息 */
    @Volatile
    private var activeChannel: UpdateChannel? = null

    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()

    override suspend fun checkForUpdate(): UpdateState = withContext(ioDispatcher) {
        checkMutex.withLock { performCheck() }
    }

    override fun isForceUpdate(info: VersionInfo): Boolean =
        info.forceUpdate || info.minSupportedVersion > BuildConfig.VERSION_CODE

    override suspend fun downloadApk(info: VersionInfo): File? = withContext(ioDispatcher) {
        downloadMutex.withLock { performDownload(info) }
    }

    override fun reset() {
        activeChannel = null
        _state.value = UpdateState.Idle
    }

    // ------------------------------------------------------------------
    // 检查更新
    // ------------------------------------------------------------------

    private fun performCheck(): UpdateState {
        if (_state.value is UpdateState.Downloading) {
            // 下载进行中不打断，避免进度状态被检查流程覆盖
            return _state.value
        }

        _state.value = UpdateState.Checking
        val localVersionName = BuildConfig.VERSION_NAME
        var lastChannel: UpdateChannel? = null
        var lastReason = "三级通道均无有效响应"

        for (channel in UpdateChannel.entries) {
            lastChannel = channel
            when (val outcome = probe(channel)) {
                is ProbeOutcome.Found -> {
                    activeChannel = channel
                    val result = if (outcome.info.versionCode > BuildConfig.VERSION_CODE) {
                        UpdateState.Available(
                            info = outcome.info,
                            channel = channel,
                            apkSizeBytes = outcome.sizeBytes
                        )
                    } else {
                        UpdateState.UpToDate(localVersionName = localVersionName, channel = channel)
                    }
                    _state.value = result
                    return result
                }

                ProbeOutcome.None -> {
                    // 404：仓库尚无发布产物，按“暂无更新”处理，不再继续降级
                    activeChannel = channel
                    val result = UpdateState.UpToDate(localVersionName = localVersionName, channel = channel)
                    _state.value = result
                    return result
                }

                is ProbeOutcome.Unavailable -> {
                    lastReason = outcome.reason
                }
            }
        }

        val failed = UpdateState.Failed(
            message = "更新检查失败（主通道→备用通道→API 依次降级后仍不可用）：$lastReason",
            channel = lastChannel
        )
        _state.value = failed
        return failed
    }

    /** 探测单个通道；不做任何降级，把判定结果交给 [performCheck] */
    private fun probe(channel: UpdateChannel): ProbeOutcome {
        val url = channelUrl(channel)
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", CACHE_CONTROL_VALUE)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    code == HTTP_NOT_FOUND -> ProbeOutcome.None

                    code == HTTP_FORBIDDEN || code == HTTP_TOO_MANY_REQUESTS ->
                        ProbeOutcome.Unavailable("通道「${channel.label}」触发限流（HTTP $code）")

                    !response.isSuccessful ->
                        ProbeOutcome.Unavailable("通道「${channel.label}」返回 HTTP $code")

                    else -> {
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) {
                            ProbeOutcome.Unavailable("通道「${channel.label}」返回空响应")
                        } else if (channel == UpdateChannel.GITHUB_API) {
                            parseGithubRelease(body)
                        } else {
                            parseVersionJson(body, channel)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // 超时 / DNS / 连接中断等网络异常 → 降级
            ProbeOutcome.Unavailable("通道「${channel.label}」网络异常：${e.message ?: e.javaClass.simpleName}")
        } catch (e: SerializationException) {
            ProbeOutcome.Unavailable("通道「${channel.label}」数据解析失败：${e.message ?: "JSON 格式错误"}")
        } catch (e: IllegalArgumentException) {
            ProbeOutcome.Unavailable("通道「${channel.label}」URL 非法：${e.message ?: url}")
        }
    }

    /** 通道 1 / 2：直接反序列化 `release/version.json` */
    private fun parseVersionJson(body: String, channel: UpdateChannel): ProbeOutcome {
        val info = json.decodeFromString(VersionInfo.serializer(), body)
        if (info.downloadUrl.isBlank()) {
            return ProbeOutcome.Unavailable("通道「${channel.label}」的 version.json 缺少 downloadUrl")
        }
        return ProbeOutcome.Found(info = info, sizeBytes = 0L)
    }

    /** 通道 3：从 GitHub Releases API 响应组装 [VersionInfo] */
    private fun parseGithubRelease(body: String): ProbeOutcome {
        val release = json.decodeFromString(GithubReleaseDto.serializer(), body)
        val asset = release.assets.firstOrNull()
        val downloadUrl = asset?.browserDownloadUrl.orEmpty()
        if (downloadUrl.isBlank()) {
            return ProbeOutcome.Unavailable("通道「${UpdateChannel.GITHUB_API.label}」的最新 Release 未提供 APK 资产")
        }
        val tag = release.tagName.orEmpty().trim()
        val info = VersionInfo(
            versionCode = inferVersionCodeFromTag(tag),
            // tag 形如 v1.2.3，去掉版本号前缀的 v 后再作为展示用版本名
            versionName = tag.removePrefix("v").removePrefix("V").ifBlank { UNKNOWN_VERSION_NAME },
            downloadUrl = downloadUrl,
            changelog = release.body.orEmpty(),
            // Releases API 不携带这两个字段，保持安全默认值
            forceUpdate = false,
            minSupportedVersion = 1
        )
        return ProbeOutcome.Found(info = info, sizeBytes = asset?.size ?: 0L)
    }

    /** 从 tag（如 `v1.0.1`）推断 versionCode；取不到时用 1000 */
    private fun inferVersionCodeFromTag(tag: String): Int {
        val normalized = tag.filter { it.isDigit() || it == '.' }.trim('.')
        if (normalized.isEmpty()) return FALLBACK_VERSION_CODE
        val parts = normalized.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return FALLBACK_VERSION_CODE

        if (parts.size == 1) {
            // 形如 tag = "1001"，直接当作 versionCode
            val single = parts[0].toIntOrNull() ?: return FALLBACK_VERSION_CODE
            return if (single > 0) single else FALLBACK_VERSION_CODE
        }

        val major = parts[0].toIntOrNull() ?: return FALLBACK_VERSION_CODE
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        // 与 app/build.gradle.kts 的 1.0.0 -> 1000 保持一致
        val code = major * 1000 + minor * 10 + patch
        return if (code > 0) code else FALLBACK_VERSION_CODE
    }

    private fun channelUrl(channel: UpdateChannel): String {
        val owner = BuildConfig.GITHUB_OWNER
        val repo = BuildConfig.GITHUB_REPO
        val branch = BuildConfig.GITHUB_BRANCH
        return when (channel) {
            UpdateChannel.GHFAST_PROXY ->
                "https://ghfast.top/https://raw.githubusercontent.com/$owner/$repo/$branch/$VERSION_JSON_PATH"

            UpdateChannel.RAW_GITHUB ->
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$VERSION_JSON_PATH"

            UpdateChannel.GITHUB_API ->
                "https://api.github.com/repos/$owner/$repo/releases/latest"
        }
    }

    // ------------------------------------------------------------------
    // 下载 APK
    // ------------------------------------------------------------------

    private fun performDownload(info: VersionInfo): File? {
        val apkDir = File(context.cacheDir, APK_DIR_NAME)
        if (!apkDir.exists() && !apkDir.mkdirs()) {
            return failDownload("无法创建下载目录：${apkDir.absolutePath}")
        }

        val fileName = "$APK_FILE_PREFIX${info.versionName}$APK_FILE_SUFFIX"
        val target = File(apkDir, fileName)
        val part = File(apkDir, fileName + PART_SUFFIX)
        part.delete()
        target.delete()

        val request = Request.Builder()
            .url(info.downloadUrl)
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use failDownload("下载失败：HTTP ${response.code}")
                }
                val body = response.body
                if (body == null) {
                    return@use failDownload("下载失败：响应内容为空")
                }

                val totalBytes = body.contentLength().takeIf { it > 0L } ?: 0L
                _state.value = UpdateState.Downloading(
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    totalBytes = totalBytes
                )

                var downloadedBytes = 0L
                var lastPercent = -1
                var lastReportedBytes = 0L
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)

                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        var read = input.read(buffer)
                        while (read >= 0) {
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val percent = if (totalBytes > 0L) {
                                    ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                // 每 1% 或每 64KB 上报一次，避免 StateFlow 抖动
                                if (percent != lastPercent ||
                                    downloadedBytes - lastReportedBytes >= PROGRESS_BYTES_STEP
                                ) {
                                    lastPercent = percent
                                    lastReportedBytes = downloadedBytes
                                    _state.value = UpdateState.Downloading(
                                        progressPercent = percent,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes
                                    )
                                }
                            }
                            read = input.read(buffer)
                        }
                        output.flush()
                    }
                }

                if (downloadedBytes <= 0L) {
                    part.delete()
                    return@use failDownload("下载失败：未收到任何数据")
                }

                if (totalBytes > 0L &&
                    abs(downloadedBytes - totalBytes) * 100L > totalBytes * SIZE_TOLERANCE_PERCENT
                ) {
                    part.delete()
                    return@use failDownload(
                        "下载失败：安装包大小校验未通过（期望 $totalBytes 字节，实际 $downloadedBytes 字节）"
                    )
                }

                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    // 兜底：重命名失败时复制，保证返回一个完整可用的 APK
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                if (!target.exists() || target.length() <= 0L) {
                    return@use failDownload("下载失败：写入缓存目录失败")
                }

                _state.value = UpdateState.Downloaded(apkFile = target)
                target
            }
        } catch (e: IOException) {
            part.delete()
            failDownload("下载失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun failDownload(message: String): File? {
        _state.value = UpdateState.Failed(message = message, channel = activeChannel)
        return null
    }

    // ------------------------------------------------------------------
    // 通道探测结果（内部使用，不外泄）
    // ------------------------------------------------------------------

    private sealed interface ProbeOutcome {
        /** 拿到可用的版本信息；sizeBytes 仅通道 3 能给出 */
        data class Found(val info: VersionInfo, val sizeBytes: Long) : ProbeOutcome

        /** 404：暂无更新 */
        data object None : ProbeOutcome

        /** 需要降级到下一通道 */
        data class Unavailable(val reason: String) : ProbeOutcome
    }

    /** GitHub Releases API 响应（只取需要的字段） */
    @Serializable
    private data class GithubReleaseDto(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("body") val body: String? = null,
        @SerialName("assets") val assets: List<GithubAssetDto> = emptyList()
    )

    @Serializable
    private data class GithubAssetDto(
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
        @SerialName("size") val size: Long = 0L
    )

    private companion object {
        const val VERSION_JSON_PATH = "release/version.json"
        const val APK_DIR_NAME = "apk"
        const val APK_FILE_PREFIX = "daoshu-compass-v"
        const val APK_FILE_SUFFIX = ".apk"
        const val PART_SUFFIX = ".part"
        const val CACHE_CONTROL_VALUE = "public, max-age=300"
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_BYTES_STEP = 64L * 1024L
        const val SIZE_TOLERANCE_PERCENT = 10L
        const val FALLBACK_VERSION_CODE = 1000
        const val UNKNOWN_VERSION_NAME = "unknown"
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
