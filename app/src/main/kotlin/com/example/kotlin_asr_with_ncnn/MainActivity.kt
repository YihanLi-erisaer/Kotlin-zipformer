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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.kotlin_asr_with_ncnn.core.media.ModelConfig
import com.example.kotlin_asr_with_ncnn.core.media.NcnnNativeBridge
import com.example.kotlin_asr_with_ncnn.core.ui.AppTheme
import com.example.kotlin_asr_with_ncnn.core.ui.rememberThemeState
import com.example.kotlin_asr_with_ncnn.feature.home.ASRScreen
import com.example.kotlin_asr_with_ncnn.feature.home.ASRViewModel
import com.example.kotlin_asr_with_ncnn.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            lifecycleScope.launch {
                val beam = themePreferences.useBeamSearchFlow.first()
                initASRModel(beam)
            }
        } else {
            Log.e("MainActivity", "Audio recording permission denied")
            mainUiViewModel.setModelInitResult(success = false, "Microphone permission required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasAudioPermission()) {
            lifecycleScope.launch {
                val beam = themePreferences.useBeamSearchFlow.first()
                initASRModel(beam)
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val darkTheme by themePreferences.darkThemeFlow.collectAsState(initial = false)
            val useBeamSearch by themePreferences.useBeamSearchFlow.collectAsState(initial = false)
            val modelInitState by mainUiViewModel.modelInitState.collectAsState()
            var decoderModeSynced by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(useBeamSearch, modelInitState) {
                if (modelInitState !is ModelInitState.Ready) return@LaunchedEffect
                val synced = decoderModeSynced
                if (synced == null) {
                    decoderModeSynced = useBeamSearch
                    return@LaunchedEffect
                }
                if (synced == useBeamSearch) return@LaunchedEffect
                decoderModeSynced = useBeamSearch
                if (hasAudioPermission()) {
                    nativeBridge.releaseModel()
                    initASRModel(useBeamSearch)
                }
            }
            val scope = rememberCoroutineScope()
            val themeState = rememberThemeState(
                initialDarkTheme = darkTheme,
                onThemeChanged = { scope.launch { themePreferences.setDarkTheme(it) } }
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
                                useBeamSearch = useBeamSearch,
                                appVersion = appVersion,
                                onDarkThemeChanged = { themeState.updateDarkTheme(it) },
                                onUseBeamSearchChanged = { scope.launch { themePreferences.setUseBeamSearch(it) } },
                                onBack = { mainUiViewModel.closeSettings() }
                            )
                        } else {
                            ASRScreen(
                                viewModel = viewModel,
                                isModelLoading = modelInitState is ModelInitState.Loading,
                                modelErrorMessage = (modelInitState as? ModelInitState.Error)?.message,
                                onSettingsClick = { mainUiViewModel.openSettings() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initASRModel(useBeamSearch: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val modelConfig = ModelConfig(
                    encoderParam = "encoder.param",
                    encoderBin = "encoder.bin",
                    decoderParam = "decoder.param",
                    decoderBin = "decoder.bin",
                    joinerParam = "joiner.param",
                    joinerBin = "joiner.bin",
                    tokens = "tokens.txt",
                    numThreads = 4,
                    useVulkanCompute = false,
                    useBeamSearch = useBeamSearch
                )
                Log.d("MainActivity", "Attempting to initialize native ASR model (background)...")
                val success = nativeBridge.initModel(assets, modelConfig)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Log.i("MainActivity", "ASR Model initialized successfully")
                        mainUiViewModel.setModelInitResult(success = true)
                    } else {
                        Log.e("MainActivity", "Failed to initialize ASR Model.")
                        mainUiViewModel.setModelInitResult(success = false, "Model failed to load")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Exception during ASR initialization", e)
                withContext(Dispatchers.Main) {
                    mainUiViewModel.setModelInitResult(success = false, e.message ?: "Unknown error")
                }
            }
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
            getString(R.string.unknown_version)
        }
    }
}
