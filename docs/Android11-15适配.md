# 道枢罗盘 · Android 11–15 适配

> 对应原始需求文档第 5 节。本工程 `minSdk = 30`（Android 11）、`targetSdk = 35`（Android 15）、`compileSdk = 35`，需要覆盖 Android 11–15 全版本行为差异。
> 相关文件：`app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`、`app/src/main/java/com/daoshu/compass/MainActivity.kt`、`app/src/main/java/com/daoshu/compass/CompassApplication.kt`、`app/src/main/java/com/daoshu/compass/data/install/ApkInstaller.kt`、`app/src/main/java/com/daoshu/compass/ui/theme/Theme.kt`。

---

## 一、权限总表（已在清单中声明）

`app/src/main/AndroidManifest.xml` 中实际声明的权限：

| 权限 | 级别 | 起始版本 | 本工程用途 | 声明状态 |
|------|------|----------|-----------|----------|
| `android.permission.INTERNET` | 普通 | 全部 | 更新通道请求与 APK 下载 | 已声明 |
| `android.permission.ACCESS_NETWORK_STATE` | 普通 | 全部 | 网络状态判断 | 已声明 |
| `android.permission.ACCESS_COARSE_LOCATION` | **运行时** | 全部 | 粗略定位（磁偏角） | 已声明 |
| `android.permission.ACCESS_FINE_LOCATION` | **运行时** | 全部 | 精确定位（磁偏角） | 已声明 |
| `android.permission.REQUEST_INSTALL_PACKAGES` | 普通（但受系统开关控制） | Android 8.0 (API 26)+ | 应用内自动更新安装 APK | 已声明 |
| `android.permission.POST_NOTIFICATIONS` | **运行时** | Android 13 (API 33)+ | 下载完成通知 | 已声明 |
| `android.permission.FOREGROUND_SERVICE` | 普通 | Android 9 (API 28)+ | 预留：若下载改为前台服务 | 已声明 |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | 普通 | Android 14 (API 34)+ | 预留：`dataSync` 前台服务类型 | 已声明（带 `tools:ignore="ProtectedPermissions"`） |

清单中的 `uses-feature`：

| feature | required | 说明 |
|---------|----------|------|
| `android.hardware.sensor.compass` | `true` | 无磁力计的设备不予安装（罗盘为唯一核心功能） |
| `android.hardware.sensor.accelerometer` | `false` | 加速度计缺失时仍可运行（走旋转矢量路径） |

### 1.1 运行时权限请求

| 权限组 | 请求时机 | 行为 |
|--------|----------|------|
| 定位（`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`） | `MainActivity` 启动后 / 用户点击“刷新定位” | 同时申请，系统弹窗可合并为一次；拒绝后真北模式显示未授权提示，磁北功能不受影响 |
| 通知（`POST_NOTIFICATIONS`） | Android 13+ 首次启动 | 拒绝不影响更新下载，仅“下载完成通知”不可见 |

未授权时的降级行为（不崩溃）：

| 情况 | 降级表现 |
|------|----------|
| 无定位权限 | `GeoState.permissionGranted = false`、`errorMessage` 提示；`northType = TRUE` 时磁偏角不可用，界面提示改用磁北 |
| 无通知权限 | 下载照常进行，无通知；用户需在前台等待下载完成后确认安装 |
| 无“安装未知应用”授权 | 跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 授权页，授权后重试安装 |

---

## 二、逐版本行为差异与适配

| Android 版本 | API | 变化点 | 本工程适配 |
|--------------|-----|--------|-----------|
| Android 11 | 30 | 包可见性（Package Visibility）收紧：查询其他应用/Intent 需 `<queries>` | `AndroidManifest.xml` 已声明 `<queries>`：`ACTION_VIEW` + `https`、`INSTALL_PACKAGE` + `content`；`REQUEST_INSTALL_PACKAGES` 已声明 |
| Android 11 | 30 | 一次性权限、后台定位需二次授权 | 前台使用，只申请前台定位权限；不申请 `ACCESS_BACKGROUND_LOCATION` |
| Android 12 | 31 | 精确/粗略定位权限拆分；`PendingIntent` 必须显式指定可变性 | 清单同时声明 `ACCESS_FINE_LOCATION` 与 `ACCESS_COARSE_LOCATION`；所有 `PendingIntent` 使用 `FLAG_IMMUTABLE` |
| Android 12 | 31 | 前台服务启动限制 | 当前下载不使用前台服务，仅 OkHttp + 通知 |
| Android 12 | 31 | 应用启动画面（SplashScreen）统一 | 使用 `Theme.DaoShuCompass.Splash` + `res/drawable/bg_splash.xml` 自绘启动主题，避免白屏 |
| Android 13 | 33 | 通知需运行时权限 `POST_NOTIFICATIONS` | 清单已声明；运行时在 `MainActivity` 申请；未授权时静默降级 |
| Android 13 | 33 | 后台传感器需 `BODY_SENSORS_BACKGROUND` | **本工程不申请**：罗盘仅前台使用，`onPause` 即停止传感器与定位，避免后台耗电与权限申请 |
| Android 13 | 33 | 精确闹钟、媒体权限调整 | 不涉及 |
| Android 14 | 34 | 前台服务必须声明类型；`dataSync` 类型需 `FOREGROUND_SERVICE_DATA_SYNC` | 已预留声明，但**当前实现不启动前台服务**（见 `AndroidManifest.xml` 注释） |
| Android 14 | 34 | 隐式 Intent 限制、部分权限收紧 | APK 安装使用 `ACTION_VIEW` + `content://` URI + `FLAG_GRANT_READ_URI_PERMISSION`，非隐式广播 |
| Android 15 | 35 | `targetSdk 35` 强制 edge-to-edge：系统栏透明、内容延伸到系统栏区域 | `MainActivity` 调用 `WindowCompat.setDecorFitsSystemWindows(window, false)`，Compose 侧用 `WindowInsets` / `Scaffold` 内边距避让（见第三节） |
| Android 15 | 35 | 传感器权限无新增；`SENSOR_*` 行为不变 | 无需额外适配 |
| Android 15 | 35 | 前台服务类型继续收紧 | 不使用前台服务，天然规避 |

---

## 三、Compose 与 edge-to-edge 适配

### 3.1 全版本统一 edge-to-edge

`MainActivity` 中（全版本一致，不区分 SDK 版本）：

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
```

- Android 11–14：系统栏默认非透明，调用后由应用自行避让 insets。
- Android 15（`targetSdk 35`）：系统**强制** edge-to-edge，此调用是显式声明意图。

### 3.2 insets 避让

| 场景 | 处理 |
|------|------|
| 顶部标题栏 / 底部操作区 | `Scaffold` 的 `innerPadding` 或 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` |
| 手势导航条（Android 10+） | 使用 `safeDrawing` / `navigationBars` insets，禁止用固定 `dp` 硬编码避让 |
| 状态栏图标明暗 | `ui/theme/Theme.kt` 的 `DaoShuTheme` 中通过 `WindowCompat.getInsetsController(window, view)` 设置 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`，与主题明暗同步 |
| 键盘弹出 | `AndroidManifest.xml` 中 Activity 设 `android:windowSoftInputMode="adjustResize"` |

### 3.3 旋转与配置变更

`AndroidManifest.xml` 中 `MainActivity` 声明：

```xml
android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode|density|smallestScreenSize"
```

罗盘为全屏绘制场景，避免旋转时 Activity 重建导致传感器短暂中断；`Compose` 内部状态不依赖 Activity 重建即可保持。

---

## 四、`PendingIntent` 与 APK 安装适配

| 版本 | 约束 | 适配方式 |
|------|------|----------|
| Android 11 (30) | 安装 APK 需 `REQUEST_INSTALL_PACKAGES` + 用户授权“安装未知应用” | 清单声明权限；`ApkInstaller.canRequestPackageInstalls()` 检查；未授权时 `openInstallPermissionSettings()` 跳转 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` |
| Android 12 (31)+ | 创建 `PendingIntent` 必须显式指定 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`，否则抛 `IllegalArgumentException` | 统一使用 `PendingIntent.FLAG_IMMUTABLE`（下载完成通知等场景） |
| Android 13 (33)+ | 通知权限 | 运行时申请 `POST_NOTIFICATIONS`；`ApkInstaller.ensureNotificationChannel()` 创建渠道（渠道 ID `daoshu_update`） |
| Android 14 (34)+ | 安装来源必须已获得“安装未知应用”授权才可调起安装器 | “立即更新”按钮先做授权检查，未授权先跳设置页，返回后重试 |
| 全版本 | URI 暴露限制（FileUriExposedException） | 使用 `FileProvider.getUriForFile` + `FLAG_GRANT_READ_URI_PERMISSION`，authority = `com.daoshu.compass.fileprovider` |

安装链路：

```text
UI 点击「立即更新」
   └─ UpdateRepository.downloadApk()  →  cacheDir/apk/daoshu-compass-v{versionName}.apk
        └─ ApkInstaller.install(context, apkFile)
             ├─ FileProvider.getUriForFile(context, "com.daoshu.compass.fileprovider", apkFile)
             ├─ Intent(ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
             ├─ addFlags(FLAG_GRANT_READ_URI_PERMISSION)
             └─ context.startActivity(intent)
```

---

## 五、传感器适配

| 项目 | 说明 |
|------|------|
| 版本差异 | `SensorManager` 在 Android 11–15 行为一致，无需按版本分支 |
| 采样优先级 | `TYPE_ROTATION_VECTOR` → `TYPE_GAME_ROTATION_VECTOR` → `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD`（`getRotationMatrix` / `getOrientation` + `remapCoordinateSystem`） |
| 权限 | 磁力计 / 加速度计 / 旋转矢量均为普通权限，无需运行时申请 |
| 后台访问 | Android 13+ 后台读取传感器需 `BODY_SENSORS_BACKGROUND`，**本工程不申请**：`CompassViewModel.onPause()` 会停止传感器与定位 |
| 省电 | 前台用 `SENSOR_DELAY_GAME` 级别采样；`onPause` 立即 `stop()` 注销监听 |
| 兼容性声明 | 无磁力计设备通过 `uses-feature android:required="true"` 排除；仅在 `SensorState.isAvailable` 为 `false` 时 UI 给出不可用提示 |
| 校准提示 | 精度低于 `SENSOR_STATUS_ACCURACY_MEDIUM` 时 `needsCalibration = true`，界面提示 8 字校准 |

---

## 六、构建与打包适配

| 项目 | 取值 | 说明 |
|------|------|------|
| `minSdk` | 30 | Android 11 |
| `targetSdk` | 35 | Android 15 |
| `compileSdk` | 35 | 需安装 `platforms;android-35` |
| 签名方案 | **实测产物同时含 v1 + v2 + v3**（v1：`META-INF/MANIFEST.MF`/`CERT.SF`/`CERT.RSA`，`CERT.SF` 头部 `X-Android-APK-Signed: 2, 3`；v2/v3：APK Signing Block `0x7109871a` / `0xf05368c0`）；`apksigner` 默认区间（30..35）显示 **v3 = true、v1/v2 = false** | v1/v2/v3 三行是「按查询的 SDK 区间实际选用哪个方案完成校验」的上报口径，**不是产物包含哪些签名的清单**；区间对照实测命中 v1(23..23) / v2(24..27) / v3(28..35)，四组均 exit=0。Android 11–15 的安装校验由 **v3** 覆盖；`enableV1Signing/V2/V3 = true`、`enableV4Signing = false` 的声明均有效。详见 [签名与密钥安全.md](签名与密钥安全.md) 第六节 |
| `vectorDrawables.useSupportLibrary` | `true` | 低版本矢量图兼容 |
| `resourceConfigurations` | `["zh", "en"]` | 仅保留中英文资源，减小包体 |
| Lint | `abortOnError = true`（release 不阻塞） | 见 `app/build.gradle.kts` 的 `lint { }` |

---

## 七、逐版本验收清单

### Android 11（API 30）

- [ ] 首次安装需要“安装未知应用”授权，跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 正常。
- [ ] `ACTION_VIEW` 打开 `https` 与安装器 Intent 未被包可见性拦截（`<queries>` 生效）。
- [ ] 定位权限弹窗正常，拒绝后应用不崩溃、真北提示不可用。
- [ ] 罗盘、层开关、设置持久化正常。

### Android 12（API 31）

- [ ] 定位权限弹窗出现“精确 / 粗略”选择，两种选择下应用均不崩溃。
- [ ] 下载完成通知可正常发出（无 `PendingIntent` 可变性崩溃）。
- [ ] 启动画面（Splash）无白屏闪烁。

### Android 13（API 33）

- [ ] 首次启动弹出通知权限申请；拒绝后下载仍可完成。
- [ ] 下载完成通知可见（渠道 ID `daoshu_update`）。
- [ ] 后台切换（`onPause`）后传感器与定位停止，无后台耗电异常。

### Android 14（API 34）

- [ ] 未启动前台服务，无“前台服务类型未声明”崩溃或 ANR。
- [ ] “安装未知应用”未授权时，点击更新先跳授权页，返回后可继续。
- [ ] `content://` URI 安装 APK 成功，无 `FileUriExposedException`。

### Android 15（API 35）

- [ ] edge-to-edge 生效：内容不被状态栏/导航栏遮挡，标题栏与底部控件留出 insets。
- [ ] 状态栏/导航栏图标明暗与主题一致（浅色主题深色图标）。
- [ ] 三键导航与手势导航切换后布局正确。
- [ ] 全部权限流程与 Android 13/14 行为一致。

### 全版本通用

- [x] `compileSdk 35` 构建通过（实测 `:app:compileDebugKotlin` / `:app:assembleDebug` / `:app:assembleRelease` 均 BUILD SUCCESSFUL）。
- [x] `apksigner verify --verbose` 输出 `Verifies` 且 **exit code = 0**；默认区间 v3 = true，产物内含 v1+v2+v3（区间对照实测，见 [签名与密钥安全.md](签名与密钥安全.md) 6.1）。
- [x] `scripts/verify-apk.sh` 断言「v2 或 v3 至少一项为 true」，本工程产物实测通过（纯 v1 包会被正确拦截）。
- [ ] 三级更新通道至少通道 1/2 之一可用（当前实测三条 URL 均 404，因仓库为空仓库；首次发布后复测，见 [验证报告.md](验证报告.md) 第四节）。
- [ ] 真机/模拟器冒烟：冷启动、权限弹窗、罗盘旋转、更新对话框（**本机受环境所限无法执行**：`HypervisorPresent=False`，Android Emulator 报 `x86_64 emulation currently requires hardware acceleration! ... hypervisor driver is not installed on this machine`，需在有 WHPX/AEHD 的机器或 CI 上补做）。
