package com.stardazz.smeeting

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stardazz.smeeting.core.common.ThemePreferences
import com.stardazz.smeeting.core.startup.AsrModelManager
import com.stardazz.smeeting.core.startup.ModelInitNotifier
import com.stardazz.smeeting.core.startup.ModelInitPipelineEvent
import com.stardazz.smeeting.core.startup.StartupPreferenceCache
import com.stardazz.smeeting.core.startup.StartupRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ModelInitState {
    data object Loading : ModelInitState()
    data object Ready : ModelInitState()
    data class Error(val message: String) : ModelInitState()
}

enum class MainScreen {
    Home,
    Settings,
    History,
}

@HiltViewModel
class MainUiViewModel @Inject constructor(
    private val application: Application,
    private val asrModelManager: AsrModelManager,
    private val themePreferences: ThemePreferences,
    private val modelInitNotifier: ModelInitNotifier,
) : ViewModel() {

    // ── Navigation ──

    private val _currentScreen = MutableStateFlow(MainScreen.Home)
    val currentScreen: StateFlow<MainScreen> = _currentScreen.asStateFlow()

    // ── Model init state ──

    private val _modelInitState = MutableStateFlow<ModelInitState>(ModelInitState.Loading)
    val modelInitState: StateFlow<ModelInitState> = _modelInitState.asStateFlow()

    private var syncedDecoderMode: Boolean? = null

    init {
        observeModelInitEvents()
        observeBeamSearchChanges()
    }

    fun triggerStartupPipeline() {
        StartupRunner.runRegisteredPipelineOnce(viewModelScope)
    }

    // ── Permission ──

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    fun onAudioPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModelScope.launch {
                val useBeamSearch = themePreferences.useBeamSearchFlow.first()
                StartupPreferenceCache.useBeamSearch = useBeamSearch
                asrModelManager.loadModel(application.assets, useBeamSearch)
            }
        } else {
            Log.e(TAG, "Audio recording permission denied")
            _modelInitState.value =
                ModelInitState.Error("Microphone permission required")
        }
    }

    // ── Model init event observation ──

    private fun observeModelInitEvents() {
        viewModelScope.launch {
            modelInitNotifier.events.collect { event ->
                when (event) {
                    is ModelInitPipelineEvent.Finished -> {
                        _modelInitState.value = if (event.success) {
                            ModelInitState.Ready
                        } else {
                            ModelInitState.Error(event.error ?: "Model failed to load")
                        }
                    }
                    ModelInitPipelineEvent.SkippedAwaitingPermission -> {
                        Log.d(TAG, "Startup pipeline deferred ASR init until RECORD_AUDIO is granted")
                    }
                }
            }
        }
    }

    // ── Beam-search / decoder mode sync ──

    /**
     * Re-evaluates whenever beam-search preference OR model-init state changes,
     * mirroring the original LaunchedEffect(useBeamSearch, modelInitState) trigger.
     */
    private fun observeBeamSearchChanges() {
        viewModelScope.launch {
            combine(
                themePreferences.useBeamSearchFlow,
                _modelInitState,
            ) { useBeamSearch, _ -> useBeamSearch }
                .collect { useBeamSearch -> syncDecoderMode(useBeamSearch) }
        }
    }

    private suspend fun syncDecoderMode(useBeamSearch: Boolean) {
        if (_modelInitState.value !is ModelInitState.Ready) return

        val synced = syncedDecoderMode
        if (synced == null) {
            syncedDecoderMode = useBeamSearch
            return
        }
        if (synced == useBeamSearch) return

        syncedDecoderMode = useBeamSearch
        if (hasAudioPermission()) {
            asrModelManager.reloadModel(application.assets, useBeamSearch)
        }
    }

    // ── Navigation ──

    fun openSettings() {
        _currentScreen.value = MainScreen.Settings
    }

    fun closeSettings() {
        onBack()
    }

    fun openHistory() {
        _currentScreen.value = MainScreen.History
    }

    fun closeHistory() {
        onBack()
    }

    fun onBack() {
        _currentScreen.update { screen ->
            if (screen == MainScreen.Home) screen else MainScreen.Home
        }
    }

    private companion object {
        const val TAG = "MainUiViewModel"
    }
}
