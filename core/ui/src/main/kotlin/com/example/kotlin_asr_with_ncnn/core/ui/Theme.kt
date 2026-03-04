package com.example.kotlin_asr_with_ncnn.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
class ThemeState internal constructor(
    initialDarkTheme: Boolean,
    private val onThemeChanged: (Boolean) -> Unit
) {
    var darkTheme by mutableStateOf(initialDarkTheme)
        private set

    fun updateDarkTheme(isDarkTheme: Boolean) {
        darkTheme = isDarkTheme
        onThemeChanged(isDarkTheme)
    }
}

@Composable
fun rememberThemeState(
    initialDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit
): ThemeState {
    return remember(initialDarkTheme, onThemeChanged) {
        ThemeState(initialDarkTheme = initialDarkTheme, onThemeChanged = onThemeChanged)
    }
}
