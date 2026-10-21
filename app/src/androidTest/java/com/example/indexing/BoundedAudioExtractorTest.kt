package com.example.indexing

import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BoundedAudioExtractorTest {
    private val directory: File get() = ApplicationProvider.getApplicationContext<Context>().cacheDir
    private fun wav(seconds: Int): File {
        val rate = 16000
        val size = rate * seconds * 2
        val file = File.createTempFile("synthetic-", ".wav", directory)
        file.outputStream().use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size)
            out.write(header.array())
            val second = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(rate) { sample -> second.putShort((kotlin.math.sin(sample * 2.0 * Math.PI * 440 / rate) * 10000).toInt().toShort()) }
            repeat(seconds) { out.write(second.array()) }
        }
        return file
    }
    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun duration(file: File): Long = MediaMetadataRetriever().use {
        it.setDataSource(file.absolutePath)
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
    }
    private fun encodedSampleSummary(file: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            val sampleRate = extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var samples = 0
            val firstUs = extractor.sampleTime
            var lastUs = firstUs
            while (extractor.sampleTime >= 0) {
                lastUs = extractor.sampleTime
                samples++
                extractor.advance()
            }
            return "AAC packets=$samples, sampleRate=$sampleRate, firstPtsUs=$firstUs, lastPtsUs=$lastUs"
        } finally { extractor.release() }
    }

    @Test fun shortWavAndAacProducePlayableBoundedAudioWithoutChangingOriginal() = runBlocking {
        val source = wav(2)
        val before = hash(source)
        val converted = File.createTempFile("converted-", ".m4a", directory)
        try {
            val result = BoundedAudioExtractor.extract(source, directory)
            assertFalse(result.partial)
            converted.writeBytes(result.bytes)
            assertTrue(duration(converted) in 1900..2200)
            val aac = BoundedAudioExtractor.extract(converted, directory)
            assertFalse(aac.partial)
            assertTrue(aac.bytes.isNotEmpty())
            assertArrayEquals(before, hash(source))
        } finally { source.delete(); converted.delete() }
    }

    @Test fun longerRecordingStopsAtFiveMinutesAndReportsPartial() = runBlocking {
        val source = wav(305)
        val before = hash(source)
        val converted = File.createTempFile("five-minutes-", ".m4a", directory)
        try {
            val result = BoundedAudioExtractor.extract(source, directory)
            assertTrue(result.partial)
            assertTrue(result.bytes.size <= BoundedAudioExtractor.MAX_BYTES)
            converted.writeBytes(result.bytes)
            val convertedDuration = duration(converted)
            assertTrue("Expected 299000..300100ms; sourceMs=${duration(source)}, convertedMs=$convertedDuration; " +
                encodedSampleSummary(converted), convertedDuration in 299000..300100)
            assertArrayEquals(before, hash(source))
        } finally { source.delete(); converted.delete() }
    }

    @Test fun corruptAudioFailsWithoutPublishingAnUploadOrLeavingConversionFiles() = runBlocking {
        val source = File.createTempFile("invalid-", ".mp3", directory).apply { writeText("not audio") }
        val before = directory.listFiles().orEmpty().filter { it.name.startsWith("audio-analysis-") }.map { it.name }.toSet()
        try {
            try { BoundedAudioExtractor.extract(source, directory); fail("Invalid audio must fail") } catch (_: Exception) { }
            assertEquals(before, directory.listFiles().orEmpty().filter { it.name.startsWith("audio-analysis-") }.map { it.name }.toSet())
        } finally { source.delete() }
    }
}
