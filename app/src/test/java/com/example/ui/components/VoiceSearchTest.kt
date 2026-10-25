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
        assertTrue("Recognition error must explain the available typed fallback", status.contains("type", ignoreCase = true))
        assertTrue("The microphone must offer retry", status.contains("microphone", ignoreCase = true))
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

    @Test fun networkAndPermissionErrorsExplainTheActualFailure() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        var recognizer: SpeechRecognizer? = null
        var status = ""
        startListening(context, {}, { status = it }, {}, { recognizer = it })
        val shadow = shadowOf(recognizer!!)
        shadowOf(Looper.getMainLooper()).idle()
        shadow.triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(status.contains("network", ignoreCase = true))
        shadow.triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(status.contains("permission", ignoreCase = true))
        recognizer!!.destroy()
    }

    @Test fun partialSpeechIsEditableAndAnEmptyFinalResultExplainsRetry() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        var recognizer: SpeechRecognizer? = null
        var listening = false
        var transcript = ""
        var status = ""
        startListening(context, { listening = it }, { status = it }, { transcript = it }, { recognizer = it })
        assertTrue("The first microphone press must immediately enter the recording state", listening)
        val shadow = shadowOf(recognizer!!)
        shadowOf(Looper.getMainLooper()).idle()
        shadow.triggerOnPartialResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Ryan birthday"))
        })
        assertEquals("Ryan birthday", transcript)
        shadow.triggerOnResults(Bundle())
        assertFalse(listening)
        assertEquals("Ryan birthday", transcript)
        assertTrue(status.contains("No speech was recognized"))
        assertTrue(status.contains("retry"))
        recognizer!!.destroy()
    }

    @Test fun serviceFailuresOfferRetryInsteadOfAnUnexplainedStoppedState() {
        for ((error, reason) in listOf(
            SpeechRecognizer.ERROR_AUDIO to "audio",
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to "heard",
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to "busy",
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED to "unavailable",
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to "English",
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to "too many"
        )) {
            val message = speechRecognitionErrorMessage(error)
            assertTrue(message.contains(reason, ignoreCase = true))
            assertTrue(message.contains("microphone"))
            assertTrue(message.contains("type your query"))
        }
    }
}
