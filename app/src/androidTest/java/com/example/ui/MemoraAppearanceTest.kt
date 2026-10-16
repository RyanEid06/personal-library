package com.example.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoraAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsPersistAppearanceAndUseMatchingWordmarks() {
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithTag("theme_mode_dark").performClick().assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithTag("memora_wordmark_dark").assertExists()
        screenshot("memora-dark.png")

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("memora_wordmark_dark").assertExists()
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithTag("theme_mode_dark").assertIsSelected()
        compose.onNodeWithTag("theme_mode_light").performClick().assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithTag("memora_wordmark_light").assertExists()
        screenshot("memora-light.png")

        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithTag("theme_mode_system").performClick().assertIsSelected()
        Espresso.pressBack()
        val dark = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        compose.onNodeWithTag(if (dark) "memora_wordmark_dark" else "memora_wordmark_light").assertExists()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
