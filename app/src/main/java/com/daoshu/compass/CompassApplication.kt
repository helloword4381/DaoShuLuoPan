package com.daoshu.compass

import android.app.Application
import com.daoshu.compass.data.install.ApkInstaller
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口：Hilt 容器挂载点，并在进程启动时创建 APK 下载完成通知渠道
 * （Android 13+ 通知为运行时权限，渠道本身可以先建好）。
 */
@HiltAndroidApp
class CompassApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ApkInstaller.ensureNotificationChannel(this)
    }
}
