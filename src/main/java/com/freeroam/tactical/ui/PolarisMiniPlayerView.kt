package com.freeroam.tactical.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.cardview.widget.CardView
import com.freeroam.tactical.R
import java.util.concurrent.TimeUnit

/**
 * Polaris Mini Player Overlay
 * 
 * A contextual media control overlay that appears during TTS playback.
 * Provides progress tracking, playback controls, and dismiss functionality.
 * 
 * Features:
 * - Auto-show when playback starts
 * - Real-time progress updates
 * - Seekable progress bar
 * - Play/Pause/Stop controls
 * - Smooth fade in/out animations
 */
class PolarisMiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val FADE_DURATION = 300L
    }

    // UI Components
    private val cardView: CardView
    private val titleText: TextView
    private val closeButton: ImageButton
    private val progressBar: SeekBar
    private val currentTimeText: TextView
    private val totalTimeText: TextView
    private val playPauseButton: ImageButton
    private val stopButton: ImageButton

    // State
    private var isPlaying = false
    private var currentPosition = 0
    private var duration = 0
    private var onPlayPauseClick: (() -> Unit)? = null
    private var onStopClick: (() -> Unit)? = null
    private var onCloseClick: (() -> Unit)? = null
    private var onSeek: ((Int) -> Unit)? = null

    // Progress update runnable
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && duration > 0) {
                currentPosition += 100 // Increment by 100ms
                updateProgressUI()
                postDelayed(this, 100)
            }
        }
    }

    init {
        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)

        // Bind views
        cardView = findViewById(R.id.miniPlayerCard)
        titleText = findViewById(R.id.miniPlayerTitle)
        closeButton = findViewById(R.id.closeButton)
        progressBar = findViewById(R.id.progressBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        playPauseButton = findViewById(R.id.playPauseButton)
        stopButton = findViewById(R.id.stopButton)

        // Setup click listeners
        setupClickListeners()
        setupSeekBarListener()

        // Initially hidden
        isVisible = false
        alpha = 0f
    }

    private fun setupClickListeners() {
        playPauseButton.setOnClickListener {
            onPlayPauseClick?.invoke()
        }

        stopButton.setOnClickListener {
            onStopClick?.invoke()
        }

        closeButton.setOnClickListener {
            onCloseClick?.invoke()
        }
    }

    private fun setupSeekBarListener() {
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTimeText.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Pause updates while user is seeking
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                onSeek?.invoke(progress)
            }
        })
    }

    /**
     * Show the mini player with fade-in animation
     */
    fun showPlayer() {
        if (isVisible) return

        isVisible = true
        animate().alpha(1f).setDuration(FADE_DURATION).start()
        
        // Start progress updates
        isPlaying = true
        removeCallbacks(updateProgressRunnable)
        post(updateProgressRunnable)
    }

    /**
     * Hide the mini player with fade-out animation
     */
    fun hidePlayer() {
        if (!isVisible) return

        animate().alpha(0f).setDuration(FADE_DURATION).withEndAction {
            isVisible = false
        }.start()

        // Stop progress updates
        isPlaying = false
        removeCallbacks(updateProgressRunnable)
    }

    /**
     * Update playback state (play/pause)
     */
    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        
        // Update play/pause button icon
        val playPauseIcon = if (playing) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play
        }
        playPauseButton.setImageResource(playPauseIcon)

        // Start/stop progress updates
        removeCallbacks(updateProgressRunnable)
        if (playing) {
            post(updateProgressRunnable)
        }
    }

    /**
     * Set total duration and initialize progress
     */
    fun setDuration(durationMs: Int) {
        duration = durationMs
        progressBar.max = durationMs
        totalTimeText.text = formatTime(durationMs)
    }

    /**
     * Update current playback position
     */
    fun updatePosition(positionMs: Int) {
        currentPosition = positionMs.coerceIn(0, duration)
        updateProgressUI()
    }

    private fun updateProgressUI() {
        progressBar.progress = currentPosition
        currentTimeText.text = formatTime(currentPosition)
    }

    /**
     * Reset player state
     */
    fun reset() {
        currentPosition = 0
        duration = 0
        progressBar.progress = 0
        progressBar.max = 100
        currentTimeText.text = "0:00"
        totalTimeText.text = "0:00"
        isPlaying = false
        playPauseButton.setImageResource(R.drawable.ic_play)
        removeCallbacks(updateProgressRunnable)
    }

    /**
     * Set callback for play/pause button clicks
     */
    fun setOnPlayPauseClickListener(listener: () -> Unit) {
        onPlayPauseClick = listener
    }

    /**
     * Set callback for stop button clicks
     */
    fun setOnStopClickListener(listener: () -> Unit) {
        onStopClick = listener
    }

    /**
     * Set callback for close button clicks
     */
    fun setOnCloseClickListener(listener: () -> Unit) {
        onCloseClick = listener
    }

    /**
     * Set callback for seek bar changes
     */
    fun setOnSeekListener(listener: (Int) -> Unit) {
        onSeek = listener
    }

    /**
     * Set custom title text
     */
    fun setTitle(title: String) {
        titleText.text = title
    }

    /**
     * Format milliseconds to mm:ss
     */
    private fun formatTime(ms: Int): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Cleanup when view is detached
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(updateProgressRunnable)
    }
}