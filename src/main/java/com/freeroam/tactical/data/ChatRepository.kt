package com.freeroam.tactical.data

import android.content.Context
import android.util.Log
import com.freeroam.tactical.network.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Simplified ChatRepository - "Dumb Terminal" Architecture
 * 
 * This repository is now a thin wrapper around the Polaris Gateway API.
 * All intelligence (message history, day boundaries, memory distillation)
 * lives on the Gateway. The Android app is purely a presentation layer.
 * 
 * API Endpoints:
 * - GET  /api/chat/messages?device_id=freeroam-android
 * - POST /api/chat/message
 * - POST /api/chat/audio
 */
class ChatRepository private constructor(
    private val context: Context
) {
    private val networkRepository = NetworkRepository(
        serverIp = "192.168.50.51",
        serverPort = 8082,
        deviceId = "freeroam-android"
    )
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    private val baseUrl = "http://192.168.50.51:8082"
    
    // (WireGuard identity fix) Identity declared by MainActivity's
    // detectUserIdentity(). Sent as ?user_id= (GET history) and body user_id
    // (POST chat) so the gateway resolves OUR bucket even when WireGuard
    // NATs this phone to a Docker bridge IP (172.18.0.1), which the gateway
    // otherwise maps to the empty "Dashboard" bucket.
    @Volatile
    private var userIdentity: String = "rich"
    
    /**
     * Set the user identity to declare to the gateway (e.g. "Rich").
     * Called by MainActivity after identity detection; defaults to "rich".
     */
    fun setUserIdentity(identity: String) {
        val normalized = identity.trim().lowercase()
        if (normalized.isNotEmpty()) {
            userIdentity = normalized
        }
        Log.d(TAG, "Chat identity set to '$userIdentity'")
    }
    
    companion object {
        private const val TAG = "ChatRepository"
        
        @Volatile
        private var instance: ChatRepository? = null
        
        fun getInstance(context: Context): ChatRepository {
            return instance ?: synchronized(this) {
                instance ?: ChatRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    /**
     * Fetch chat history from Polaris Gateway
     * Uses IP-based user identity detection on the server:
     * - 192.168.50.42 → "rich"
     * - 192.168.50.98 → "matt"
     * 
     * @return List of ChatMessage objects sorted by timestamp
     */
    suspend fun getMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            // Use /api/chat/history endpoint. The gateway resolves identity
            // from our source IP when possible, but we declare user_id so a
            // tunneled connection (WireGuard NAT -> 172.18.0.1) still maps
            // to our bucket instead of the empty Dashboard default.
            val request = Request.Builder()
                .url("$baseUrl/api/chat/history?user_id=$userIdentity")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(responseBody)
                    val messagesArray = jsonResponse.getJSONArray("messages")
                    val messages = mutableListOf<ChatMessage>()
                    
                    for (i in 0 until messagesArray.length()) {
                        val msgJson = messagesArray.getJSONObject(i)
                        try {
                            val msg = ChatMessage(
                                id = msgJson.optLong("id", System.currentTimeMillis() + i),
                                text = msgJson.optString("content", ""),
                                isUser = parseHistorySenderPolarity(
                                    msgJson.optString("role", ""),
                                    msgJson.optString("sender", "")
                                ),
                                timestamp = parseTimestamp(msgJson.optString("timestamp", "")),
                                audioFilePath = null
                            )
                            messages.add(msg)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing message at index $i: ${e.message}")
                        }
                    }
                    messages.sortedBy { it.timestamp }
                } else {
                    Log.e(TAG, "Failed to fetch messages: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching messages: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Parse ISO timestamp string to epoch milliseconds
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            if (timestamp.isNotEmpty()) {
                java.time.Instant.parse(timestamp).toEpochMilli()
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
    /**
     * Sender polarity for history rows: role is authoritative when present
     * ("user" vs assistant/polaris/ai), then sender, CASE-INSENSITIVE — the
     * gateway's master timeline stores "Polaris" with a capital P. The old
     * precedence-golf check mislabeled assistant rows carrying unexpected
     * senders as user-side. Bare legacy rows keep the legacy default
     * (user side).
     */
    private fun parseHistorySenderPolarity(role: String, sender: String): Boolean {
        val r = role.trim().lowercase()
        val s = sender.trim().lowercase()
        if (r == "user") return true
        if (r == "assistant" || r == "polaris" || r == "ai") return false
        if (s == "polaris" || s == "assistant" || s == "ai") return false
        if (s == "user" || s == "self") return true
        if (s == userIdentity.trim().lowercase()) return true
        return r.isEmpty() && s.isEmpty()
    }
    
    /**
     * Send a text message to Polaris Gateway
     * Gateway handles persistence, memory distillation, and response generation
     * 
     * Uses IP-based user identity detection on the server side.
     * The server determines user identity from the client IP address:
     * - 192.168.50.42 → "rich"
     * - 192.168.50.98 → "matt"
     */
    suspend fun sendMessage(text: String, audioFilePath: String? = null): Result<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("prompt", text)  // Use "prompt" to match gateway API
                put("timestamp", System.currentTimeMillis())
                put("client_source", "freeroam-android")
                put("user_id", userIdentity)  // (WireGuard fix) keep our bucket over tunnels
                audioFilePath?.let { put("audioFilePath", it) }
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = jsonBody.toString().toRequestBody(mediaType)
            
            // Use /api/chat endpoint (same as Polaris Dashboard)
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val aiResponse = json.optString("response", "")
                    val message = ChatMessage(
                        id = System.currentTimeMillis(),
                        text = text,  // Return the sent message
                        isUser = true,
                        timestamp = System.currentTimeMillis(),
                        audioFilePath = audioFilePath
                    )
                    Result.success(message)
                } else {
                    Result.failure(Exception("Failed to send message: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Upload audio file for server-side STT processing
     */
    suspend fun sendAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Uploading audio file for STT: ${audioFile.name}")
            val transcription = networkRepository.sendAudioForTranscription(audioFile)
            if (transcription != null && transcription.isNotEmpty()) {
                Result.success(transcription)
            } else {
                Result.failure(Exception("Empty transcription result"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Alias for getMessages() - used by ChatArchiverWorker
     */
    suspend fun getAllMessages(): List<ChatMessage> = getMessages()
    
    /**
     * Clear all messages - used by ChatArchiverWorker
     * Note: This is a local operation, gateway is the source of truth
     */
    suspend fun clearAllMessages() {
        // Local cache clearing only - gateway remains source of truth
        Log.d(TAG, "Clearing local message cache")
    }
    
    /**
     * Save a single message - used by ChatArchiverWorker
     * Note: Messages should be sent via sendMessage(), this is for restoration
     */
    suspend fun saveMessage(message: ChatMessage) {
        // Local cache restoration only - gateway is source of truth
        Log.d(TAG, "Restoring message to local cache: ${message.id}")
    }
}
