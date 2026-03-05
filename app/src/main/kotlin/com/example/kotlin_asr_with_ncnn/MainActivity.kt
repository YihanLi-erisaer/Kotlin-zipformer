package com.example.kotlin_asr_with_ncnn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.kotlin_asr_with_ncnn.core.media.ModelConfig
import com.example.kotlin_asr_with_ncnn.core.media.NcnnNativeBridge
import com.example.kotlin_asr_with_ncnn.core.ui.AppTheme
import com.example.kotlin_asr_with_ncnn.core.ui.rememberThemeState
import com.example.kotlin_asr_with_ncnn.feature.home.ASRScreen
import com.example.kotlin_asr_with_ncnn.feature.home.ASRViewModel
import com.example.kotlin_asr_with_ncnn.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var nativeBridge: NcnnNativeBridge

    @Inject
    lateinit var themePreferences: ThemePreferences

    private val viewModel: ASRViewModel by viewModels()
    private val mainUiViewModel: MainUiViewModel by viewModels()

    // Audio recording permission request
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initASRModel()
        } else {
            Log.e("MainActivity", "Audio recording permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasAudioPermission()) {
            initASRModel()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val themeState = rememberThemeState(
                initialDarkTheme = themePreferences.darkTheme,
                onThemeChanged = { themePreferences.darkTheme = it }
            )
            val showSettings by mainUiViewModel.showSettings.collectAsState()
            val appVersion = remember { getAppVersion() }

            BackHandler(enabled = showSettings) {
                mainUiViewModel.closeSettings()
            }

            AppTheme(darkTheme = themeState.darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AnimatedContent(
                        targetState = showSettings,
                        transitionSpec = {
                            if (targetState) {
                                slideInHorizontally(initialOffsetX = { it }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { -it })
                            } else {
                                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { it })
                            }
                        },
                        label = "settings_transition"
                    ) { isSettings ->
                        if (isSettings) {
                            SettingsScreen(
                                darkTheme = themeState.darkTheme,
                                appVersion = appVersion,
                                onDarkThemeChanged = { themeState.updateDarkTheme(it) },
                                onBack = { mainUiViewModel.closeSettings() }
                            )
                        } else {
                            ASRScreen(
                                viewModel = viewModel,
                                onSettingsClick = { mainUiViewModel.openSettings() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initASRModel() {
        try {
            // Note: These paths must be relative to the assets/ folder root
            val modelConfig = ModelConfig(
                encoderParam = "encoder.param",
                encoderBin = "encoder.bin",
                decoderParam = "decoder.param",
                decoderBin = "decoder.bin",
                joinerParam = "joiner.param",
                joinerBin = "joiner.bin",
                tokens = "tokens.txt",
                numThreads = 4,
                useVulkanCompute = false 
            )
            
            Log.d("MainActivity", "Attempting to initialize native ASR model...")
            val success = nativeBridge.initModel(assets, modelConfig)
            
            if (success) {
                Log.i("MainActivity", "ASR Model initialized successfully")
                // Start inference if needed or wait for user action
            } else {
                Log.e("MainActivity", "Failed to initialize ASR Model. Check logcat (tag: NcnnASR-Native) for detailed error messages.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Exception during ASR initialization", e)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to read app version", e)
            "Unknown"
        }
    }
}
