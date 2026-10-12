package com.freeroam.tactical

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.freeroam.tactical.data.ChatRepository
import com.freeroam.tactical.media.MediaSessionManager
import com.freeroam.tactical.media.PolarisAudioStore
import com.freeroam.tactical.media.PolarisTextStore
import com.freeroam.tactical.network.PolarisWebSocketClient
import com.freeroam.tactical.network.PolarisWebSocketListener
import com.freeroam.tactical.service.GpsForegroundService
import com.freeroam.tactical.service.TacticalBackgroundService
import com.freeroam.tactical.ui.PolarisMiniPlayerView
import com.freeroam.tactical.voice.KokoroTTS
import com.freeroam.tactical.voice.StreamingTtsPlayer
import com.freeroam.tactical.voice.VoiceCaptureController
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), PolarisWebSocketListener {

    // UI Components - Top Nav
    private lateinit var connectionStatus: TextView
    private lateinit var processingIndicator: View
    private lateinit var voiceSelector: Spinner
    private lateinit var speedSlider: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var userIdentityButton: ImageButton
    
    // Mini Player Overlay
    private lateinit var miniPlayerView: PolarisMiniPlayerView
    
    // Chat state - rolling window of the last 10 messages (5 per side)
    private val chatMessages = mutableListOf<com.freeroam.tactical.data.ChatMessage>()
    private val MAX_CHAT_MESSAGES = 10
    // (Looking-Glass seed v2) On open the chat mirrors the master timeline:
    // the latest 5 messages from EACH side (Rich + Polaris), interleaved
    // chronologically, so the conversation is always full from the start.
    private val SEED_MESSAGES_PER_SIDE = 5

    // UI Components - Chat Area
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatBubbleContainer: LinearLayout
    private lateinit var noChatsText: TextView

    // UI Components - Input Area
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton

    // LGI HUD — Looking Glass (v1.4 port): header LEDs, ⋯ controls drawer,
    // ▼ TACTICAL OPERATIONS pane (task feed + chips).
    private lateinit var hudKebab: TextView
    private lateinit var hudSettingsDrawer: View
    private lateinit var opsToggle: TextView
    private lateinit var opsContainer: View
    private lateinit var taskFeedContainer: LinearLayout
    private lateinit var taskFeedPlaceholder: TextView
    private lateinit var taskStatusLine: TextView
    private lateinit var polLed: View
    private lateinit var ttsLed: View
    private lateinit var micLed: View
    private lateinit var feedLed: View
    private lateinit var voiceChip: TextView
    private lateinit var speedChip: TextView
    private lateinit var micChip: TextView

    // Voice Capture (walkie-talkie): tap-to-toggle mic button; live partials
    // land in messageInput and a red mic tint marks the capturing state.
    private lateinit var micButton: ImageButton
    private var voiceCaptureController: VoiceCaptureController? = null

    // Resting hint for the input field, stashed while a capture runs so the
    // "Listening..." capture hint can be restored verbatim afterwards.
    private var restingInputHint: String? = null
    // Set when the push-to-talk flow requests RECORD_AUDIO; honored when the
    // grant returns so the first mic press doesn't need a second press.
    private var pendingMicPermission = false

    // Core Systems
    private lateinit var polarisWebSocket: PolarisWebSocketClient
    @Volatile
    private var kokoroTTS: KokoroTTS? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatSyncHandler = Handler(Looper.getMainLooper())

    // TTS executor — KokoroTTS.speak() runs synchronous ONNX synthesis followed
    // by blocking playback (amplitude-thread join), so it must NEVER run on the
    // main thread: doing so froze the UI before the reply bubble could render.
    // A single-thread executor also serializes overlapping AI messages.
    private val ttsExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Newest AI message (epoch ms) already queued for speech. Guards against
    // the WS broadcast and the 3s poll sync both speaking the same reply.
    // The +1000ms tolerance at the call sites covers 1ms clock-truncation
    // differences between the WS emit timestamp and the persisted history one.
    @Volatile
    private var lastSpokenTimestamp: Long = 0L

    // TTS State
    private var currentSpeed: Float = 1.0f

    // Polaris bubble-linked playback state
    @Volatile private var currentlyPlayingMessageId: Long = -1L

    // Streaming speech (v3.5): speaks Polaris's reply sentence-by-sentence as
    // the gateway streams it, then hands the full PCM back for the standard
    // WAV/bubble cache. Null-safe throughout - a missing Kokoro engine keeps
    // the whole-text fallback path.
    private var streamingTtsPlayer: StreamingTtsPlayer? = null
    private val playbackPollRunnable = object : Runnable {
        override fun run() {
            val media = MediaSessionManager.getInstance(this@MainActivity)
            if (media.playbackState.value == MediaSessionManager.PlaybackState.ENDED) {
                onPolarisPlaybackEnded()
                return
            }
            val playing = media.isPlaying()
            miniPlayerView.setPlaying(playing)
            if (playing) {
                val durationMs = media.getDuration().toInt()
                if (durationMs > 0) {
                    miniPlayerView.setDuration(durationMs)
                    miniPlayerView.updatePosition(media.getCurrentPosition().toInt())
                }
            }
            mainHandler.postDelayed(this, PLAYBACK_POLL_INTERVAL_MS)
        }
    }

    // Chat Sync - Cross-device synchronization
    private companion object {
        private const val TAG = "MainActivity"
        private const val CHAT_SYNC_INTERVAL_MS = 3000L // Sync chat every 3 seconds like Polaris Dashboard
        private const val PLAYBACK_POLL_INTERVAL_MS = 200L // Mini player position refresh
        private const val MAX_TASK_FEED_ROWS = 50 // LGI TASKFEED_WINDOW
        // v3.6 watch-PTT echo guard: watch media presses that arrive within
        // this window of the previous PTT toggle are swallowed (double
        // delivery from the wearable listener + media session, and bounce).
        private const val PTT_ECHO_GUARD_MS = 2_000L

        // v3.6.3 watch HUD: her streaming reply types across the Garmin
        // marquee - one retitle per interval (a fresh mediaId each time makes
        // the watch treat it as a "new track" and re-scroll), tail-sliced to
        // the same 180-char budget updatePolarisMetadata() enforces.
        private const val WATCH_HUD_TITLE_INTERVAL_MS = 1_500L
        private const val WATCH_HUD_TITLE_MAX_CHARS = 180

        // v3.6.4 post-playback watch HUD loop: after her reply finishes
        // speaking, the full response gets WATCH_HUD_LOOP_PASSES marquee
        // passes on the Garmin (the completion retitle is pass one; each
        // later pass re-pushes as a fresh track), then the HUD is restored
        // to "Polaris standing by". Cadence scales with reply length so a
        // long reply gets enough scroll time per pass (tune CHAR_MS if the
        // marquee pace differs in the field).
        private const val WATCH_HUD_LOOP_PASSES = 3
        private const val WATCH_HUD_LOOP_CHAR_MS = 60L
        private const val WATCH_HUD_LOOP_MIN_MS = 3_000L
        private const val WATCH_HUD_LOOP_MAX_MS = 12_000L
    }
    // One rendered task-feed row (gateway status-room event, LGI TASK_ROW_STYLES).
    private data class LgiTaskRow(val icon: String, val colorRes: Int, val title: String, val ts: String)
    private val taskFeedRows = ArrayDeque<LgiTaskRow>()
    @Volatile private var activeTaskCount = 0
    private var chatSyncRunnable: Runnable? = null

    // v3.6 watch-PTT echo guard: monotonic elapsedRealtime() stamp of the
    // last push-to-talk toggle (in-app or watch), compared against
    // PTT_ECHO_GUARD_MS by toggleVoiceCapture().
    @Volatile private var lastPttToggleAt = 0L

    // v3.6.3 watch HUD live-stream throttling state (main thread only).
    private var lastWatchHudPushAt = 0L
    private var lastWatchHudTitle = ""

    // v3.7.2 HUD-first accumulator: every gateway delta lands here at
    // WS-arrival time - BEFORE the text is fed to Kokoro for synthesis -
    // so the watch HUD types her words ahead of her voice with zero
    // dependence on synthesis timing. Main thread only.
    private val streamHudText = StringBuilder()

    // v3.6.4 post-playback HUD loop: the pending marquee-pass runnable
    // (null when not looping). Main thread only.
    private var watchHudLoopRunnable: Runnable? = null

    // Voice Options - v3.4: af_emma leads the list. The spinner's
    // onItemSelectedListener fires at layout time with position 0, so the
    // first entry IS the effective boot default; leading with af_heart was
    // overwriting the af_emma default before KokoroTTS init ever ran.
    // ("af_emma" maps to bf_emma, sid 7 - matching the gateway's voice.)
    private val voiceOptions = listOf("af_emma", "af_heart", "af_sarah", "af_bella")
    private var selectedVoice: String = "af_emma"

    // User Identity Detection
    private var deviceIpAddress: String = "192.168.50.51"
    private var userIdentity: String = "Dashboard"

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
            // A pending push-to-talk request means the user just pressed the
            // mic: start the capture they asked for instead of forcing a
            // second press.
            if (pendingMicPermission) {
                pendingMicPermission = false
                voiceCaptureController?.startListening()
            }
            // GEV GPS (v3.8): ACCESS_FINE_LOCATION was just granted - promote
            // the location-typed FGS so the periodic phone-position feed to
            // the gateway (/api/gps on the Burst Receiver :8083) keeps running
            // with the screen off. Idempotent; also re-asserted from onStart()
            // so a first-install grant flow needs no restart.
            GpsForegroundService.start(this)
        } else {
            pendingMicPermission = false
            Toast.makeText(this, "Permissions required for full functionality", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Create the WebSocket client BEFORE any lifecycle callback can touch it.
        // onResume()/onDestroy() dereference polarisWebSocket, and on Android 16
        // the first resume transaction is queued ahead of runnables posted during
        // onCreate - posting the assignment to mainHandler loses that race
        // (fixes UninitializedPropertyAccessException in onResume).
        polarisWebSocket = PolarisWebSocketClient.getInstance()

        requestPermissions()
        initializeViews()
        setupVoiceSelector()
        setupSpeedSlider()
        setupMediaControls()
        setupTextInput()
        setupVoiceCapture()
        setupWatchMediaButtons()
        setupHudPanels()
        detectUserIdentity()
        initializeSystems()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Location permissions are REQUIRED on Android 6+ (API 23+) to read WiFi info
        // This is needed for user identity detection based on IP address
        permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        permissionsToRequest.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        
        // Notification permission required on Android 13+ (API 33+) for foreground service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun initializeViews() {
        // Top Nav
        connectionStatus = findViewById(R.id.connectionStatus)
        processingIndicator = findViewById(R.id.processingIndicator)
        voiceSelector = findViewById(R.id.voiceSelector)
        speedSlider = findViewById(R.id.speedSlider)
        speedValue = findViewById(R.id.speedValue)
        userIdentityButton = findViewById(R.id.userIdentityButton)

        // Chat Area
        chatScrollView = findViewById(R.id.chatScrollView)
        chatBubbleContainer = findViewById(R.id.chatBubbleContainer)
        noChatsText = findViewById(R.id.noChatsText)

        // Input Area
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        // LGI HUD
        hudKebab = findViewById(R.id.hudKebab)
        hudSettingsDrawer = findViewById(R.id.hudSettingsDrawer)
        opsToggle = findViewById(R.id.opsToggle)
        opsContainer = findViewById(R.id.opsContainer)
        taskFeedContainer = findViewById(R.id.taskFeedContainer)
        taskFeedPlaceholder = findViewById(R.id.taskFeedPlaceholder)
        taskStatusLine = findViewById(R.id.taskStatusLine)
        polLed = findViewById(R.id.polLed)
        ttsLed = findViewById(R.id.ttsLed)
        micLed = findViewById(R.id.micLed)
        feedLed = findViewById(R.id.feedLed)
        voiceChip = findViewById(R.id.voiceChip)
        speedChip = findViewById(R.id.speedChip)
        micChip = findViewById(R.id.micChip)
    }

    private fun setupVoiceSelector() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSelector.adapter = adapter
        voiceSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVoice = voiceOptions[position]
                Log.d(TAG, "Voice selected: $selectedVoice")
                kokoroTTS?.setVoice(selectedVoice)
                voiceChip.text = getString(R.string.lgi_chip_voice, selectedVoice)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSpeedSlider() {
        // Slider 0-100 maps to 1.0x-2.0x
        speedSlider.progress = 0 // Default 1.0x
        speedValue.text = "1.0x"
        
        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSpeed = 1.0f + (progress / 100.0f)
                speedValue.text = String.format("%.1fx", currentSpeed)
                speedChip.text = getString(R.string.lgi_chip_speed, String.format("%.1fx", currentSpeed))
                MediaSessionManager.getInstance(this@MainActivity).setPlaybackSpeed(currentSpeed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupMediaControls() {
        // Initialize Mini Player Overlay
        miniPlayerView = findViewById(R.id.miniPlayerView)
        
        // Wire up Mini Player callbacks to the shared ExoPlayer. PLAY/PAUSE and
        // STOP share their bodies with the watch AVRCP commands so both
        // surfaces stay in lockstep.
        miniPlayerView.setOnPlayPauseClickListener {
            handlePlayPauseRequest()
        }
        
        miniPlayerView.setOnStopClickListener {
            handleStopRequest()
        }
        
        miniPlayerView.setOnCloseClickListener {
            MediaSessionManager.getInstance(this).pause()
            mainHandler.removeCallbacks(playbackPollRunnable)
            miniPlayerView.setPlaying(false)
            miniPlayerView.hidePlayer()
            rebuildChatUI()
        }
        
        miniPlayerView.setOnSeekListener { positionMs ->
            MediaSessionManager.getInstance(this).seekToMs(positionMs.toLong())
            miniPlayerView.updatePosition(positionMs)
        }

        userIdentityButton.setOnClickListener {
            // Show user identity dialog
            Toast.makeText(this, "Connected as: $userIdentity ($deviceIpAddress)", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTextInput() {
        messageInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || 
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendMessage()
                true
            } else {
                false
            }
        }

        sendButton.setOnClickListener {
            sendMessage()
        }
    }

    // ------------------------------------------------------------------
    // Walkie-talkie voice capture: the in-app mic button is tap-to-toggle
    // and, since v3.3, the watch PLAY/PAUSE pair drives the same toggle.
    // ------------------------------------------------------------------

    /**
     * True when the device exposes any speech recognition service. The
     * on-device engine needs API 31+; on API 26-30 some devices ship no
     * recognition service at all, so the push-to-talk affordance is hidden
     * there instead of offering a dead button.
     */
    private fun hasSpeechRecognitionService(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ||
                SpeechRecognizer.isRecognitionAvailable(this)
        } else {
            SpeechRecognizer.isRecognitionAvailable(this)
        }
    }

    /** Wire up the mic button and the recognizer controller. */
    private fun setupVoiceCapture() {
        micButton = findViewById(R.id.micButton)

        if (!hasSpeechRecognitionService()) {
            Log.i(TAG, "No speech recognition service - hiding micButton")
            micButton.isVisible = false
        }

        voiceCaptureController = VoiceCaptureController(this).apply {
            setListener(voiceCaptureListener)
        }

        // Tap-to-toggle: first tap starts listening, second tap stops and
        // sends - the same toggle body the watch PLAY/PAUSE buttons use.
        micButton.setOnClickListener {
            toggleVoiceCapture()
        }
    }

    /**
     * Shared push-to-talk toggle for the in-app mic button and the watch NEXT
     * button: first activation starts a capture, the next one stops and
     * sends it.
     *
     * v3.6 watch-PTT echo guard: wearable media presses can arrive twice
     * (echo between the watch callback and the media session, plus bounce),
     * which flipped the mic on and instantly off. Watch-sourced toggles
     * inside a 2 s window of the previous toggle are swallowed;
     * elapsedRealtime() is immune to wall-clock jumps. In-app taps pass
     * fromWatch=false and always run - the upstream 500 ms debounce still
     * covers them.
     */
    private fun toggleVoiceCapture(fromWatch: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (fromWatch && now - lastPttToggleAt <= PTT_ECHO_GUARD_MS) {
            Log.i(TAG, "Watch PTT toggle within ${PTT_ECHO_GUARD_MS}ms echo window - ignored")
            return
        }
        lastPttToggleAt = now
        // v3.6.4: a PTT press (start or stop) always supersedes the
        // post-playback HUD loop - her pending marquee passes end here.
        cancelWatchHudLoop()
        val controller = voiceCaptureController ?: return
        if (controller.isListening()) {
            controller.stopListening()
        } else {
            startVoiceCapture()
        }
    }

    /** Begin a capture, requesting RECORD_AUDIO on first use. */
    private fun startVoiceCapture() {
        val controller = voiceCaptureController ?: return
        // Mic hygiene (v3.3): duck her voice before the mic goes live so the
        // recognizer never transcribes Polaris's own TTS output.
        // v3.5: abort a streaming reply too - the watch PLAY push-to-talk
        // press must silence her mid-sentence, instantly.
        MediaSessionManager.getInstance(this).pause()
        streamingTtsPlayer?.abort()
        if (currentlyPlayingMessageId != -1L) {
            // A bubble replay was speaking - reset its play icon.
            currentlyPlayingMessageId = -1L
            rebuildChatUI()
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            controller.startListening()
        } else {
            Log.i(TAG, "Requesting RECORD_AUDIO before voice capture")
            pendingMicPermission = true
            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
        }
    }

    // Voice capture callbacks (delivered on the main thread). Live partials
    // stream into the input field; a red mic tint marks the capturing state.
    private val voiceCaptureListener = object : VoiceCaptureController.VoiceCaptureListener {
        override fun onCaptureStarted() {
            micButton.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.alert_red)
            )
            // v3.4 capture visibility: make a watch PLAY press obvious on the
            // phone WITHOUT the operator having to tap the input field first.
            // Red-bordered field + focused caret + "Listening..." hint; the
            // IME stays hidden (walkie-talkie mode - talking, not typing).
            messageInput.background = ContextCompat.getDrawable(
                this@MainActivity, R.drawable.input_field_bg_capture
            )
            if (restingInputHint == null) restingInputHint = messageInput.hint.toString()
            messageInput.hint = "Listening..."
            messageInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(messageInput.windowToken, 0)
            // LGI ⚤ MIC LED — capture is live.
            setMicLed(true)
        }

        override fun onPartialResult(text: String) {
            if (text.isNotBlank()) {
                // Live partials land in the input field so the operator can
                // watch (and correct) the transcript as it forms.
                messageInput.setText(text)
                messageInput.setSelection(messageInput.text.length)
            }
        }

        override fun onCaptureComplete(text: String) {
            resetCaptureUi()
            if (text.isBlank()) return
            // Route through the standard send path so the transcript gets the
            // same gateway-ready checks and WebSocket delivery as typed input.
            messageInput.setText(text)
            sendMessage()
        }

        override fun onCaptureError(reason: String) {
            resetCaptureUi()
            Toast.makeText(this@MainActivity, "Voice capture: $reason", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Restore the resting capture visuals after a capture ends (any terminal
     * path: complete, error, or a watch-STOP hard abort). Re-tints the mic
     * button and returns the input field to its resting background + hint.
     */
    private fun resetCaptureUi() {
        micButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.electric_blue)
        )
        messageInput.background = ContextCompat.getDrawable(
            this, R.drawable.input_field_bg
        )
        restingInputHint?.let { messageInput.hint = it }
        // LGI ⚤ MIC LED — back to idle on every terminal capture path.
        setMicLed(false)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return

        // If the WebSocket client somehow isn't ready yet, keep the typed text
        // and let the user retry rather than crashing (lateinit guard)
        if (!this::polarisWebSocket.isInitialized) {
            Log.w(TAG, "WebSocket not initialized - message not sent")
            Toast.makeText(this, "Polaris Gateway not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        // Do NOT add message here - wait for WebSocket broadcast to prevent duplicates
        // The gateway broadcasts all messages via chat_message event
        messageInput.text.clear()

        // Send to Polaris via WebSocket
        polarisWebSocket.sendMessage(text)
    }

    private fun initializeSystems() {
        // v3.6: sweep the legacy WAV cache (text-only playback writes no new
        // artifacts) so Music/Polaris never accumulates again. Idempotent -
        // after the first sweep it is an empty query.
        Thread {
            val pruned = PolarisAudioStore.pruneAllWavs(this)
            if (pruned > 0) Log.i(TAG, "Pruned $pruned legacy WAV artifact(s)")
        }.start()

        // Initialize Kokoro TTS on a background thread. Native ONNX initialization
        // (System.loadLibrary via OrtEnvironment + createSession) must never run on
        // the main thread: an UnsatisfiedLinkError or native crash there kills the
        // whole app with no chance to degrade gracefully.
        Thread {
            try {
                Log.d(TAG, "Initializing Kokoro TTS (background thread)...")
                val ttsInstance = KokoroTTS(this)
                ttsInstance.setVoice(selectedVoice)
                ttsInstance.setSpeed(currentSpeed)
                kokoroTTS = ttsInstance
                Log.i(TAG, "Kokoro TTS initialized successfully")
            } catch (t: Throwable) {
                // Throwable, NOT Exception: native init failures surface as Errors
                // (UnsatisfiedLinkError, OutOfMemoryError). The app must survive a
                // TTS failure and remain fully usable for text chat.
                Log.e(TAG, "Failed to initialize Kokoro TTS: ${t.message}", t)
                kokoroTTS = null
                mainHandler.post {
                    Toast.makeText(this, "TTS engine unavailable - voice features disabled", Toast.LENGTH_LONG).show()
                }
            }
        }.start()

        // Initialize Polaris WebSocket with detected user identity
        // Post to ensure activity is fully initialized before connecting
        mainHandler.post {
            try {
                Log.i(TAG, "Connecting as $userIdentity from IP $deviceIpAddress")
                // Client instance is created synchronously in onCreate; only the
                // (non-blocking) connect happens here after identity detection.
                polarisWebSocket.connect(userIdentity, deviceIpAddress, this)
                Log.i(TAG, "Polaris WebSocket initialization complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Polaris WebSocket: ${e.message}", e)
                Toast.makeText(this, "Failed to connect to Polaris Gateway", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Detect device IP address and map to user identity
     * IP ending in .42 -> Rich
     * IP ending in .98 -> Matt
     * Others -> Rich (fallback for unknown devices on the network)
     * 
     * REQUIRES: ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission (Android 6+)
     * Without location permission, WiFi info cannot be read and this will fail.
     */
    private fun detectUserIdentity() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            
            // Check if WiFi is connected
            val networkId = wifiInfo.networkId
            val ssid = wifiInfo.ssid.removePrefix("\"").removeSuffix("\"") // Remove quotes from SSID
            
            Log.d(TAG, "WiFi Network ID: $networkId, SSID: $ssid")
            
            if (networkId == -1) {
                Log.w(TAG, "WiFi not connected to any network (networkId=-1). Using fallback identity.")
                deviceIpAddress = "192.168.50.42"
                userIdentity = "Rich"
                return
            }
            
            val ipInt = wifiInfo.ipAddress
            
            Log.d(TAG, "WiFi IP (int): $ipInt, SSID: $ssid, Network ID: $networkId")
            
            if (ipInt != 0) {
                // Convert IP integer to string (little-endian format)
                val ipBytes = byteArrayOf(
                    (ipInt and 0xff).toByte(),
                    (ipInt shr 8 and 0xff).toByte(),
                    (ipInt shr 16 and 0xff).toByte(),
                    (ipInt shr 24 and 0xff).toByte()
                )
                deviceIpAddress = InetAddress.getByAddress(ipBytes).hostAddress ?: "192.168.50.42"
                
                Log.i(TAG, "Device IP address: $deviceIpAddress, connected to SSID: $ssid")
                
                // Map IP to user identity based on last octet
                val lastOctet = deviceIpAddress.substringAfterLast(".")
                userIdentity = when (lastOctet) {
                    "42" -> {
                        Log.i(TAG, "Detected Rich's device (IP: $deviceIpAddress)")
                        "Rich"
                    }
                    "98" -> {
                        Log.i(TAG, "Detected Matt's device (IP: $deviceIpAddress)")
                        "Matt"
                    }
                    else -> {
                        Log.i(TAG, "Unknown device ($lastOctet) at IP $deviceIpAddress, defaulting to Rich")
                        "Rich"  // Default to Rich for unknown IPs on the network
                    }
                }
            } else {
                Log.w(TAG, "WiFi connected but IP address is 0 (not yet assigned). Using fallback.")
                deviceIpAddress = "192.168.50.42"
                userIdentity = "Rich"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied! Cannot read WiFi info. Using fallback identity.", e)
            Toast.makeText(this, "Location permission required for WiFi identity detection", Toast.LENGTH_LONG).show()
            deviceIpAddress = "192.168.50.42"
            userIdentity = "Rich"
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting IP address: ${e.message}", e)
            // Fallback to Rich's IP instead of server IP
            deviceIpAddress = "192.168.50.42"
            userIdentity = "Rich"
        }
        
        Log.i(TAG, "Final user identity: $userIdentity, IP: $deviceIpAddress")
        
        // (WireGuard identity fix) Push the detected identity into
        // ChatRepository so REST calls declare ?user_id=/user_id — the
        // gateway can no longer rely on our source IP alone (WireGuard NATs
        // this phone to a Docker bridge IP that maps to "Dashboard").
        ChatRepository.getInstance(this).setUserIdentity(userIdentity)
    }

    private fun addMessageBubble(text: String, isUser: Boolean) {
        noChatsText.isVisible = false

        // Create and store the message in our rolling window
        val message = com.freeroam.tactical.data.ChatMessage(
            id = System.currentTimeMillis(),
            text = text,
            isUser = isUser,
            timestamp = System.currentTimeMillis()
        )
        
        // Add to our rolling window
        chatMessages.add(message)
        
        // Maintain only last 10 messages
        if (chatMessages.size > MAX_CHAT_MESSAGES) {
            chatMessages.removeAt(0)
        }
        
        // Rebuild the UI from the rolling window
        rebuildChatUI()
    }
    
    /**
     * Rebuild chat UI from the rolling window of messages
     */
    private fun rebuildChatUI() {
        chatBubbleContainer.removeAllViews()
        
        chatMessages.forEach { message ->
            val bubbleView = LayoutInflater.from(this)
                .inflate(R.layout.item_message_bubble, chatBubbleContainer, false)

            val bubbleText = bubbleView.findViewById<TextView>(R.id.bubbleText)
            val bubbleSender = bubbleView.findViewById<TextView>(R.id.bubbleSender)
            val bubbleBackground = bubbleView.findViewById<View>(R.id.bubbleBackground)

            bubbleText.text = message.text
            bubbleSender.text = if (message.isUser) "YOU" else "POLARIS"
            // LGI chat tinting: user rows #f8fafc / Polaris rows #9ae6ff.
            bubbleText.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (message.isUser) R.color.lgi_you_text else R.color.lgi_polaris_text
                )
            )
            bubbleSender.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (message.isUser) R.color.lgi_ledtag else R.color.lgi_title
                )
            )

            if (message.isUser) {
                bubbleBackground.setBackgroundResource(R.drawable.bubble_user)
            } else {
                bubbleBackground.setBackgroundResource(R.drawable.bubble_ai)
            }

            // Per-bubble playback control for Polaris replies
            val bubblePlayButton = bubbleView.findViewById<ImageButton>(R.id.bubblePlayButton)
            if (message.isUser) {
                bubblePlayButton.isVisible = false
            } else {
                bubblePlayButton.isVisible = true
                val media = MediaSessionManager.getInstance(this)
                val isPlayingThis = currentlyPlayingMessageId == message.id && media.isPlaying()
                bubblePlayButton.setImageResource(
                    if (isPlayingThis) R.drawable.ic_pause else R.drawable.ic_play
                )
                bubblePlayButton.setOnClickListener { toggleBubblePlayback(message) }
            }

            // Align bubbles: user messages hug the right edge, Polaris replies
            // the left. bubbleBackground is wrap_content now, so gravity works.
            val bubbleLp = bubbleBackground.layoutParams as FrameLayout.LayoutParams
            bubbleLp.gravity = if (message.isUser) Gravity.END else Gravity.START
            bubbleBackground.layoutParams = bubbleLp

            chatBubbleContainer.addView(bubbleView)
        }
        
        chatScrollView.post {
            chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * Speak a Polaris reply through the streaming pipeline (v3.6 text-only):
     * sentences synthesize on the Kokoro executor while earlier ones are
     * already audible - no WAV artifact is ever written; the bubble's replay
     * button re-synthesizes from text on demand. The reply text is persisted
     * to PolarisTextStore so cross-restart replays keep working.
     */
    private fun speakPolarisText(message: com.freeroam.tactical.data.ChatMessage) {
        val text = message.text.trim()
        if (text.isEmpty()) return
        val engine = kokoroTTS ?: return  // TTS unavailable - nothing to speak
        val player = streamingTtsPlayer ?: StreamingTtsPlayer(ttsExecutor) { sid, fullText ->
            onStreamingPlaybackFinished(sid, fullText)
        }.also { streamingTtsPlayer = it }
        // A new speak preempts whatever is currently playing (v3.6.4: and
        // kills any pending post-playback HUD loop from the last reply).
        cancelWatchHudLoop()
        // v3.7.2 HUD-first: her words are already known on a replay - title
        // the watch with the FULL text before Kokoro starts synthesizing, so
        // the marquee scrolls her response as her first sentence speaks
        // (~1-2 s of lead) instead of a placeholder that outlives her voice.
        // Reset the throttle + accumulator so this push lands instantly.
        streamHudText.setLength(0)
        lastWatchHudPushAt = 0L
        lastWatchHudTitle = ""
        MediaSessionManager.getInstance(this).updatePolarisMetadata(text, userIdentity)
        player.speakText("speak_${message.id}_${System.currentTimeMillis()}", text, engine)
        // LGI ◈ TTS LED — she is speaking.
        setTtsLed(true)
        // Persist the reply text so reserved replay paths keep working
        // across restarts.
        ttsExecutor.execute {
            if (!PolarisTextStore.saveMessageText(this, message.id, text)) {
                Log.w(TAG, "Failed to persist text for message ${message.id}")
            }
        }
        MediaSessionManager.getInstance(this).ensureTetherLooping()
    }

    /**
     * v3.5 streaming speech: feed one chat_stream delta into the player.
     * Creates the session on the first delta of a new stream (v3.7.2: the
     * watch HUD gets her first words the moment she starts talking - no
     * placeholder ever ships). Runs on the main thread from
     * onStreamingMessage.
     */
    private fun startStreamingPlayback(streamId: String, delta: String) {
        val engine = kokoroTTS ?: return  // TTS unavailable - whole-text fallback
        val player = streamingTtsPlayer ?: StreamingTtsPlayer(ttsExecutor) { sid, fullText ->
            onStreamingPlaybackFinished(sid, fullText)
        }.also { streamingTtsPlayer = it }
        if (!player.isActiveFor(streamId)) {
            player.begin(streamId, engine)
            // LGI ◈ TTS LED — streaming speech is live.
            setTtsLed(true)
            // v3.6.4: a live stream supersedes any post-playback HUD loop.
            cancelWatchHudLoop()
            // v3.7.2 HUD-first: no "Polaris is speaking..." placeholder - it
            // raced the first live slice as two AVRCP track changes in one
            // frame and the watch coalesced the burst onto the placeholder,
            // which then stuck for the whole speech (deltas stop arriving
            // long before her voice drains). The FIRST thing the watch sees
            // is her actual first words, pushed below. Reset the throttle +
            // pre-Kokoro accumulator so the first slice lands instantly.
            streamHudText.setLength(0)
            lastWatchHudPushAt = 0L
            lastWatchHudTitle = ""
            // The capture's mic hygiene paused the silent seed - engage the
            // loop so the session reads PLAYING while she speaks: the watch
            // shows a pause affordance, and pressing it stops her speech
            // and starts the mic (both PLAY and PAUSE route to the PTT
            // toggle). v3.6.3: mid-speech HUD updates flow again, one
            // throttled retitle per WATCH_HUD_TITLE_INTERVAL_MS - every
            // push stamps a fresh mediaId so the Garmin treats it as a new
            // track and reliably refreshes (the churn that banned these
            // updates predates v3.5.2's mediaId refresh); her full reply
            // still takes the title once, at completion.
            MediaSessionManager.getInstance(this).ensureTetherLooping()
        }
        // Superseded / stale deltas never touch the accumulator or player.
        if (!player.isActiveFor(streamId)) return
        // v3.7.2 HUD-first, Kokoro-second: the delta hits the accumulator
        // and the watch BEFORE it is fed into synthesis - the display starts
        // typing her response while Kokoro is still working on the audio.
        streamHudText.append(delta)
        pushWatchHudTitle(streamId)
        player.feed(streamId, delta)
    }

    /**
     * Streaming playback drained (v3.6 text-only): no WAV is written - the
     * bubble's replay button re-synthesizes from text on demand. Refresh the
     * watch HUD with her full reply, keep the silent tether loop hot, and
     * reset the bubble-playing state when this was a replay.
     */
    private fun onStreamingPlaybackFinished(streamId: String, fullText: String) {
        Log.i(TAG, "Streaming playback finished ($streamId, ${fullText.length} chars)")
        // v3.6.3: the completion retitle below owns the HUD now - reset the
        // push throttle so the next stream's first slice lands instantly.
        // v3.7.2: and retire the pre-Kokoro HUD accumulator with the stream.
        streamHudText.setLength(0)
        lastWatchHudPushAt = 0L
        lastWatchHudTitle = ""
        // LGI ◈ TTS LED — speech drained.
        setTtsLed(false)
        // Her full reply takes the track title (fresh mediaId = a "new
        // track" the Garmin reliably refreshes and marquee-scrolls)...
        MediaSessionManager.getInstance(this).updatePolarisMetadata(fullText, userIdentity)
        // ...then the silent seed parks PAUSED: the watch flips back to a
        // PLAY button exactly as her voice drains, ready for the next
        // push-to-talk press - no more stuck pause affordance at idle.
        MediaSessionManager.getInstance(this).pauseTetherLoop()
        // v3.6.4: loop the full reply across the watch - the completion
        // retitle above was pass one; WATCH_HUD_LOOP_PASSES total passes,
        // then the HUD is restored to "Polaris standing by".
        scheduleWatchHudLoop(fullText)
        if (currentlyPlayingMessageId != -1L) {
            currentlyPlayingMessageId = -1L
            rebuildChatUI()
        }
    }

    /**
     * v3.7.2 watch HUD (was v3.6.3): stream her generating reply across the
     * Garmin track title from the PRE-KOKORO accumulator - the HUD lags her
     * typing, never her synthesis pipeline. Throttled to one AVRCP retitle
     * per WATCH_HUD_TITLE_INTERVAL_MS; every push stamps a fresh mediaId
     * (inside updatePolarisMetadata) so the watch refreshes reliably instead
     * of ignoring an in-place update. The tail slice keeps the newest words
     * scrolling; identical titles are skipped to avoid pointless churn
     * (whole-text replays are titled with the full text at play start).
     */
    private fun pushWatchHudTitle(streamId: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWatchHudPushAt < WATCH_HUD_TITLE_INTERVAL_MS) return
        val live = streamHudText.toString().trim()
        if (live.isBlank()) return
        val title = if (live.length > WATCH_HUD_TITLE_MAX_CHARS) {
            live.takeLast(WATCH_HUD_TITLE_MAX_CHARS)
        } else {
            live
        }
        if (title == lastWatchHudTitle) return
        lastWatchHudPushAt = now
        lastWatchHudTitle = title
        MediaSessionManager.getInstance(this).updatePolarisMetadata(title, userIdentity)
    }

    /**
     * v3.6.4 watch HUD close-out: once her reply finishes speaking, the
     * Garmin gets WATCH_HUD_LOOP_PASSES marquee passes of the full response -
     * the completion retitle in onStreamingPlaybackFinished is pass one, then
     * each scheduled pass re-pushes the reply as a fresh "track" (the fresh
     * mediaId inside updatePolarisMetadata makes the watch re-scroll it).
     * The cadence scales with the reply's length (WATCH_HUD_LOOP_CHAR_MS per
     * char, clamped) so a long reply gets enough scroll time per pass. After
     * the last pass the HUD is restored to "Polaris standing by" so the idle
     * watch is deterministic: the PLAY affordance under the standing-by
     * title, ready for the next push-to-talk press. Cancelled by any new
     * speech (capture / stream / replay), a STOP, or teardown.
     */
    private fun scheduleWatchHudLoop(fullText: String) {
        cancelWatchHudLoop()
        val interval = (fullText.length * WATCH_HUD_LOOP_CHAR_MS)
            .coerceIn(WATCH_HUD_LOOP_MIN_MS, WATCH_HUD_LOOP_MAX_MS)
        var pass = 1
        val runnable = object : Runnable {
            override fun run() {
                watchHudLoopRunnable = null
                if (pass >= WATCH_HUD_LOOP_PASSES) {
                    // All passes shown - the watch settles on "Polaris
                    // standing by" (fresh mediaId = the guaranteed refresh),
                    // and the HUD push throttle resets for the next stream.
                    lastWatchHudPushAt = 0L
                    lastWatchHudTitle = ""
                    MediaSessionManager.getInstance(this@MainActivity)
                        .restoreStandbyTitle(userIdentity)
                    return
                }
                pass++
                MediaSessionManager.getInstance(this@MainActivity)
                    .updatePolarisMetadata(fullText, userIdentity)
                watchHudLoopRunnable = this
                mainHandler.postDelayed(this, interval)
            }
        }
        watchHudLoopRunnable = runnable
        mainHandler.postDelayed(runnable, interval)
    }

    /** Kill any pending post-playback HUD loop (v3.6.4). Main thread only. */
    private fun cancelWatchHudLoop() {
        watchHudLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        watchHudLoopRunnable = null
    }

    /** Register the wearable (Garmin AVRCP) media-button hooks. */
    private fun setupWatchMediaButtons() {
        MediaSessionManager.getInstance(this).setWatchMediaListener(watchMediaListener)
    }

    // Watch media-button routing (v3.3 operator map): PLAY/PAUSE is the
    // push-to-talk toggle, STOP cancels an in-flight capture or halts
    // playback tethered, BACK/NEXT are disabled. The in-app mini player
    // keeps its full transport separately.
    private val watchMediaListener = object : MediaSessionManager.WatchMediaListener {
        override fun onWatchPlay() {
            // v3.6 echo guard: this press (and its AVRCP sibling, PAUSE -
            // both map to onWatchPlay()) may arrive twice from the wearable
            // layer; the 2 s window in toggleVoiceCapture(fromWatch = true)
            // swallows the duplicate instead of flipping the mic off again.
            toggleVoiceCapture(fromWatch = true)
        }

        override fun onWatchStop() {
            handleWatchStopRequest()
        }

        override fun onWatchPrevious() {
            Log.i(TAG, "Watch BACK pressed - disabled")
        }

        override fun onWatchNext() {
            Log.i(TAG, "Watch NEXT pressed - disabled")
        }
    }

    /**
     * Watch STOP: cancel a live capture (hard abort - nothing is sent), or,
     * when idle, halt playback. cancel() delivers no listener callback, so
     * the capture visuals reset here.
     */
    private fun handleWatchStopRequest() {
        val controller = voiceCaptureController
        if (controller != null && controller.isListening()) {
            Log.i(TAG, "Watch STOP pressed mid-capture - cancelling")
            controller.cancel()
            resetCaptureUi()
            return
        }
        handleStopRequest()
    }

    /** In-app mini player PLAY/PAUSE body (watch PLAY is push-to-talk since v3.3). */
    private fun handlePlayPauseRequest() {
        if (currentlyPlayingMessageId == -1L) return
        val media = MediaSessionManager.getInstance(this)
        media.togglePlayPause()
        miniPlayerView.setPlaying(media.isPlaying())
        if (media.isPlaying()) {
            mainHandler.removeCallbacks(playbackPollRunnable)
            mainHandler.post(playbackPollRunnable)
        }
        rebuildChatUI()
    }

    /**
     * Shared STOP body: mini player stop and watch STOP (when idle) land
     * here. Halts audio tethered - the media session survives with her
     * latest response still scrolling on the watch display.
     */
    private fun handleStopRequest() {
        // v3.6.4: halting playback never leaves the reply looping on the
        // watch - the HUD settles on standing-by right away.
        cancelWatchHudLoop()
        // v3.5: a tethered halt also silences a streaming reply.
        streamingTtsPlayer?.abort()
        // LGI ◈ TTS LED — halted on request.
        setTtsLed(false)
        MediaSessionManager.getInstance(this).stop()
        // v3.6.4: the post-stop re-seed now carries the last-pushed title
        // (her reply); lay the standing-by title over it with a fresh mediaId
        // so the idle watch is deterministic: PLAY under "Polaris standing by".
        MediaSessionManager.getInstance(this).restoreStandbyTitle(userIdentity)
        mainHandler.removeCallbacks(playbackPollRunnable)
        currentlyPlayingMessageId = -1L
        miniPlayerView.setPlaying(false)
        miniPlayerView.reset()
        miniPlayerView.hidePlayer()
        rebuildChatUI()
    }

    /**
     * Reserved replay path (v3.2 watch BACK). No watch button routes here
     * since v3.3 disabled BACK/NEXT, but the re-seed/WAV-reuse/re-synthesis
     * logic stays warm for future re-binding. Post-restart the rolling
     * window may be empty, so fall back to PolarisTextStore: re-seed the
     * bubble, reuse the WAV if it still exists, otherwise re-synthesize
     * from the stored text and speak aloud.
     */
    @Suppress("unused")
    private fun replayLatestPolarisMessage() {
        val target = chatMessages.lastOrNull { !it.isUser }
            ?: PolarisTextStore.findLatest(this)?.let { stored ->
                val stub = com.freeroam.tactical.data.ChatMessage(
                    id = stored.messageId,
                    text = stored.text,
                    isUser = false,
                    timestamp = stored.messageId
                )
                chatMessages.add(stub)
                if (chatMessages.size > MAX_CHAT_MESSAGES) {
                    chatMessages.removeAt(0)
                }
                rebuildChatUI()
                stub
            }
        if (target == null) {
            Toast.makeText(this, "No Polaris message to replay yet", Toast.LENGTH_SHORT).show()
            return
        }
        // (v3.6 text-only) speak fresh from the stored text - no WAV reuse.
        speakPolarisText(target)
    }

    /**
     * Link a saved WAV Uri to the ChatMessage in the rolling window and
     * refresh the bubble UI.
     */
    private fun updateMessageAudioPath(messageId: Long, audioUri: Uri) {
        val index = chatMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            chatMessages[index] = chatMessages[index].copy(audioFilePath = audioUri.toString())
            rebuildChatUI()
        }
    }

    /**
     * Take over the mini player with this Polaris message's saved audio and
     * start the position poller.
     */
    private fun playPolarisMessage(message: com.freeroam.tactical.data.ChatMessage) {
        val audioPath = message.audioFilePath ?: return
        val audioUri = try {
            Uri.parse(audioPath)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid audio Uri for message ${message.id}: $audioPath")
            return
        }
        currentlyPlayingMessageId = message.id
        val media = MediaSessionManager.getInstance(this)
        media.playUri(audioUri, currentSpeed)
        media.updatePolarisMetadata(message.text, userIdentity)
        val title = if (message.text.length > 60) message.text.take(60) + "..." else message.text
        miniPlayerView.setTitle(title)
        miniPlayerView.reset()
        miniPlayerView.showPlayer()
        miniPlayerView.setPlaying(true)
        rebuildChatUI()
        mainHandler.removeCallbacks(playbackPollRunnable)
        mainHandler.post(playbackPollRunnable)
    }

    /**
     * Bubble playback (v3.6 text-only): tapping a play button speaks the
     * message's text fresh through the streaming pipeline (first word in
     * ~1-2 s); tapping it again stops the speech. No WAV artifacts - replay
     * always synthesizes from text.
     */
    private fun toggleBubblePlayback(message: com.freeroam.tactical.data.ChatMessage) {
        if (currentlyPlayingMessageId == message.id) {
            // Same bubble tapped again - stop the speech.
            streamingTtsPlayer?.abort()
            // LGI ◈ TTS LED — replay halted.
            setTtsLed(false)
            currentlyPlayingMessageId = -1L
            rebuildChatUI()
            return
        }
        currentlyPlayingMessageId = message.id
        rebuildChatUI()
        speakPolarisText(message)
    }

    /**
     * Natural completion cleanup: stop the poller and reset the mini player
     * and bubble icons. The mini player stays up so play can replay it.
     */
    private fun onPolarisPlaybackEnded() {
        mainHandler.removeCallbacks(playbackPollRunnable)
        miniPlayerView.setPlaying(false)
        // LGI ◈ TTS LED — natural playback end.
        setTtsLed(false)
        rebuildChatUI()
    }

    // ── LGI HUD (Looking Glass v1.4 port) ─────────────────────────────────
    /** ⋯ kebab toggles the ⚙ CONTROLS drawer; ▼ toggles TACTICAL OPERATIONS. */
    private fun setupHudPanels() {
        hudKebab.setOnClickListener {
            val willShow = hudSettingsDrawer.visibility != View.VISIBLE
            hudSettingsDrawer.visibility = if (willShow) View.VISIBLE else View.GONE
        }
        opsToggle.setOnClickListener {
            val willCollapse = opsContainer.visibility == View.VISIBLE
            opsContainer.visibility = if (willCollapse) View.GONE else View.VISIBLE
            opsToggle.text = getString(
                if (willCollapse) R.string.lgi_ops_title_closed
                else R.string.lgi_ops_title_open
            )
        }
        setLedState(polLed, false)
        setLedState(feedLed, false)
        setLedState(ttsLed, false)
        setLedState(micLed, false)
        updateTaskStatusLine()
        refreshHudChips()
    }

    /** LGI dot states: on = linked/active (green), off = dim slot. */
    private fun setLedState(led: View, on: Boolean) {
        try {
            led.setBackgroundResource(if (on) R.drawable.led_on else R.drawable.led_off)
        } catch (t: Throwable) {
            Log.w(TAG, "LED update failed: ${t.message}")
        }
    }

    /** ◈ TTS LED — main-thread safe; driven from speak/stream/stop hooks. */
    private fun setTtsLed(on: Boolean) {
        mainHandler.post {
            if (this::ttsLed.isInitialized) setLedState(ttsLed, on)
        }
    }

    /** ◈ MIC LED + chip — capture lifecycle (started / any terminal path). */
    private fun setMicLed(on: Boolean) {
        mainHandler.post {
            if (!this::micLed.isInitialized) return@post
            setLedState(micLed, on)
            micChip.text = getString(
                R.string.lgi_chip_mic,
                getString(if (on) R.string.lgi_mic_listening else R.string.lgi_mic_idle)
            )
        }
    }

    /** VOICE/SPD chips mirror the active Kokoro settings. */
    private fun refreshHudChips() {
        try {
            if (!this::voiceChip.isInitialized) return
            voiceChip.text = getString(R.string.lgi_chip_voice, selectedVoice)
            speedChip.text = getString(
                R.string.lgi_chip_speed, String.format("%.1fx", currentSpeed))
        } catch (t: Throwable) {
            Log.w(TAG, "HUD chips refresh failed: ${t.message}")
        }
    }

    /** gateway_feed.py TASK_ROW_STYLES as a Kotlin when (icon + color). */
    private fun lgiTaskRowStyle(kind: String): Pair<String, Int> = when (kind) {
        "task_registered" -> "◇" to R.color.task_registered
        "task_started" -> "▶" to R.color.task_started
        "task_progress" -> "⟳" to R.color.task_progress
        "task_completed" -> "✓" to R.color.task_completed
        "task_failed" -> "✗" to R.color.task_failed
        "chat_tool" -> "⚙" to R.color.chat_tool
        else -> "•" to R.color.lgi_button_text
    }

    /**
     * ▼ TACTICAL OPERATIONS feed: render one gateway status-room event as an
     * LGI monospace row. Newest at top; the feed rolls at 50 events
     * (LGI TASKFEED_WINDOW). Main thread only.
     */
    private fun appendTaskFeedRow(kind: String, task: org.json.JSONObject) {
        try {
            if (!this::taskFeedContainer.isInitialized) return
            if (taskFeedPlaceholder.visibility == View.VISIBLE) {
                taskFeedPlaceholder.visibility = View.GONE
            }
            val (icon, colorRes) = lgiTaskRowStyle(kind)
            val title = task.optString("title").ifEmpty {
                task.optString("name").ifEmpty {
                    task.optString("task").ifEmpty {
                        task.optString("id").ifEmpty { "task event" }
                    }
                }
            }
            val progress = task.optInt("progress", -1)
            val detail = if (progress in 0..99) " · $progress%" else ""
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            taskFeedRows.addFirst(LgiTaskRow(icon, colorRes, "$title$detail", ts))
            if (taskFeedRows.size > MAX_TASK_FEED_ROWS) {
                taskFeedRows.removeLast()
                taskFeedContainer.removeViewAt(taskFeedContainer.childCount - 1)
            }
            val rowView = TextView(this).apply {
                text = getString(R.string.lgi_task_row, icon, taskFeedRows.first().title) +
                    "   " + taskFeedRows.first().ts
                setTextColor(ContextCompat.getColor(context, taskFeedRows.first().colorRes))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(4, 7, 4, 3)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            taskFeedContainer.addView(rowView, 0)
            activeTaskCount = when (kind) {
                "task_started" -> activeTaskCount + 1
                "task_completed", "task_failed" -> (activeTaskCount - 1).coerceAtLeast(0)
                else -> activeTaskCount
            }
            updateTaskStatusLine()
        } catch (t: Throwable) {
            Log.w(TAG, "Task-feed render failed: ${t.message}")
        }
    }

    /** Active-tasks snapshot line (LGI active-tasks status row). */
    private fun updateTaskStatusLine() {
        if (!this::taskStatusLine.isInitialized) return
        taskStatusLine.text = getString(
            R.string.lgi_task_status_none, activeTaskCount,
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()))
    }

    // PolarisWebSocketListener Implementation
    override fun onConnectionStatusChanged(connected: Boolean) {
        mainHandler.post {
            if (connected) {
                connectionStatus.text = "CONNECTED"
                connectionStatus.setTextColor(Color.parseColor("#44ff44"))
                processingIndicator.setBackgroundResource(R.drawable.sphere_connected)
                // LGI ◈ POL / FEED LEDs — gateway link up.
                setLedState(polLed, true)
                setLedState(feedLed, true)
                refreshHudChips()
                // Fetch chat history after successful connection
                fetchChatHistory()
                // Start periodic chat sync polling
                startChatSyncPolling()
            } else {
                connectionStatus.text = "DISCONNECTED"
                connectionStatus.setTextColor(Color.parseColor("#ff4444"))
                processingIndicator.setBackgroundResource(R.drawable.sphere_disconnected)
                // LGI ◈ POL / FEED LEDs — link down.
                setLedState(polLed, false)
                setLedState(feedLed, false)
                // Stop polling when disconnected
                stopChatSyncPolling()
            }
        }
    }
    
    /**
     * Fetch chat history from Polaris Gateway via REST API
     * Uses IP-based user identity detection on the server
     */
    private fun fetchChatHistory() {
        lifecycleScope.launch {
            try {
                val chatRepository = ChatRepository.getInstance(this@MainActivity)
                val messages = chatRepository.getMessages()
                Log.d(TAG, "Fetched ${messages.size} messages from server")
                onChatHistory(messages)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch chat history: ${e.message}")
            }
        }
    }

    /**
     * Sync chat history with server - checks for new messages from other devices
     * Called periodically every 3 seconds like Polaris Dashboard
     */
    private fun syncChatHistory() {
        lifecycleScope.launch {
            try {
                val chatRepository = ChatRepository.getInstance(this@MainActivity)
                val messages = chatRepository.getMessages()

                // LGI chips mirror active settings; piggyback on the 3s sync.
                refreshHudChips()

                // Sort messages by timestamp to get the latest
                val sortedMessages = messages.sortedBy { it.timestamp }
                val latestRemoteMessage = sortedMessages.lastOrNull()
                val latestLocalMessage = chatMessages.lastOrNull()
                
                // Check if there are new messages we don't have yet
                val hasNewMessages = if (latestRemoteMessage == null) {
                    false
                } else if (latestLocalMessage == null) {
                    // We have no local messages but server has some
                    true
                } else {
                    // Compare timestamps - if remote is newer, we have new messages
                    latestRemoteMessage.timestamp > latestLocalMessage.timestamp
                }
                
                if (hasNewMessages) {
                    Log.d(TAG, "New messages detected from other devices, syncing...")
                    // Update our rolling window with new messages
                    chatMessages.clear()
                    val startIndex = maxOf(0, sortedMessages.size - MAX_CHAT_MESSAGES)
                    for (i in startIndex until sortedMessages.size) {
                        chatMessages.add(sortedMessages[i])
                    }
                    rebuildChatUI()
                    
                    // Trigger TTS for new Polaris messages
                    val newMessages = sortedMessages.filter { 
                        it.timestamp > (latestLocalMessage?.timestamp ?: 0L) 
                    }
                    newMessages.forEach { message ->
                        if (message.isUser == false && message.timestamp > lastSpokenTimestamp + 1000) { // Polaris messages have isUser = false
                            lastSpokenTimestamp = message.timestamp
                            speakPolarisText(message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync chat history: ${e.message}")
            }
        }
    }

    /**
     * Start periodic chat history polling to sync with other devices
     */
    private fun startChatSyncPolling() {
        // Stop any existing polling first
        stopChatSyncPolling()
        
        chatSyncRunnable = object : Runnable {
            override fun run() {
                syncChatHistory()
                chatSyncHandler.postDelayed(this, CHAT_SYNC_INTERVAL_MS)
            }
        }
        chatSyncRunnable?.let { chatSyncHandler.post(it) }
        Log.d(TAG, "Chat sync polling started (every ${CHAT_SYNC_INTERVAL_MS}ms)")
    }

    /**
     * Stop periodic chat history polling
     */
    private fun stopChatSyncPolling() {
        chatSyncRunnable?.let { chatSyncHandler.removeCallbacks(it) }
        chatSyncRunnable = null
        Log.d(TAG, "Chat sync polling stopped")
    }

    override fun onStreamingMessage(content: String, sender: String, streamId: String) {
        // v3.5: her reply streams as sentence-safe deltas - feed each one to
        // the streaming TTS player so she speaks while she writes.
        mainHandler.post {
            startStreamingPlayback(streamId, content)
        }
    }

    override fun onStreamComplete(streamId: String) {
        // Final marker from the gateway: flush the held-back tail so the
        // last sentence speaks and the session can drain + persist.
        mainHandler.post {
            streamingTtsPlayer?.end(streamId)
        }
    }

    // ── LGI status-room feed (gateway task lifecycle + tool runs) ──────────
    // Payloads arrive on the Socket.IO thread — render on the main handler.
    override fun onTaskEvent(kind: String, task: org.json.JSONObject) {
        mainHandler.post { appendTaskFeedRow(kind, task) }
    }

    override fun onActiveTasks(tasks: org.json.JSONArray) {
        mainHandler.post {
            activeTaskCount = tasks.length()
            updateTaskStatusLine()
        }
    }

    override fun onToolReport(tool: org.json.JSONObject) {
        mainHandler.post { appendTaskFeedRow("chat_tool", tool) }
    }

    override fun onUserMessage(content: String, sender: String, timestamp: String?) {
        // User message echoed back from server
        mainHandler.post {
            Log.d(TAG, "User message: $content from $sender")
        }
    }
    
    override fun onChatHistory(messages: List<com.freeroam.tactical.data.ChatMessage>) {
        // Load historical messages into rolling window
        mainHandler.post {
            Log.d(TAG, "Received ${messages.size} historical messages")
            
            // Clear our rolling window
            chatMessages.clear()
            
            // (Rolling-window seed, Looking-Glass v2) Open with the latest
            // SEED_MESSAGES_PER_SIDE messages from each side — last 5 from
            // Rich + last 5 from Polaris, interleaved chronologically — so
            // the chat is always full on open. The window then rolls up to
            // MAX_CHAT_MESSAGES as new messages stream in (WS + 3s poll).
            seedLatestExchange(messages).forEach { chatMessages.add(it) }
            
            // Rebuild UI with rolling window
            rebuildChatUI()
            
            if (chatMessages.isEmpty()) {
                noChatsText.isVisible = true
            }
        }
    }
    
    /**
     * Opening seed for the chat window (Looking-Glass v2): the latest
     * SEED_MESSAGES_PER_SIDE messages from EACH side (Rich + Polaris),
     * interleaved in chronological order — the chat is always full on open
     * and rolls up to MAX_CHAT_MESSAGES as streaming continues.
     */
    private fun seedLatestExchange(messages: List<com.freeroam.tactical.data.ChatMessage>): List<com.freeroam.tactical.data.ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val sorted = messages.sortedBy { it.timestamp }
        val latestUserIds = sorted.filter { it.isUser }
            .takeLast(SEED_MESSAGES_PER_SIDE)
            .map { it.id }
        val latestPolarisIds = sorted.filter { !it.isUser }
            .takeLast(SEED_MESSAGES_PER_SIDE)
            .map { it.id }
        val seedIds = (latestUserIds + latestPolarisIds).toSet()
        return sorted.filter { it.id in seedIds }
    }

    override fun onError(error: String) {
        mainHandler.post {
            Log.e(TAG, "Polaris error: $error")
            Toast.makeText(this, "Polaris error: $error", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onMessageSent(message: String) {
        mainHandler.post {
            Log.d(TAG, "Message sent confirmation: $message")
            // Show subtle confirmation toast
            Toast.makeText(this, "✓ Sent", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onChatMessage(sender: String, message: String, timestamp: String?) {
        // Render messages from WebSocket broadcast (source of truth)
        mainHandler.post {
            Log.d(TAG, "Chat message broadcast: $sender - $message")
            
            // Determine if this is a user message based on sender. Explicitly
            // exclude the AI senders FIRST (any casing), and require the
            // sender to be OUR identity / the generic "user" — an unknown or
            // remote identity can never "You"-ify her replies.
            val senderLower = sender.trim().lowercase()
            val isUser = senderLower != "polaris" && senderLower != "assistant" &&
                         senderLower != "ai" &&
                         (senderLower == "user" || senderLower == "self" ||
                          sender.equals(userIdentity, ignoreCase = true))
            
            // Parse timestamp if provided
            val messageTimestamp = timestamp?.let {
                try {
                    java.time.Instant.parse(it).toEpochMilli()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
            
            // Create and store the message in our rolling window
            val chatMessage = com.freeroam.tactical.data.ChatMessage(
                id = messageTimestamp,
                text = message,
                isUser = isUser,
                timestamp = messageTimestamp
            )
            
            // Add to our rolling window
            chatMessages.add(chatMessage)
            
            // Maintain only last 10 messages
            if (chatMessages.size > MAX_CHAT_MESSAGES) {
                chatMessages.removeAt(0)
            }
            
            // Rebuild the UI from the rolling window
            rebuildChatUI()
            
            // Auto-speak AI responses: synthesize via Kokoro, save as a
            // playable WAV, then take over the mini player. Synthesis runs on
            // the TTS executor, never the main thread. The timestamp guard
            // stops the 3s poll sync from double-speaking a reply the WS
            // broadcast already queued.
            if (!isUser && messageTimestamp > lastSpokenTimestamp + 1000) {
                lastSpokenTimestamp = messageTimestamp
                // v3.5: when the streaming player already spoke this exact
                // reply, do NOT re-synthesize the whole text - hand the
                // session this message's ID so its accumulated PCM becomes
                // the bubble's replayable WAV. Aborted or mismatched streams
                // fall back to the whole-text path.
                if (streamingTtsPlayer?.consumeIfMatches(chatMessage.text) == true) {
                    // Already speaking via the stream - nothing to do (v3.6
                    // text-only: no WAV to link; the bubble replays from text).
                } else {
                    speakPolarisText(chatMessage)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // v3.6.4: no stray HUD loop callbacks survive teardown.
        cancelWatchHudLoop()
        mainHandler.removeCallbacks(playbackPollRunnable)
        if (currentlyPlayingMessageId != -1L) {
            MediaSessionManager.getInstance(this).stop()
            currentlyPlayingMessageId = -1L
        }
        if (this::polarisWebSocket.isInitialized) {
            polarisWebSocket.disconnect()
        }
        streamingTtsPlayer?.abort()
        // Teardown order is load-bearing for the native engine: after abort()
        // no new chunks are queued, queued ones self-skip (s.aborted) and the
        // executor drains almost instantly; shutdown() blocks on KokoroTTS's
        // engine lock until any in-flight generate() returns, so the C++
        // object is never freed underneath running native code (the
        // "destroyed mutex" SIGABRT seen in KokoroTTS.synthesize).
        ttsExecutor.shutdown()
        kokoroTTS?.shutdown()
        // Release the mic recognizer and detach the wearable media-button hooks.
        voiceCaptureController?.destroy()
        voiceCaptureController = null
        MediaSessionManager.getInstance(this).setWatchMediaListener(null)
        // GEV GPS (v3.8): the activity no longer tears down the position
        // feeder — GpsForegroundService (still running) owns GpsReporter's
        // lifecycle, so the feed survives activity destruction while the
        // process lives (the FGS keeps the process pinned).
        stopChatSyncPolling()
    }

    override fun onStart() {
        super.onStart()
        // Keep the mic-capable foreground service alive for the whole
        // foreground session; every start re-asserts startForeground()
        // (idempotent in onStartCommand). The manifest FGS type
        // (dataSync|microphone) lets speech captures survive screen-lock.
        ContextCompat.startForegroundService(
            this,
            Intent(this, TacticalBackgroundService::class.java)
        )
        // GEV GPS (v3.8): promote the location-typed FGS the same way, on
        // every foreground entry. Started while the app is foregrounded with
        // while-in-use location permission, the location FGS type keeps the
        // GPS feed alive screen-off — no ACCESS_BACKGROUND_LOCATION prompt.
        GpsForegroundService.start(this)
    }

    override fun onPause() {
        super.onPause()
        // v3.4 lock-screen audio: playback deliberately KEEPS RUNNING when
        // the display sleeps or the app backgrounds - the system's promise
        // is voice-in AND voice-out with the screen locked (freeroam.md).
        // ExoPlayer holds its own partial wake lock (WAKE_MODE_LOCAL) and
        // audio focus, and the TacticalBackgroundService FGS keeps the
        // process alive, so pausing here was cutting Polaris's replies
        // mid-sentence at every screen-lock. The mini player re-syncs from
        // true state via playbackPollRunnable in onResume().
        mainHandler.removeCallbacks(playbackPollRunnable)
        stopChatSyncPolling()
        // GEV GPS (v3.8): the position feed deliberately KEEPS RUNNING when
        // the app backgrounds/locks. GpsForegroundService (location FGS type,
        // promoted from onStart()) owns GpsReporter's lifecycle now, so the
        // v3.5.1/v3.6 foreground-only stop() here is gone — it was starving
        // the gateway the moment the screen locked, which Polaris reported as
        // "N min old" fixes (Oct 11, 2026 field report).
        // Don't disconnect WebSocket on pause - let it stay connected
        // The server will handle transient disconnections gracefully
        Log.d(TAG, "MainActivity paused - playback continues through lock")
        // NOTE: captures are deliberately NOT cancelled on pause anymore -
        // screen-lock push-to-talk (watch PLAY) must survive the onPause()
        // that fires when the display sleeps. Playback now enjoys the same
        // screen-lock immunity.
    }

    override fun onResume() {
        super.onResume()
        // (Wake fix, Oct 3 2026) onPause() stops the 3 s sync poller; it
        // previously only returned via the WS-reconnect path — a socket that
        // SURVIVED the sleep left the chat silent until a real reconnect.
        // Idempotent: startChatSyncPolling() stops any existing loop first.
        startChatSyncPolling()
        if (currentlyPlayingMessageId != -1L) {
            // Playback was paused in onPause(); the poller reflects true state
            miniPlayerView.setPlaying(false)
            mainHandler.removeCallbacks(playbackPollRunnable)
            mainHandler.post(playbackPollRunnable)
        }
        // Reconnect WebSocket if it was disconnected (lateinit guard is defense
        // in depth: the client is assigned synchronously in onCreate)
        if (this::polarisWebSocket.isInitialized && !polarisWebSocket.isConnected()) {
            Log.d(TAG, "MainActivity resumed - reconnecting WebSocket")
            polarisWebSocket.reconnect(userIdentity, deviceIpAddress)
        }
        // GEV GPS (v3.8): no direct re-assert here anymore — onStart() promotes
        // GpsForegroundService ahead of every resume, and the grant callback
        // (onRequestPermissionsResult) covers the fresh-install flow.
    }
}
