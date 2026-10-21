package com.example.indexing

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

data class BoundedAudio(val bytes: ByteArray, val partial: Boolean)

/** Decode supported platform audio formats and encode only the first five minutes as bounded AAC. */
object BoundedAudioExtractor {
    const val MAX_DURATION_US = 300_000_000L
    const val MAX_BYTES = 1_350_000L

    suspend fun extract(source: File, cacheDirectory: File): BoundedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val output = File.createTempFile("audio-analysis-", ".m4a", cacheDirectory)
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track")
            val inputFormat = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val originUs = extractor.sampleTime.coerceAtLeast(0L)
            var partial = inputFormat.containsKey(MediaFormat.KEY_DURATION) && inputFormat.getLong(MediaFormat.KEY_DURATION) > MAX_DURATION_US
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val dec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inputFormat, null, null, 0)
            dec.start()
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val info = MediaCodec.BufferInfo()
            val encodedInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var decoderEnded = false
            var encoderEnded = false
            var encoderEosSent = false
            var pending: ByteBuffer? = null
            var pendingIndex = -1
            var pendingIsLast = false
            var frames = 0L
            var channels = 0
            var sampleRate = 0
            var muxerTrack = -1
            var encodedBytes = 0L
            val deadline = SystemClock.elapsedRealtime() + 120_000L

            while (!encoderEnded) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "Audio conversion exceeded time budget" }
                if (!inputEnded) {
                    val index = dec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val time = extractor.sampleTime
                        if (time < 0 || time - originUs >= MAX_DURATION_US) {
                            if (time >= 0) partial = true
                            dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val buffer = dec.getInputBuffer(index)!!
                            buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) {
                                dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                dec.queueInputBuffer(index, 0, count, time - originUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                if (pending == null && !decoderEnded) {
                    val index = dec.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(encoder == null) { "Unsupported changing audio format" }
                        val pcm = dec.outputFormat
                        sampleRate = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(channels in 1..2 && sampleRate in 8000..96000)
                        check(!pcm.containsKey(MediaFormat.KEY_PCM_ENCODING) || pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                        val aac = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                        }
                        encoder = MediaCodec.createEncoderByType("audio/mp4a-latm").also {
                            it.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            it.start()
                        }
                    } else if (index >= 0) {
                        pendingIndex = index
                        pendingIsLast = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        pending = dec.getOutputBuffer(index)!!.apply {
                            position(info.offset)
                            val allowedFrames = ((MAX_DURATION_US * sampleRate / 1_000_000L) - frames).coerceAtLeast(0)
                            val allowedBytes = minOf(info.size.toLong(), allowedFrames * channels * 2).toInt()
                            if (allowedBytes < info.size) partial = true
                            limit(info.offset + allowedBytes)
                        }
                    }
                }
                val enc = encoder
                if (enc != null) {
                    val pcm = pending
                    if ((pcm != null || decoderEnded) && !encoderEosSent) {
                        val index = enc.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = enc.getInputBuffer(index)!!
                            input.clear()
                            val count = minOf(input.remaining(), pcm?.remaining() ?: 0) / (channels * 2) * (channels * 2)
                            if (count > 0 && pcm != null) {
                                val slice = pcm.duplicate().apply { limit(position() + count) }
                                input.put(slice)
                                pcm.position(pcm.position() + count)
                            }
                            val finished = (pcm == null || !pcm.hasRemaining()) && (decoderEnded || pendingIsLast)
                            val timeUs = frames * 1_000_000L / sampleRate
                            frames += count / (channels * 2)
                            enc.queueInputBuffer(index, 0, count, timeUs, if (finished) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (finished) encoderEosSent = true
                            if (pcm != null && !pcm.hasRemaining()) {
                                dec.releaseOutputBuffer(pendingIndex, false)
                                pending = null
                                decoderEnded = pendingIsLast
                            }
                        }
                    }
                    var index = enc.dequeueOutputBuffer(encodedInfo, 10_000)
                    while (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted)
                            muxerTrack = writer.addTrack(enc.outputFormat)
                            writer.start()
                            muxerStarted = true
                        } else {
                            // AAC encoders can emit padding packets after the bounded PCM ends.
                            if (encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encodedInfo.size > 0 &&
                                encodedInfo.presentationTimeUs < MAX_DURATION_US) {
                                check(muxerStarted)
                                encodedBytes += encodedInfo.size
                                check(encodedBytes <= MAX_BYTES && output.length() <= MAX_BYTES) { "Audio exceeds upload budget" }
                                writer.writeSampleData(muxerTrack, enc.getOutputBuffer(index)!!, encodedInfo)
                            }
                            encoderEnded = encodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            enc.releaseOutputBuffer(index, false)
                        }
                        index = enc.dequeueOutputBuffer(encodedInfo, 0)
                    }
                } else if (decoderEnded) error("No decoded audio")
            }
            check(muxerStarted && frames > 0)
            writer.stop()
            muxerStarted = false
            check(output.length() in 1..MAX_BYTES)
            return BoundedAudio(output.readBytes(), partial)
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { encoder?.stop() } catch (_: Exception) { }
            try { encoder?.release() } catch (_: Exception) { }
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            extractor.release()
            output.delete()
        }
    }
}
