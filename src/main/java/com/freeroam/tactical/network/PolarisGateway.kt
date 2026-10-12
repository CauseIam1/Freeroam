package com.freeroam.tactical.network

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Listener interface for Polaris Gateway connection and message events
 */
interface PolarisGatewayListener {
    fun onMessageReceived(message: String, isUser: Boolean)
    fun onConnectionStatusChanged(connected: Boolean)
}

/**
 * Data class representing a file push request to Polaris Gateway
 */
data class FilePushRequest(
    val files: List<File>,
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * PolarisGateway - Simple synchronous wrapper around NetworkRepository
 * for blocking calls in non-coroutine contexts.
 * 
 * This class provides blocking methods for:
 * 1. Sending chat messages to Polaris Gateway
 * 2. File push operations
 * 3. Memory distillation
 */
class PolarisGateway(
    private val serverIp: String = "192.168.50.51",
    private val serverPort: Int = 8082,
    private val deviceId: String = "freeroam-android"
) {
    private val networkRepository = NetworkRepository(serverIp, serverPort, deviceId)
    private var listener: PolarisGatewayListener? = null

    fun setListener(listener: PolarisGatewayListener?) {
        this.listener = listener
    }

    /**
     * Send a chat message to Polaris Gateway and get response
     * Uses runBlocking to convert suspend function to blocking call
     */
    fun sendMessage(message: String): String {
        return runBlocking {
            try {
                Log.d(TAG, "Sending message to Polaris Gateway: $message")
                
                val jsonBody = JSONObject().apply {
                    put("prompt", message)
                    put("timestamp", System.currentTimeMillis())
                    put("device_id", deviceId)
                }

                val response = networkRepository.sendVoiceCommand(message)
                response ?: "Error: No response from Polaris Gateway"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message: ${e.message}")
                "Error: ${e.message}"
            }
        }
    }

    /**
     * Send audio data for server-side STT processing
     */
    fun speechToText(audioData: ByteArray): String {
        return runBlocking {
            try {
                Log.d(TAG, "Sending audio data for STT processing (${audioData.size} bytes)")
                
                // Create temporary file for audio data
                val tempFile = java.io.File.createTempFile("stt_audio_", ".webm")
                tempFile.writeBytes(audioData)
                tempFile.deleteOnExit()
                
                val transcription = networkRepository.sendAudioForTranscription(tempFile)
                transcription ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "STT processing failed: ${e.message}")
                ""
            }
        }
    }

    /**
     * Request TTS generation from Polaris Gateway
     * Currently returns null as client handles TTS locally via Kokoro
     */
    fun generateTTS(text: String): ByteArray? {
        return runBlocking {
            try {
                Log.d(TAG, "Requesting TTS generation for: $text")
                
                val ttsFile = networkRepository.generateTTS(text)
                ttsFile?.readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "TTS generation failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Test connection to Polaris Gateway
     */
    fun testConnection(): Boolean {
        return runBlocking {
            networkRepository.testConnection()
        }
    }

    /**
     * Check if gateway is reachable
     */
    fun isGatewayReachable(): Boolean {
        return runBlocking {
            try {
                networkRepository.checkPolarisGatewayConnection()
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Send a text message to Polaris Gateway
     */
    suspend fun sendTextMessage(message: String): String {
        return try {
            Log.d(TAG, "Sending text message: $message")
            val response = networkRepository.sendVoiceCommand(message)
            response ?: "Error: No response from gateway"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Push files to Polaris Gateway
     */
    suspend fun pushFiles(request: FilePushRequest): Boolean {
        return try {
            Log.d(TAG, "Pushing ${request.files.size} files to gateway")
            val result = networkRepository.sendFilesToPolaris(request.files)
            result != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push files: ${e.message}")
            false
        }
    }

    /**
     * Distill memory from chat log
     */
    suspend fun distillMemory(chatLog: String): Boolean {
        return try {
            Log.d(TAG, "Distilling memory from chat log")
            val result = networkRepository.syncDailyTranscript(chatLog)
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to distill memory: ${e.message}")
            false
        }
    }

    /**
     * Check if audio is currently playing
     * Note: This method requires Context which PolarisGateway doesn't have
     * Use MediaSessionManager directly from Context-aware components
     */
    // fun isAudioPlaying(): Boolean = false  // Removed - use MediaSessionManager.getInstance(context).isPlaying() instead

    /**
     * Get current audio position in milliseconds
     * Note: This method requires Context which PolarisGateway doesn't have
     * Use MediaSessionManager directly from Context-aware components
     */
    // fun currentAudioPosition(): Long = 0L  // Removed - use MediaSessionManager.getInstance(context).getCurrentPosition() instead

    companion object {
        private const val TAG = "PolarisGateway"
    }
}