package com.shiina.mobile.character

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shiina.mobile.debug.AppDebugServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Push-to-talk microphone capture (RESEARCH_BACKLOG R2, BUILD_PLAN Phase 2a).
 *
 * Records 16 kHz mono PCM16 via [MediaRecorder.AudioSource.VOICE_RECOGNITION] — the
 * source Android recommends for speech that will be sent to a recognizer — and encodes
 * the raw frames into a standard RIFF/WAVE container with [encodeWavPcm16] so the clip
 * can be attached to a Gemini request as `inline_data` `audio/wav`.
 *
 * v1 is deliberately push-to-talk only: [start] / [stop] are called from the UI mic
 * button and the clip is hard-capped at [MAX_CLIP_MS] (30 s) to bound the ~1.9 MB/min
 * PCM payload (base64 inflates it a further ~33 %). Always-listening/wake-word is out
 * of scope.
 *
 * Stability: the capture thread is daemon-flagged, every native call is guarded, and
 * [release] is safe to call repeatedly from a lifecycle teardown.
 */
class VoiceInputEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val MAX_CLIP_MS = 30_000L

        /**
         * Pure PCM16 -> WAV encoder. Emits the canonical 44-byte RIFF/WAVE header
         * (RIFF, WAVE, fmt, data) followed by the little-endian PCM payload.
         * No Android APIs are touched, so this is unit-testable on the JVM.
         */
        fun encodeWavPcm16(
            pcm: ByteArray,
            sampleRate: Int = SAMPLE_RATE,
            channels: Int = CHANNELS,
        ): ByteArray {
            val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
            val blockAlign = channels * BITS_PER_SAMPLE / 8
            val dataLen = pcm.size
            val out = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray(Charsets.US_ASCII))
            out.putInt(36 + dataLen)
            out.put("WAVE".toByteArray(Charsets.US_ASCII))
            out.put("fmt ".toByteArray(Charsets.US_ASCII))
            out.putInt(16)                                  // PCM fmt chunk size
            out.putShort(1)                                 // audioFormat = PCM
            out.putShort(channels.toShort())
            out.putInt(sampleRate)
            out.putInt(byteRate)
            out.putShort(blockAlign.toShort())
            out.putShort(BITS_PER_SAMPLE.toShort())
            out.put("data".toByteArray(Charsets.US_ASCII))
            out.putInt(dataLen)
            out.put(pcm)
            return out.array()
        }
    }

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var recording = false
    private val buffer = ByteArrayOutputStream()

    /** True while the microphone is actively capturing. */
    val isRecording: Boolean get() = recording

    /**
     * Opens the recorder and begins capturing on a background thread.
     * Returns false (never throws) if the device cannot provide a usable input buffer.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(0)
        if (minBuffer <= 0) {
            AppDebugServer.log("VOICE_INPUT", "start failed: min buffer size unavailable ($minBuffer)")
            return false
        }

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        } catch (e: Exception) {
            AppDebugServer.log("VOICE_INPUT", "start failed: ${e::class.simpleName}: ${e.message}")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            AppDebugServer.log("VOICE_INPUT", "start failed: recorder not initialized")
            return false
        }

        synchronized(this) { buffer.reset() }
        recording = true
        record = rec
        runCatching { rec.startRecording() }.onFailure {
            AppDebugServer.log("VOICE_INPUT", "startRecording failed: ${it.message}")
            recording = false
            runCatching { rec.release() }
            record = null
            return false
        }

        val startedAt = System.currentTimeMillis()
        captureThread = Thread {
            val chunk = ByteArray(4096)
            try {
                while (recording) {
                    val read = rec.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        synchronized(this) { buffer.write(chunk, 0, read) }
                    } else if (read < 0) {
                        break
                    }
                    if (System.currentTimeMillis() - startedAt >= MAX_CLIP_MS) {
                        // Hard 30 s cap: close the clip without an extra UI round-trip.
                        recording = false
                    }
                }
            } catch (e: Exception) {
                AppDebugServer.log("VOICE_INPUT", "capture thread ended: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }

        AppDebugServer.log("VOICE_INPUT", "Recording started (16 kHz mono PCM16, cap ${MAX_CLIP_MS / 1000}s)")
        return true
    }

    /**
     * Stops capture and writes the buffered PCM to a WAV file in the app cache.
     * Returns null when nothing (or nothing usable) was captured.
     */
    fun stop(): File? {
        val rec = record
        if (!recording && rec == null) return null
        recording = false
        runCatching { rec?.stop() }
        runCatching { captureThread?.join(1500L) }
        runCatching { rec?.release() }
        record = null
        captureThread = null

        val pcm = synchronized(this) { buffer.toByteArray() }
        if (pcm.isEmpty()) {
            AppDebugServer.log("VOICE_INPUT", "stop: no audio captured")
            return null
        }
        return runCatching {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
            file.writeBytes(encodeWavPcm16(pcm))
            AppDebugServer.log(
                "VOICE_INPUT",
                "Clip saved: ${file.name} (${pcm.size / 1024}KB PCM, ${pcm.size / 2} samples, " +
                    "${"%.1f".format(pcm.size / 2.0 / SAMPLE_RATE)}s)",
            )
            file
        }.onFailure {
            AppDebugServer.log("VOICE_INPUT", "stop: failed to save clip: ${it.message}")
        }.getOrNull()
    }

    /** Full teardown for a composable/lifecycle end. Safe to call repeatedly. */
    fun release() {
        runCatching {
            if (recording || record != null) stop()
            synchronized(this) { buffer.reset() }
        }
        record = null
        captureThread = null
        recording = false
    }
}
