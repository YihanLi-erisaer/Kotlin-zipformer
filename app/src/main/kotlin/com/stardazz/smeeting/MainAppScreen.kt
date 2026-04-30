package com.stardazz.smeeting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.stardazz.smeeting.core.common.ThemeMode
import com.stardazz.smeeting.core.common.ThemePreferences
import com.stardazz.smeeting.core.ui.AppTheme
import com.stardazz.smeeting.core.ui.ThemeState
import com.stardazz.smeeting.feature.history.HistoryScreen
import com.stardazz.smeeting.feature.history.HistoryViewModel
import com.stardazz.smeeting.feature.home.ASRScreen
import com.stardazz.smeeting.feature.home.ASRViewModel
import com.stardazz.smeeting.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun MainAppScreen(
    viewModel: ASRViewModel,
    historyViewModel: HistoryViewModel,
    mainUiViewModel: MainUiViewModel,
    themePreferences: ThemePreferences,
    themeState: ThemeState,
    currentScreen: MainScreen,
    useBeamSearch: Boolean,
    appVersion: String,
    modelInitState: ModelInitState,
    onStartRequested: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        mainUiViewModel.triggerStartupPipeline()
    }

    BackHandler(enabled = currentScreen != MainScreen.Home) {
        mainUiViewModel.onBack()
    }

    val darkTheme = when (themeState.themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    AppTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        when {
                            initialState == MainScreen.Home && targetState == MainScreen.Settings -> {
                                slideInHorizontally(initialOffsetX = { it }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { -it })
                            }

                            initialState == MainScreen.Settings && targetState == MainScreen.Home -> {
                                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { it })
                            }

                            initialState == MainScreen.Home && targetState == MainScreen.History -> {
                                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { it })
                            }

                            initialState == MainScreen.History && targetState == MainScreen.Home -> {
                                slideInHorizontally(initialOffsetX = { it }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { -it })
                            }

                            else -> {
                                slideInHorizontally(initialOffsetX = { 0 }) togetherWith
                                    slideOutHorizontally(targetOffsetX = { 0 })
                            }
                        }
                    },
                    label = "main_screen_transition",
                    modifier = Modifier.fillMaxSize(),
                ) { screen ->
                    when (screen) {
                        MainScreen.Settings -> {
                            SettingsScreen(
                                themeMode = themeState.themeMode,
                                useBeamSearch = useBeamSearch,
                                appVersion = appVersion,
                                onThemeModeChanged = { themeState.updateThemeMode(it) },
                                onUseBeamSearchChanged = {
                                    scope.launch { themePreferences.setUseBeamSearch(it) }
                                },
                                onBack = { mainUiViewModel.onBack() },
                            )
                        }

                        MainScreen.History -> {
                            HistoryScreen(
                                viewModel = historyViewModel,
                                onBack = { mainUiViewModel.onBack() },
                            )
                        }

                        MainScreen.Home -> {
                            ASRScreen(
                                viewModel = viewModel,
                                isModelLoading = modelInitState is ModelInitState.Loading,
                                modelErrorMessage = (modelInitState as? ModelInitState.Error)?.message,
                                onSettingsClick = { mainUiViewModel.openSettings() },
                                onHistoryClick = { mainUiViewModel.openHistory() },
                                onStartRequested = onStartRequested,
                            )
                        }
                    }
                }
            }
        }
    }
}
