package com.example.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(private val preferences: SharedPreferences, private val context: Context) {
    private val mutableMode = MutableStateFlow(ThemeMode.fromStored(preferences.getString("theme_mode", null)))
    val mode: StateFlow<ThemeMode> = mutableMode.asStateFlow()

    init { synchronizeLaunchMode(mutableMode.value) }

    fun setMode(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
        mutableMode.value = mode
        synchronizeLaunchMode(mode)
    }

    private fun synchronizeLaunchMode(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(when (mode) {
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                // Android clears the application override for AUTO, inheriting the system mode.
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            })
        }
    }

    companion object {
        /** Set the first app window before Activity creation, including on Android 10/11. */
        fun applyActivityTheme(activity: Activity) {
            val preferences = activity.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
            val theme = when (ThemeMode.fromStored(preferences.getString("theme_mode", null))) {
                ThemeMode.LIGHT -> R.style.Theme_PersonalLibrary_Light
                ThemeMode.DARK -> R.style.Theme_PersonalLibrary_Dark
                ThemeMode.SYSTEM -> R.style.Theme_PersonalLibrary
            }
            activity.setTheme(theme)
        }
    }
}
