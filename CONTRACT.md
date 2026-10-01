# 道枢罗盘 · 接口契约（CONTRACT）

> **本文件是并行开发的唯一事实来源。所有成员必须严格按此签名编写代码，不得改动公共签名。**
> 如需变更契约，必须先通过消息通知 Lead，由 Lead 统一修改本文件并通知全体成员。

## 0. 工程根

- 工程根目录：`E:\DeepSeek Harness\工作区域\daoshu-compass`
- applicationId / namespace：`com.daoshu.compass`
- 包根：`app/src/main/java/com/daoshu/compass/`
- 语言：**全部代码与注释、字符串使用中文注释 + 中文 UI 文案；Kotlin 代码禁止英文注释以外的其他语言混排。**
- 代码中**不得**出现硬编码密钥、密码、keystore 内容。

## 1. 工具链版本（已固定在 `gradle/libs.versions.toml`）

AGP 8.7.3 / Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / Hilt 2.53.1 / Compose BOM 2025.01.00 /
compileSdk 35 / targetSdk 35 / minSdk 30 / Java 17 / Gradle 8.11.1

可用依赖（不得擅自添加新依赖，确需新增必须先问 Lead）：
core-ktx、appcompat、lifecycle-runtime-ktx、lifecycle-runtime-compose、lifecycle-viewmodel-compose、
activity-compose、navigation-compose、compose-bom(ui/ui-graphics/ui-tooling/ui-tooling-preview/material3/material-icons-extended)、
datastore-preferences、hilt-android + hilt-android-compiler + hilt-navigation-compose、
okhttp、kotlinx-serialization-json、kotlinx-coroutines-android、play-services-location。
**本项目不使用 Room**（文档提及但无持久化表需求，统一用 DataStore；不要引入 room）。

## 2. 文件归属（写作用域，互不重叠）

| 成员 | 负责目录/文件 |
|------|---------------|
| lead | `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、`app/proguard-rules.pro`、`gradle.properties`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/**`、`app/src/test/**`、`di/**`、`ui/settings/**`、`ui/theme/**` |
| data-dev | `app/src/main/java/com/daoshu/compass/data/sensor/**`、`data/location/**`、`data/settings/**` |
| update-dev | `app/src/main/java/com/daoshu/compass/data/update/**`、`data/install/**` |
| ui-dev | `app/src/main/java/com/daoshu/compass/ui/compass/**`、`MainActivity.kt`、`CompassApplication.kt` |
| ci-dev | `.github/workflows/**`、`scripts/**`、`.gitignore`、`release/version.json`、`keystore/README.md` |
| docs-dev | `README.md`、`docs/**` |

> 注：`MainActivity.kt` / `CompassApplication.kt` 归 ui-dev，由其完成导航、权限请求与入口装配。
| ci-dev | `.github/workflows/**`、`scripts/**`、`.gitignore`、`release/version.json`、`keystore/README.md` |
| docs-dev | `README.md`、`docs/**` |

## 3. 传感器层契约（data-dev 实现，ui-dev/lead 消费）

```kotlin
package com.daoshu.compass.data.sensor

/** 单次传感器采样结果（角度单位为度，方位角为相对手机顶部指向的磁方位角） */
data class SensorReading(
    val azimuthDegrees: Float,      // 0..360，磁北为 0
    val accuracy: Int,              // SensorManager.SENSOR_STATUS_*
    val magneticStrengthUt: Float,  // 磁场强度微特斯拉
    val tiltDegrees: Float,         // 手机俯仰角
    val timestampNanos: Long
)

/** 对外暴露的传感器状态 */
data class SensorState(
    val reading: SensorReading? = null,
    val isAvailable: Boolean = false,        // 设备是否有 ROTATION_VECTOR 或 MAGNETIC_FIELD
    val needsCalibration: Boolean = false,   // 精度低于 SENSOR_STATUS_ACCURACY_MEDIUM 时为 true
    val calibrationPercentage: Int = 0       // 0..100 校准进度（由 MagnetometerCalibrator 给出）
)

@javax.inject.Singleton
class CompassSensorManager @javax.inject.Inject constructor(
    private val sensorManager: android.hardware.SensorManager,
    @com.daoshu.compass.di.IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) {
    val state: kotlinx.coroutines.flow.StateFlow<SensorState>
    fun start()   // 幂等，注册 ROTATION_VECTOR / MAGNETIC_FIELD / ACCELEROMETER
    fun stop()    // 幂等，注销监听
    fun isRunning(): Boolean
    /** 低通滤波系数 0..1，越大越平滑 */
    fun setSmoothing(factor: Float)
}

/** 磁力计 8 字校准辅助：统计 min/max 并给出校准建议点 */
@javax.inject.Singleton
class MagnetometerCalibrator @javax.inject.Inject constructor() {
    fun onSample(x: Float, y: Float, z: Float)
    fun calibrationPercentage(): Int   // 0..100
    fun reset()
    /** 校准点集合，供 UI 提示用（x,y,z 三元组） */
    fun calibrationPoints(): List<FloatArray>
}
```

实现要求：
- 优先 `Sensor.TYPE_ROTATION_VECTOR`（+ `TYPE_GAME_ROTATION_VECTOR` 兜底），无旋转矢量时用 `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` 走 `SensorManager.getRotationMatrix` / `getOrientation`，并处理 `remapCoordinateSystem` 适配竖屏。
- 方位角归一化到 `0..360`；对连续角度做最短路径平滑，避免 359°→1° 跳变。
- 磁场强度 = sqrt(x²+y²+z²)。

## 4. 定位与磁偏角契约（data-dev 实现）

```kotlin
package com.daoshu.compass.data.location

/** 定位结果 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val provider: String,
    val timeMillis: Long
)

/** 磁偏角结果 */
data class DeclinationInfo(
    val declinationDegrees: Float,   // 东偏为正
    val fieldStrengthUt: Float,
    val inclinationDegrees: Float,
    val modelYear: Double
)

data class GeoState(
    val fix: LocationFix? = null,
    val declination: DeclinationInfo? = null,
    val permissionGranted: Boolean = false,
    val errorMessage: String? = null
)

interface LocationRepository {
    val state: kotlinx.coroutines.flow.StateFlow<GeoState>
    /** 是否已授予定位权限（不触发请求） */
    fun hasLocationPermission(): Boolean
    /** 主动请求一次定位更新；无权限时直接置 errorMessage */
    fun requestLocationUpdate()
    /** 用最近一次定位（或上次保存值）计算磁偏角 */
    fun computeDeclination(latitude: Double, longitude: Double, altitudeMeters: Double): DeclinationInfo
    fun stop()
}

/** FusedLocationProviderClient 实现，构造由 Hilt 注入 */
@javax.inject.Singleton
class FusedLocationRepository @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    @com.daoshu.compass.di.ApplicationScope private val scope: kotlinx.coroutines.CoroutineScope
) : LocationRepository
```

实现要求：
- 主路径 `FusedLocationProviderClient.lastLocation` + `requestLocationUpdates`（`LocationRequest.Builder`，`Priority.PRIORITY_BALANCED_POWER_ACCURACY`）。
- 若 Google Play 服务不可用（`GoogleApiAvailability` 判断）或抛异常，降级到 `android.location.LocationManager`（`NETWORK_PROVIDER`/`GPS_PROVIDER`）。
- 磁偏角必须使用 Android 平台自带 `android.hardware.GeomagneticField`（`getDeclination()`/`getFieldStrength()`/`getInclination()`），**不要自己写 WMM 球谐系数**；`modelYear` 可直接取当前年份。

## 5. 设置层契约（data-dev 实现，lead/ui-dev 消费）

```kotlin
package com.daoshu.compass.data.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class NorthType { MAGNETIC, TRUE }

/** 罗盘显示层开关 */
data class LayerVisibility(
    val bagua: Boolean = true,        // 八卦
    val mountains: Boolean = true,    // 二十四山
    val jiazi: Boolean = true,        // 六十甲子
    val xiu: Boolean = true,          // 二十八宿
    val degreeTicks: Boolean = true   // 360 度刻度
)

data class CompassSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val northType: NorthType = NorthType.MAGNETIC,
    val layers: LayerVisibility = LayerVisibility(),
    val smoothing: Float = 0.15f,             // 0.02..1.0
    val calibrationOffsetDegrees: Float = 0f, // 用户手动校准偏移
    val checkUpdateOnStart: Boolean = true,
    val showUpdateDialogOnStart: Boolean = true
)

interface SettingsRepository {
    val settings: kotlinx.coroutines.flow.Flow<CompassSettings>
    suspend fun update(transform: (CompassSettings) -> CompassSettings)
    suspend fun reset()
}

@javax.inject.Singleton
class DataStoreSettingsRepository @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : SettingsRepository
```

实现要求：`preferencesDataStore(name = "daoshu_compass_settings")` 扩展属性写在**本文件内**（`Context.dataStore`），键名用下划线小写；读取任何键都必须有默认值，避免首次启动崩溃。

## 6. 自动更新契约（update-dev 实现，lead/ui-dev 消费）

```kotlin
package com.daoshu.compass.data.update

@kotlinx.serialization.Serializable
data class VersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String = "",
    val forceUpdate: Boolean = false,
    val minSupportedVersion: Int = 1
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** 已是最新 */
    data class UpToDate(val localVersionName: String, val channel: UpdateChannel) : UpdateState
    /** 发现新版本（apkSizeBytes 来自通道 3 的 assets[].size，通道 1/2 无法获知时填 0） */
    data class Available(
        val info: VersionInfo,
        val channel: UpdateChannel,
        val apkSizeBytes: Long = 0L
    ) : UpdateState
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : UpdateState
    data class Downloaded(val apkFile: java.io.File) : UpdateState
    data class Failed(val message: String, val channel: UpdateChannel?) : UpdateState
}

/** 三级降级通道 */
enum class UpdateChannel(val label: String) {
    GHFAST_PROXY("主通道·加速代理"),
    RAW_GITHUB("备用通道·raw.githubusercontent"),
    GITHUB_API("最终降级·GitHub Releases API")
}

interface UpdateRepository {
    val state: kotlinx.coroutines.flow.StateFlow<UpdateState>
    /** 依次尝试三级通道，任一成功即返回；全部失败置 Failed */
    suspend fun checkForUpdate(): UpdateState
    /** 是否强制更新（远程 minSupportedVersion 大于本地 versionCode 时也必须强制） */
    fun isForceUpdate(info: VersionInfo): Boolean
    /** 下载 APK 到 cacheDir，返回文件；通过 state 上报进度 */
    suspend fun downloadApk(info: VersionInfo): java.io.File?
    fun reset()
}

@javax.inject.Singleton
class DefaultUpdateRepository @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val httpClient: okhttp3.OkHttpClient,
    @com.daoshu.compass.di.IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val json: kotlinx.serialization.json.Json
) : UpdateRepository
```

> 注：`Context` 参数必须使用 Hilt 的 `@ApplicationContext`（`android.content.Context` 只是类型，不是 Qualifier）。

通道 URL 规则（`BuildConfig.GITHUB_OWNER = "helloword4381"`，`BuildConfig.GITHUB_REPO = "DaoShuLuoPan"`，`BuildConfig.GITHUB_BRANCH = "main"`）：
1. `https://ghfast.top/https://raw.githubusercontent.com/{owner}/{repo}/{branch}/release/version.json`
2. `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/release/version.json`
3. `https://api.github.com/repos/{owner}/{repo}/releases/latest` → 从返回 JSON 组装 `VersionInfo`
   （`tag_name` → versionName，`body` → changelog，`assets[0].browser_download_url` → downloadUrl，
   `assets[0].size` → apk 体积；versionCode 从 tag 数字推断，取不到时用 `1000`）

实现要求：
- OkHttp 单例由 `di/NetworkModule.kt`（lead 提供）注入；仓库内部**不得**再 new OkHttpClient。
- 自动更新检查需要 `Cache-Control`，OkHttp 缓存目录用 `context.cacheDir/"http_cache"`。
- 403/429（限流）→ 降级下一通道；404 → 视为“暂无更新”（`UpToDate`）；超时/IO 异常 → 降级。
- 下载使用 OkHttp + 进度回调，文件名 `daoshu-compass-v{versionName}.apk`，写入 `context.cacheDir/apk/`，大小校验（与已知 size 差异 > 10% 判为失败）。
- **不要**在 `data/update` 内直接跳转安装 Intent；安装逻辑放 `data/install`。

```kotlin
package com.daoshu.compass.data.install

/** APK 安装器：FileProvider + ACTION_VIEW，兼容 Android 11–15 */
object ApkInstaller {
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    /** 已授予“安装未知应用”权限？ */
    fun canRequestPackageInstalls(context: android.content.Context): Boolean
    /** 跳转“安装未知应用”设置页（Android 8.0+） */
    fun openInstallPermissionSettings(context: android.content.Context)
    /** 通过 FileProvider 触发系统安装；FileProvider.getUriForFile + FLAG_GRANT_READ_URI_PERMISSION */
    fun install(context: android.content.Context, apkFile: java.io.File): Boolean
    /** 下载完成通知渠道（Android 13+ 需 POST_NOTIFICATIONS） */
    fun ensureNotificationChannel(context: android.content.Context)
    fun notifyDownloadComplete(context: android.content.Context, apkFile: java.io.File)
}
```

FileProvider 权威值固定为 `${applicationId}.fileprovider`，paths 资源名固定为 `@xml/file_paths`（lead 已提供，含 `cache-path` 与 `external-cache-path`）。

## 7. UI 契约（ui-dev 实现 `ui/compass`，lead 实现 `ui/settings`+`ui/theme`）

```kotlin
package com.daoshu.compass.ui.compass

import com.daoshu.compass.data.location.DeclinationInfo
import com.daoshu.compass.data.location.LocationFix
import com.daoshu.compass.data.settings.CompassSettings
import com.daoshu.compass.data.sensor.SensorReading
import com.daoshu.compass.data.update.UpdateState

/** 罗盘主界面状态（由 CompassViewModel 组装） */
data class CompassUiState(
    val settings: CompassSettings = CompassSettings(),
    val reading: SensorReading? = null,
    val northHeadingDegrees: Float = 0f,   // 已按 真北/磁北 + 校准偏移 修正后的朝向
    val magneticHeadingDegrees: Float = 0f,
    val declination: DeclinationInfo? = null,
    val fix: LocationFix? = null,
    val needsCalibration: Boolean = false,
    val calibrationPercentage: Int = 0,
    val locationPermissionGranted: Boolean = false,
    val updateState: UpdateState = UpdateState.Idle,
    val updateDialogVisible: Boolean = false
)

/** 罗盘主界面 Composable */
@androidx.compose.runtime.Composable
fun CompassScreen(
    state: CompassUiState,
    onToggleLayer: (com.daoshu.compass.ui.compass.CompassLayer) -> Unit,
    onSwitchNorth: () -> Unit,
    onResetCalibration: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onStartUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
)

/** 罗盘可切换的绘制层 */
enum class CompassLayer { BAGUA, MOUNTAINS, JIAZI, XIU, DEGREE_TICKS }

/** 罗盘表盘绘制入口（无状态、纯 Canvas 绘制） */
@androidx.compose.runtime.Composable
fun CompassDial(
    headingDegrees: Float,
    layers: com.daoshu.compass.data.settings.LayerVisibility,
    showTrueNorth: Boolean,
    declinationDegrees: Float,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
)

/** 供 lead 在 MainActivity 中使用的 Hilt ViewModel */
@dagger.hilt.android.lifecycle.HiltViewModel
class CompassViewModel @javax.inject.Inject constructor(
    private val sensorManager: com.daoshu.compass.data.sensor.CompassSensorManager,
    private val locationRepository: com.daoshu.compass.data.location.LocationRepository,
    private val settingsRepository: com.daoshu.compass.data.settings.SettingsRepository,
    private val updateRepository: com.daoshu.compass.data.update.UpdateRepository,
    // 第 5 个参数：调用 ApkInstaller 触发系统安装需要 Context；前 4 个参数顺序保持不变
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : androidx.lifecycle.ViewModel() {
    val uiState: kotlinx.coroutines.flow.StateFlow<CompassUiState>
    fun onResume()                       // 启动传感器 + 定位 + 可选检查更新
    fun onPause()                        // 停止传感器/定位，省电
    fun toggleLayer(layer: CompassLayer)
    fun switchNorthType()
    fun resetCalibration()
    fun dismissUpdateDialog()
    fun startUpdate()                    // 下载并触发安装
    fun checkUpdateManually()
    fun refreshLocation()
}
```

绘制要求（`CompassDial` 及其 `Canvas` 支持文件）：- 多层同心圆，自外向内：360° 刻度 → 二十八宿（28 等分）→ 六十甲子（60 等分）→ 二十四山（24 等分，含八卦方位色）→ 八卦（8 等分）→ 中心指针。
- 二十四山名称固定：`子 癸 丑 艮 寅 甲 卯 乙 辰 巽 巳 丙 午 丁 未 坤 申 庚 酉 辛 戌 乾 亥 壬`（自正北顺时针，每山 15°）。
- 八卦名称固定：`坎 艮 震 巽 离 坤 兑 乾`。
- 二十八宿名称固定（自正北起）：`斗 牛 女 虚 危 室 壁 奎 娄 胃 昴 毕 觜 参 井 鬼 柳 星 张 翼 轸 角 亢 氐 房 心 尾 箕`。
- 六十甲子由天干 `甲乙丙丁戊己庚辛壬癸` 与地支 `子丑寅卯辰巳午未申酉戌亥` 组合生成（60 个）。
- 盘面**旋转**表示设备转动（表盘反向旋转 heading），顶部固定指针指示当前朝向。
- 性能：`Canvas` 内的字符串/Path 必须用 `remember` 预计算，禁止在每帧 `onDraw` 里分配大对象或做字符串拼接。
- 真北模式下额外绘制磁北参考线（`declinationDegrees` 角度差）。

## 8. lead 提供的 DI 契约（所有成员按此注入，不要另建 @Module）

```kotlin
package com.daoshu.compass.di

@javax.inject.Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@javax.inject.Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@javax.inject.Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

// AppModule 提供：SensorManager、CoroutineDispatcher(带 Qualifier)、CoroutineScope(带 Qualifier)
// NetworkModule 提供：OkHttpClient（带磁盘缓存）、Json(kotlinx.serialization)
// 并在 NetworkModule 中 @Binds 绑定：
//   FusedLocationRepository -> LocationRepository
//   DataStoreSettingsRepository -> SettingsRepository
//   DefaultUpdateRepository -> UpdateRepository
```

## 9. 通用规则

1. **只写自己作用域内的文件**；发现别人文件有问题，用消息反馈给 Lead，不要直接改。
2. 所有 `data` 层类如需 Hilt 注入，构造器加 `@Inject`，不要在 `data` 包内写 `@Module`。
3. UI 文案全部中文。罗盘相关术语必须与第 7 节给出的名称表完全一致。
4. 不新增第三方依赖；不引入 Room、Coil、Retrofit、Accompanist。
5. Kotlin 编译必须零错误：注意 `@Serializable` 需要 kotlinx-serialization 插件（已配置）；`BuildConfig` 需 `buildFeatures.buildConfig = true`（已配置）。
6. 完成后自检：`grep` 自己写过的每个类名，确认被引用处与声明处完全一致（包名、参数顺序、默认值）。
