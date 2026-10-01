package com.daoshu.compass.di

import android.content.Context
import com.daoshu.compass.data.location.FusedLocationRepository
import com.daoshu.compass.data.location.LocationRepository
import com.daoshu.compass.data.settings.DataStoreSettingsRepository
import com.daoshu.compass.data.settings.SettingsRepository
import com.daoshu.compass.data.update.DefaultUpdateRepository
import com.daoshu.compass.data.update.UpdateRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络与仓库绑定。
 *
 * OkHttpClient 全局单例：
 * - 磁盘缓存用于 GitHub API 响应（配合 Cache-Control，缓解 403 限流）
 * - 超时控制在移动网络下可接受的范围内，避免更新检查长时间挂起
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val HTTP_CACHE_DIR = "http_cache"
    private const val HTTP_CACHE_SIZE_BYTES = 8L * 1024 * 1024

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        // 版本号在运行时从 PackageManager 读取：BuildConfig 属于 app 模块自身的生成类，
        // 在 di 包的顶层属性/闭包中直接引用会在编译期产生“未解析引用”风险，故不走 BuildConfig。
        val versionName: String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        return OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, HTTP_CACHE_DIR), HTTP_CACHE_SIZE_BYTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                // 标识客户端，便于 GitHub / CDN 侧识别
                val request = chain.request().newBuilder()
                    .header("User-Agent", "DaoShuCompass/$versionName")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}

/**
 * 接口 -> 实现的绑定。
 * 所有实现类都在 data 层，本模块是唯一的绑定位置。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindLocationRepository(impl: FusedLocationRepository): LocationRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindUpdateRepository(impl: DefaultUpdateRepository): UpdateRepository
}
