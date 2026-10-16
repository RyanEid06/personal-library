package com.example.capture

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.example.model.SharePayload

object SharePayloadDecoder {
    fun decode(intent: Intent): SharePayload? {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        val candidates = linkedSetOf<Uri>()
        val clipText = mutableListOf<String>()
        val text = try { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() } catch (_: RuntimeException) { "" }
        try {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { candidates.addAll(it) }
            } else IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { candidates.add(it) }
            intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { candidates.add(it) }
                clip.getItemAt(index).text?.toString()?.let { clipText.add(it) }
            } }
        } catch (_: RuntimeException) { }
        val readable = candidates.filter { it.scheme.equals("content", true) && !it.authority.isNullOrBlank() }
        return SharePayload(text.ifEmpty { clipText.joinToString("\n") }.ifBlank { null }, readable.map { it.toString() }, intent.type, candidates.size - readable.size)
    }
}
