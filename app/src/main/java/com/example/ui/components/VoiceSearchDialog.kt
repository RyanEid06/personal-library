package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun VoiceSearchDialog(
    onDismiss: () -> Unit,
    onQueryConfirmed: (String) -> Unit
) {
    val context = LocalContext.current
    var spokenText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Starting microphone…") }
    var session by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(true) }

    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun beginListening() {
        val attempt = ++session
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        startListening(
            context = context,
            onListeningState = { if (active && session == attempt) isListening = it },
            onStatus = { if (active && session == attempt) statusText = it },
            onResult = { if (active && session == attempt) spokenText = it },
            setRecognizer = { speechRecognizer = it }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && active) beginListening()
        else if (active) statusText = "Microphone permission denied. Tap the microphone to retry, or type your query."
    }
    fun requestListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginListening()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { requestListening() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session++
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
                statusText = "Microphone paused. Tap the microphone to retry, or type your query."
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            active = false
            session++
            lifecycle.removeObserver(observer)
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    val pulseScale by rememberInfiniteTransition(label = "mic_pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "English Voice Search",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .testTag("voice_microphone_toggle")
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (isListening) "Stop recording" else "Start recording") {
                            if (isListening) {
                                speechRecognizer?.stopListening()
                                isListening = false
                                statusText = "Finishing speech… Tap the microphone to record again."
                            } else requestListening()
                        }
                        .scale(pulseScale)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = if (isListening) "Stop recording" else "Start recording",
                        tint = if (isListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = spokenText,
                    onValueChange = { spokenText = it },
                    label = { Text("Search Query") },
                    placeholder = { Text("e.g. Find chemistry grade from last month") },
                    modifier = Modifier.fillMaxWidth().testTag("voice_query_text_field")
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (spokenText.isNotBlank()) {
                                onQueryConfirmed(spokenText)
                                onDismiss()
                            }
                        },
                        enabled = spokenText.isNotBlank(),
                        modifier = Modifier.testTag("submit_voice_search_button")
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Search")
                    }
                }
            }
        }
    }
}

internal fun startListening(
    context: Context,
    onListeningState: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onResult: (String) -> Unit,
    setRecognizer: (SpeechRecognizer) -> Unit
) {
    onListeningState(false)
    var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
        onStatus("Speech recognition is not available on this device. Type your query below.")
        return
    }

    val recognizer = try { if (onDevice) {
        try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: Exception) {
            onDevice = false
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onStatus("Speech recognition is not available on this device. Type your query below.")
                return
            }
            onStatus("Using the default speech service (may use internet)")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    } else {
        onStatus("Using the default speech service (may use internet)")
        SpeechRecognizer.createSpeechRecognizer(context)
    }
    } catch (_: SecurityException) {
        onStatus("Microphone permission is unavailable. Tap the microphone to retry, or type your query.")
        return
    } catch (_: Exception) {
        onStatus("Speech service could not start. Tap the microphone to retry, or type your query.")
        return
    }
    setRecognizer(recognizer)

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onListeningState(true)
            onStatus(if (onDevice) "Listening on device..." else "Listening with default speech service (may use internet)...")
        }

        override fun onBeginningOfSpeech() {
            onStatus("Hearing speech...")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            onListeningState(false)
            onStatus("Processing speech...")
        }

        override fun onError(error: Int) {
            onListeningState(false)
            onStatus(speechRecognitionErrorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            onListeningState(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                onResult(matches[0])
                onStatus("Speech recognized. Edit your query or tap Search.")
            } else onStatus(speechRecognitionErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                onResult(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    try {
        onListeningState(true)
        onStatus(if (onDevice) "Starting on-device microphone…" else "Starting speech service (may use internet)…")
        recognizer.startListening(intent)
    } catch (_: SecurityException) {
        onListeningState(false)
        onStatus(speechRecognitionErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    } catch (_: Exception) {
        onListeningState(false)
        onStatus("Speech service could not start. Tap the microphone to retry, or type your query.")
    }
}

internal fun speechRecognitionErrorMessage(error: Int): String {
    val reason = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio could not be recorded."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network connection failed."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service is unavailable."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech recognition is unavailable on this device."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service received too many requests. Try again shortly."
        else -> "Speech recognition could not finish."
    }
    return "$reason Tap the microphone to retry, or type your query."
}
