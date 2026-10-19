package com.example.ui.theme

import android.app.UiModeManager
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchAppearanceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun systemDarkLaunchWindowUsesTheExistingSlatePalette() {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_PersonalLibrary)
        val values = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        val background = values.getDrawable(0)
        values.recycle()
        assertTrue("Launch background must be a solid palette color", background is ColorDrawable)
        assertEquals("Cold launch must match Memora's dark background", Color.rgb(15, 23, 42), (background as ColorDrawable).color)
    }

    @Test fun selectedAppearanceAlsoUpdatesThePersistedAndroidSplashMode() {
        val preferences = context.getSharedPreferences("launch-appearance-test", Context.MODE_PRIVATE)
        val store = ThemePreferences(preferences, context)
        val manager = context.getSystemService(UiModeManager::class.java)
        store.setMode(ThemeMode.DARK)
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(manager).applicationNightMode)
        store.setMode(ThemeMode.LIGHT)
        assertEquals(UiModeManager.MODE_NIGHT_NO, shadowOf(manager).applicationNightMode)
        store.setMode(ThemeMode.SYSTEM)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, shadowOf(manager).applicationNightMode)
    }

    @Test fun existingSavedManualChoiceIsSynchronizedOnStartup() {
        val preferences = context.getSharedPreferences("launch-existing-choice", Context.MODE_PRIVATE)
        preferences.edit().putString("theme_mode", ThemeMode.DARK.name).commit()
        ThemePreferences(preferences, context)
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(context.getSystemService(UiModeManager::class.java)).applicationNightMode)
    }

    @Test @Config(sdk = [29, 34], qualifiers = "notnight")
    fun savedDarkChoiceOverridesALightSystemForTheAppWindow() = assertActivityBackground(ThemeMode.DARK, Color.rgb(15, 23, 42))

    @Test @Config(sdk = [29, 34], qualifiers = "night")
    fun savedLightChoiceOverridesADarkSystemForTheAppWindow() = assertActivityBackground(ThemeMode.LIGHT, Color.rgb(248, 250, 252))

    private fun assertActivityBackground(mode: ThemeMode, expected: Int) {
        context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", mode.name).commit()
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            ThemePreferences.applyActivityTheme(controller.get())
            val values = controller.get().obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val background = values.getDrawable(0) as ColorDrawable
            values.recycle()
            assertEquals(expected, background.color)
        } finally {
            controller.destroy()
        }
    }
}
