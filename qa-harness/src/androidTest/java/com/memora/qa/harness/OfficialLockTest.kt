package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Opted-in, credential-free synthetic AVD only; no biometric enrollment or physical-device proof. */
@RunWith(AndroidJUnit4::class)
class OfficialLockTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val keyguard: KeyguardManager get() = checkNotNull(context.getSystemService(KeyguardManager::class.java))
    private lateinit var ui: ReleaseUi
    private lateinit var evidenceDir: File
    private lateinit var marker: String
    private var expectedCount = 0
    private var guarded = false
    private var credentialUiActive = false

    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) {
                // Never persist command output, exception text, or a credential-entry screenshot.
                File(evidenceDir, "outcome.txt").writeText("FAIL ${error.javaClass.simpleName}\n")
                if (!credentialUiActive) runCatching { ui.checkpoint("FAILED-without-credential-input") }
            }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) {
            File(evidenceDir, "outcome.txt").writeText("PASS\n")
            Log.i("MemoraQA", "${description.methodName}: PASS; synthetic credential removed")
        }
    }

    @Before fun requireCredentialFreeOfficialSyntheticEmulator() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("class") == "${javaClass.name}#$METHOD" &&
            arguments.getString("allowSyntheticCredentialSetup") == "true") { "Explicit single-method synthetic credential setup required" }
        check(arguments.getString("rollbackSnapshot").orEmpty().matches(Regex("memora-official-before-auth-[0-9-]+"))) {
            "Host must first save and independently verify a unique pre-auth rollback snapshot"
        }
        marker = arguments.getString("expectedLibraryMarker") ?: "QA-BK-3626615.pdf"
        check(marker.matches(Regex("QA-BK-[0-9]+\\.pdf"))) { "Known synthetic PDF marker required" }
        expectedCount = arguments.getString("expectedLibraryCount")?.toIntOrNull() ?: 278
        check(expectedCount in 211..500) { "Known bounded synthetic library count required" }
        evidenceDir = File(context.filesDir, "qa-evidence/$METHOD-${SystemClock.elapsedRealtime()}").apply { mkdirs() }
        ui = ReleaseUi(instrumentation.uiAutomation, evidenceDir)
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the disposable emulator is allowed" }
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        val info = context.packageManager.getPackageInfo(PRODUCT, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.versionName == "1.0.1" && info.longVersionCode == 3L) { "Expected official 1.0.1/code3" }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        check(hex(MessageDigest.getInstance("SHA-256").digest(checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray())) == CERTIFICATE) {
            "Expected permanent release signer"
        }
        val application = checkNotNull(info.applicationInfo)
        check(application.splitSourceDirs.isNullOrEmpty()) { "Expected published universal APK" }
        val digest = MessageDigest.getInstance("SHA-256")
        File(application.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(hex(digest.digest()) == APK_SHA256) { "Expected exact published 1.0.1 APK" }
        check(!keyguard.isDeviceSecure) { "Refusing an AVD with an existing device credential" }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        guarded = true
        File(evidenceDir, "outcome.txt").writeText("STARTED\n")
        launchProduct()
        ui.waitText("Library")
        verifyLibrary("baseline")
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.scrollTo("Biometric App Lock")
        check(!appLockSwitch().isChecked && ui.exact("No lock enabled") != null) { "Pre-test App Lock must already be off" }
    }

    @Test fun T35_T37_nativeCredentialCancelFailureSuccessAndBackgroundPrivacy() {
        // This is artificial fixture data. It is never sent through runner arguments or written to evidence.
        val pin = (100000 + SecureRandom().nextInt(900000)).toString()
        var setupAttempted = false
        var primaryFailure: Throwable? = null
        try {
            check(!keyguard.isDeviceSecure) { "Credential-free precondition changed before setup" }
            setupAttempted = true
            ownedCredentialCommand("locksettings set-pin $pin")
            privateAwait("Synthetic credential setup was not confirmed") { keyguard.isDeviceSecure }
            ui.clickNode(appLockSwitch())
            assertLocked("enabled")

            openCredentialPrompt()
            ui.back()
            privateAwait("Cancelled native prompt must return to the locked product") { lockedVisible() }
            credentialUiActive = false
            assertLocked("native-cancel")

            openCredentialPrompt()
            val wrong = pin.dropLast(1) + ((pin.last().digitToInt() + 1) % 10)
            enterCredential(wrong)
            privateAwait("One wrong credential must report native rejection") {
                check(!libraryVisible()) { "Wrong credential exposed the library" }
                val nativePackage = foreground()
                nativePackage in NATIVE_AUTH && (credentialField() != null || nativePinPad() != null) &&
                    ui.nodes().any { it.isVisibleToUser && it.packageName?.toString() == nativePackage &&
                        Regex("(?i)(wrong|incorrect|try again)").containsMatchIn(it.text?.toString().orEmpty()) }
            }
            check(!libraryVisible()) { "Rejected credential must leave the library inaccessible" }
            // Remain inside the native credential UI; no screenshot is taken with input present.
            enterCredential(pin)
            awaitAuthenticated()
            verifyLibrary("native-credential-success")

            ui.shell("input keyevent KEYCODE_HOME")
            privateAwait("Home must background Memora") { foreground() != PRODUCT }
            launchProduct()
            assertLocked("home-resume")
            authenticate(pin)
            verifyLibrary("home-resume-unlocked")

            ui.shell("am force-stop $PRODUCT")
            launchProduct()
            assertLocked("cold-restart")
            authenticate(pin)
            verifyLibrary("cold-restart-unlocked")
            File(evidenceDir, "coverage.txt").writeText(
                "Native credential cancel, one rejection, successful PIN, settled locked accessibility, Home/resume and cold restart verified.\n" +
                    "Biometric enrollment/sensor, notification redaction, recents masking and first-frame visual privacy are untested.\n"
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (setupAttempted) {
                try { cleanupSyntheticCredential(pin) } catch (cleanup: Throwable) {
                    primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    private fun cleanupSyntheticCredential(pin: String) {
        var lockFailure: Throwable? = null
        try {
            if (foreground() in NATIVE_AUTH) {
                ui.back()
                privateAwait("Native auth must close for cleanup") { foreground() == PRODUCT }
            }
            if (!libraryVisible()) {
                launchProduct()
                privateAwait("Product must settle for authenticated cleanup") { libraryVisible() || lockedVisible() }
                if (lockedVisible()) authenticate(pin)
            }
            privateAwait("Authenticated library required to disable App Lock") { libraryVisible() }
            credentialUiActive = false
            ui.click("Settings")
            ui.scrollTo("Biometric App Lock")
            if (appLockSwitch().isChecked) ui.clickNode(appLockSwitch())
            ui.await("App Lock must be disabled through Settings") { !appLockSwitch().isChecked && ui.exact("No lock enabled") != null }
        } catch (error: Throwable) {
            lockFailure = error
        } finally {
            // Never clear a credential that predated this method: setup was allowed only after two positive no-credential checks.
            if (keyguard.isDeviceSecure) ownedCredentialCommand("locksettings clear --old $pin")
            privateAwait("Owned synthetic device credential must be removed") { !keyguard.isDeviceSecure }
        }
        lockFailure?.let { throw it }
        credentialUiActive = false
        ui.checkpoint("T36-owned-credential-removed-app-lock-off")
        ui.back()
        verifyLibrary("cleanup-library-preserved")
    }

    private fun authenticate(pin: String) {
        openCredentialPrompt()
        enterCredential(pin)
        awaitAuthenticated()
    }

    private fun openCredentialPrompt() {
        check(lockedVisible()) { "Credential authentication must start from Memora's lock screen" }
        credentialUiActive = true
        ui.clickNode(checkNotNull(ui.exact("Unlock Library")))
        try {
            privateAwait("Expected native PIN/password entry or native credential fallback") {
                if (foreground() !in NATIVE_AUTH) return@privateAwait false
                if (credentialField() != null || nativePinPad() != null) return@privateAwait true
                val fallback = listOf("Use PIN", "Use screen lock", "Use device credential", "Use password")
                    .firstNotNullOfOrNull { ui.exact(it) }
                fallback?.let(ui::clickNode)
                false
            }
        } catch (error: Throwable) {
            // Structural diagnostics only: native text, descriptions and field values may contain credentials.
            File(evidenceDir, "native-prompt-structure.txt").writeText(ui.nodes().joinToString("\n") {
                "package=${it.packageName} class=${it.className} id=${it.viewIdResourceName} " +
                    "visible=${it.isVisibleToUser} editable=${it.isEditable} password=${it.isPassword} clickable=${it.isClickable}"
            })
            throw error
        }
    }

    private fun enterCredential(value: String) {
        check(credentialUiActive && foreground() in NATIVE_AUTH) { "Credential entry refused outside native authentication" }
        privateAwait("Native credential field or PIN keypad required") { credentialField() != null || nativePinPad() != null }
        if (nativePinPad() != null) {
            // API37's native Compose PIN pad deliberately exposes no editable/password node.
            fun nativeAction(id: String) = ui.nodes().singleOrNull {
                it.isVisibleToUser && it.packageName?.toString() == "com.android.systemui" && it.viewIdResourceName == id
            }
            repeat(6) { ui.clickNode(checkNotNull(nativeAction("com.android.systemui:id/delete_button"))) }
            value.forEach { digit ->
                check(nativePinPad() != null && foreground() == "com.android.systemui") { "Native PIN keypad changed during input" }
                val key = ui.nodes().singleOrNull {
                    it.isVisibleToUser && it.packageName?.toString() == "com.android.systemui" && it.text?.toString() == digit.toString()
                }
                check(key != null) { "Unique native digit key required" }
                ui.clickNode(key)
            }
            ui.clickNode(checkNotNull(nativeAction("com.android.systemui:id/key_enter")))
            return
        }
        check(checkNotNull(credentialField()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Native credential field rejected synthetic input" }
        ui.shell("input keyevent KEYCODE_ENTER")
    }

    private fun credentialField() = ui.nodes().filter { it.isVisibleToUser && it.isEditable && it.isPassword }.singleOrNull()
    private fun nativePinPad() = ui.nodes().singleOrNull {
        it.isVisibleToUser && it.packageName?.toString() == "com.android.systemui" && it.viewIdResourceName == "cred_pin_pad"
    }
    private fun awaitAuthenticated() {
        privateAwait("Native authentication must reveal the library", 30000) { libraryVisible() && credentialField() == null }
        credentialUiActive = false
    }

    private fun assertLocked(label: String) {
        ui.await("Memora must show its settled lock screen") { lockedVisible() }
        check(ui.nodes().none { it.isVisibleToUser && (it.text?.toString() in setOf("Library", "Settings", marker, SEARCH) || it.hintText?.toString() == SEARCH) }) {
            "Library content or navigation is accessible while locked"
        }
        ui.checkpoint("T37-$label-locked")
    }

    private fun verifyLibrary(label: String) {
        ui.waitText("Library")
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
        ui.await("Original synthetic library count must be retained") { libraryCount() == expectedCount }
        ui.edit(SEARCH, marker)
        ui.await("Original synthetic PDF marker must be retained") { libraryCount() == 1 && ui.exact(marker) != null }
        ui.checkpoint("T36-$label-library-preserved")
        ui.edit(SEARCH, "")
        ui.await("Full library count must return") { libraryCount() == expectedCount }
    }

    private fun appLockSwitch(): AccessibilityNodeInfo {
        val label = checkNotNull(ui.exact("Biometric App Lock")) { "App Lock label required" }
        val bounds = Rect().also(label::getBoundsInScreen)
        ui.nodes().filter { node ->
            val other = Rect().also(node::getBoundsInScreen)
            node.isVisibleToUser && node.isCheckable && other.left >= bounds.right && other.top < bounds.bottom && other.bottom > bounds.top
        }.singleOrNull()?.let { return it }
        var ancestor: AccessibilityNodeInfo? = label
        repeat(6) {
            val matches = ancestor?.let(::descendants).orEmpty().filter { it.isVisibleToUser && it.isCheckable }
            if (matches.size == 1) return matches.single()
            ancestor = ancestor?.parent
        }
        throw AssertionError("Unique App Lock switch required")
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(descendants(it)) }
    }
    private fun launchProduct() = context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    private fun foreground() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
    private fun libraryVisible() = foreground() == PRODUCT && ui.exact("Library") != null && ui.exact("Memora is Locked") == null
    private fun lockedVisible() = foreground() == PRODUCT && ui.exact("Memora is Locked") != null && ui.exact("Unlock Library") != null
    private fun libraryCount() = ui.nodes().filter { it.isVisibleToUser }.firstNotNullOfOrNull {
        Regex("^(\\d+) items • Saved Date$").matchEntire(it.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
    }
    private fun privateAwait(message: String, timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        // Unlike ReleaseUi.await, this deliberately never captures a native credential screen on timeout.
        throw AssertionError(message)
    }

    private fun ownedCredentialCommand(command: String) {
        // Discard both output and any platform exception text: either may echo this runtime-only PIN.
        try { ui.shell(command) } catch (_: Throwable) { throw AssertionError("Owned synthetic credential command failed") }
    }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
        private const val METHOD = "T35_T37_nativeCredentialCancelFailureSuccessAndBackgroundPrivacy"
        private const val CERTIFICATE = "98686d31bfe4370cc30024439b56d9e72de71c081951d31f5d4fdd31d4eb0598"
        private const val APK_SHA256 = "b53d05b83e45dfb667effab1d1117aac9025701723e4f13b843d044d1fe2143d"
        private val NATIVE_AUTH = setOf("com.android.settings", "com.android.systemui", "android")
    }
}
