package com.freeroam.tactical.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * StreamingTtsPlayer - the v3.5 speak-while-she-writes pipeline for Polaris
 * replies.
 *
 * The gateway already streams her responses as sentence-safe text deltas;
 * this player turns that text into continuous speech: every completed
 * sentence is synthesized on the shared Kokoro engine (via the caller's
 * single-thread TTS executor) while earlier sentences are already audible
 * through one persistent 24 kHz mono AudioTrack. Synthesis and playback run
 * as a pipeline - the first sentence is heard seconds after it exists, and
 * AudioTrack's blocking write provides natural backpressure so the queue can
 * never outrun the speaker.
 *
 * When the stream ends and the last chunk has drained, the FULL spoken text is
 * handed back via [onFinished] (v3.6 text-only: no audio artifacts exist -
 * bubble replay re-synthesizes from text through [speakText]). Abort paths
 * (mic capture, watch STOP, supersession, teardown) halt speech instantly
 * and discard the session.
 *
 * Lifecycle: every public method must be called on the main thread (the WS
 * listener hops there); synthesis runs on the injected executor; playback
 * runs on a per-session private thread. Per-session state lives in [Session]
 * so a superseding stream can never corrupt its predecessor's pipeline.
 */
class StreamingTtsPlayer(
    private val synthExecutor: ExecutorService,
    private val onFinished: (streamId: String, fullText: String) -> Unit
) {

    companion object {
        private const val TAG = "StreamingTtsPlayer"

        // Sentence segmentation: release a chunk at punctuation + whitespace.
        private const val MIN_SENTENCE_CHARS = 2
        private const val MAX_SENTENCE_CHARS = 300
        private const val MIN_SPLIT_CHARS = 40   // don't split tiny heads off monsters
        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?…])\\s+")

        // Playback poison pill (empty PCM ends the drain loop).
        private val END_OF_STREAM = ByteArray(0)
    }

    /** Per-stream pipeline state; each begin() gets a fresh one. */
    private inner class Session(val id: String, val engine: KokoroTTS) {
        val spokenText = StringBuilder()                      // main thread
        val pendingTail = StringBuilder()                     // main thread
        val pcmQueue = LinkedBlockingQueue<ByteArray>()      // synth -> playback
        val pendingSynthesis = AtomicInteger(0)
        val endOfStreamSent = AtomicBoolean(false)
        @Volatile var streamEnded = false
        @Volatile var aborted = false
    }

    @Volatile
    private var session: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while [id] is the live, unaborted stream. */
    fun isActiveFor(id: String): Boolean {
        val s = session ?: return false
        return s.id == id && !s.aborted
    }

    /** The session's text so far (spoken + pending tail) for HUD display. */
    fun currentText(id: String): String {
        val s = session ?: return ""
        if (s.id != id) return ""
        return (s.spokenText.toString() + s.pendingTail.toString()).trim()
    }

    /**
     * Whole-text playback (v3.6 bubble replay): synthesizes and speaks the
     * text sentence-by-sentence through the same pipeline as a live stream -
     * first word in ~1-2 s instead of waiting for a full synthesis.
     */
    fun speakText(id: String, text: String, engine: KokoroTTS) {
        begin(id, engine)
        feed(id, text)
        end(id)
    }

    /** Start (or preempt - the previous stream aborts) a streaming session. */
    fun begin(id: String, engine: KokoroTTS) {
        session?.let { abortSession(it) }
        val s = Session(id, engine)
        session = s
        Thread({ playbackLoop(s) }, "polaris-stream-playback").start()
    }

    /** Feed a text delta from the gateway; complete sentences become speech. */
    fun feed(id: String, delta: String) {
        val s = session ?: return
        if (s.id != id || s.aborted || delta.isEmpty()) return
        s.pendingTail.append(delta)

        while (true) {
            val text = s.pendingTail.toString()
            if (text.length < MIN_SENTENCE_CHARS) return
            val boundary = SENTENCE_BOUNDARY.find(text) ?: return
            var sentence = text.substring(0, boundary.range.first).trim()
            s.pendingTail.setLength(0)
            s.pendingTail.append(text.substring(boundary.range.last + 1))
            // Split monster sentences at clause boundaries so synthesis
            // stays comfortably ahead of the speaker.
            while (sentence.length > MAX_SENTENCE_CHARS) {
                val cut = maxOf(
                    sentence.lastIndexOf(",", MAX_SENTENCE_CHARS),
                    sentence.lastIndexOf(";", MAX_SENTENCE_CHARS),
                    sentence.lastIndexOf(" ", MAX_SENTENCE_CHARS)
                )
                val splitAt = if (cut >= MIN_SPLIT_CHARS) cut + 1 else MAX_SENTENCE_CHARS
                val head = sentence.substring(0, splitAt).trim()
                if (head.isNotEmpty()) {
                    s.spokenText.append(head).append(' ')
                    submitSynthesis(s, head)
                }
                sentence = sentence.substring(splitAt).trim()
            }
            if (sentence.isNotEmpty()) {
                s.spokenText.append(sentence).append(' ')
                submitSynthesis(s, sentence)
            }
        }
    }

    /** The stream is over: flush the held-back tail as the final chunk. */
    fun end(id: String) {
        val s = session ?: return
        if (s.id != id || s.streamEnded) return
        s.streamEnded = true
        val tail = s.pendingTail.toString().trim()
        s.pendingTail.setLength(0)
        if (tail.isNotEmpty()) {
            s.spokenText.append(tail).append(' ')
            submitSynthesis(s, tail)
        }
        maybeSendEndOfStream(s)
    }

    /** Halt the live stream immediately (mic capture, watch STOP, teardown). */
    fun abort() {
        session?.let { abortSession(it) }
        session = null
    }

    /**
     * True when the live stream's assembled text matches the finalized
     * message - the caller then skips whole-text re-synthesis and attaches
     * the message ID instead. Aborted sessions never match: after a
     * mid-stream interruption the whole reply still needs a full speak.
     */
    fun consumeIfMatches(text: String): Boolean {
        val s = session ?: return false
        if (s.aborted || !s.streamEnded) return false
        val mine = s.spokenText.toString().trim()
        if (mine.isEmpty()) return false
        val theirs = text.trim()
        return theirs == mine || theirs.startsWith(mine)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun abortSession(s: Session) {
        if (s.aborted) return
        s.aborted = true
        s.pcmQueue.clear()
        s.pcmQueue.offer(END_OF_STREAM)   // wake the playback thread
        // No onFinished for a partial reply: the bubble re-synthesizes on demand.
    }

    private fun submitSynthesis(s: Session, sentence: String) {
        s.pendingSynthesis.incrementAndGet()
        try {
            synthExecutor.execute {
                try {
                    // Skip chunks already queued when abort()/teardown hit:
                    // no point racing a seconds-long native synthesis for a
                    // session whose audio is discarded anyway.
                    if (s.aborted) return@execute
                    val pcm = s.engine.synthesize(sentence)
                    if (!s.aborted && pcm != null && pcm.isNotEmpty()) {
                        s.pcmQueue.put(pcm)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Chunk synthesis failed: ${t.message}")
                } finally {
                    if (s.pendingSynthesis.decrementAndGet() == 0 && s.streamEnded) {
                        maybeSendEndOfStream(s)
                    }
                }
            }
        } catch (t: Throwable) {
            // Executor rejected (teardown races) - keep the counter honest.
            s.pendingSynthesis.decrementAndGet()
            Log.w(TAG, "Chunk synthesis rejected: ${t.message}")
        }
    }

    private fun maybeSendEndOfStream(s: Session) {
        if (s.pendingSynthesis.get() > 0) return
        if (s.endOfStreamSent.compareAndSet(false, true)) {
            s.pcmQueue.offer(END_OF_STREAM)
        }
    }

    private fun playbackLoop(s: Session) {
        val track = buildAudioTrack()
        if (track == null) {
            Log.e(TAG, "AudioTrack creation failed - abandoning stream ${s.id}")
            mainHandler.post {
                abortSession(s)
                if (session === s) session = null
            }
            return
        }
        val playedPcm = ArrayList<ByteArray>()
        try {
            track.play()
            while (true) {
                val chunk = s.pcmQueue.take()
                if (chunk === END_OF_STREAM || chunk.isEmpty()) break
                if (s.aborted) break
                // Blocking write: AudioTrack buffers ~2x min-buffer, so
                // synthesis stays ahead while this thread speaks.
                track.write(chunk, 0, chunk.size)
                playedPcm.add(chunk)
            }
            // v3.5.1 clip fix: write() only QUEUES into the AudioTrack's
            // internal buffer - the speaker can lag up to that buffer (~1 s
            // of speech) behind the last write. Stopping here cut the final
            // words of her reply; wait for the playback head to reach the
            // last written frame before teardown (mirrors KokoroTTS's own
            // completion wait), with a generous timeout as a stuck-track
            // safety net.
            if (!s.aborted && playedPcm.isNotEmpty()) {
                val totalFrames = playedPcm.sumOf { it.size } / 2
                val drainDeadlineMs = SystemClock.elapsedRealtime() +
                    (totalFrames * 1000L / KokoroTTS.SAMPLE_RATE) + 3_000L
                while (track.playbackHeadPosition < totalFrames &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    SystemClock.elapsedRealtime() < drainDeadlineMs
                ) {
                    Thread.sleep(25)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Stream playback failed: ${t.message}")
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
        if (s.aborted) return

        val text = s.spokenText.toString().trim()
        mainHandler.post {
            if (s.aborted) return@post
            if (session === s) session = null
            Log.i(TAG, "Stream drained: ${s.id} (${text.length} chars)")
            onFinished(s.id, text)
        }
    }

    private fun buildAudioTrack(): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(
            KokoroTTS.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(KokoroTTS.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf * 2, 48_000))
            .build()
    } catch (t: Throwable) {
        Log.e(TAG, "AudioTrack build failed: ${t.message}")
        null
    }
}