package com.daoshu.compass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.daoshu.compass.data.settings.SettingsRepository
import com.daoshu.compass.ui.compass.CompassLayer
import com.daoshu.compass.ui.compass.CompassScreen
import com.daoshu.compass.ui.compass.CompassUiState
import com.daoshu.compass.ui.compass.CompassViewModel
import com.daoshu.compass.ui.settings.SettingsScreen
import com.daoshu.compass.ui.theme.DaoShuTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ROUTE_COMPASS = "compass"
private const val ROUTE_SETTINGS = "settings"

/**
 * 应用唯一 Activity：edge-to-edge 装配、运行时权限申请、Compose 导航与主题。
 *
 * - 定位权限（FINE/COARSE）与 Android 13+ 通知权限在首次进入时申请；
 * - 设置页的数值型设置直接写入 [SettingsRepository]，层开关等动作走 [CompassViewModel]，
 *   保证同一份 DataStore 数据只通过一条写入路径被修改。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15（API 35）强制 edge-to-edge：所有版本统一交由 Compose 的 WindowInsets 避让
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val compassViewModel: CompassViewModel = hiltViewModel()
            val state by compassViewModel.uiState.collectAsStateWithLifecycle()

            // 生命周期：ON_RESUME 启动传感器 + 定位，ON_PAUSE 立即停止以省电
            LifecycleResumeEffect(compassViewModel) {
                compassViewModel.onResume()
                onPauseOrDispose { compassViewModel.onPause() }
            }

            DaoShuTheme(themeMode = state.settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompassApp(
                        state = state,
                        compassViewModel = compassViewModel,
                        settingsRepository = settingsRepository
                    )
                }
            }
        }
    }
}

@Composable
private fun CompassApp(
    state: CompassUiState,
    compassViewModel: CompassViewModel,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------------- 运行时权限 ----------------
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 结果本身由 LocationRepository 的权限状态体现，这里立刻补一次定位请求
        compassViewModel.refreshLocation()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 通知权限被拒绝不影响下载与安装，仅少了完成提醒 */ }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission(context)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------- 导航：罗盘 / 设置 ----------------
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_COMPASS) {
        composable(route = ROUTE_COMPASS) {
            CompassScreen(
                state = state,
                onToggleLayer = compassViewModel::toggleLayer,
                onSwitchNorth = compassViewModel::switchNorthType,
                onResetCalibration = compassViewModel::resetCalibration,
                onDismissUpdateDialog = compassViewModel::dismissUpdateDialog,
                onStartUpdate = compassViewModel::startUpdate,
                onOpenSettings = {
                    navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                }
            )
        }
        composable(route = ROUTE_SETTINGS) {
            SettingsScreen(
                settings = state.settings,
                updateState = state.updateState,
                onBack = { navController.popBackStack() },
                onThemeChange = { mode ->
                    scope.launch { settingsRepository.update { it.copy(themeMode = mode) } }
                },
                onNorthTypeChange = { northType ->
                    scope.launch { settingsRepository.update { it.copy(northType = northType) } }
                },
                onSmoothingChange = { smoothing ->
                    scope.launch { settingsRepository.update { it.copy(smoothing = smoothing) } }
                },
                onToggleBagua = { compassViewModel.toggleLayer(CompassLayer.BAGUA) },
                onToggleMountains = { compassViewModel.toggleLayer(CompassLayer.MOUNTAINS) },
                onToggleJiazi = { compassViewModel.toggleLayer(CompassLayer.JIAZI) },
                onToggleXiu = { compassViewModel.toggleLayer(CompassLayer.XIU) },
                onToggleDegreeTicks = { compassViewModel.toggleLayer(CompassLayer.DEGREE_TICKS) },
                onCalibrationOffsetChange = { offset ->
                    scope.launch { settingsRepository.update { it.copy(calibrationOffsetDegrees = offset) } }
                },
                onResetCalibration = compassViewModel::resetCalibration,
                onToggleCheckOnStart = { checked ->
                    scope.launch { settingsRepository.update { it.copy(checkUpdateOnStart = checked) } }
                },
                onCheckUpdate = compassViewModel::checkUpdateManually,
                onResetAll = { scope.launch { settingsRepository.reset() } }
            )
        }
    }
}

/** 是否已获得任一档定位权限（精确或粗略） */
private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}
