package com.example.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.ui.MainViewModel
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity

/** Window/lifecycle policy; real native authentication and Overview are verified separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], shadows = [AppLockPrivacyTest.RecentsActivityShadow::class])
class AppLockPrivacyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var hadLockPreference = false
    private var previousLockEnabled = false

    @Before fun preserveLockPreference() {
        val preferences = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        hadLockPreference = preferences.contains("lock_enabled")
        previousLockEnabled = preferences.getBoolean("lock_enabled", false)
    }

    @After fun restoreLockPreference() {
        val edit = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).edit()
        if (hadLockPreference) edit.putBoolean("lock_enabled", previousLockEnabled) else edit.remove("lock_enabled")
        check(edit.commit())
    }

    @Test fun persistedAppLockProtectsNewActivity() {
        context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try { assertProtected(controller.get(), true) } finally { controller.destroy() }
    }

    @Test fun toggleProtectsImmediatelyAndUnlockKeepsProtectionUntilDisabled() {
        context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val manager = AppLockManager(context)
            assertProtected(controller.get(), false)
            manager.setLockEnabled(true)
            assertProtected(controller.get(), true)
            manager.unlock()
            assertFalse(manager.isLocked.value)
            assertTrue(manager.isLockEnabled())
            assertProtected(controller.get(), true)
            manager.setLockEnabled(false)
            assertFalse(manager.isLocked.value)
            assertProtected(controller.get(), false)
        } finally { controller.destroy() }
    }

    @Test fun pauseWithoutStopRelocksAuthenticatedLibraryBeforeReturningFromOverview() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(true)
            assertTrue("Fixture must enable the activity's own manager", manager.isLockEnabled())
            manager.unlock()
            assertFalse(manager.isLocked.value)

            // Overview can pause the same activity without delivering onStop.
            controller.pause()
            assertTrue("Leaving the foreground must lock before onStop", manager.isLocked.value)
            controller.resume()
            assertTrue("Returning to the same activity must still require authentication", manager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pauseWithoutStopDoesNotLockWhenAppLockIsDisabled() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(false)
            assertFalse(manager.isLockEnabled())
            controller.get().onTopResumedActivityChanged(false)
            controller.get().onTopResumedActivityChanged(true)
            controller.pause().resume()
            assertFalse(manager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun authenticationSuccessWhilePausedRemainsUnlockedOnResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(true)
            assertTrue(manager.isLockEnabled())
            controller.get().onTopResumedActivityChanged(false)
            controller.pause()
            assertTrue(manager.isLocked.value)

            // The native authentication success callback uses this same manager operation.
            manager.unlock()
            controller.resume()
            controller.get().onTopResumedActivityChanged(true)
            assertFalse("Resume must preserve a successful authentication result", manager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun losingTopResumedStateRelocksWithoutPauseOrStop() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(true)
            manager.unlock()
            assertTrue(manager.isLockEnabled())
            assertFalse(manager.isLocked.value)

            controller.get().onTopResumedActivityChanged(false)
            assertTrue("Overview must lock even when the activity stays resumed", manager.isLocked.value)
            controller.get().onTopResumedActivityChanged(true)
            assertTrue("Regaining the top position must still require authentication", manager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun losingWindowFocusAloneDoesNotRelockForDialogs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(true)
            manager.unlock()

            controller.get().onWindowFocusChanged(false)
            controller.get().onWindowFocusChanged(true)
            assertFalse("Dialog focus changes alone must preserve authentication", manager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertProtected(activity: Activity, expected: Boolean) {
        val secure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("App Lock must drive the OS Recents policy independently of authentication", !expected,
                Shadow.extract<RecentsActivityShadow>(activity).recentsEnabled)
            assertEquals("App Lock must protect the window as well as cached task screenshots", expected, secure)
        } else {
            assertEquals("Older supported Android needs a secure window while App Lock is enabled", expected, secure)
        }
    }

    @Implements(Activity::class)
    class RecentsActivityShadow : ShadowActivity() {
        var recentsEnabled = true
            private set

        @Implementation(minSdk = 33)
        protected fun setRecentsScreenshotEnabled(enabled: Boolean) { recentsEnabled = enabled }
    }
}
