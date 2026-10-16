package com.example.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun systemFollowsBothPhoneAppearances() {
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
    }

    @Test fun manualAppearanceOverridesEitherPhoneSetting() {
        assertFalse(ThemeMode.LIGHT.isDark(false))
        assertFalse(ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.DARK.isDark(false))
        assertTrue(ThemeMode.DARK.isDark(true))
    }

    @Test fun storedChoicesRoundTripAndInvalidPreferencesFollowSystem() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStored(mode.name))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("old-invalid-value"))
    }
}
