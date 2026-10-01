package com.daoshu.compass.data.install

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.daoshu.compass.BuildConfig
import com.daoshu.compass.R
import java.io.File

/**
 * APK 安装器：FileProvider + ACTION_VIEW，兼容 Android 11–15。
 *
 * 兼容性要点：
 * - Android 8.0+（API 26）安装 APK 需要「安装未知应用」授权，跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`；
 * - Android 12+（API 31）要求 PendingIntent 显式声明可变性，本类统一使用 `FLAG_IMMUTABLE`；
 * - Android 13+（API 33）通知需要运行时权限 `POST_NOTIFICATIONS`，无权限时静默跳过通知；
 * - 通过 FileProvider（authority = `{运行时包名}.fileprovider`，与清单 `${applicationId}` 一致）暴露
 *   cacheDir 中的 APK，并附带 `FLAG_GRANT_READ_URI_PERMISSION` 让系统安装器可读。
 */
object ApkInstaller {

    /** FileProvider 权威值后缀，与清单 `${applicationId}.fileprovider` 对应（debug 后缀包名同样适用） */
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    /** 下载完成通知渠道 ID（固定值，Android 8.0+ 必须显式建渠道） */
    const val NOTIFICATION_CHANNEL_ID = "daoshu_update"

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val PENDING_INTENT_REQUEST_CODE = 2001
    private const val NOTIFICATION_ID = 2001

    /**
     * 是否已授予「安装未知应用」权限。
     * Android 8.0+ 由 PackageManager 提供查询；minSdk 30，直接调用即可。
     */
    fun canRequestPackageInstalls(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (e: SecurityException) {
        // 部分定制 ROM 在受限场景会拒绝查询，保守返回 false，由后续跳设置页引导
        false
    }

    /** 跳转「安装未知应用」设置页；不支持该页面的 ROM 回退到应用详情页 */
    fun openInstallPermissionSettings(context: Context) {
        val target = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
        if (!startSafely(context, target)) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
            startSafely(context, fallback)
        }
    }

    /**
     * 通过 FileProvider 触发系统安装。
     *
     * @return true 表示已成功调起安装器；false 表示文件不可用、无安装权限（此时已自动跳转授权页）
     *         或系统无安装器。
     */
    fun install(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return false
        }
        if (!canRequestPackageInstalls(context)) {
            // 未授权时直接引导用户去开启，避免静默失败
            openInstallPermissionSettings(context)
            return false
        }
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(context, authorityOf(context), apkFile)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startSafely(context, intent)
        } catch (e: IllegalArgumentException) {
            // apkFile 不在 file_paths.xml 白名单内（或未配置 FileProvider）
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** 下载完成通知渠道：重复调用幂等 */
    fun ensureNotificationChannel(context: Context) {
        val manager = notificationManager(context) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "应用更新",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "自动更新下载完成与安装提示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发送「下载完成」通知，点击直达安装（未授权时点击进入「安装未知应用」设置页）。
     * Android 13+ 无 POST_NOTIFICATIONS 权限时直接返回，不抛异常。
     */
    @SuppressLint("MissingPermission")
    fun notifyDownloadComplete(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return
        }
        if (!hasNotificationPermission(context)) {
            return
        }
        val manager = notificationManager(context) ?: return
        ensureNotificationChannel(context)

        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            // 小图标必须来自本应用包（系统以应用包解析资源），使用已有的单色罗盘矢量图
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("更新已下载完成")
            .setContentText("点击安装道枢罗盘新版本")
            .setContentIntent(buildContentIntent(context, apkFile))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * FileProvider 权威值。
     * 必须以运行时包名为准：debug 变体带 applicationIdSuffix = ".debug"，
     * 而 AndroidManifest 中 provider 的 authorities 是 "${applicationId}.fileprovider"。
     * BuildConfig.FILE_PROVIDER_AUTHORITY 是 release 包名的构建期常量，仅作兜底。
     */
    private fun authorityOf(context: Context): String =
        (context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX)
            .ifBlank { BuildConfig.FILE_PROVIDER_AUTHORITY }

    private fun buildContentIntent(context: Context, apkFile: File): PendingIntent {
        val intent = if (canRequestPackageInstalls(context)) {
            val apkUri: Uri = FileProvider.getUriForFile(context, authorityOf(context), apkFile)
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Android 12+ 强制要求显式声明可变性；本意图无需被系统改写，固定 IMMUTABLE
        return PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
}
