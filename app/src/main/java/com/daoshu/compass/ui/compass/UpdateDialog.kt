package com.daoshu.compass.ui.compass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daoshu.compass.data.update.UpdateState
import com.daoshu.compass.data.update.VersionInfo
import com.daoshu.compass.ui.FormatUtils.formatSize

/**
 * 自动更新对话框。
 *
 * - 发现新版本：展示版本号、更新说明、APK 体积，按钮为「立即更新 / 稍后」
 * - forceUpdate = true 时不提供「稍后」，仅保留强制更新
 * - 下载中：显示进度条，不可取消
 * - 下载完成：提示即将调起系统安装器
 * - 失败：提示原因并提供重试
 */
@Composable
fun UpdateDialog(
    info: VersionInfo,
    state: UpdateState,
    forceUpdate: Boolean,
    currentVersionName: String,
    apkSizeBytes: Long = 0L,
    onDismiss: () -> Unit,
    onStartUpdate: () -> Unit
) {
    val downloading = state is UpdateState.Downloading
    val downloaded = state is UpdateState.Downloaded
    val failure = state as? UpdateState.Failed

    AlertDialog(
        onDismissRequest = { if (!downloading && !forceUpdate) onDismiss() },
        title = {
            Text(
                text = if (forceUpdate) "发现重要更新（需强制升级）" else "发现新版本",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoLine("当前版本", currentVersionName)
                InfoLine("最新版本", "${info.versionName}（versionCode ${info.versionCode}）")
                InfoLine("安装包大小", formatSize(apkSizeBytes))
                if (forceUpdate) {
                    Text(
                        text = "该版本为必需升级，低于 ${info.minSupportedVersion} 的版本将无法继续使用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    text = "更新说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = info.changelog.ifBlank { "本次更新未提供说明。" },
                    style = MaterialTheme.typography.bodyMedium
                )

                when (state) {
                    is UpdateState.Downloading -> {
                        Text(
                            text = "正在下载… ${state.progressPercent}%",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${formatSize(state.downloadedBytes)} / ${formatSize(state.totalBytes)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    is UpdateState.Downloaded -> {
                        Text(
                            text = "下载完成，即将调起系统安装器。若提示「未知来源」，请先允许本应用安装应用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    else -> Unit
                }

                if (failure != null) {
                    Text(
                        text = "更新失败：${failure.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onStartUpdate,
                enabled = !downloading && !downloaded
            ) {
                Text(if (failure != null) "重试" else "立即更新")
            }
        },
        dismissButton = {
            if (!forceUpdate && !downloading) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 更新状态 -> 设置页可读文案 */
fun updateStateText(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "尚未检查更新"
    is UpdateState.Checking -> "正在检查更新…"
    is UpdateState.UpToDate -> "已是最新版本（${state.localVersionName}）· ${state.channel.label}"
    is UpdateState.Available -> "发现新版本 ${state.info.versionName} · ${state.channel.label}"
    is UpdateState.Downloading -> "正在下载 ${state.progressPercent}%"
    is UpdateState.Downloaded -> "下载完成，等待安装"
    is UpdateState.Failed -> "检查失败：${state.message}"
}
