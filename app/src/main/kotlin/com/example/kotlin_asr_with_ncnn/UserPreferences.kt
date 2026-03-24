package com.example.kotlin_asr_with_ncnn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
private val KEY_USE_BEAM_SEARCH = booleanPreferencesKey("use_beam_search")

class ThemePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val darkThemeFlow: Flow<Boolean> = context.themeDataStore.data
        .map { preferences -> preferences[KEY_DARK_THEME] ?: false }

    /** When false (default), greedy search; when true, modified beam search. */
    val useBeamSearchFlow: Flow<Boolean> = context.themeDataStore.data
        .map { preferences -> preferences[KEY_USE_BEAM_SEARCH] ?: false }

    suspend fun setDarkTheme(value: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[KEY_DARK_THEME] = value
        }
    }

    suspend fun setUseBeamSearch(value: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[KEY_USE_BEAM_SEARCH] = value
        }
    }
}
