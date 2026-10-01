package com.daoshu.compass.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daoshu.compass.BuildConfig
import com.daoshu.compass.data.settings.CompassSettings
import com.daoshu.compass.data.settings.NorthType
import com.daoshu.compass.data.settings.ThemeMode
import com.daoshu.compass.data.update.UpdateState
import com.daoshu.compass.ui.compass.updateStateText
import kotlin.math.roundToInt

/**
 * 设置页：主题、层开关、真北/磁北、校准、更新检查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: CompassSettings,
    updateState: UpdateState,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onNorthTypeChange: (NorthType) -> Unit,
    onSmoothingChange: (Float) -> Unit,
    onToggleBagua: (Boolean) -> Unit,
    onToggleMountains: (Boolean) -> Unit,
    onToggleJiazi: (Boolean) -> Unit,
    onToggleXiu: (Boolean) -> Unit,
    onToggleDegreeTicks: (Boolean) -> Unit,
    onCalibrationOffsetChange: (Float) -> Unit,
    onResetCalibration: () -> Unit,
    onToggleCheckOnStart: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onResetAll: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- 外观 ----------------
            SettingsCard(title = "外观") {
                Text("主题模式", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            label = { Text(mode.label()) }
                        )
                    }
                }
            }

            // ---------------- 罗盘 ----------------
            SettingsCard(title = "罗盘") {
                Text("北向基准", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NorthType.entries.forEach { type ->
                        FilterChip(
                            selected = settings.northType == type,
                            onClick = { onNorthTypeChange(type) },
                            label = { Text(type.label()) }
                        )
                    }
                }
                Text(
                    text = if (settings.northType == NorthType.TRUE) {
                        "真北 = 磁北 + 磁偏角（依据当前位置由系统地磁模型计算）"
                    } else {
                        "磁北：直接使用磁力计读数，不补偿磁偏角"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------------- 显示层 ----------------
            SettingsCard(title = "显示层") {
                SwitchRow("八卦", "乾坎艮震巽离坤兑", settings.layers.bagua, onToggleBagua)
                SwitchRow("二十四山", "每山 15°，自正北顺时针", settings.layers.mountains, onToggleMountains)
                SwitchRow("六十甲子", "天干地支六十循环", settings.layers.jiazi, onToggleJiazi)
                SwitchRow("二十八宿", "斗牛女虚危室壁…", settings.layers.xiu, onToggleXiu)
                SwitchRow("360° 刻度", "外圈度数与方位点", settings.layers.degreeTicks, onToggleDegreeTicks)
            }

            // ---------------- 数据处理 ----------------
            SettingsCard(title = "数据处理") {
                Text(
                    text = "传感器平滑系数：${"%.2f".format(settings.smoothing)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = settings.smoothing,
                    onValueChange = onSmoothingChange,
                    valueRange = 0.02f..1.0f,
                    steps = 48
                )
                Text(
                    text = "数值越大越平滑、响应越慢；抖动明显时适当调大。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------------- 校准 ----------------
            SettingsCard(title = "校准") {
                Text(
                    text = "手动偏移：${settings.calibrationOffsetDegrees.roundToInt()}°",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = settings.calibrationOffsetDegrees,
                    onValueChange = onCalibrationOffsetChange,
                    valueRange = -180f..180f,
                    steps = 359
                )
                Text(
                    text = "铁磁干扰或已知基准下可手动修正；正常情况请使用八字校准（在空中画 8 字）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onResetCalibration) { Text("重置校准") }
            }

            // ---------------- 更新 ----------------
            SettingsCard(title = "应用更新") {
                SwitchRow(
                    title = "启动时检查更新",
                    subtitle = "每次冷启动后台静默检查，不打扰使用",
                    checked = settings.checkUpdateOnStart,
                    onCheckedChange = onToggleCheckOnStart
                )
                Text(
                    text = updateStateText(updateState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onCheckUpdate,
                    enabled = updateState !is UpdateState.Checking &&
                        updateState !is UpdateState.Downloading
                ) { Text("立即检查更新") }
            }

            // ---------------- 关于 ----------------
            SettingsCard(title = "关于") {
                KeyValueLine("应用名称", "道枢罗盘")
                KeyValueLine("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                KeyValueLine("更新通道", "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}@${BuildConfig.GITHUB_BRANCH}")
                KeyValueLine("系统版本", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                KeyValueLine("设备", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                TextButton(onClick = onResetAll) { Text("恢复默认设置") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun KeyValueLine(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 主题模式中文名 */
fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

/** 北向基准中文名 */
fun NorthType.label(): String = when (this) {
    NorthType.MAGNETIC -> "磁北"
    NorthType.TRUE -> "真北"
}
