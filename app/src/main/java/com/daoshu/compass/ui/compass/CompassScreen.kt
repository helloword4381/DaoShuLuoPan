package com.daoshu.compass.ui.compass

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.daoshu.compass.BuildConfig
import com.daoshu.compass.data.settings.NorthType
import com.daoshu.compass.data.update.UpdateState
import com.daoshu.compass.data.update.VersionInfo
import com.daoshu.compass.ui.theme.DaoShuTheme
import kotlin.math.abs

/**
 * 罗盘主界面：读数卡片 + 表盘 + 层开关 + 指向/校准操作 + 更新对话框。
 *
 * 所有绘制细节由 [CompassDial] 承担，本页只负责布局与状态展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(
    state: CompassUiState,
    onToggleLayer: (CompassLayer) -> Unit,
    onSwitchNorth: () -> Unit,
    onResetCalibration: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onStartUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("道枢罗盘") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        // Scaffold 已按 edge-to-edge 的 WindowInsets 处理系统栏避让
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReadoutCard(state = state, modifier = Modifier.fillMaxWidth())

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CompassDial(
                    headingDegrees = state.northHeadingDegrees,
                    layers = state.settings.layers,
                    showTrueNorth = state.settings.northType == NorthType.TRUE,
                    declinationDegrees = state.declination?.declinationDegrees ?: 0f,
                    modifier = Modifier.fillMaxSize()
                )
            }

            LayerChipRow(state = state, onToggleLayer = onToggleLayer)

            ActionRow(
                state = state,
                onSwitchNorth = onSwitchNorth,
                onResetCalibration = onResetCalibration
            )

            CalibrationHint(state = state, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    UpdateDialogHost(
        state = state,
        onDismissUpdateDialog = onDismissUpdateDialog,
        onStartUpdate = onStartUpdate
    )
}

// ---------------------------------------------------------------------------
// 子组件
// ---------------------------------------------------------------------------

@Composable
private fun ReadoutCard(state: CompassUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatHeading(state.northHeadingDegrees),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = bearingLabel(state.northHeadingDegrees),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (state.settings.northType == NorthType.TRUE) "真北基准" else "磁北基准",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Text(
                text = if (state.settings.northType == NorthType.TRUE) {
                    "磁方位 ${formatHeading(state.magneticHeadingDegrees)} · 已补偿磁偏角"
                } else {
                    "磁方位 ${formatHeading(state.magneticHeadingDegrees)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = declinationText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = locationText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = updateStateText(state.updateState),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LayerChipRow(state: CompassUiState, onToggleLayer: (CompassLayer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "显示层（点击切换）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompassLayer.entries.forEach { layer ->
                FilterChip(
                    selected = layer.isVisibleIn(state.settings.layers),
                    onClick = { onToggleLayer(layer) },
                    label = { Text(layer.label) }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    state: CompassUiState,
    onSwitchNorth: () -> Unit,
    onResetCalibration: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = state.settings.northType == NorthType.TRUE,
            onClick = onSwitchNorth,
            label = { Text(if (state.settings.northType == NorthType.TRUE) "北向：真北" else "北向：磁北") }
        )
        TextButton(onClick = onResetCalibration) { Text("重置校准") }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "平滑 ${"%.2f".format(state.settings.smoothing)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CalibrationHint(state: CompassUiState, modifier: Modifier = Modifier) {
    if (state.needsCalibration) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "磁场受干扰，请水平缓慢画「8」字校准（${state.calibrationPercentage}%）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            LinearProgressIndicator(
                progress = { state.calibrationPercentage / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 更新对话框宿主：保留最近一次「发现新版本」的 [VersionInfo]，
 * 使下载中 / 下载完成 / 下载失败状态仍能正确显示版本信息与重试按钮。
 */
@Composable
private fun UpdateDialogHost(
    state: CompassUiState,
    onDismissUpdateDialog: () -> Unit,
    onStartUpdate: () -> Unit
) {
    val available = state.updateState as? UpdateState.Available
    var dialogInfo by remember { mutableStateOf<VersionInfo?>(null) }
    LaunchedEffect(available?.info) {
        if (available != null) dialogInfo = available.info
    }
    val info = dialogInfo
    if (state.updateDialogVisible && info != null) {
        UpdateDialog(
            info = info,
            state = state.updateState,
            // 远程 forceUpdate 或本地版本低于最低支持版本时都必须强制升级
            forceUpdate = info.forceUpdate || BuildConfig.VERSION_CODE < info.minSupportedVersion,
            currentVersionName = BuildConfig.VERSION_NAME,
            // 安装包体积：发现新版本时来自通道上报，下载中改用实际总字节数
            apkSizeBytes = when (val updateState = state.updateState) {
                is UpdateState.Available -> updateState.apkSizeBytes
                is UpdateState.Downloading -> updateState.totalBytes
                else -> 0L
            },
            onDismiss = onDismissUpdateDialog,
            onStartUpdate = onStartUpdate
        )
    }
}

// ---------------------------------------------------------------------------
// 文本格式化
// ---------------------------------------------------------------------------

private fun formatHeading(degrees: Float): String = "%.1f°".format(normalizeDegrees(degrees))

private fun declinationText(state: CompassUiState): String {
    val declination = state.declination
    if (declination == null) {
        return if (state.locationPermissionGranted) {
            "磁偏角：等待定位结果…"
        } else {
            "磁偏角：未授予定位权限（真北模式下按 0° 处理）"
        }
    }
    val eastWest = if (declination.declinationDegrees >= 0f) "东偏" else "西偏"
    val magnitude = abs(declination.declinationDegrees)
    return "磁偏角 $eastWest ${"%.2f".format(magnitude)}° · 磁场 ${
        "%.1f".format(declination.fieldStrengthUt)
    }μT · 模型 ${declination.modelYear.toInt()} 年"
}

private fun locationText(state: CompassUiState): String {
    val fix = state.fix
    if (fix != null) {
        return "位置 ${"%.4f".format(fix.latitude)}, ${"%.4f".format(fix.longitude)}" +
            " · 海拔 ${"%.0f".format(fix.altitudeMeters)}m" +
            " · 精度 ${"%.0f".format(fix.accuracyMeters)}m"
    }
    return if (state.locationPermissionGranted) {
        "位置：定位中…"
    } else {
        "位置：未授权定位"
    }
}

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CompassScreenPreview() {
    DaoShuTheme {
        CompassScreen(
            state = CompassUiState(),
            onToggleLayer = {},
            onSwitchNorth = {},
            onResetCalibration = {},
            onDismissUpdateDialog = {},
            onStartUpdate = {},
            onOpenSettings = {}
        )
    }
}
