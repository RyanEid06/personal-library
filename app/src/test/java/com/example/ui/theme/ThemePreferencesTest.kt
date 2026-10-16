package com.example.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemePreferencesTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("theme-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test fun existingInstallDefaultsToSystemWithoutChangingOtherSettings() {
        val preferences = preferences()
        preferences.edit().putBoolean("ai_paused", true).putString("library_layout", "GRID").commit()
        val store = ThemePreferences(preferences)
        assertEquals(ThemeMode.SYSTEM, store.mode.value)
        store.setMode(ThemeMode.DARK)
        assertTrue(preferences.getBoolean("ai_paused", false))
        assertEquals("GRID", preferences.getString("library_layout", null))
    }

    @Test fun everySelectionUpdatesImmediatelyAndSurvivesStoreRecreation() {
        val preferences = preferences()
        val store = ThemePreferences(preferences)
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            store.setMode(mode)
            assertEquals(mode, store.mode.value)
            assertEquals(mode, ThemePreferences(preferences).mode.value)
        }
    }
}
