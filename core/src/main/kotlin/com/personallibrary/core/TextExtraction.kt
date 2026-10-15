package com.personallibrary.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class ExtractedText(val text: String, val coverage: String)

object TextExtraction {
    const val MAX_BYTES = 1024 * 1024

    fun read(source: InputStream, maxBytes: Int = MAX_BYTES): ExtractedText = source.use { input ->
        require(maxBytes in 1..MAX_BYTES) { "Use a supported extraction budget" }
        val bytes = ByteArrayOutputStream(minOf(maxBytes + 1, 64 * 1024))
        val buffer = ByteArray(minOf(maxBytes + 1, 64 * 1024))
        while (bytes.size() <= maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - bytes.size()))
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                bytes.write(single)
            } else bytes.write(buffer, 0, count)
        }
        val raw = bytes.toByteArray()
        val partial = raw.size > maxBytes
        val length = minOf(raw.size, maxBytes)
        val charset = when {
            length >= 2 && raw[0] == 0xff.toByte() && raw[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            length >= 2 && raw[0] == 0xfe.toByte() && raw[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val skip = when {
            charset != Charsets.UTF_8 -> 2
            length >= 3 && raw[0] == 0xef.toByte() && raw[1] == 0xbb.toByte() && raw[2] == 0xbf.toByte() -> 3
            else -> 0
        }
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = CharBuffer.allocate(length)
        val result = decoder.decode(ByteBuffer.wrap(raw, skip, length - skip), decoded, !partial)
        if (result.isError) return@use ExtractedText("", "unsupported")
        if (!partial && decoder.flush(decoded).isError) return@use ExtractedText("", "unsupported")
        decoded.flip()
        val text = decoded.toString()
        if ('\u0000' in text) ExtractedText("", "unsupported")
        else ExtractedText(text, if (partial) "partial" else "complete")
    }
}
