package com.freeroam.tactical.service

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import com.freeroam.tactical.voice.KokoroTTS

/**
 * ClipboardMonitorService - Monitors clipboard for text changes and triggers Kokoro TTS
 * 
 * This service provides the Android equivalent of the X17 laptop's Control+S hotkey:
 * - Monitors clipboard for new text content
 * - When triggered, synthesizes clipboard text via Kokoro TTS
 * - Integrates with existing media player system
 * 
 * Usage:
 * 1. Start service to begin monitoring
 * 2. Call triggerClipboardPlayback() to read current clipboard content
 * 3. Service automatically stops after playback completes
 */
class ClipboardMonitorService : Service() {

    companion object {
        private const val TAG = "ClipboardMonitorService"
        const val ACTION_TRIGGER_CLIPBOARD = "com.freeroam.action.TRIGGER_CLIPBOARD"
        const val ACTION_STOP_SERVICE = "com.freeroam.action.STOP_CLIPBOARD_SERVICE"
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardText: String = ""
    private var kokoroTTS: KokoroTTS? = null
    private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Clipboard Monitor Service created")
        
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // Native ONNX init can throw Errors (UnsatisfiedLinkError, OutOfMemoryError)
        // on the service's main thread; absorb them so the process survives and the
        // synthesize path degrades gracefully (kokoroTTS stays null -> safe-call).
        try {
            kokoroTTS = KokoroTTS(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Kokoro TTS init failed in service: ${t.message}", t)
            kokoroTTS = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_CLIPBOARD -> {
                Log.d(TAG, "Trigger clipboard playback requested")
                triggerClipboardPlayback()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stop service requested")
                stopSelf()
            }
        }
        
        // Service will restart if killed during playback
        return START_STICKY
    }

    /**
     * Get current clipboard text and play it through Kokoro TTS
     */
    private fun triggerClipboardPlayback() {
        val clipboardText = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
        
        if (clipboardText.isNullOrBlank()) {
            handler.post {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
            Log.w(TAG, "Clipboard is empty, nothing to play")
            stopSelf()
            return
        }

        if (clipboardText == lastClipboardText) {
            Log.d(TAG, "Clipboard content unchanged from last playback")
            stopSelf()
            return
        }

        Log.d(TAG, "Clipboard text: $clipboardText")
        
        handler.post {
            Toast.makeText(this, "Reading clipboard...", Toast.LENGTH_SHORT).show()
        }

        // Synthesize and play clipboard text
        synthesizeAndPlay(clipboardText)
    }

    /**
     * Synthesize text via Kokoro TTS and play the audio
     */
    private fun synthesizeAndPlay(text: String) {
        if (isPlaying) {
            Log.w(TAG, "Already playing, skipping")
            return
        }

        try {
            isPlaying = true
            val pcmData = kokoroTTS?.synthesize(text)
            
            if (pcmData == null || pcmData.isEmpty()) {
                Log.e(TAG, "TTS synthesis failed")
                handler.post {
                    Toast.makeText(this, "TTS synthesis failed", Toast.LENGTH_SHORT).show()
                }
                isPlaying = false
                stopSelf()
                return
            }

            Log.d(TAG, "Synthesized ${pcmData.size} bytes, starting playback")
            
            // Play through Kokoro's built-in player with amplitude tracking
            kokoroTTS?.playAudio(pcmData) {
                Log.d(TAG, "Playback completed")
                isPlaying = false
                lastClipboardText = text
                stopSelf()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
            handler.post {
                Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show()
            }
            isPlaying = false
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Clipboard Monitor Service destroyed")
        kokoroTTS?.release()
        kokoroTTS = null
        isPlaying = false
        super.onDestroy()
    }
}