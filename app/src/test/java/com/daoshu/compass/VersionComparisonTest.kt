package com.daoshu.compass

import com.daoshu.compass.data.update.VersionInfo
import com.daoshu.compass.ui.FormatUtils.formatSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较与体积格式化的纯逻辑单元测试（不依赖 Android 框架）。
 */
class VersionComparisonTest {

    private fun newer(remote: VersionInfo, localCode: Int): Boolean = remote.versionCode > localCode

    @Test
    fun remoteHigherVersionCode_isNewer() {
        val remote = VersionInfo(
            versionCode = 1001,
            versionName = "1.0.1",
            downloadUrl = "https://example.com/a.apk"
        )
        assertTrue(newer(remote, 1000))
    }

    @Test
    fun sameVersionCode_isNotNewer() {
        val remote = VersionInfo(
            versionCode = 1000,
            versionName = "1.0.0",
            downloadUrl = "https://example.com/a.apk"
        )
        assertFalse(newer(remote, 1000))
    }

    @Test
    fun forceUpdateFlag_defaultsFalse() {
        val remote = VersionInfo(
            versionCode = 1000,
            versionName = "1.0.0",
            downloadUrl = "https://example.com/a.apk"
        )
        assertFalse(remote.forceUpdate)
        assertEquals(1, remote.minSupportedVersion)
    }

    @Test
    fun formatSize_readableUnits() {
        assertEquals("未知", formatSize(0L))
        assertEquals("512 B", formatSize(512L))
        assertEquals("2 KB", formatSize(2048L))
        assertEquals("1.50 MB", formatSize(1_572_864L))
    }
}
