plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// 版本与仓库信息
// ---------------------------------------------------------------------------
val appVersionCode = 1000
val appVersionName = "1.0.0"
val githubOwner = "helloword4381"
val githubRepo = "DaoShuLuoPan"
val githubBranch = "main"

// ---------------------------------------------------------------------------
// 签名配置：全部来自环境变量，代码与仓库中不出现任何密钥内容
//   ANDROID_KEYSTORE_BASE64  keystore 文件的 base64（CI 中解码到 KEYSTORE_PATH）
//   KEYSTORE_PATH            已解码的 keystore 绝对路径（可选，优先于 base64）
//   ANDROID_KEY_ALIAS        别名
//   ANDROID_KEY_PASSWORD     key 密码
//   ANDROID_STORE_PASSWORD   keystore 密码
// 环境变量缺失（本地开发）时自动回退到 debug 签名
// ---------------------------------------------------------------------------
val localKeystorePath: String? = System.getenv("KEYSTORE_PATH")
    ?: rootProject.file("release-key.jks").takeIf { it.exists() }?.absolutePath
val keyAliasEnv: String? = System.getenv("ANDROID_KEY_ALIAS")
val keyPasswordEnv: String? = System.getenv("ANDROID_KEY_PASSWORD")
val storePasswordEnv: String? = System.getenv("ANDROID_STORE_PASSWORD")
val hasReleaseSigning: Boolean = localKeystorePath != null &&
    keyAliasEnv != null && keyPasswordEnv != null && storePasswordEnv != null

android {
    namespace = "com.daoshu.compass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daoshu.compass"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("zh", "en")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(localKeystorePath!!)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
                // 兼容 Android 11–15：v1 + v2 + v3 全部写入产物，v4 关闭避免额外产物。
                // 注意 apksigner 的核对口径：`apksigner verify --verbose` 的三行输出表示
                // “按查询的 SDK 区间实际选用哪个方案完成校验”，不是“产物内包含哪些签名”。
                // 本产物实测（minSdk 30 / 签名块解析）：
                //   签名块内含 v2(0x7109871a) 与 v3(0xf05368c0)，且 META-INF 下存在
                //   MANIFEST.MF / CERT.SF / CERT.RSA（CERT.SF 中 X-Android-APK-Signed: 2, 3）。
                //   按区间查询：--min/max 23..23 -> v1=true；24..27 -> v2=true；28..35 -> v3=true。
                // 因此 CI 校验脚本按“v2 或 v3 至少一项为 true”断言，见 scripts/verify-apk.sh。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 无签名环境变量时回退 debug 签名，保证本地 assembleRelease 也能出包
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    androidResources {
        generateLocaleConfig = false
    }
}

// 供更新模块与 CI 使用的构建期常量
androidComponents {
    onVariants { variant ->
        variant.buildConfigFields.apply {
            put("GITHUB_OWNER", com.android.build.api.variant.BuildConfigField("String", "\"$githubOwner\"", "更新通道仓库所有者"))
            put("GITHUB_REPO", com.android.build.api.variant.BuildConfigField("String", "\"$githubRepo\"", "更新通道仓库名"))
            put("GITHUB_BRANCH", com.android.build.api.variant.BuildConfigField("String", "\"$githubBranch\"", "version.json 所在分支"))
            put("FILE_PROVIDER_AUTHORITY", com.android.build.api.variant.BuildConfigField("String", "\"com.daoshu.compass.fileprovider\"", "APK 安装用 FileProvider 权威值"))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // 网络（自动更新通道 + APK 下载）
    implementation(libs.okhttp)

    // 定位（FusedLocationProviderClient）
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
