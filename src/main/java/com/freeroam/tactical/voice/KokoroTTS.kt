package com.freeroam.tactical.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.freeroam.tactical.media.MediaSessionManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KokoroTTS - On-device Text-to-Speech backed by sherpa-onnx.
 *
 * Runs the official quantized kokoro-en-v0_19 model (134MB int8, 11 English
 * voices, fixed 24kHz output): espeak-ng phonemization -> phoneme token IDs
 * -> int8 inference -> float samples -> 16-bit PCM. The sherpa-onnx native
 * runtime replaces the old hand-rolled ONNX code, which fed the graph an
 * incomplete input map (missing style/speed), "tokenized" text as character
 * codes instead of the 178-entry phoneme vocab, and misread the output tensor
 * - so synthesis silently returned empty PCM and no audio ever played.
 *
 * Emits amplitude values for avatar visualization during playback.
 */
class KokoroTTS(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTTS"
        const val SAMPLE_RATE = 24000 // Fixed for kokoro-en-v0_19

        private const val MODEL_FILE = "kokoro.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VOICES_FILE = "voices.bin"
        private const val ESPEAK_ASSET_DIR = "espeak-ng-data"
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 50L

        // Exact byte size of the shipped fp32 asset. A filesDir copy that does
        // not match is stale (e.g. the earlier 134MB / defective 86MB builds)
        // and gets replaced from assets on init.
        private const val EXPECTED_MODEL_BYTES = 345_555_491L
        private const val MIN_MODEL_SIZE_BYTES = 50L * 1024L * 1024L

        // Speaker IDs for kokoro-en-v0_19 voices.bin (11 voices, sid 0-10).
        // "af_emma" is not a v0.19 voice; the app spinner entry maps to
        // bf_emma, the only Emma in the voicepack.
        private val VOICE_SIDS = mapOf(
            "af_heart" to 0, "af_bella" to 1, "af_nicole" to 2, "af_sarah" to 3,
            "af_sky" to 4, "am_adam" to 5, "am_michael" to 6, "bf_emma" to 7,
            "bf_isabella" to 8, "bm_george" to 9, "bm_lewis" to 10,
            "af_emma" to 7,
        )
        private const val VOICE_DEFAULT_SID = 7 // bf_emma ("af_emma" spinner alias)
    }

    // Guards ALL access to the native engine handle. synthesize() holds it
    // across the C++ generate() call; release() holds it while freeing. A
    // teardown that lands mid-synthesis therefore parks until the running
    // generation completes instead of deleting the engine under native code
    // (which aborts with "destroyed mutex" in KokoroTTS.synthesize).
    private val engineLock = Any()

    @Volatile
    private var tts: OfflineTts? = null
    @Volatile
    private var isInitialized = false

    @Volatile
    private var currentSid: Int = VOICE_DEFAULT_SID
    @Volatile
    private var currentSpeed: Float = 1.0f

    // Amplitude tracking for avatar visualization
    @Volatile
    private var currentAmplitude: Float = 0f
    private var amplitudeCallback: ((Float) -> Unit)? = null

    // AudioTrack reference for external control (pause/stop from callers)
    @Volatile
    private var currentAudioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying: Boolean = false

    init {
        initializeEngine()
    }

    /**
     * Copy the model/voices/tokens assets into filesDir (espeak-ng needs real
     * files on disk) and construct the sherpa-onnx OfflineTts with absolute
     * paths. Any Throwable (including native Errors) leaves the engine null
     * so the app degrades gracefully to text-only chat.
     */
    private fun initializeEngine() {
        try {
            val modelFile = ensureModelFile()
            val tokensFile = ensureFileFromAssets(TOKENS_FILE)
            val voicesFile = ensureFileFromAssets(VOICES_FILE)
            val espeakDir = ensureEspeakDataDir()

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = modelFile.absolutePath,
                        voices = voicesFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = espeakDir.absolutePath,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
            )
            val engine = OfflineTts(config = config) // assetManager=null -> newFromFile
            tts = engine
            isInitialized = true
            Log.i(
                TAG,
                "Kokoro engine ready: rate=${engine.sampleRate()} " +
                    "speakers=${engine.numSpeakers()}"
            )
        } catch (t: Throwable) {
            // Throwable, NOT Exception: native failures surface as Errors
            // (UnsatisfiedLinkError, OutOfMemoryError) and must be absorbed so
            // the app survives a TTS failure and remains usable for text chat.
            Log.e(TAG, "Failed to initialize Kokoro TTS: ${t.message}", t)
            tts = null
            isInitialized = false
        }
    }

    /** Copy the model from assets, replacing any stale/corrupt filesDir copy. */
    private fun ensureModelFile(): File {
        val modelFile = File(context.filesDir, MODEL_FILE)
        val stale = !modelFile.exists() ||
            modelFile.length() != EXPECTED_MODEL_BYTES ||
            !isValidModelFile(modelFile)
        if (stale) {
            if (modelFile.exists()) {
                Log.w(
                    TAG,
                    "Stale/corrupt model in filesDir (${modelFile.length()} " +
                        "bytes) - replacing from assets"
                )
                modelFile.delete()
            }
            copyAsset(MODEL_FILE, modelFile)
        }
        return modelFile
    }

    /** Copy a single asset file into filesDir if missing or empty. */
    private fun ensureFileFromAssets(name: String): File {
        val outFile = File(context.filesDir, name)
        if (!outFile.exists() || outFile.length() == 0L) {
            copyAsset(name, outFile)
        }
        return outFile
    }

    /**
     * Recursively copy the espeak-ng-data tree from assets to filesDir once.
     * A version marker file avoids re-copying ~355 files on every launch; a
     * marker only counts when the directory actually exists beside it.
     */
    private fun ensureEspeakDataDir(): File {
        val dir = File(context.filesDir, ESPEAK_ASSET_DIR)
        val marker = File(context.filesDir, ".espeak_data_v1")
        val copied = marker.exists() && dir.isDirectory
        if (!copied) {
            val t0 = System.currentTimeMillis()
            copyAssetDir(ESPEAK_ASSET_DIR, dir)
            marker.createNewFile()
            val count = dir.walkTopDown().filter { it.isFile }.count()
            Log.i(
                TAG,
                "Copied espeak-ng-data ($count files) in " +
                    "${System.currentTimeMillis() - t0}ms"
            )
        }
        return dir
    }

    private fun copyAsset(assetPath: String, outFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { input.copyTo(it) }
        }
        Log.d(TAG, "Copied $assetPath (${outFile.length()} bytes)")
    }

    /** Recursively mirror an asset directory. A leaf (no children) is a file. */
    private fun copyAssetDir(assetPath: String, outDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            copyAsset(assetPath, outDir)
        } else {
            outDir.mkdirs()
            for (child in children) {
                copyAssetDir("$assetPath/$child", File(outDir, child))
            }
        }
    }

    /**
     * Sanity-validate an ONNX model file before handing it to native code.
     * Real ONNX protobuf files begin with field tag 0x08 (ir_version varint);
     * a corrupt blob can SIGSEGV inside the native parser beyond any catch.
     */
    private fun isValidModelFile(file: File): Boolean {
        if (file.length() < MIN_MODEL_SIZE_BYTES) return false
        return try {
            file.inputStream().use { input -> input.read() == 0x08 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Synthesize speech from text via sherpa-onnx (espeak phonemization +
     * int8 inference). Returns 16-bit PCM mono at SAMPLE_RATE (24kHz), or an
     * empty array when the engine is unavailable or synthesis fails.
     */
    fun synthesize(text: String): ByteArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return byteArrayOf()
        // The generate() call runs INSIDE engineLock: release() takes the same
        // lock, so teardown cannot free the C++ object while it is in use, and
        // a synthesize() racing a finished teardown sees tts == null and bails.
        return try {
            val audio = synchronized(engineLock) {
                val engine = tts
                if (engine == null || !isInitialized) {
                    Log.e(TAG, "Engine not initialized")
                    null
                } else {
                    engine.generate(text = trimmed, sid = currentSid, speed = currentSpeed)
                }
            }
            if (audio == null) return byteArrayOf()
            if (audio.samples.isEmpty()) {
                Log.w(TAG, "Kokoro produced no audio (text length ${trimmed.length})")
                return byteArrayOf()
            }
            if (audio.sampleRate != SAMPLE_RATE) {
                Log.w(TAG, "Unexpected sample rate ${audio.sampleRate} (expected $SAMPLE_RATE)")
            }
            floatToPcm(audio.samples)
        } catch (t: Throwable) {
            Log.e(TAG, "Synthesis failed: ${t.message}")
            byteArrayOf()
        }
    }

    /**
     * Convert float audio samples to PCM bytes
     */
    private fun floatToPcm(floatSamples: FloatArray): ByteArray {
        // CRITICAL: Android AudioTrack expects native little-endian PCM16.
        // ByteBuffer defaults to BIG_ENDIAN; without nativeOrder() every
        // 16-bit sample gets byte-swapped -> static noise following the
        // speech envelope instead of intelligible voice.
        val byteBuffer = ByteBuffer.allocate(floatSamples.size * 2)
            .order(ByteOrder.nativeOrder())
        for (sample in floatSamples) {
            val shortValue = (sample * Short.MAX_VALUE).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()
            byteBuffer.putShort(shortValue)
        }
        return byteBuffer.array()
    }

    /**
     * Set callback for amplitude updates (called during playback)
     */
    fun setAmplitudeCallback(callback: (Float) -> Unit) {
        this.amplitudeCallback = callback
    }

    /**
     * Get current amplitude value (for polling)
     */
    fun getCurrentAmplitude(): Float = currentAmplitude

    /**
     * Check if currently playing audio
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying

    /**
     * Pause current audio playback
     */
    fun pause() {
        currentAudioTrack?.pause()
        isPlaying = false
        Log.d(TAG, "KokoroTTS: Audio paused")
    }

    /**
     * Resume paused audio playback
     */
    fun resume() {
        currentAudioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                track.play()
                isPlaying = true
                Log.d(TAG, "KokoroTTS: Audio resumed")
            }
        }
    }

    /**
     * Stop current audio playback. Only stops the track - playAudio() owns
     * the release: releasing from both threads double-frees the native track
     * while the playback thread is still inside write()/head polling.
     */
    fun stop() {
        currentAudioTrack?.let { track ->
            try {
                track.stop()
                currentAudioTrack = null
                isPlaying = false
                Log.d(TAG, "KokoroTTS: Audio stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio: ${e.message}")
            }
        }
    }

    /**
     * Play synthesized audio through speaker with amplitude tracking.
     * USAGE_MEDIA (not VOICE_COMMUNICATION) so speech routes to the media
     * stream - call audio routing is muted or earpiece-only on many devices.
     */
    fun playAudio(pcmData: ByteArray, onComplete: () -> Unit = {}) {
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcmData.size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Store reference for external control
        currentAudioTrack = audioTrack
        isPlaying = true

        audioTrack.play()
        audioTrack.write(pcmData, 0, pcmData.size)

        // Start amplitude tracking thread
        val amplitudeThread = Thread {
            try {
                while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val position = audioTrack.playbackHeadPosition
                    val frameSize = 2 // 16-bit mono = 2 bytes per frame
                    val startIdx = (position * frameSize).toInt()
                    val endIdx = ((position + 10) * frameSize).toInt()

                    // Calculate RMS amplitude from current position
                    var sumSquared = 0f
                    var count = 0
                    for (idx in startIdx until endIdx step 2) {
                        if (idx + 1 < pcmData.size) {
                            val sample = ((pcmData[idx].toInt() and 0xFF) or (pcmData[idx + 1].toInt() shl 8)).toShort()
                            val normalized = sample.toFloat() / Short.MAX_VALUE
                            sumSquared += normalized * normalized
                            count++
                        }
                    }
                    currentAmplitude = if (count > 0) {
                        kotlin.math.sqrt(sumSquared / count)
                    } else {
                        0f
                    }

                    amplitudeCallback?.invoke(currentAmplitude)

                    Thread.sleep(AMPLITUDE_UPDATE_INTERVAL_MS)
                }
                // Playback complete - emit final zero amplitude
                currentAmplitude = 0f
                amplitudeCallback?.invoke(0f)
            } catch (e: Exception) {
                Log.e(TAG, "Amplitude tracking error: ${e.message}")
            }
        }
        amplitudeThread.start()

        // Wait for playback to complete. A streaming AudioTrack stays in
        // PLAYSTATE_PLAYING after it has consumed all written data (underrun),
        // so waiting on the state alone can spin forever. Instead, wait for
        // the playback head to reach the final frame.
        val totalFrames = pcmData.size / 2 // 16-bit mono = 2 bytes per frame
        while (audioTrack.playbackHeadPosition < totalFrames &&
            audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
        ) {
            Thread.sleep(100)
        }

        // Stop deterministically ends PLAYING (underruns leave it stuck on),
        // so the amplitude thread's play-state check terminates cleanly.
        audioTrack.stop()
        audioTrack.release()
        currentAudioTrack = null
        isPlaying = false
        amplitudeThread.join()
        onComplete()
    }

    /**
     * Release native engine resources. Takes the engine lock first: while a
     * synthesis is inside native generate(), this waits for it to return so
     * the C++ object is never deleted beneath a running call.
     */
    fun release() {
        synchronized(engineLock) {
            try {
                tts?.release()
                tts = null
                isInitialized = false
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources: ${e.message}")
            }
        }
    }

    /**
     * Convenience method: Synthesize and play text
     * Also updates MediaSession metadata for Garmin watch display
     */
    fun speak(text: String) {
        try {
            val pcmData = synthesize(text)
            if (pcmData.isNotEmpty()) {
                playAudio(pcmData)
            }

            // Update Garmin watch metadata for AI voice responses
            MediaSessionManager.getInstance(context).updatePolarisMetadata(text, "AI")
        } catch (e: Exception) {
            Log.e(TAG, "Speak failed: ${e.message}")
        }
    }

    /**
     * Shutdown TTS engine (native + any active AudioTrack)
     */
    fun shutdown() {
        stop()
        release()
    }

    /**
     * Select the speaker by voice name (kokoro-en-v0_19 voicepack, sid 0-10).
     * Unknown names fall back to bf_emma (sid 7) - v3.4, matching the
     * gateway's af_emma default and the spinner's first entry.
     */
    fun setVoice(voiceId: String) {
        currentSid = VOICE_SIDS[voiceId.lowercase()] ?: VOICE_DEFAULT_SID
        Log.d(TAG, "Voice set to: $voiceId (sid=$currentSid)")
    }

    /**
     * Set playback speed (applied at synthesis time by the engine)
     */
    fun setSpeed(speed: Float) {
        currentSpeed = speed
        Log.d(TAG, "Speed set to: ${speed}x")
    }
}
