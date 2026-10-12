package com.freeroam.tactical.media

import android.content.Context
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * MediaSessionManager - Thread-safe singleton for centralized media playback control.
 * 
 * This singleton manages:
 * 1. ExoPlayer instance for audio playback (Kokoro TTS output, Polaris responses)
 * 2. MediaSession for receiving media button events from wearables
 * 3. Dynamic metadata (Title, Artist, Album) for media notifications
 * 4. Direct AudioTrack passthrough for low-latency Polaris streaming
 * 5. Avatar animation callbacks synchronized with playback state
 * 
 * Thread-safety is achieved through lazy initialization with @Volatile and double-checked locking.
 */
class MediaSessionManager private constructor(
    private val context: Context
) : Player.Listener {

    /**
     * Watch-side media commands surfaced by WatchSessionPlayer when a paired
     * wearable (Garmin D2 Mach 1 Pro over AVRCP) sends a button press.
     * v3.3 button map (operator decision):
     *  - PLAY / PAUSE -> push-to-talk toggle (listen, then stop + send)
     *  - STOP         -> cancel an in-flight capture (hard abort, nothing
     *                    sent); when idle, halt playback TETHERED (player
     *                    stays alive paused at 0:00, AVRCP keeps listing it)
     *  - BACK / NEXT  -> disabled (stripped from advertised commands)
     */
    interface WatchMediaListener {
        /** PLAY: toggle pause/resume of current playback */
        fun onWatchPlay()
        /** STOP: cancel playback entirely */
        fun onWatchStop()
        /** BACK (previous-track): replay the current Polaris message */
        fun onWatchPrevious()
        /** NEXT (next-track): toggle the mic — press = start recording,
         *  press again = stop + send the transcript to Polaris */
        fun onWatchNext()
    }

    /** AVRCP media commands intercepted from the wearable. */
    enum class WatchCommand { PLAY, PAUSE, STOP, BACK, NEXT }

    // Wearable button listener (weakly coupled; set/cleared by MainActivity)
    @Volatile
    private var watchListener: WatchMediaListener? = null

    /**
     * Attach or detach the wearable media-button listener. Pass null on teardown.
     */
    fun setWatchMediaListener(listener: WatchMediaListener?) {
        watchListener = listener
        Log.d(TAG, "Watch media listener ${if (listener != null) "attached" else "detached"}")
    }

    enum class PlaybackState {
        IDLE,
        PLAYING,
        PAUSED,
        ENDED
    }

    /**
     * Avatar animation callback interface for synchronizing Polaris avatar with playback
     */
    interface AvatarCallback {
        fun onAudioAmplitude(amplitude: Float)
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackEnded()
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Avatar animation callback (weak reference to prevent leaks)
    private var avatarCallback: AvatarCallback? = null
    
    // Current AudioTrack for Polaris streaming (managed by singleton)
    private var currentAudioTrack: AudioTrack? = null
    private var amplitudeThread: Thread? = null
    private var currentPcmData: ByteArray? = null

    // ExoPlayer for audio playback
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handle audio focus
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                addListener(this@MediaSessionManager)
            }
    }

    /**
     * ForwardingPlayer intercepting AVRCP media-button commands from the
     * paired wearable before they reach ExoPlayer, so the watch can drive
     * the walkie-talkie app (push-to-talk toggle, tethered stop) - even
     * when no audio is currently loaded. v3.3: BACK/NEXT are fully
     * retired - stripped from the advertised command set so well-behaved
     * AVRCP stacks grey them out, and no-op'd here if a stack sends them
     * anyway.
     */
    private inner class WatchSessionPlayer(player: Player) : ForwardingPlayer(player) {

        // Strip every track-skip/scrub command from the set Media3 reports
        // to AVRCP. A single-item player never really offers NEXT/BACK, and
        // advertising commands we can't honor is what made the watch's
        // NEXT button a silent dead press.
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands()
                .buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_BACK)
                .remove(Player.COMMAND_SEEK_FORWARD)
                .build()

        override fun play() {
            handleWatchCommand(WatchCommand.PLAY)
        }

        override fun pause() {
            handleWatchCommand(WatchCommand.PAUSE)
        }

        override fun stop() {
            handleWatchCommand(WatchCommand.STOP)
        }

        // v3.3: track-skip buttons are dead ends by design - log and drop.
        override fun seekToNext() {
            Log.i(TAG, "Watch NEXT pressed - disabled")
        }

        override fun seekToNextMediaItem() {
            Log.i(TAG, "Watch NEXT pressed - disabled")
        }

        override fun seekToPrevious() {
            Log.i(TAG, "Watch BACK pressed - disabled")
        }

        override fun seekToPreviousMediaItem() {
            Log.i(TAG, "Watch BACK pressed - disabled")
        }

        override fun seekBack() {
            Log.i(TAG, "Watch BACK pressed - disabled")
        }

        override fun seekForward() {
            Log.i(TAG, "Watch NEXT pressed - disabled")
        }
    }

    // v3.5.1 silent keep-alive loop (the v3.3 reserve lever, now engaged):
    // true while the silent seed is the loaded item and looping on
    // REPEAT_MODE_ONE, keeping the session PLAYING so no AVRCP stack ages
    // the connection out. Real playback clears it; stop()/completion re-seed.
    @Volatile
    private var seedLoopActive = false

    // Her latest Polaris reply title - re-applied whenever the silent seed
    // re-seeds, so the watch keeps marquee-scrolling the newest response.
    // v3.6.4: honest init/backing for STANDBY_TITLE; restoreStandbyTitle()
    // resets it here after the post-playback loop, so idle re-seeds show
    // standing by instead of the latest reply.
    @Volatile
    private var lastPolarisTitle = STANDBY_TITLE

    // v3.5.2: monotonically increasing mediaId for every retitle - legacy
    // AVRCP stacks refresh on "track change", not in-place metadata edits,
    // so each new title must present as a new track.
    private val titleVersion = java.util.concurrent.atomic.AtomicInteger(0)

    // MediaSession for wearable (AVRCP) integration. Media3 sessions become
    // AVRCP-discoverable the moment they are BUILT — construction must happen
    // on a Looper thread (main); see activateSession().
    private val mediaSession: MediaSession by lazy {
        MediaSession.Builder(context, WatchSessionPlayer(exoPlayer))
            .build()
    }

    /**
     * Activate the MediaSession so paired wearables (Garmin D2 Mach 1 Pro)
     * can discover it over AVRCP and start sending button presses.
     * Safe to call multiple times; hops to the main thread if needed.
     */
    fun activateSession() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { activateSession() }
            return
        }
        // Media3 has no legacy isActive flag — the session is discoverable as
        // soon as it's built. Touching the lazy forces construction here on
        // the main thread (a Looper thread), which AVRCP discovery requires.
        mediaSession.token
        // v3.3 watch tether: seed a silent, paused item so the session is
        // AVRCP-visible from launch. An empty player looks dead to the
        // watch; a paused 0:00 item looks like paused music and stays
        // listed. Real Polaris audio replaces it the moment it plays.
        seedSilentTetherIfEmpty()
        Log.i(TAG, "MediaSession activated - wearable AVRCP commands live")
    }

    /**
     * Load the silent tether item (paused, READY at 0:00) when no real
     * media is loaded - main thread only, after the session is built.
     * playUri()/playAudioFile() replace it with real content.
     *
     * v3.6.4: the seed re-titles from lastPolarisTitle (the CURRENT watch
     * HUD - her live text while she speaks, her latest reply at completion)
     * instead of the hardcoded standing-by string that stomped every push
     * at each re-seed - the flash-then-revert field report.
     */
    private fun seedSilentTetherIfEmpty() {
        try {
            if (exoPlayer.mediaItemCount > 0) return
            val seed = MediaItem.Builder()
                .setUri(ensureSilentTetherFile())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(lastPolarisTitle.ifBlank { STANDBY_TITLE })
                        .setArtist("Polaris AI")
                        .setAlbumTitle("Freeroam")
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(seed)
            exoPlayer.prepare()
            // v3.5.2 play-state model: the seed carries REPEAT_MODE_ONE but
            // rests PAUSED at idle (the watch shows PLAY, ready for
            // push-to-talk); ensureTetherLooping() engages the silent loop
            // only WHILE she speaks (the watch's pause affordance - pressing
            // it stops her speech and starts the mic), and pauseTetherLoop()
            // parks it PAUSED again the moment her voice drains, keeping her
            // latest reply scrolling on the watch as a paused track.
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            exoPlayer.playWhenReady = false
            seedLoopActive = true
            Log.i(TAG, "Watch tether seeded - silent 60s track paused (loop armed)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to seed silent tether: ${t.message}")
        }
    }

    /**
     * Sixty seconds of 16-bit 16 kHz mono silence in cacheDir (~1.9 MB,
     * written once and reused across launches). Pure zeros: if the operator
     * ever hits play while only the seed is loaded, it plays silence -
     * never feedback, never her voice. v3.5.2: was one second, but a 1 s
     * track on REPEAT_MODE_ONE wrapped every second - constant track-end
     * churn that left AVRCP clients (the Garmin) flashing titles and holding
     * stale display state. Sixty seconds loops rarely and reads like a
     * normal paused track while idle.
     */
    private fun ensureSilentTetherFile(): Uri {
        val file = java.io.File(context.cacheDir, SILENT_TETHER_FILENAME)
        val sampleRate = 16000
        val dataBytes = sampleRate * 2 * SEED_SECONDS
        if (file.exists() && file.length() == 44L + dataBytes) return Uri.fromFile(file)
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeBytes("RIFF")
            writeLeInt(out, 36 + dataBytes)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLeInt(out, 16)                    // PCM chunk size
            writeLeShort(out, 1)                   // audio format: PCM
            writeLeShort(out, 1)                   // channels: mono
            writeLeInt(out, sampleRate)
            writeLeInt(out, sampleRate * 2)        // byte rate
            writeLeShort(out, 2)                   // block align
            writeLeShort(out, 16)                  // bits per sample
            out.writeBytes("data")
            writeLeInt(out, dataBytes)
            val zeros = ByteArray(4096)
            var written = 0
            while (written < dataBytes) {
                val chunk = minOf(zeros.size, dataBytes - written)
                out.write(zeros, 0, chunk)
                written += chunk
            }
        }
        return Uri.fromFile(file)
    }

    /** WAV headers are little-endian; DataOutputStream writes big-endian. */
    private fun writeLeInt(out: java.io.DataOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    /** WAV headers are little-endian; DataOutputStream writes big-endian. */
    private fun writeLeShort(out: java.io.DataOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    init {
        // Singleton construction can happen on any thread (getInstance is
        // also called from ClipboardMonitorService); bounce activation to
        // the main thread so the watch always sees a live session.
        handler.post { activateSession() }
    }
    
    /**
     * Play audio from file path (for chat message playback)
     */
    fun playAudioFile(filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Audio file not found: $filePath")
                return
            }
            
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            exoPlayer.apply {
                stop()
                clearMediaItems()
                // v3.5.1: real audio never loops - the repeat-one keep-alive
                // belongs to the silent seed alone.
                repeatMode = Player.REPEAT_MODE_OFF
                seedLoopActive = false
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
            Log.d(TAG, "Playing audio file: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${e.message}")
        }
    }
    
    /**
     * Play a media Uri (MediaStore content:// or app-scoped file://) through
     * the shared ExoPlayer - used for bubble-linked Polaris WAV playback.
     * Takes over whatever is currently playing; playback speed is applied up
     * front so the operator's slider preference is honored.
     */
    fun playUri(uri: Uri, speed: Float = 1.0f) {
        try {
            val mediaItem = MediaItem.fromUri(uri)
            exoPlayer.apply {
                stop()
                clearMediaItems()
                // v3.5.1: real audio never loops - the repeat-one keep-alive
                // belongs to the silent seed alone.
                repeatMode = Player.REPEAT_MODE_OFF
                seedLoopActive = false
                setMediaItem(mediaItem)
                prepare()
                setPlaybackSpeed(speed)
                playWhenReady = true
            }
            _playbackState.value = PlaybackState.PLAYING
            updateMetadata("Polaris AI", "Freeroam", "Tactical Comms")
            Log.d(TAG, "Playing Polaris audio: $uri at ${speed}x")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio uri: ${e.message}")
        }
    }

    /**
     * Toggle the shared ExoPlayer between play and pause. A completed
     * (ENDED) stream restarts from the beginning, matching mini player
     * semantics. The v3.3 tether normally rewinds finished streams to
     * 0:00 already; the ENDED branch stays as a safety net.
     */
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
            }
            play()
        }
    }

    /**
     * Seek the shared ExoPlayer (mini player scrubber).
     */
    fun seekToMs(positionMs: Long) {
        try {
            exoPlayer.seekTo(positionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed: ${e.message}")
        }
    }

    /**
     * Live playback speed change for the shared ExoPlayer (speed slider).
     */
    fun setPlaybackSpeed(speed: Float) {
        try {
            exoPlayer.setPlaybackSpeed(speed)
            Log.d(TAG, "Playback speed set to $speed")
        } catch (e: Exception) {
            Log.e(TAG, "Setting playback speed failed: ${e.message}")
        }
    }

    /**
     * Play audio from ByteArray (for real-time TTS streaming)
     */
    fun playAudioData(pcmData: ByteArray, sampleRate: Int = 16000) {
        currentPcmData = pcmData
        currentAudioTrack?.release()
        
        val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val track = AudioTrack(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            android.media.AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build(),
            bufferSize,
            AudioTrack.MODE_STATIC,
            0
        )
        
        track.write(pcmData, 0, pcmData.size)
        currentAudioTrack = track
        
        coroutineScope.launch {
            track.play()
            _playbackState.value = PlaybackState.PLAYING
            avatarCallback?.onPlaybackStarted()
            
            // Monitor amplitude for avatar animation
            amplitudeThread = Thread {
                try {
                    while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val amplitude = calculateAmplitude(pcmData)
                        handler.post {
                            avatarCallback?.onAudioAmplitude(amplitude)
                        }
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Amplitude monitoring error: ${e.message}")
                }
            }.apply { start() }
            
            // Wait for playback to complete
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(100)
            }
            
            // Playback completed
            handler.post {
                _playbackState.value = PlaybackState.ENDED
                avatarCallback?.onPlaybackEnded()
                // voiceProcessor removed - no callback needed
            }
        }
    }
    
    /**
     * Calculate RMS amplitude from PCM data
     */
    private fun calculateAmplitude(pcmData: ByteArray): Float {
        if (pcmData.isEmpty()) return 0f
        
        var sum = 0.0
        for (i in pcmData.indices step 2) {
            val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF))
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        val rms = Math.sqrt(sum / (pcmData.size / 2))
        return rms.toFloat()
    }

    /**
     * Set avatar animation callback for Polaris synchronization
     */
    fun setAvatarCallback(callback: AvatarCallback?) {
        avatarCallback = callback
    }
    
    /**
     * Clear avatar animation callback
     */
    fun clearAvatarCallback() {
        avatarCallback = null
    }

    /**
     * Play PCM stream through AudioTrack for low-latency Polaris voice output
     * @param pcmData Raw PCM audio data (16-bit, 16kHz, mono)
     * @param sampleRate Sample rate in Hz (default: 16000)
     * @param channelConfig Channel configuration (default: mono)
     * @param audioFormat Audio format (default: 16-bit PCM)
     */
    fun playPcmStream(
        pcmData: ByteArray,
        sampleRate: Int = 16000,
        channelConfig: Int = android.media.AudioFormat.CHANNEL_OUT_MONO,
        audioFormat: Int = android.media.AudioFormat.ENCODING_PCM_16BIT
    ) {
        // Stop any existing AudioTrack
        stopAudioTrack()
        
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferSize, pcmData.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            
            audioTrack.write(pcmData, 0, pcmData.size)
            currentAudioTrack = audioTrack
            currentPcmData = pcmData.clone()
            
            // Start amplitude monitoring thread
            startAmplitudeMonitoring(audioTrack)
            
            // Start playback
            audioTrack.play()
            _playbackState.value = PlaybackState.PLAYING
            avatarCallback?.onPlaybackStarted()
            
            // Note: AudioTrack playback completion handled via position monitoring, not callbacks
            // onPlaybackStopped callback removed - API signature changed in newer Android versions
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play PCM stream", e)
            _playbackState.value = PlaybackState.IDLE
        }
    }
    
    /**
     * Stop and release current AudioTrack
     */
    fun stopAudioTrack() {
        currentAudioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack", e)
            }
            currentAudioTrack = null
        }
        
        // Clear PCM data reference
        currentPcmData = null
        
        // Stop amplitude monitoring thread
        amplitudeThread?.interrupt()
        amplitudeThread = null
    }
    
    /**
     * Start amplitude monitoring thread for avatar animation
     */
    private fun startAmplitudeMonitoring(audioTrack: AudioTrack) {
        amplitudeThread?.interrupt()
        amplitudeThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val amplitude = calculateCurrentAmplitude(audioTrack)
                    handler.post {
                        avatarCallback?.onAudioAmplitude(amplitude)
                    }
                    Thread.sleep(50) // 20Hz update rate
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
    
    /**
     * Calculate current amplitude from AudioTrack
     * Uses the stored PCM data to calculate RMS amplitude
     */
    private fun calculateCurrentAmplitude(audioTrack: AudioTrack): Float {
        val pcmData = currentPcmData ?: return 0f
        
        if (pcmData.isEmpty()) {
            return 0f
        }
        
        // Check if audioTrack is still playing
        if (audioTrack.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) {
            return 0f
        }
        
        // Calculate position in the buffer based on playback position
        val position = audioTrack.playbackHeadPosition
        val sampleSize = 2 // 16-bit = 2 bytes per sample
        
        // Calculate which samples to analyze (window around current position)
        val windowSize = 256 // Number of samples to analyze
        val startSample = (position / sampleSize).toInt()
        val endSample = minOf(startSample + windowSize, pcmData.size / sampleSize)
        
        if (startSample >= endSample) {
            return 0f
        }
        
        // Calculate RMS (Root Mean Square) amplitude
        var sum = 0L
        var count = 0
        
        for (i in startSample until endSample) {
            val offset = i * sampleSize
            if (offset + 1 < pcmData.size) {
                // Convert 2 bytes to signed 16-bit sample (little-endian)
                val sample = ((pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF))
                val normalizedSample = sample / 32767.0f // Normalize to -1.0 to 1.0
                sum += (normalizedSample * normalizedSample).toLong()
                count++
            }
        }
        
        return if (count > 0) {
            val rms = kotlin.math.sqrt(sum.toDouble() / count)
            rms.toFloat()
        } else {
            0f
        }
    }

    /**
     * Singleton instance holder with thread-safe lazy initialization
     */
    companion object {
        private const val TAG = "MediaSessionManager"

        // v3.6.4: single source of truth for the idle watch HUD title. The
        // seed item used to hardcode this string and stomp our pushes: every
        // re-seed (STOP halt, ENDED rewind) reverted the Garmin to standing
        // by even while her text was live - the flash-then-revert the
        // operator field-reported. Re-seeds now carry lastPolarisTitle, and
        // the post-playback HUD loop resets it here when her reply is done
        // looping.
        private const val STANDBY_TITLE = "Polaris standing by - press PLAY to talk"
        // v3.5.2: a 60 s silent seed (was 1 s) - the repeat-wrap churn of a
        // 1-second looping track kept AVRCP clients flashing titles and
        // holding stale display state. The 60 s file loops rarely and reads
        // like a normal paused track while idle.
        private const val SILENT_TETHER_FILENAME = "polaris_silent_tether_60s.wav"
        private const val SEED_SECONDS = 60
        // How long a finished stream parks in ENDED before the tether
        // rewinds it to 0:00. 600ms = 3 mini-player poller ticks, so the
        // in-app end-path always fires first; imperceptible to AVRCP.
        private const val TETHER_REWIND_DELAY_MS = 600L
        
        @Volatile
        private var instance: MediaSessionManager? = null
        
        /**
         * Get or create the singleton instance
         * @param context Application context (will be converted to avoid leaks)
         */
        fun getInstance(context: Context): MediaSessionManager {
            return instance ?: synchronized(this) {
                instance ?: MediaSessionManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        /**
         * Reset singleton instance (for testing or reinitialization)
         */
        fun resetInstance() {
            synchronized(this) {
                instance?.release()
                instance = null
            }
        }
    }

    // Debounce protection for media button events (500ms)
    private var lastButtonEventTime = 0L
    private val debounceWindowMs = 500L

    /**
     * Update dynamic metadata for media notifications
     * Note: In Media3, metadata is set via MediaItem or MediaSession
     */
    fun updateMetadata(title: String, artist: String, album: String) {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build()
        
        // Update the current media item with new metadata.
        // replaceMediaItem() swaps the item in place at the current index —
        // setMediaItem() RESETS the player (position back to 0:00, hard
        // re-prepare), which made every watch HUD refresh restart the audio.
        val currentMediaItem = exoPlayer.currentMediaItem
        if (currentMediaItem != null) {
            // v3.5.2: each retitle gets a FRESH mediaId - legacy AVRCP
            // stacks ignore in-place metadata replaces on an unchanged
            // track but reliably refresh (and restart the marquee) on a
            // "new track", which is what the reply-as-title needs to stick.
            val updatedMediaItem = currentMediaItem.buildUpon()
                .setMediaId("polaris-title-v${titleVersion.incrementAndGet()}")
                .setMediaMetadata(metadata)
                .build()
            exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, updatedMediaItem)
        } else {
            // No media item loaded, create a placeholder with metadata
            val placeholderItem = MediaItem.Builder()
                .setMediaId("polaris-title-v${titleVersion.incrementAndGet()}")
                .setMediaMetadata(metadata)
                .build()
            exoPlayer.setMediaItem(placeholderItem)
        }
    }

    /**
     * Update metadata specifically for Polaris AI responses
     * Displays on Garmin watch media screen for real-time chat streaming
     * 
     * @param response Polaris AI response text (truncated to 180 chars)
     * @param userName Name of user who sent the original message (Rich/Matt)
     */
    fun updatePolarisMetadata(response: String, userName: String) {
        // v3.3: 180 chars - the Garmin marquee-scrolls the title while the
        // watch tether holds the player paused, so a longer slice of her
        // response keeps scrolling on the display.
        val truncatedResponse = if (response.length > 180) {
            response.take(177) + "..."
        } else {
            response
        }
        // v3.5.1: remember her latest reply - the silent keep-alive seed
        // re-titles from this, so the watch marquee keeps scrolling it.
        lastPolarisTitle = truncatedResponse
        
        updateMetadata(
            title = truncatedResponse,
            artist = "Polaris AI",
            album = userName
        )
        
        Log.d(TAG, "Polaris metadata updated: \"$truncatedResponse\" from $userName")
    }

    /**
     * v3.5.1: resume the silent seed keep-alive loop - e.g. after the mic
     * hygiene pause that a capture triggers. Only acts while the seed is
     * the loaded item; real audio never loops.
     */
    fun ensureTetherLooping() {
        if (!seedLoopActive) return
        handler.post {
            if (!seedLoopActive) return@post
            try {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                exoPlayer.playWhenReady = true
                Log.d(TAG, "Tether loop resumed")
            } catch (t: Throwable) {
                Log.e(TAG, "Tether loop resume failed: ${t.message}")
            }
        }
    }

    /**
     * v3.5.2: park the silent seed PAUSED - the idle state the operator's
     * watch should show: her latest reply as the track title, the PLAY
     * button available for push-to-talk. Called the moment her speech
     * drains. Only acts while the seed is the loaded item.
     */
    fun pauseTetherLoop() {
        if (!seedLoopActive) return
        handler.post {
            if (!seedLoopActive) return@post
            try {
                exoPlayer.pause()
                _playbackState.value = PlaybackState.PAUSED
                Log.d(TAG, "Tether loop parked (session PAUSED - watch shows PLAY)")
            } catch (t: Throwable) {
                Log.e(TAG, "Tether loop park failed: ${t.message}")
            }
        }
    }

    /**
     * v3.6.4: restore the watch HUD to the standing-by title. Ends the
     * post-playback loop: after her reply has marquee-scrolled its passes,
     * this re-arms lastPolarisTitle and pushes the standing-by title with a
     * fresh mediaId (legacy AVRCP stacks refresh on a "new track", not
     * in-place edits), so the idle watch deterministically shows the PLAY
     * affordance under "Polaris standing by" - ready for the next
     * push-to-talk press.
     *
     * @param userName the operator identity carried in the album field
     */
    fun restoreStandbyTitle(userName: String) {
        lastPolarisTitle = STANDBY_TITLE
        updateMetadata(
            title = STANDBY_TITLE,
            artist = "Polaris AI",
            album = userName
        )
        Log.d(TAG, "Polaris HUD restored to standing-by (album $userName)")
    }

    /**
     * Load audio data for playback (Kokoro TTS output)
     */
    fun loadAudioData(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        // v3.5.1: real audio never loops.
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        seedLoopActive = false
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    /**
     * Load audio data from PCM bytes (direct Kokoro output)
     */
    fun loadAudioData(pcmData: ByteArray, sampleRate: Int = 22050) {
        // Create a temporary file for PCM data
        val tempFile = java.io.File.createTempFile("kokoro_output_", ".pcm", context.cacheDir)
        tempFile.writeBytes(pcmData)
        tempFile.deleteOnExit()

        val uri = Uri.fromFile(tempFile)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType("audio/raw")
            .build()

        // v3.5.1: real audio never loops.
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        seedLoopActive = false
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    /**
     * Start playback
     * Handles both ExoPlayer (URI) and AudioTrack (PCM) playback modes
     */
    fun play() {
        // If AudioTrack is active, resume it
        currentAudioTrack?.let { audioTrack ->
            if (audioTrack.playState == android.media.AudioTrack.PLAYSTATE_PAUSED) {
                audioTrack.play()
                _playbackState.value = PlaybackState.PLAYING
                avatarCallback?.onPlaybackStarted()
                startAmplitudeMonitoring(audioTrack)
                return
            }
        }
        
        // Otherwise use ExoPlayer
        exoPlayer.play()
    }

    /**
     * Pause playback
     * Handles both ExoPlayer (URI) and AudioTrack (PCM) playback modes
     */
    fun pause() {
        // If AudioTrack is active, pause it
        currentAudioTrack?.let { audioTrack ->
            if (audioTrack.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause()
                _playbackState.value = PlaybackState.PAUSED
                avatarCallback?.onPlaybackPaused()
                // Stop amplitude monitoring thread
                amplitudeThread?.interrupt()
                amplitudeThread = null
                return
            }
        }
        
        // Otherwise use ExoPlayer
        exoPlayer.pause()
    }

    /**
     * Halt audible playback WITHOUT idling the player. v3.3: STOP must keep
     * the watch tether alive - exoPlayer.stop() parks the session in
     * STATE_IDLE, which AVRCP stacks treat as a dead player and drop.
     * Pause + rewind leaves the item loaded at 0:00, ready to replay, with
     * the session still listed on the watch and her response still
     * scrolling as the track title.
     */
    fun stop() {
        // AudioTrack path has no session stakes - just halt it.
        stopAudioTrack()

        if (exoPlayer.playbackState != Player.STATE_IDLE) {
            try {
                exoPlayer.pause()
                exoPlayer.seekTo(0)
            } catch (t: Throwable) {
                Log.e(TAG, "Tethered stop failed: ${t.message}")
            }
        }
        // v3.5.2: a tethered halt re-seeds the silent track PAUSED (v3.5.1
        // re-engaged the loop, which left the watch showing a pause button
        // at idle). The seed re-titles to her latest reply, so the Garmin
        // keeps marquee-scrolling it with the PLAY button available.
        try {
            exoPlayer.clearMediaItems()
            seedSilentTetherIfEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Keep-alive reseed after stop failed: ${t.message}")
        }
        _playbackState.value = PlaybackState.PAUSED
        avatarCallback?.onPlaybackEnded()
    }

    /**
     * Release all resources
     */
    fun release() {
        // Stop and release AudioTrack
        stopAudioTrack()
        
        // Release ExoPlayer
        exoPlayer.release()
        
        // Release MediaSession
        mediaSession.release()
        
        // Remove all pending callbacks
        handler.removeCallbacksAndMessages(null)
        
        // Clear avatar callback reference
        avatarCallback = null
    }

    /**
     * Check if currently playing
     * Handles both ExoPlayer (URI) and AudioTrack (PCM) playback modes
     */
    fun isPlaying(): Boolean {
        // Check AudioTrack first
        currentAudioTrack?.let { audioTrack ->
            if (audioTrack.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                return true
            }
        }
        // Otherwise check ExoPlayer
        return exoPlayer.isPlaying
    }

    /**
     * Get current playback position in milliseconds
     */
    fun getCurrentPosition(): Long = exoPlayer.currentPosition

    /**
     * Get duration of current media in milliseconds
     */
    fun getDuration(): Long = exoPlayer.duration

    // Player.Listener callbacks
    override fun onPlaybackStateChanged(playbackState: Int) {
        _playbackState.value = when (playbackState) {
            Player.STATE_IDLE -> PlaybackState.IDLE
            // Honest reporting (v3.3): READY means "ready", not "playing" -
            // a paused player held at 0:00 by the tether must read PAUSED,
            // or the UI pollers and AVRCP stacks misread the hold as sound.
            Player.STATE_READY ->
                if (exoPlayer.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
            Player.STATE_BUFFERING ->
                if (exoPlayer.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
            Player.STATE_ENDED -> PlaybackState.ENDED
            else -> PlaybackState.IDLE
        }
        // v3.3 watch tether: AVRCP stacks drop sessions parked in ENDED
        // (position == duration - the "finished track" state). After a beat
        // for the UI pollers to catch the natural end, rewind to 0:00 and
        // hold paused - exactly like a paused music app - so the session
        // stays listed and the watch keeps marquee-scrolling her latest
        // response as the track title. Posted (never mutate the player
        // from inside its own state-change callback) and re-verified, so
        // a new track that started inside the window is left untouched.
        if (playbackState == Player.STATE_ENDED) {
            handler.postDelayed({
                try {
                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                        // v3.5.2: swap back to the (paused) silent seed,
                        // re-titled to her latest reply - the natural-end
                        // state matches idle: PLAY available on the watch.
                        exoPlayer.clearMediaItems()
                        seedSilentTetherIfEmpty()
                        Log.i(TAG, "Watch tether: silent seed re-seeded (PAUSED)")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Watch tether rewind failed: ${t.message}")
                }
            }, TETHER_REWIND_DELAY_MS)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying && _playbackState.value == PlaybackState.ENDED) {
            // Playback completed - notify for avatar fade-out
            avatarCallback?.onPlaybackEnded()
        }
    }

    /**
     * Handle wearable AVRCP media commands with debounce protection.
     * Routes to the attached WatchMediaListener (MainActivity). When no
     * listener is attached, falls back to plain local player control so
     * the session still behaves like a normal media app.
     */
    private fun handleWatchCommand(command: WatchCommand) {
        val currentTime = SystemClock.elapsedRealtime()
        
        // Debounce protection - ignore events within 500ms window
        // (AVRCP wearables can double-send on a single press)
        if (currentTime - lastButtonEventTime < debounceWindowMs) {
            return
        }
        lastButtonEventTime = currentTime

        val listener = watchListener
        if (listener == null) {
            // Fallback: plain player control with no watch listener attached.
            // STOP stays tethered here too - never idle the session.
            when (command) {
                WatchCommand.PLAY -> if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                WatchCommand.PAUSE -> exoPlayer.pause()
                WatchCommand.STOP -> { exoPlayer.pause(); exoPlayer.seekTo(0) }
                WatchCommand.BACK -> Unit // disabled (v3.3)
                WatchCommand.NEXT -> Unit // disabled (v3.3)
            }
            Log.d(TAG, "Watch command $command handled locally (no listener)")
            return
        }

        when (command) {
            // PLAY and PAUSE both toggle: AVRCP sends whichever is opposite
            // to the currently reported state. Since v3.3 both resolve to
            // the push-to-talk toggle in MainActivity's onWatchPlay().
            WatchCommand.PLAY -> listener.onWatchPlay()
            WatchCommand.PAUSE -> listener.onWatchPlay()
            WatchCommand.STOP -> listener.onWatchStop()
            WatchCommand.BACK -> listener.onWatchPrevious()
            WatchCommand.NEXT -> listener.onWatchNext()
        }
        Log.d(TAG, "Watch command $command routed to listener")
    }

    /**
     * Extension function to check if playback state is playing
     */
    private fun PlaybackState.isPlaying(): Boolean = this == PlaybackState.PLAYING
}