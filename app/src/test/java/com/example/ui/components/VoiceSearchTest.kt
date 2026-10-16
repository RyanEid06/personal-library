package com.example.ui.components

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSearchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun onDeviceRecognitionWorksWithoutADefaultServiceAndReturnsEditableEnglishText() {
        assertFalse(SpeechRecognizer.isRecognitionAvailable(context))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        var recognizer: SpeechRecognizer? = null
        var status = ""
        var listening = false
        var transcript = ""
        startListening(context, { listening = it }, { status = it }, { transcript = it }, { recognizer = it })
        assertNotNull("Available on-device recognition must not be gated by default-service discovery", recognizer)
        val shadow = shadowOf(recognizer!!)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("en-US", shadow.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        shadow.triggerOnReadyForSpeech(Bundle())
        assertTrue(listening)
        assertTrue(status.contains("on device"))
        shadow.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("chemistry grades")) })
        assertEquals("chemistry grades", transcript)
        assertFalse(listening)
        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(listening)
        recognizer!!.destroy()
        assertTrue(shadow.isDestroyed)
    }

    @Test fun unavailableRecognitionKeepsTypedSearchAvailable() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        var status = ""
        var started = false
        startListening(context, {}, { status = it }, {}, { started = true })
        assertFalse(started)
        assertTrue(status.contains("not available"))
    }
}
