package com.example.ui.theme

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(private val preferences: SharedPreferences) {
    private val mutableMode = MutableStateFlow(ThemeMode.fromStored(preferences.getString("theme_mode", null)))
    val mode: StateFlow<ThemeMode> = mutableMode.asStateFlow()

    fun setMode(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
        mutableMode.value = mode
    }
}
