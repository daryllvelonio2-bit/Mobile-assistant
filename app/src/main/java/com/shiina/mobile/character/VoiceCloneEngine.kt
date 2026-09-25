package com.shiina.mobile.character

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.shiina.mobile.debug.AppDebugServer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Zero-shot voice cloning via sherpa-onnx + ZipVoice distill-int8 (Backlog B5-clone).
 *
 * Fully offline: model files live in app-private storage (filesDir/zipvoice),
 * reference voice in filesDir/clone/ref.wav + ref.txt — all pushed once via adb.
 * Until all pieces are present, [ShiinaVoiceSpeaker] falls back to system TTS.
 */
class VoiceCloneEngine(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val tts = AtomicReference<OfflineTts?>(null)
    private val ready = AtomicBoolean(false)

    @Volatile private var activeTrack: AudioTrack? = null

    fun isReady(): Boolean = ready.get()

    fun ensureLoaded(): Boolean {
        if (ready.get()) return true
        synchronized(this) {
            if (ready.get()) return true
            if (!isClonePresent(context)) {
                AppDebugServer.log("VOICE_CLONE", "Clone files missing — system TTS fallback")
                return false
            }
            return try {
                val dir = modelDir(context)
                val pocket = OfflineTtsPocketModelConfig(
                    lmFlow = File(dir, "lm_flow.int8.onnx").absolutePath,
                    lmMain = File(dir, "lm_main.int8.onnx").absolutePath,
                    encoder = File(dir, "encoder.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    textConditioner = File(dir, "text_conditioner.onnx").absolutePath,
                    vocabJson = File(dir, "vocab.json").absolutePath,
                    tokenScoresJson = File(dir, "token_scores.json").absolutePath,
                )
                val modelCfg = OfflineTtsModelConfig(pocket = pocket)
                val config = OfflineTtsConfig(
                    model = modelCfg,
                    maxNumSentences = 20,
                )
                tts.set(OfflineTts(null, config))
                ready.set(true)
                AppDebugServer.log("VOICE_CLONE", "Clone engine loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                AppDebugServer.log("VOICE_CLONE", "Native lib missing: ${e.message}")
                false
            } catch (e: Exception) {
                AppDebugServer.log("VOICE_CLONE", "Init failed: ${e.message}")
                false
            }
        }
    }

    fun prewarm() {
        executor.submit {
            if (isClonePresent(context)) ensureLoaded()
        }
    }

    /** Synthesizes [text] in the cloned voice, flushing in-progress speech. */
    fun speak(text: String, speed: Float = 1.0f) {
        if (text.isBlank()) return
        executor.submit {
            val engine = if (ensureLoaded()) tts.get() else null
            val ref = loadReference()
            if (engine == null || ref == null) return@submit
            try {
                stopPlayback()
                stopFlag = false
                // Sentence-chunked streaming: first sentence plays as soon as it's
                // rendered instead of waiting for the whole reply (~4x faster to first sound).
                val chunks = splitSentences(text)
                var totalSamples = 0
                var totalSynthMs = 0L
                var rate = 24000
                for (chunk in chunks) {
                    if (stopRequested()) break
                    val gen = GenerationConfig()
                    gen.speed = speed
                    gen.sid = 0
                    gen.referenceAudio = ref.samples
                    gen.referenceSampleRate = ref.sampleRate
                    gen.numSteps = 2
                    val t0 = System.currentTimeMillis()
                    val audio = engine.generateWithConfig(chunk, gen)
                    totalSynthMs += System.currentTimeMillis() - t0
                    rate = audio.sampleRate
                    totalSamples += audio.samples.size
                    playPcm16(audio.samples, audio.sampleRate)
                }
                AppDebugServer.log("VOICE_CLONE", "Spoke $totalSamples samples @${rate}Hz (synth ${totalSynthMs}ms, ${chunks.size} chunks)")
            } catch (e: Exception) {
                AppDebugServer.log("VOICE_CLONE", "Synthesis failed: ${e.message}")
            }
        }
    }

    private data class Reference(val samples: FloatArray, val sampleRate: Int)

    @Volatile private var stopFlag = false

    private fun stopRequested(): Boolean = stopFlag

    /** Splits a reply into speakable sentences (keeps delimiters, caps chunk length). */
    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?…])\\s+|\\n+|(?<=,)\\s+(?=[A-Z0-9])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            // No sentence breaks: fall back to clause/phrase splitting so long
            // single-block replies still stream instead of rendering all at once.
            val clauses = text.split(Regex("(?<=,)\\s+|\\s+—\\s+|;\\s+"))
                .map { it.trim() }
                .filter { it.length > 3 }
            if (clauses.size <= 1) return listOf(text)
            return packChunks(clauses)
        }
        return packChunks(parts)
    }

    private fun packChunks(parts: List<String>): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (p in parts) {
            if (cur.length + p.length > 220 && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun loadReference(): Reference? {
        return try {
            // PocketTTS needs only the voice sample — no transcript required.
            val (samples, rate) = readWavMono16(File(cloneDir(context), "ref.wav")) ?: return null
            Reference(samples, rate)
        } catch (e: Exception) {
            AppDebugServer.log("VOICE_CLONE", "Reference load failed: ${e.message}")
            null
        }
    }

    private fun playPcm16(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        activeTrack = track
        track.play()
        var offset = 0
        while (offset < pcm.size) {
            val trackRef = activeTrack ?: break
            if (trackRef != track) break
            val written = track.write(pcm, offset, pcm.size - offset)
            if (written <= 0) break
            offset += written
        }
        track.stop()
        track.release()
        if (activeTrack == track) activeTrack = null
    }

    private fun stopPlayback() {
        runCatching {
            stopFlag = true
            activeTrack?.stop()
            activeTrack?.release()
            activeTrack = null
        }
    }

    fun stop() {
        executor.submit { stopPlayback() }
    }

    fun release() {
        runCatching {
            stopPlayback()
            executor.shutdownNow()
            tts.getAndSet(null)?.release()
            ready.set(false)
        }
    }

    companion object {
        const val MODEL_DIR_NAME = "pocket"
        const val CLONE_DIR_NAME = "clone"

        fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)
        fun cloneDir(context: Context): File = File(context.filesDir, CLONE_DIR_NAME)

        fun isClonePresent(context: Context): Boolean {
            val dir = runCatching { modelDir(context) }.getOrNull() ?: return false
            if (!dir.isDirectory) return false
            if (!listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json")
                    .all { File(dir, it).isFile }) return false
            val cdir = runCatching { cloneDir(context) }.getOrNull() ?: return false
            return File(cdir, "ref.wav").isFile
        }

        /** Reads a 16-bit PCM WAV (any channels → mono mix, any rate kept). */
        fun readWavMono16(file: File): Pair<FloatArray, Int>? {
            return try {
                val bytes = file.readBytes()
                if (bytes.size < 44 || bytes[0] != 'R'.code.toByte()) return null
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val channels = buf.getShort(22).toInt()
                val sampleRate = buf.getInt(24)
                // find "data" chunk
                var dataPos = 36
                while (dataPos + 8 <= bytes.size) {
                    val id = String(bytes, dataPos, 4, Charsets.US_ASCII)
                    val size = buf.getInt(dataPos + 4)
                    if (id == "data") {
                        val start = dataPos + 8
                        val frames = size / 2 / channels
                        val out = FloatArray(frames)
                        for (i in 0 until frames) {
                            var acc = 0
                            for (c in 0 until channels) {
                                acc += buf.getShort(start + (i * channels + c) * 2).toInt()
                            }
                            out[i] = (acc / channels) / 32768f
                        }
                        return Pair(out, sampleRate)
                    }
                    dataPos += 8 + size
                }
                null
            } catch (e: Exception) {
                null
            }
        }

        /** Clone speed multiplier from mood — mirrors ShiinaVoiceSpeaker acoustics. */
        fun speedFor(mood: String, isLateNight: Boolean): Float = when {
            isLateNight -> 0.92f
            mood.equals("pouty", ignoreCase = true) || mood.equals("sulky", ignoreCase = true) -> 0.95f
            mood.equals("melancholy", ignoreCase = true) -> 0.9f
            mood.equals("warm", ignoreCase = true) || mood.equals("excited", ignoreCase = true) -> 1.05f
            else -> 1.0f
        }
    }
}
