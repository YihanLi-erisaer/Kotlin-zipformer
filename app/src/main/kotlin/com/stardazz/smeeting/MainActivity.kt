package com.stardazz.smeeting

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.stardazz.smeeting.core.common.ThemePreferences
import com.stardazz.smeeting.core.common.ThemeMode
import com.stardazz.smeeting.core.ui.rememberThemeState
import com.stardazz.smeeting.feature.home.ASRViewModel
import com.stardazz.smeeting.feature.history.HistoryViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themePreferences: ThemePreferences

    private val viewModel: ASRViewModel by viewModels()
    private val mainUiViewModel: MainUiViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> mainUiViewModel.onAudioPermissionResult(isGranted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by themePreferences.themeModeFlow.collectAsState(initial = ThemeMode.FOLLOW_SYSTEM)
            val useBeamSearch by themePreferences.useBeamSearchFlow.collectAsState(initial = false)
            val modelInitState by mainUiViewModel.modelInitState.collectAsState()
            val currentScreen by mainUiViewModel.currentScreen.collectAsState()
            val themeState = rememberThemeState(
                initialThemeMode = themeMode,
                onThemeModeChanged = { lifecycleScope.launch { themePreferences.setThemeMode(it) } },
            )
            val appVersion = remember { getAppVersion() }

            MainAppScreen(
                viewModel = viewModel,
                historyViewModel = historyViewModel,
                mainUiViewModel = mainUiViewModel,
                themePreferences = themePreferences,
                themeState = themeState,
                currentScreen = currentScreen,
                useBeamSearch = useBeamSearch,
                appVersion = appVersion,
                modelInitState = modelInitState,
                onStartRequested = {
                    if (mainUiViewModel.hasAudioPermission()) {
                        viewModel.onIntent(com.stardazz.smeeting.feature.home.ASRContract.Intent.ToggleListening)
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to read app version", e)
            getString(R.string.unknown_version)
        }
    }
}
