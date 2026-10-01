package com.daoshu.compass.ui

/**
 * 纯 Kotlin 展示工具（不依赖 Android / Compose），便于 JVM 单元测试直接覆盖。
 */
object FormatUtils {

    /** 字节数转可读体积，例如 1572864 -> "1.50 MB" */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "未知"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}
