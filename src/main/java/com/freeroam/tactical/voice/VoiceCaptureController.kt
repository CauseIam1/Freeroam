package com.freeroam.tactical.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * VoiceCaptureController - Mic capture + speech-to-text for the freeroam
 * walkie-talkie.
 *
 * Wraps Android's SpeechRecognizer behind a small push-to-talk-shaped API:
 * startListening(), stopListening() (graceful flush of the final result),
 * and cancel() (hard abort, no result). Partial results stream live so the
 * caller can render a capture banner while the user talks; the final
 * transcript is delivered exactly once via onCaptureComplete().
 *
 * End-of-speech patience (v3.4): the recognition service's internal VAD
 * finalizes a "segment" after its own short silence window (~1 s), which
 * used to auto-send mid-sentence pauses as incomplete messages. The
 * controller now folds each service-finalized segment into a running
 * transcript, restarts the recognizer, and keeps the mic hot until the
 * operator stops, END_OF_SPEECH_SILENCE_TIMEOUT_MS passes with no new
 * speech, or the hard 30 s cap is reached.
 *
 * Engine selection: on-device recognition (API 31+, fully offline - no
 * audio ever leaves the phone) is preferred when available; otherwise the
 * system recognition service is used. Devices with neither surface an
 * error callback - the caller must degrade gracefully to text input,
 * mirroring how MainActivity survives a missing Kokoro engine.
 *
 * Threading: SpeechRecognizer must be created and driven from a Looper
 * thread and delivers its callbacks on that thread. Everything here hops
 * through a main-looper Handler, so all listener callbacks are guaranteed
 * to arrive on the main thread - safe for direct UI updates.
 *
 * Permissions: RECORD_AUDIO must be granted before startListening() (the
 * manifest already declares it; MainActivity owns the runtime request via
 * its permission launcher). A missing grant surfaces as an error callback
 * rather than a crash.
 *
 * Lifecycle: owned by MainActivity; call destroy() in onDestroy() to
 * release the recognizer.
 */
class VoiceCaptureController(context: Context) {

    /** Caller callbacks. All methods run on the main thread. */
    interface VoiceCaptureListener {
        /** Mic is live - show the capture banner. */
        fun onCaptureStarted()

        /** Live partial transcript - update the banner as the user talks. */
        fun onPartialResult(text: String)

        /** Final transcript ready - send it to Polaris. */
        fun onCaptureComplete(text: String)

        /** Capture failed or heard nothing - dismiss the capture UI. */
        fun onCaptureError(reason: String)
    }

    companion object {
        private const val TAG = "VoiceCaptureController"
        private const val LANGUAGE = "en-US"
        private const val MAX_RESULTS = 1

        // Safety net: some OEM recognition services never deliver a final
        // result after stopListening() - abandon the capture instead of
        // leaving the mic banner stuck on screen forever.
        private const val STOP_FLUSH_TIMEOUT_MS = 1500L

        // Hard ceiling so a forgotten session cannot hold the mic forever.
        private const val MAX_CAPTURE_DURATION_MS = 30_000L

        // v3.4 end-of-speech patience: how long the capture stays hot after
        // the last speech activity (beginning-of-speech or a non-blank
        // partial) before finalizing and sending. The service's own VAD
        // window (~1 s) only ends a SEGMENT now - the operator can pause
        // mid-thought without sending an incomplete message.
        private const val END_OF_SPEECH_SILENCE_TIMEOUT_MS = 2_500L

        // Gap between recognizer segments (destroy -> recreate -> start).
        // Some engines error or hang when a new session starts before the
        // old one fully released the mic.
        private const val SEGMENT_RESTART_DELAY_MS = 300L

        // Bounded retries when a segment restart fails (busy engine or a
        // throwing startListening) before the capture gives up and
        // delivers what it has.
        private const val SEGMENT_RESTART_MAX_RETRIES = 2

        // v3.6.2 engine self-heal: silent captures before the next capture
        // is forced onto the system (cloud) recognizer. 1 = hop immediately
        // after the first silent capture.
        private const val SILENT_CAPTURES_BEFORE_ENGINE_HOP = 1

        // v3.6.3 same-press self-heal: bounded transparent retries when a
        // capture is about to end with nothing heard because the recognizer
        // never engaged or died mid-flight - the first press recovers on
        // its own instead of costing a failed press.
        private const val SELF_HEAL_MAX_RETRIES = 2

        // A healthy recognizer produces SOME callback (ready-for-speech, RMS
        // level changes, beginning-of-speech) within ~2 s of startListening()
        // - the on-device cold model load included. Zero callbacks past this
        // point is the RedMagic's documented wedge signature ("no callbacks
        // at all"): hop engines immediately instead of waiting out the
        // watchdog chain (2.5 s silence + 1.5 s flush) that ate the press.
        private const val ENGINE_WARMUP_TIMEOUT_MS = 2_000L

        // Errors a transparent same-press retry can cure. Excluded on
        // purpose: ERROR_INSUFFICIENT_PERMISSIONS is permanent until the
        // grant lands, and ERROR_AUDIO is a mic-hardware conflict no
        // restart repairs.
        private val SELF_HEAL_RETRYABLE_ERRORS: Set<Int> = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER
        )
    }

    private enum class CaptureState { IDLE, LISTENING, STOPPING }

    // App context, not the Activity: the recognizer must never pin the
    // Activity, and controller runnables outlive individual frames anyway.
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: VoiceCaptureListener? = null
    @Volatile
    private var state = CaptureState.IDLE

    // Not thread-safe: only ever touched on the main thread (every
    // mutating path hops through mainHandler).
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false

    // v3.6.2 engine self-heal: the RedMagic's on-device engine occasionally
    // wedges mid-capture - no callbacks at all - and every later press hears
    // nothing until a reboot. After a silent capture, hop to the system
    // (cloud) recognizer for the next attempt; a successful capture clears
    // the hop and returns to the private engine.
    private var preferFallbackEngine = false
    private var consecutiveSilentCaptures = 0

    // Running transcript across recognizer segments (v3.4 continuation).
    // Main-thread only, like everything else here.
    private val accumulatedSegments = StringBuilder()

    // ElapsedRealtime of the last speech activity; drives the
    // end-of-speech silence watchdog.
    @Volatile
    private var lastSpeechEventAt = 0L

    // Remaining bounded retries for the in-flight segment restart.
    private var segmentRestartsLeft = SEGMENT_RESTART_MAX_RETRIES

    // True while a segment restart hop is queued: the current recognizer
    // session already delivered its final result, so a stopListening() on it
    // would only earn ERROR_SERVER_DISCONNECTED (the RedMagic's "recognition
    // error 11"). stopListeningInternal() delivers directly instead of
    // flushing a finished session.
    private var segmentRestartPending = false

    // v3.6.3 same-press self-heal: per-capture retry budget, refilled by
    // every fresh PTT press and charged only by transparent recoveries.
    private var selfHealRetriesLeft = 0

    // v3.6.3: liveness detector for the warmup watchdog - true once the
    // live recognizer session has produced ANY callback after startListening().
    private var receivedEngineCallback = false

    // v3.6.3: last non-blank live partial of the current session. A wedge
    // can kill the engine before any segment finalization, leaving these
    // partial words as the only copy of what the operator said - a self-heal
    // restart folds them back into the running transcript instead of
    // discarding them.
    private var lastLivePartial = ""

    // v3.6.3: true when the pending stop was triggered by a timer (end-of-
    // speech watchdog or the 30 s cap) rather than the operator's PTT
    // press. A blank capture on an automatic stop is self-heal territory;
    // a blank capture on a manual stop is the operator ending an empty
    // press - reported honestly.
    private var pendingStopIsAuto = false

    fun setListener(captureListener: VoiceCaptureListener?) {
        listener = captureListener
    }

    /** True while a capture is starting, listening, or finalizing. */
    fun isListening(): Boolean = state != CaptureState.IDLE

    // Partial wake lock held for the duration of a capture so the CPU stays
    // alive (and recognition callbacks keep flowing) with the screen locked.
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Begin mic capture. Idempotent: a second call while a capture is
     * running is ignored - the caller owns the PTT toggle semantics.
     */
    fun startListening() {
        mainHandler.post {
            // Fresh press, fresh same-press self-heal budget (v3.6.3).
            selfHealRetriesLeft = SELF_HEAL_MAX_RETRIES
            startListeningInternal()
        }
    }

    /**
     * Stop and flush: the recognizer is asked to finalize, which triggers
     * onCaptureComplete with the transcript (or onCaptureError if nothing
     * usable was captured). Idempotent; ignored when not listening.
     */
    fun stopListening() {
        mainHandler.post { stopListeningInternal() }
    }

    /** Hard abort: teardown with no result delivery. */
    fun cancel() {
        mainHandler.post { cancelInternal() }
    }

    /** Release everything. Call from onDestroy(). */
    fun destroy() {
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
        cancelInternal()
    }

    // ------------------------------------------------------------------
    // Internal machinery (main thread only)
    // ------------------------------------------------------------------

    /**
     * Start (or transparently restart) a recognizer session. keepTranscript
     * preserves the running transcript across a v3.6.3 same-press self-heal
     * hop; a fresh press clears it.
     */
    private fun startListeningInternal(keepTranscript: Boolean = false) {
        if (state != CaptureState.IDLE) {
            Log.w(TAG, "startListening ignored - capture already in progress ($state)")
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO not granted - refusing to start capture")
            listener?.onCaptureError("Microphone permission not granted")
            return
        }

        val created = createRecognizer()
        if (created == null) {
            listener?.onCaptureError("No speech recognition service available")
            return
        }
        recognizer = created
        created.setRecognitionListener(recognitionListener)

        try {
            created.startListening(buildRecognitionIntent())
        } catch (t: Throwable) {
            // Some OEM builds throw instead of routing the error callback.
            Log.e(TAG, "startListening threw: ${t.message}")
            finishCapture()
            listener?.onCaptureError("Failed to start speech capture")
            return
        }

        state = CaptureState.LISTENING
        if (!keepTranscript) {
            accumulatedSegments.setLength(0)
        }
        segmentRestartsLeft = SEGMENT_RESTART_MAX_RETRIES
        // v3.6.3: (re)arm the warmup watchdog for this recognizer session -
        // zero callbacks by its deadline means the engine never engaged.
        receivedEngineCallback = false
        lastLivePartial = ""
        pendingStopIsAuto = false
        mainHandler.removeCallbacks(engineWarmupRunnable)
        mainHandler.postDelayed(engineWarmupRunnable, ENGINE_WARMUP_TIMEOUT_MS)
        acquireWakeLock()
        mainHandler.postDelayed(maxDurationRunnable, MAX_CAPTURE_DURATION_MS)
        // Arm the end-of-speech watchdog; onBeginningOfSpeech and partials
        // re-arm it as speech flows.
        markSpeechActivity()
        listener?.onCaptureStarted()
    }

    /** Recognition intent for every segment of a capture. */
    private fun buildRecognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            // The on-device engine honors offline preference; harmless for
            // the cloud service, and it keeps audio private when we can.
            if (usingOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /**
     * Speech activity heartbeat: stamp the clock and re-arm the
     * end-of-speech watchdog. Called at capture start, on
     * beginning-of-speech, and on every non-blank partial.
     */
    private fun markSpeechActivity() {
        lastSpeechEventAt = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(silenceWatchdogRunnable)
        mainHandler.postDelayed(silenceWatchdogRunnable, END_OF_SPEECH_SILENCE_TIMEOUT_MS)
    }

    /**
     * Prefer the private on-device engine; fall back to the system service.
     * v3.6.2 self-heal: after a silent/hung capture the hop flag forces the
     * system recognizer for the next session, so a wedged on-device engine
     * cannot lock voice away until a reboot.
     */
    private fun createRecognizer(): SpeechRecognizer? {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        val cloudAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        val wantOnDevice = onDevice && !preferFallbackEngine
        if (preferFallbackEngine && cloudAvailable) {
            Log.i(TAG, "Engine self-heal - last capture went silent, using system recognizer")
        }
        return try {
            val created: SpeechRecognizer? = when {
                wantOnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                    .also { Log.i(TAG, "Using on-device recognizer (offline, private)") }
                cloudAvailable -> SpeechRecognizer.createSpeechRecognizer(appContext)
                    .also { Log.i(TAG, "Using system speech recognition service") }
                onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                    .also { Log.i(TAG, "On-device recognizer (system service unavailable)") }
                else -> {
                    Log.e(TAG, "No speech recognition service on this device")
                    null
                }
            }
            usingOnDevice = wantOnDevice && created != null
            created
        } catch (t: Throwable) {
            usingOnDevice = false
            Log.e(TAG, "Recognizer creation failed: ${t.message}")
            null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            receivedEngineCallback = true
        }

        // Speech activity heartbeat for the end-of-speech watchdog.
        override fun onBeginningOfSpeech() {
            receivedEngineCallback = true
            markSpeechActivity()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Ambient-audio level proves the mic pipeline is live - the
            // warmup watchdog's "engine engaged" signal (fires before any
            // speech exists, so it never touches the silence watchdog).
            receivedEngineCallback = true
        }
        override fun onBufferReceived(buffer: ByteArray?) {}

        // The service ends a segment on its own short VAD silence. The
        // follow-up onResults either continues the capture (service-driven
        // end while still LISTENING) or finalizes it (a manual stop flushed
        // us into STOPPING). Nothing to do here - the branching happens in
        // onResults.
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Cancel/destroy already ended this session; a straggler
            // callback must not resurrect the capture UI.
            if (state == CaptureState.IDLE) {
                Log.w(TAG, "Spurious onError($error) after capture ended - ignoring")
                return
            }

            // A restarted segment raced a still-shutting-down engine: retry
            // instead of dropping an in-flight capture.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY &&
                state == CaptureState.LISTENING &&
                segmentRestartsLeft > 0
            ) {
                segmentRestartsLeft--
                Log.w(TAG, "Recognizer busy on segment restart - retrying")
                scheduleSegmentRestart()
                return
            }

            // v3.4.1: whatever killed the session - the RedMagic's on-device
            // engine reports ERROR_SERVER_DISCONNECTED (11) when a stop lands
            // on a finished session or during a restart hop - the operator's
            // words win: deliver the accumulated transcript instead of
            // erroring it away. The reason still surfaces when nothing was
            // heard.
            val reason = describeError(error)
            Log.e(TAG, "Recognition error $error: $reason")

            // v3.6.3 same-press self-heal: an error before any words landed
            // may be a wedged or cold-failed engine - restart transparently
            // on the other engine while the budget lasts, and surface the
            // error only once the budget is spent.
            if (state == CaptureState.LISTENING &&
                error in SELF_HEAL_RETRYABLE_ERRORS &&
                accumulatedSegments.isBlank() &&
                selfHealRetriesLeft > 0
            ) {
                Log.w(TAG, "Retryable error with no speech yet - same-press self-heal")
                selfHealRestart(reason)
                return
            }

            finalizeAndDeliver(blankReason = reason)
        }

        override fun onResults(results: Bundle?) {
            if (state == CaptureState.IDLE) {
                Log.w(TAG, "Spurious onResults after capture ended - ignoring")
                return
            }
            val segmentText = extractResults(results)

            if (state == CaptureState.STOPPING) {
                // Manual stop, silence-watchdog flush, or the 30 s cap:
                // deliver the full running transcript. finalizeAndDeliver()
                // combines BEFORE teardown - finishCapture() clears the
                // accumulator, so the old order silently dropped every
                // segment except the last.
                if (segmentText.isNullOrBlank()) {
                    Log.w(TAG, "onResults carried no usable text")
                }
                finalizeAndDeliver(segmentText)
                return
            }

            // Service-driven end of a speech segment (its own ~1 s VAD
            // silence): fold the segment into the running transcript and
            // keep the mic hot. The capture only ends on the watchdog, a
            // manual stop, or the cap - a mid-sentence pause no longer
            // sends an incomplete message.
            if (!segmentText.isNullOrBlank()) {
                appendSegment(segmentText)
            }
            Log.i(
                TAG,
                "Segment finalized (${segmentText?.length ?: 0} new chars, " +
                    "${accumulatedSegments.length} accumulated) - continuing capture"
            )
            scheduleSegmentRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state == CaptureState.IDLE) return
            val partial = extractResults(partialResults)
            if (!partial.isNullOrBlank()) {
                receivedEngineCallback = true
                // v3.6.3: remember the live partial - a mid-speech wedge
                // leaves these as the only words, and the self-heal restart
                // folds them into the running transcript.
                lastLivePartial = partial
                markSpeechActivity()
                // Show the full running transcript (finalized segments plus
                // the live partial), not just the current segment.
                listener?.onPartialResult(combineTranscript(partial))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Fold a finalized recognizer segment into the running transcript. */
    private fun appendSegment(segment: String) {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return
        if (accumulatedSegments.isNotEmpty()) accumulatedSegments.append(' ')
        accumulatedSegments.append(trimmed)
    }

    /**
     * The running transcript: finalized segments plus an optional live
     * partial from the current recognizer segment.
     */
    private fun combineTranscript(livePartial: String? = null): String {
        val base = accumulatedSegments.toString().trim()
        val partial = livePartial?.trim().orEmpty()
        return when {
            base.isEmpty() -> partial
            partial.isEmpty() -> base
            else -> "$base $partial"
        }
    }

    /**
     * Terminal deliver for every path that ends a capture with words in hand
     * (manual stop, watchdog flush, 30 s cap, stop-flush timeout, errors,
     * service death). Combines the running transcript BEFORE teardown -
     * finishCapture() clears the accumulator, so the combine order is
     * load-bearing - then delivers it, or surfaces the caller's blank-reason
     * when nothing usable was heard.
     */
    private fun finalizeAndDeliver(
        liveSegment: String? = null,
        blankReason: String = "Nothing was heard",
        allowSelfHealRetry: Boolean = false
    ) {
        val combined = combineTranscript(liveSegment)
        finishCapture()
        if (combined.isBlank()) {
            // v3.6.3 same-press self-heal: when the blank came from a timer
            // stop or an internal failure - not the operator ending an empty
            // press - retry transparently on the other engine while the
            // budget lasts. The red capture UI never drops mid-press.
            if (allowSelfHealRetry && selfHealRetriesLeft > 0) {
                selfHealRestart(blankReason)
                return
            }
            // v3.6.2 self-heal: a silent capture arms an engine hop - the
            // next press is served by the other recognizer instead of the
            // same wedged one, so voice recovers without a reboot.
            consecutiveSilentCaptures++
            if (consecutiveSilentCaptures >= SILENT_CAPTURES_BEFORE_ENGINE_HOP) {
                preferFallbackEngine = true
                Log.w(TAG, "Silent capture #$consecutiveSilentCaptures - next capture hops to the system recognizer")
            }
            listener?.onCaptureError(blankReason)
        } else {
            consecutiveSilentCaptures = 0
            preferFallbackEngine = false
            Log.i(TAG, "Capture delivered: ${combined.length} chars")
            listener?.onCaptureComplete(combined)
        }
    }

    /**
     * Hop to a fresh recognizer session after the service ends a segment.
     * Runs after a short delay: engines need the mic released between
     * sessions or the restart errors out.
     */
    private fun scheduleSegmentRestart() {
        segmentRestartPending = true
        mainHandler.removeCallbacks(segmentRestartRunnable)
        mainHandler.postDelayed(segmentRestartRunnable, SEGMENT_RESTART_DELAY_MS)
    }

    private fun restartListeningSegment() {
        // A terminal path (manual stop / cancel / watchdog race) won while
        // the restart was queued - nothing to do.
        if (state != CaptureState.LISTENING) return
        segmentRestartPending = false

        destroyRecognizer()
        val created = createRecognizer()
        if (created == null) {
            if (segmentRestartsLeft > 0) {
                segmentRestartsLeft--
                Log.w(TAG, "Recognizer recreation failed - retrying")
                scheduleSegmentRestart()
                return
            }
            Log.e(TAG, "Recognizer recreation failed - delivering accumulated transcript")
            finalizeAndDeliver(blankReason = "Speech service unavailable mid-capture")
            return
        }
        recognizer = created
        created.setRecognitionListener(recognitionListener)
        try {
            created.startListening(buildRecognitionIntent())
            Log.i(TAG, "Recognizer segment restarted (${accumulatedSegments.length} chars so far)")
        } catch (t: Throwable) {
            Log.e(TAG, "Segment restart threw: ${t.message}")
            if (segmentRestartsLeft > 0) {
                segmentRestartsLeft--
                scheduleSegmentRestart()
            } else {
                finalizeAndDeliver(blankReason = "Failed to continue speech capture")
            }
        }
    }

    private fun stopListeningInternal(initiatedByUser: Boolean = true) {
        if (state != CaptureState.LISTENING) {
            Log.w(TAG, "stopListening ignored - not capturing ($state)")
            return
        }
        state = CaptureState.STOPPING
        // v3.6.3: remember who asked for the stop - the blank-delivery
        // paths below consult this to decide whether a retry is legitimate.
        pendingStopIsAuto = !initiatedByUser
        mainHandler.removeCallbacks(maxDurationRunnable)

        // v3.4.1: the live session already finalized (the service's VAD ended
        // it) and a restart hop is queued - there is nothing left to flush.
        // stopListening() on the finished session is exactly what produced
        // the RedMagic's "recognition error 11" (ERROR_SERVER_DISCONNECTED)
        // toast instead of sending. Deliver the running transcript directly.
        if (segmentRestartPending) {
            Log.i(TAG, "Stop during segment hop - delivering accumulated transcript")
            finalizeAndDeliver(
                blankReason = "Nothing was heard",
                allowSelfHealRetry = pendingStopIsAuto
            )
            return
        }

        try {
            // Ask the service to flush; final onResults (or onError) follows.
            recognizer?.stopListening()
        } catch (t: Throwable) {
            Log.e(TAG, "stopListening threw: ${t.message}")
            finalizeAndDeliver(
                blankReason = "Failed to finalize speech capture",
                allowSelfHealRetry = pendingStopIsAuto
            )
            return
        }
        mainHandler.postDelayed(stopFlushRunnable, STOP_FLUSH_TIMEOUT_MS)
    }

    // Safety net for OEM services that never deliver after stopListening().
    private val stopFlushRunnable = Runnable {
        if (state == CaptureState.STOPPING) {
            Log.w(TAG, "No final result after stop - abandoning capture")
            // Some OEM services drop the final callback entirely; keep the
            // operator's words instead of erroring them away.
            finalizeAndDeliver(
                blankReason = "No speech captured before stop",
                allowSelfHealRetry = pendingStopIsAuto
            )
        }
    }

    // Hard ceiling - a forgotten session must not hold the mic forever.
    private val maxDurationRunnable = Runnable {
        if (state == CaptureState.LISTENING) {
            Log.i(TAG, "Max capture duration reached - finalizing")
            stopListeningInternal(initiatedByUser = false)
        }
    }

    // v3.4 end-of-speech watchdog: once no speech has flowed for
    // END_OF_SPEECH_SILENCE_TIMEOUT_MS, finalize the capture with the full
    // running transcript. Re-armed on every speech activity; cleared on
    // every terminal path in finishCapture().
    private val silenceWatchdogRunnable = Runnable {
        if (state == CaptureState.LISTENING) {
            Log.i(
                TAG,
                "End-of-speech silence (${END_OF_SPEECH_SILENCE_TIMEOUT_MS}ms) - finalizing"
            )
            stopListeningInternal(initiatedByUser = false)
        }
    }

    // v3.6.3 warmup watchdog: zero callbacks of any kind since startListening
    // past ENGINE_WARMUP_TIMEOUT_MS means the engine never engaged (the
    // RedMagic's documented "no callbacks at all" wedge) - hop engines and
    // restart within the same press; if the budget is spent, end the
    // capture honestly instead of hanging.
    private val engineWarmupRunnable = Runnable {
        if (state == CaptureState.LISTENING && !receivedEngineCallback) {
            if (selfHealRetriesLeft > 0) {
                Log.w(TAG, "No recognizer callback within ${ENGINE_WARMUP_TIMEOUT_MS}ms - engine never engaged, hopping")
                selfHealRestart("Speech engine never engaged")
            } else {
                Log.e(TAG, "No recognizer callback within ${ENGINE_WARMUP_TIMEOUT_MS}ms - self-heal budget spent")
                finalizeAndDeliver(blankReason = "Speech engine unresponsive")
            }
        }
    }

    /**
     * v3.6.3 same-press self-heal: transparently hop engines and restart
     * the capture when it is about to end with nothing heard - or when the
     * live session produced zero callbacks at all. The operator sees
     * nothing: onCaptureStarted re-fires so the red capture UI stays up,
     * and the press stays alive until the budget is spent.
     */
    private fun selfHealRestart(reason: String) {
        selfHealRetriesLeft--
        // Hop NOW, not next press: createRecognizer() serves the other
        // engine for the fresh session while this flag is set, and the
        // next successful capture clears it back to the private engine.
        consecutiveSilentCaptures++
        preferFallbackEngine = true
        Log.w(TAG, "Self-heal restart ($reason) - retrying on the other engine, budget left: $selfHealRetriesLeft")
        finishCapture()
        // A wedge before the first segment finalization leaves the live
        // partial as the only copy of the operator's words - fold it into
        // the running transcript so the retry continues their sentence.
        val folded = lastLivePartial.trim()
        if (folded.isNotBlank()) {
            accumulatedSegments.append(folded)
            Log.i(TAG, "Self-heal folded ${folded.length} partial chars into the running transcript")
        }
        startListeningInternal(keepTranscript = true)
    }

    // Continuation hop between recognizer segments; see
    // scheduleSegmentRestart() / restartListeningSegment().
    private val segmentRestartRunnable = Runnable { restartListeningSegment() }

    private fun cancelInternal() {
        if (state == CaptureState.IDLE) return
        finishCapture()
    }

    /** Common terminal cleanup: cancel all timers/runnables (duration cap,
     *  stop flush, silence watchdog, pending segment restart), release the
     *  wake lock, clear the running transcript, tear down the recognizer. */
    private fun finishCapture() {
        mainHandler.removeCallbacks(maxDurationRunnable)
        mainHandler.removeCallbacks(stopFlushRunnable)
        mainHandler.removeCallbacks(silenceWatchdogRunnable)
        mainHandler.removeCallbacks(segmentRestartRunnable)
        mainHandler.removeCallbacks(engineWarmupRunnable)
        state = CaptureState.IDLE
        segmentRestartPending = false
        accumulatedSegments.setLength(0)
        releaseWakeLock()
        destroyRecognizer()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freeroam:voice_capture").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) {
            // Safety timeout matches the hard capture ceiling so a missed
            // release can never pin the CPU; finishCapture() releases sooner.
            wakeLock?.acquire(MAX_CAPTURE_DURATION_MS + 5_000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "Wake lock release failed: ${t.message}")
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "Recognizer destroy failed: ${t.message}")
        }
        recognizer = null
    }

    /** First non-blank hypothesis from a results bundle, or null. */
    private fun extractResults(bundle: Bundle?): String? {
        if (bundle == null) return null
        val candidates = try {
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        } catch (t: Throwable) {
            Log.e(TAG, "Result extraction failed: ${t.message}")
            null
        }
        return candidates?.firstOrNull { !it.isNullOrBlank() }
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognition client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
        SpeechRecognizer.ERROR_NETWORK -> "Recognition network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Nothing was heard"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy - try again"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition service disconnected"
        else -> "Recognition error $error"
    }
}