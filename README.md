# 道枢罗盘 · DaoShuLuoPan

> 一款纯 Android（Kotlin + Jetpack Compose）的传统罗盘应用：多层同心圆盘面（360° 刻度 / 二十八宿 / 六十甲子 / 二十四山 / 八卦 / 中心指针）、传感器融合方位解算、GPS 磁偏角真北修正、层开关与主题设置，以及**三级降级通道**的应用内自动更新（GitHub Releases）。

- 仓库地址：<https://github.com/helloword4381/DaoShuLuoPan>
- 应用 ID（applicationId / namespace）：`com.daoshu.compass`
- 版本：`versionName = 1.0.0`，`versionCode = 1000`
- 界面语言：全中文

---

## 一、核心能力

| 能力 | 实现要点 | 主要代码位置 |
|------|----------|--------------|
| 多层罗盘盘面 | Compose `Canvas` 纯绘制，6 层同心结构，表盘反向旋转、顶部固定指针 | `app/src/main/java/com/daoshu/compass/ui/compass/CompassDial.kt` |
| 方位角解算 | `Sensor.TYPE_ROTATION_VECTOR` 优先，缺失时 `ACCELEROMETER + MAGNETIC_FIELD` 走 `getRotationMatrix` / `getOrientation` | `app/src/main/java/com/daoshu/compass/data/sensor/CompassSensorManager.kt` |
| 磁力计校准 | 8 字校准采样统计 min/max，输出 0–100 校准进度与提示点 | `app/src/main/java/com/daoshu/compass/data/sensor/MagnetometerCalibrator.kt` |
| 定位与磁偏角 | `FusedLocationProviderClient` 优先，降级 `android.location.LocationManager`；磁偏角用平台 `android.hardware.GeomagneticField` | `app/src/main/java/com/daoshu/compass/data/location/FusedLocationRepository.kt` |
| 设置持久化 | `DataStore` Preferences（`daoshu_compass_settings`），全部键带默认值 | `app/src/main/java/com/daoshu/compass/data/settings/DataStoreSettingsRepository.kt` |
| 自动更新 | 三级降级检查 → OkHttp 带进度下载 → FileProvider 触发系统安装 | `app/src/main/java/com/daoshu/compass/data/update/DefaultUpdateRepository.kt`、`app/src/main/java/com/daoshu/compass/data/install/ApkInstaller.kt` |
| 依赖注入 | Hilt，`di/` 统一提供 `OkHttpClient` / `Json` 与仓库绑定 | `app/src/main/java/com/daoshu/compass/di/NetworkModule.kt` |
| CI/CD | GitHub Actions 构建签名 APK、推送 `release/version.json`、创建 Release | `.github/workflows/build-apk.yml` |

---

## 二、技术栈与版本（唯一事实来源：`gradle/libs.versions.toml`）

| 项目 | 版本 / 取值 | 配置文件 |
|------|-------------|----------|
| Android Gradle Plugin | 8.7.3 | `gradle/libs.versions.toml` |
| Kotlin | 2.1.0 | `gradle/libs.versions.toml` |
| KSP | 2.1.0-1.0.29 | `gradle/libs.versions.toml` |
| Hilt | 2.53.1（`hilt-navigation-compose` 1.2.0） | `gradle/libs.versions.toml` |
| Compose BOM | 2025.01.00（Material 3 + material-icons-extended） | `gradle/libs.versions.toml` |
| Navigation Compose | 2.8.5 | `gradle/libs.versions.toml` |
| DataStore Preferences | 1.1.1 | `gradle/libs.versions.toml` |
| OkHttp | 4.12.0 | `gradle/libs.versions.toml` |
| kotlinx-serialization-json | 1.7.3 | `gradle/libs.versions.toml` |
| kotlinx-coroutines-android | 1.9.0 | `gradle/libs.versions.toml` |
| play-services-location | 21.3.0 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk / minSdk | 35 / 35 / 30（Android 15 / 11） | `app/build.gradle.kts` |
| Java 版本 | 17（`sourceCompatibility` / `jvmTarget`） | `app/build.gradle.kts` |
| Gradle | 8.11.1（**Gradle Wrapper 已提交入库**：`gradlew`、`gradlew.bat`、`gradle/wrapper/**`，`distributionUrl` 指向 `gradle-8.11.1-bin.zip`） | `gradle/wrapper/gradle-wrapper.properties` |
| 单元测试 | JUnit 4.13.2；Espresso 3.6.1（实测 4/4 通过，见第 7.5 节） | `gradle/libs.versions.toml` |

> **本项目不使用 Room**。原始需求中提到 Room，但本工程无持久化表需求，全部配置统一走 DataStore；`app/build.gradle.kts` 中也没有 room 依赖，请勿引入。

---

## 三、工程结构

```text
daoshu-compass/
├── CONTRACT.md                     # 并行开发接口契约（公共签名冻结）
├── README.md                       # 本文件
├── build.gradle.kts                # 根构建脚本（仅声明插件版本）
├── settings.gradle.kts             # 仓库源 + 模块声明（rootProject.name = "道枢罗盘"）
├── gradle.properties               # JVM 参数、AndroidX、非传递 R 类
├── gradlew                         # Gradle Wrapper 启动脚本（已提交，8.11.1）
├── gradlew.bat                     # Windows 版 Wrapper 启动脚本
├── gradle/
│   ├── libs.versions.toml          # 版本目录（依赖/插件唯一来源）
│   └── wrapper/                    # gradle-wrapper.jar + gradle-wrapper.properties（已提交）
├── docs/                           # 交付文档（本目录）
│   ├── 发布流程.md
│   ├── 自动更新通道.md
│   ├── Android11-15适配.md
│   ├── 签名与密钥安全.md
│   └── 验证报告.md
├── .github/workflows/
│   ├── build-apk.yml               # CI：构建 + 签名 + 回推 version.json + Release
│   └── version-check.yml           # PR 校验：version.json 与 build.gradle.kts 版本一致
├── scripts/                        # 密钥生成/编码、APK 签名校验、打 tag 脚本
│   ├── generate-keystore.sh
│   ├── encode-keystore.sh
│   ├── verify-apk.sh
│   └── tag-release.sh
├── release/version.json            # 更新检查读取的版本描述文件
├── keystore/README.md              # 密钥目录说明（目录内不得提交任何密钥文件）
└── app/
    ├── build.gradle.kts            # 模块配置、签名、BuildConfig 字段、依赖
    ├── proguard-rules.pro          # 混淆规则
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml # 权限、FileProvider、Activity
        │   ├── java/com/daoshu/compass/
        │   │   ├── MainActivity.kt              # 入口 Activity（Compose + 导航 + 权限）
        │   │   ├── CompassApplication.kt        # @HiltAndroidApp，启动创建通知渠道
        │   │   ├── di/                          # Hilt 模块与 Qualifier
        │   │   │   ├── Qualifiers.kt
        │   │   │   ├── AppModule.kt
        │   │   │   └── NetworkModule.kt
        │   │   ├── data/
        │   │   │   ├── sensor/                  # 传感器采集与融合
        │   │   │   ├── location/                # 定位与磁偏角
        │   │   │   ├── settings/                # DataStore 设置
        │   │   │   ├── update/                  # 三级降级更新检查与下载
        │   │   │   └── install/                 # FileProvider APK 安装
        │   │   └── ui/
        │   │       ├── compass/                 # 罗盘绘制、ViewModel、更新对话框
        │   │       ├── settings/                # 设置页
        │   │       └── theme/                   # 主题与盘面色板
        │   └── res/                             # 字符串、主题、图标、FileProvider 路径
        └── test/java/com/daoshu/compass/
            └── VersionComparisonTest.kt         # 版本比较逻辑单元测试
```

---

## 四、模块说明

### 4.1 `data/sensor` — 传感器采集与融合

- `CompassSensorManager`：暴露 `StateFlow<SensorState>`，`start()` / `stop()` 幂等，`setSmoothing(factor)` 调整低通系数（0.02–1.0）。
- 采样优先级：`TYPE_ROTATION_VECTOR` → `TYPE_GAME_ROTATION_VECTOR` → `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD`（配合 `remapCoordinateSystem` 适配竖屏）。
- 方位角归一化到 `0..360`，并对连续角度做**最短路径**平滑，避免 359° → 1° 跳变；磁场强度 = `sqrt(x² + y² + z²)`。
- `MagnetometerCalibrator`：统计三轴 min/max，输出 0–100 校准进度与校准点，用于“8 字校准”提示。

### 4.2 `data/location` — 定位与磁偏角

- `FusedLocationRepository`（实现 `LocationRepository`）：主路径 `FusedLocationProviderClient`（`LocationRequest.Builder` + `Priority.PRIORITY_BALANCED_POWER_ACCURACY`）。
- Google Play 服务不可用（`GoogleApiAvailability` 判定）或抛异常时，降级到 `android.location.LocationManager`（`NETWORK_PROVIDER` / `GPS_PROVIDER`）。
- 磁偏角必须使用平台自带的 `android.hardware.GeomagneticField`（`getDeclination()` / `getFieldStrength()` / `getInclination()`），**不自行实现 WMM 球谐系数**。

### 4.3 `data/settings` — 设置持久化

- `DataStoreSettingsRepository`（实现 `SettingsRepository`）：`preferencesDataStore(name = "daoshu_compass_settings")`。
- 设置项：`themeMode`（SYSTEM / LIGHT / DARK）、`northType`（MAGNETIC / TRUE）、`layers`（八卦 / 二十四山 / 六十甲子 / 二十八宿 / 360° 刻度）、`smoothing`、`calibrationOffsetDegrees`、`checkUpdateOnStart`、`showUpdateDialogOnStart`。
- 读取任何键都有默认值，保证首次启动不崩溃。

### 4.4 `data/update` + `data/install` — 自动更新与安装

- `DefaultUpdateRepository`（实现 `UpdateRepository`）：依次尝试三级通道，任一成功即返回；`state: StateFlow<UpdateState>` 供 UI 观察。
- 下载使用注入的 `OkHttpClient`（含进度回调），文件写入 `context.cacheDir/apk/`，先写 `.part` 再改名，文件名 `daoshu-compass-v{versionName}.apk`。
- `ApkInstaller`：`FileProvider.getUriForFile` + `Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`，并处理“安装未知应用”授权与下载完成通知。
- 详见 [docs/自动更新通道.md](docs/自动更新通道.md)。

### 4.5 `ui` — 界面层

- `ui/compass`：`CompassScreen`（主界面）、`CompassDial`（无状态纯 Canvas 表盘）、`CompassViewModel`（`@HiltViewModel`，聚合四个仓库为 `StateFlow<CompassUiState>`）、`UpdateDialog`（更新对话框）、`DialData`（名称表）。
- `ui/settings`：`SettingsScreen`（主题、北向基准、层开关、平滑、校准、更新检查、关于）。
- `ui/theme`：`DaoShuTheme(themeMode)`，墨黑 + 罗盘金配色，另提供盘面专用 `DialColors`。
- 路由：`compass`（默认）与 `settings`（`androidx.navigation:navigation-compose`）。

### 4.6 `di` — 依赖注入

- `Qualifiers.kt`：`@IoDispatcher`、`@DefaultDispatcher`、`@ApplicationScope`。
- `AppModule.kt`：`SensorManager`、协程调度器与作用域。
- `NetworkModule.kt`：`OkHttpClient`（磁盘缓存 `cacheDir/http_cache`，8 MiB，超时与 UA 头）、`Json`；`RepositoryModule` 中 `@Binds` 三个仓库实现。

---

## 五、罗盘表盘与名称表

### 5.1 层结构（自外向内）

| 顺序 | 层 | 枚举 `CompassLayer` | 分度 |
|------|----|---------------------|------|
| 1 | 360° 刻度 | `DEGREE_TICKS` | 360 等分 |
| 2 | 二十八宿 | `XIU` | 28 等分 |
| 3 | 六十甲子 | `JIAZI` | 60 等分 |
| 4 | 二十四山 | `MOUNTAINS` | 24 等分，每山 15° |
| 5 | 八卦 | `BAGUA` | 8 等分 |
| 6 | 中心指针 | —（常驻） | — |

盘面**反向旋转** `heading`，顶部固定指针指示当前朝向；真北模式下额外绘制磁北参考线（角度差为磁偏角 `declinationDegrees`）。

### 5.2 名称表（与 `CONTRACT.md` 第 7 节完全一致）

- **二十四山**（自正北顺时针，每山 15°）：
  `子 癸 丑 艮 寅 甲 卯 乙 辰 巽 巳 丙 午 丁 未 坤 申 庚 酉 辛 戌 乾 亥 壬`
- **八卦**：`坎 艮 震 巽 离 坤 兑 乾`
- **二十八宿**（自正北起）：`斗 牛 女 虚 危 室 壁 奎 娄 胃 昴 毕 觜 参 井 鬼 柳 星 张 翼 轸 角 亢 氐 房 心 尾 箕`
- **六十甲子**：由天干 `甲乙丙丁戊己庚辛壬癸` × 地支 `子丑寅卯辰巳午未申酉戌亥` 组合生成 60 个。

> 名称表集中在 `app/src/main/java/com/daoshu/compass/ui/compass/DialData.kt`，并在 `remember` 中预计算；`Canvas` 的 `onDraw` 中不得做字符串拼接或大对象分配。

---

## 六、环境要求

| 组件 | 要求 | 说明 |
|------|------|------|
| JDK | 17（Temurin 17 推荐） | `app/build.gradle.kts` 使用 Java 17 目标字节码 |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0`、`platform-tools` | compileSdk 35 |
| Gradle | 通过 `gradlew` 包装器（8.11.1） | 无需本地安装 Gradle |
| 操作系统 | Windows / macOS / Linux | 命令示例给出 Windows（`gradlew.bat`）与 Unix（`./gradlew`）两种 |

本机（Windows）需配置 SDK 位置，二者任选其一：

```ini
# daoshu-compass/local.properties（已被 .gitignore 排除，不要提交）
sdk.dir=E\:\\DeepSeek Harness\\工作区域\\tools\\android-sdk
```

```powershell
# 或设置环境变量
$env:ANDROID_HOME = "E:\DeepSeek Harness\工作区域\tools\android-sdk"
```

---

## 七、构建与运行

### 7.0 Gradle Wrapper 与本地构建建议（Windows）

Gradle Wrapper **已提交入库**（`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`，版本 8.11.1），克隆后**无需本地安装 Gradle**，直接使用 `.\gradlew.bat` / `./gradlew` 即可。

```powershell
.\gradlew.bat --version      # 应输出 Gradle 8.11.1
```

> **Windows 中文路径建议**：若工程路径包含中文（例如 `E:\DeepSeek Harness\工作区域\daoshu-compass`），Windows 中文环境下 JVM 的 `sun.jnu.encoding=GBK`，Gradle **测试工作进程**读取类路径时可能解码错乱，导致 `:app:testDebugUnitTest` 报 `java.lang.ClassNotFoundException: com.daoshu.compass.VersionComparisonTest`（编译与打包不受影响）。用 `subst` 映射一个 ASCII 盘符即可规避：

```powershell
subst Z: "E:\DeepSeek Harness\工作区域\daoshu-compass"
cd Z:\
.\gradlew.bat :app:testDebugUnitTest      # 实测 4/4 通过
subst Z: /D                               # 用完解除映射
```

CI（ubuntu-latest）路径为 ASCII，不受此问题影响。详见 [docs/验证报告.md](docs/验证报告.md) 第五节 R-9。

### 7.1 调试包

```powershell
# Windows
cd daoshu-compass
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

```bash
# macOS / Linux
cd daoshu-compass
./gradlew assembleDebug
./gradlew installDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（debug 变体 applicationId 后缀为 `.debug`，即 `com.daoshu.compass.debug`）。

### 7.2 单元测试与静态检查

```powershell
.\gradlew.bat testDebugUnitTest      # JUnit 单元测试（中文路径下请先按 7.0 节 subst 映射盘符）
.\gradlew.bat lintDebug              # Lint（abortOnError = true）
.\gradlew.bat assembleRelease        # 发布包（本地无签名时回退 debug 签名）
```

实测（本机 Temurin 17.0.20.1 + Gradle 8.11.1）：`:app:testDebugUnitTest` 在中文路径下会因 `sun.jnu.encoding=GBK` 报 `ClassNotFoundException`，`subst` 到 ASCII 盘符后 **4/4 通过**。详见第 7.5 节与 [docs/验证报告.md](docs/验证报告.md) R-9。

### 7.3 Release 包与签名

`app/build.gradle.kts` 中的 `signingConfigs` **只从环境变量读取**，仓库内不保存任何密钥内容；环境变量缺失时 release 变体自动回退到 debug 签名，保证本地也能出包。

| 环境变量 | 用途 |
|----------|------|
| `KEYSTORE_PATH` | 已解码的 keystore 绝对路径（优先于 base64） |
| `ANDROID_KEYSTORE_BASE64` | keystore 文件 base64（CI 中解码为 `$RUNNER_TEMP/release-key.jks`） |
| `ANDROID_KEY_ALIAS` | 密钥别名（示例：`daoshu-compass`） |
| `ANDROID_KEY_PASSWORD` | key 密码 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |

```powershell
# 本地带签名构建（Windows PowerShell 示例）
$env:KEYSTORE_PATH = "E:\keys\release-key.jks"
$env:ANDROID_KEY_ALIAS = "daoshu-compass"
$env:ANDROID_KEY_PASSWORD = "<key 密码>"
$env:ANDROID_STORE_PASSWORD = "<store 密码>"
.\gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。签名验证：

```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

**本工程实际签名方案（实测，同一份 `app-release.apk` 只改查询区间）**：

```text
--min-sdk-version 23 --max-sdk-version 23  → v1=true,  v2=false, v3=false   (exit=0)
--min-sdk-version 24 --max-sdk-version 27  → v1=false, v2=true,  v3=false   (exit=0)
--min-sdk-version 28 --max-sdk-version 29  → v1=false, v2=false, v3=true    (exit=0)
--min-sdk-version 30 --max-sdk-version 35  → v1=false, v2=false, v3=true    (exit=0)  ← 默认区间
```

- `apksigner verify --verbose` 的 v1/v2/v3 三行**不是「产物包含哪些签名」的清单，而是「按查询的 SDK 区间实际选用哪个方案完成校验」的结果**；
- 产物内**确实同时含 v1 + v2 + v3 三种签名**：v1 为 `META-INF/MANIFEST.MF` + `CERT.SF`（头部含 `X-Android-APK-Signed: 2, 3`）+ `CERT.RSA`；v2/v3 为 APK Signing Block 中的 `0x7109871a` / `0xf05368c0`；
- 本工程 **minSdk 30 / targetSdk 35**，默认区间只把 **v3** 记为 `true`，**v1/v2 显示 `false` 属正常上报口径，并非缺失**；Android 11–15 由 **v3** 覆盖。

判定标准：**`apksigner` exit code = 0 且输出含 `Verifies`，并且 v2 或 v3 至少一项为 `true`**。详见 [docs/签名与密钥安全.md](docs/签名与密钥安全.md) 第六节（含区间对照与签名块解析原文）。

> ⚠️ 本地产物可能由本地测试密钥签名（例如仓库根目录的 `release-key.jks`，已被 `.gitignore` 排除）：正式发布必须使用 GitHub Secrets 中的密钥，签名者 DN 以 Secrets 密钥为准。

### 7.4 构建期常量（BuildConfig）

`app/build.gradle.kts` 通过 `androidComponents.onVariants` 注入，更新模块与安装模块直接读取，代码中无需硬编码：

| 字段 | 值 |
|------|----|
| `BuildConfig.GITHUB_OWNER` | `helloword4381` |
| `BuildConfig.GITHUB_REPO` | `DaoShuLuoPan` |
| `BuildConfig.GITHUB_BRANCH` | `main` |
| `BuildConfig.FILE_PROVIDER_AUTHORITY` | `com.daoshu.compass.fileprovider` |

### 7.5 实测构建状态（本机真实执行记录）

环境：Temurin JDK **17.0.20.1**、Gradle **8.11.1**（Wrapper）、Android SDK `platforms;android-35` + `build-tools;35.0.0` + `platform-tools`。

| 步骤 | 命令 | 结果 |
|------|------|------|
| 编译（Kotlin / Compose / Hilt / KSP） | `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| 单元测试 | `:app:testDebugUnitTest` | ✅ 4/4 通过（中文路径需按 7.0 节 subst） |
| Debug APK | `:app:assembleDebug` | ✅ `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK（R8 + 资源压缩 + 签名） | `:app:assembleRelease` | ✅ `app/build/outputs/apk/release/app-release.apk` ≈ 3.14 MB |
| 签名校验 | `apksigner verify --verbose --print-certs` | ✅ exit=0、`Verifies`、默认区间 v3 = true（产物内含 v1+v2+v3，见 7.3）、1 signer、RSA 2048 |
| 签名校验（区间对照） | `--min/max 23..23 / 24..27 / 28..29 / 30..35` | ✅ 分别命中 v1 / v2 / v3 / v3，四组 exit=0 |

> 明细（含命令、字节数、测试用例名、签名块解析与两条已知告警）见 [docs/验证报告.md](docs/验证报告.md) 第五、六节。
>
> **未完成的验证**：Lint、真实 GitHub Actions 运行、R8 混淆包运行时行为、首次发布后的通道复测均未执行；**设备级冒烟（模拟器/真机）在本机受环境所限无法执行**（`HypervisorPresent=False`，无 AEHD/WHPX，Android Emulator 报 `x86_64 emulation currently requires hardware acceleration!`），需在有硬件加速的机器或 CI 模拟器上补做。

---

## 八、自动更新（三级降级）

按固定顺序尝试，任一通道成功即停止，全部失败置 `UpdateState.Failed`：

| 级别 | 通道（`UpdateChannel`） | URL |
|------|------------------------|-----|
| 1 | `GHFAST_PROXY`（主通道·加速代理） | `https://ghfast.top/https://raw.githubusercontent.com/helloword4381/DaoShuLuoPan/main/release/version.json` |
| 2 | `RAW_GITHUB`（备用通道·raw.githubusercontent） | `https://raw.githubusercontent.com/helloword4381/DaoShuLuoPan/main/release/version.json` |
| 3 | `GITHUB_API`（最终降级·GitHub Releases API） | `https://api.github.com/repos/helloword4381/DaoShuLuoPan/releases/latest` |

- 403 / 429（限流）→ 降级下一通道；404 → 视为“暂无更新”（`UpToDate`）；超时 / IO 异常 → 降级。
- 通道 3 从 Release JSON 组装 `VersionInfo`（`tag_name` → `versionName`、`body` → `changelog`、`assets[0].browser_download_url` → `downloadUrl`、`assets[0].size` → APK 体积）。
- 下载完成后由 `ApkInstaller` 通过 FileProvider 调起系统安装器；未授予“安装未知应用”时先跳转授权页。
- 字段表、限流处理与排障见 [docs/自动更新通道.md](docs/自动更新通道.md)。

---

## 九、发布与适配文档索引

| 主题 | 文档 |
|------|------|
| 端到端发布流程（首次配置 / 日常发布 / 版本策略 / 回滚 / 验证清单） | [docs/发布流程.md](docs/发布流程.md) |
| 自动更新三级降级通道、version.json 字段、限流与安装授权 | [docs/自动更新通道.md](docs/自动更新通道.md) |
| Android 11–15 权限与行为适配表 | [docs/Android11-15适配.md](docs/Android11-15适配.md) |
| 签名密钥生成、base64、GitHub Secrets、CI 解码与校验 | [docs/签名与密钥安全.md](docs/签名与密钥安全.md) |
| 本工程实际验证记录（静态校验 / 真实构建与测试结果 / 未验证项与风险） | [docs/验证报告.md](docs/验证报告.md) |

日常发布最小动作：

```bash
# 1. 修改 app/build.gradle.kts 的 versionCode / versionName
# 2. 同步 release/version.json
git add . && git commit -m "release: v1.0.1" && git push
# 3. 打 tag 触发 Release（脚本见 scripts/tag-release.sh）
git tag v1.0.1 && git push origin v1.0.1
```

---

## 十、常见问题（FAQ）

| 现象 | 原因与处理 |
|------|-----------|
| 罗盘指针乱转 / 提示需要校准 | 磁力计受铁磁干扰或未校准。按提示在空中画“8”字完成校准；也可在设置页微调“手动偏移”。 |
| 方位角在正北附近来回跳 | 平滑系数过小。设置页把“传感器平滑系数”调大（越平滑、响应越慢）。 |
| 真北与磁北差异很大 | 磁偏角随经纬度变化，属正常现象；确认已授予定位权限且定位成功（真北 = 磁北 + 磁偏角）。 |
| 点击“立即更新”无反应或提示未知来源 | 需要“安装未知应用”授权：Android 8.0+ 会跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 设置页，允许后重试。Android 13+ 还需通知权限用于下载完成提示。 |
| 检查更新一直失败 | 依次排查：网络是否可达 `raw.githubusercontent.com`、GitHub API 是否限流（403/429 会自动降级）、`release/version.json` 是否已推送到 `main`。见 [docs/自动更新通道.md](docs/自动更新通道.md)。 |
| 本地 `assembleRelease` 报签名相关错误 | 未配置签名环境变量时会自动回退 debug 签名；若已配置但路径/密码错误，请核对 `KEYSTORE_PATH` 与三个密码变量。 |
| 构建报 `SDK location not found` | 缺少 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 环境变量（compileSdk 35 需要 `platforms;android-35`）。 |
| 报 `gradlew` 不存在 / `不是内部或外部命令` | Wrapper 已入库，若仍报错请确认克隆完整（`gradlew`、`gradle/wrapper/**` 均在仓库内），或在仓库根目录执行。 |
| `:app:testDebugUnitTest` 报 `ClassNotFoundException: com.daoshu.compass.VersionComparisonTest` | Windows **中文路径**下 `sun.jnu.encoding=GBK` 导致测试工作进程类路径解码错乱（编译/打包不受影响）。按第 7.0 节 `subst Z:` 映射 ASCII 盘符后运行即可，实测 4/4 通过。 |
| 构建日志出现 `aapt2.exe: Failed to stat file '...\android-35\android.jar': No such file or directory` | **已知无害告警**：该文件实际存在（`Test-Path` 为 True），是中文路径在 aapt2 日志中的编码显示问题；构建成功、产物正常，可忽略。 |
| 为什么没有 Room | 本工程无持久化表需求，统一使用 DataStore；见 [CONTRACT.md](CONTRACT.md) 第 1 节。 |

---

## 十一、说明

- 并行开发期间，所有公共签名以 [CONTRACT.md](CONTRACT.md) 为准；修改契约需先同步全体成员。
- 仓库内**不允许**出现密钥、密码、keystore 内容；发布密钥仅保存在本地与 GitHub Secrets。
- 本应用涉及磁偏角与方位参考，仅用于传统罗盘辅助与学习，不用于精密测绘或导航定位。
