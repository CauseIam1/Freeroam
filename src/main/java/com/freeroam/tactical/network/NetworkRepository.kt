package com.freeroam.tactical.network

import android.util.Log
import com.freeroam.tactical.config.AppConfig
import com.freeroam.tactical.config.AppConfig.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * NetworkRepository handles all HTTP communication over LAN.
 * Sends voice commands and transcript syncs to the Dell mainframe Polaris Gateway.
 * Automatically identifies the user (Rich/Matt) based on device IP address.
 */
class NetworkRepository(
    private val serverIp: String = AppConfig.POLARIS_SERVER_IP,  // Dell Mainframe Local IP
    private val serverPort: Int = AppConfig.POLARIS_SERVER_PORT, // Polaris Gateway port
    private val deviceId: String
) {
    // Determine current user based on device's IP address
    val currentUser: User by lazy {
        val deviceIp = getDeviceIpAddress()
        val user = AppConfig.User.fromCurrentIp(deviceIp)
        Log.d(TAG, "Device IP: $deviceIp, User: ${user.displayName}")
        user
    }

    /**
     * Gets the device's local IP address to determine user identity (Rich vs Matt).
     */
    private fun getDeviceIpAddress(): String {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val ip = address.hostAddress ?: continue
                    // IPv4 address in the 192.168.50.x range
                    if (ip.startsWith("192.168.50.")) {
                        Log.d(TAG, "Found device IP: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device IP", e)
        }
        // Fallback to Rich's IP if detection fails
        return "192.168.50.42"
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // Longer timeout for burst uploads
        .retryOnConnectionFailure(true)
        .build()

    private val baseUrl = "http://$serverIp:$serverPort"

    /**
     * Sends a burst payload containing multiple photos.
     * 
     * @param burstFiles List of image files from the burst
     * @return true if transmission was successful, false otherwise
     */
    suspend fun sendBurstPayload(
        burstFiles: List<File>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending burst payload: ${burstFiles.size} files")

            // Create multipart form data
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())

            // Add each photo file
            for ((index, file) in burstFiles.withIndex()) {
                if (file.exists() && file.canRead()) {
                    val mediaType = when (file.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                        "png" -> "image/png".toMediaTypeOrNull()
                        else -> "application/octet-stream".toMediaTypeOrNull()
                    }

                    val fileBody = file.asRequestBody(mediaType)
                    val partName = "photo_$index"
                    
                    requestBody.addFormDataPart(
                        partName,
                        file.name,
                        fileBody
                    )
                    Log.d(TAG, "Added photo file: ${file.name} (${file.length()} bytes)")
                } else {
                    Log.w(TAG, "File not readable: ${file.absolutePath}")
                }
            }

            // Build and execute request
            val request = Request.Builder()
                .url("$baseUrl/api/burst")
                .post(requestBody.build())
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                
                if (response.isSuccessful) {
                    Log.i(TAG, "Burst payload sent successfully. Response: $responseBody")
                    true
                } else {
                    Log.e(TAG, "Burst payload failed. Code: ${response.code}, Body: $responseBody")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending burst payload", e)
            false
        }
    }

    /**
     * Sends a voice command transcription to the Polaris Gateway.
     * Connects to the Dell mainframe Ollama service via /api/chat endpoint.
     * 
     * @param transcribedText The text from STT (or raw audio data)
     * @return AI response text from Ollama, or null if failed
     */
    suspend fun sendVoiceCommand(transcribedText: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending voice command to Polaris Gateway: $transcribedText")

            val jsonBody = JSONObject().apply {
                put("prompt", transcribedText)
                put("timestamp", System.currentTimeMillis())
                put("device_id", deviceId)
                put("user_id", currentUser.name.lowercase())
                put("user_ip", getDeviceIpAddress())
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    // Ollama returns { "response": "..." }
                    val aiResponse = jsonResponse.optString("response", null)
                    Log.i(TAG, "Polaris Gateway response: $aiResponse")
                    aiResponse
                } else {
                    Log.e(TAG, "Voice command failed. Code: ${response.code}, Body: ${response.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending voice command to Polaris Gateway", e)
            null
        }
    }

    /**
     * Sends raw audio data for server-side STT processing.
     * For future implementation when client-side STT is not available.
     */
    suspend fun sendAudioForTranscription(audioFile: File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending audio file for transcription: ${audioFile.name}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("user_id", currentUser.name.lowercase())
                .addFormDataPart("user_ip", getDeviceIpAddress())
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/webm".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/transcribe")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    return@use jsonResponse.optString("transcription", null)
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio for transcription", e)
            null
        }
    }

    /**
     * Sends a conversation transcript file to the Dell server for archiving
     * and memory distillation.
     * 
     * @param transcriptFile The transcript file to sync
     * @return true if transmission was successful, false otherwise
     */
    suspend fun sendTranscript(transcriptFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending transcript: ${transcriptFile.name}")

            if (!transcriptFile.exists() || !transcriptFile.canRead()) {
                Log.e(TAG, "Transcript file not readable: ${transcriptFile.absolutePath}")
                return@withContext false
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("user_id", currentUser.name.lowercase())
                .addFormDataPart("user_ip", getDeviceIpAddress())
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .addFormDataPart(
                    "transcript",
                    transcriptFile.name,
                    transcriptFile.asRequestBody("text/plain".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/transcript")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                
                if (response.isSuccessful) {
                    Log.i(TAG, "Transcript sent successfully. Response: $responseBody")
                    true
                } else {
                    Log.e(TAG, "Transcript sync failed. Code: ${response.code}, Body: $responseBody")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending transcript", e)
            false
        }
    }

    /**
     * Sends files from the For_Polaris folder to the Polaris Gateway.
     * 
     * @param files List of files to send
     * @return AI response text from Polaris, or null if failed
     */
    suspend fun sendFilesToPolaris(files: List<File>): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending files to Polaris: ${files.size} files")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("user_id", currentUser.name.lowercase())
                .addFormDataPart("user_ip", getDeviceIpAddress())
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())

            // Add each file
            for ((index, file) in files.withIndex()) {
                if (file.exists() && file.canRead()) {
                    val mediaType = when (file.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                        "png" -> "image/png".toMediaTypeOrNull()
                        "txt" -> "text/plain".toMediaTypeOrNull()
                        "pdf" -> "application/pdf".toMediaTypeOrNull()
                        else -> "application/octet-stream".toMediaTypeOrNull()
                    }

                    val fileBody = file.asRequestBody(mediaType)
                    requestBody.addFormDataPart("file_$index", file.name, fileBody)
                    Log.d(TAG, "Added file: ${file.name} (${file.length()} bytes)")
                }
            }

            val request = Request.Builder()
                .url("$baseUrl/api/files")
                .post(requestBody.build())
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    Log.i(TAG, "Files sent successfully. Response: $responseBody")
                    jsonResponse.optString("response", null)
                } else {
                    Log.e(TAG, "Files send failed. Code: ${response.code}, Body: ${response.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending files to Polaris", e)
            null
        }
    }

    /**
     * Tests connectivity to the Polaris Gateway.
     * 
     * @return true if server is reachable, false otherwise
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(JSONObject().apply {
                    put("prompt", "ping")
                    put("device_id", deviceId)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * Checks if Polaris Gateway is connected (alias for testConnection).
     * 
     * @return true if server is reachable, false otherwise
     */
    suspend fun checkPolarisGatewayConnection(): Boolean = testConnection()

    /**
     * Sends audio file to Polaris Gateway for STT processing.
     * 
     * @param audioFile The audio file to send
     * @return AI response text from Polaris, or null if failed
     */
    suspend fun sendAudioToPolaris(audioFile: File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending audio to Polaris: ${audioFile.name}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("user_id", currentUser.name.lowercase())
                .addFormDataPart("user_ip", getDeviceIpAddress())
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    Log.i(TAG, "Polaris audio response: $responseBody")
                    jsonResponse.optString("response", null)
                } else {
                    Log.e(TAG, "Audio send failed. Code: ${response.code}, Body: ${response.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio to Polaris", e)
            null
        }
    }

    /**
     * Requests TTS generation from Polaris Gateway.
     * For future implementation when server-side TTS is available.
     * Currently returns null to indicate client should handle without TTS audio.
     * 
     * @param text The text to convert to speech
     * @return TTS audio file, or null if not available
     */
    suspend fun generateTTS(text: String): File? = withContext(Dispatchers.IO) {
        // TTS generation not implemented - client handles without TTS audio file
        // This method exists for future server-side TTS integration
        Log.d(TAG, "TTS generation requested for: $text")
        null
    }

    /**
     * Sync daily transcript to the Dell server via WireGuard tunnel.
     * Sends transcript content and triggers memory distillation.
     * 
     * @param transcriptContent The full transcript text for the day
     * @param date Optional date string (defaults to today)
     */
    suspend fun syncDailyTranscript(
        transcriptContent: String,
        date: String? = null
    ): Result<SyncResponse> = withContext(Dispatchers.IO) {
        try {
            val dateStr = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            
            val jsonBody = JSONObject().apply {
                put("date", dateStr)
                put("transcript", transcriptContent)
                put("user_id", currentUser.name.lowercase())
                put("user_ip", getDeviceIpAddress())
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = jsonBody.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url("$baseUrl/api/distill/auto")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(responseBody)
                    Result.success(
                        SyncResponse(
                            success = true,
                            message = jsonResponse.optString("status", "success"),
                            factsExtracted = jsonResponse.optInt("facts_extracted", 0)
                        )
                    )
                } else {
                    Result.failure(Exception("Sync failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Read a managed list from server (grocery, todo)
     */
    suspend fun readList(listName: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/lists/$listName/read")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(responseBody)
                    val items = JSONArray(jsonResponse.getJSONArray("items"))
                    val list = mutableListOf<String>()
                    for (i in 0 until items.length()) {
                        list.add(items.getString(i))
                    }
                    Result.success(list)
                } else {
                    Result.failure(Exception("Failed to read list: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Add item to a managed list
     */
    suspend fun addToList(listName: String, item: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("item", item)
                put("user_id", currentUser.name.lowercase())
                put("user_ip", getDeviceIpAddress())
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = jsonBody.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url("$baseUrl/api/lists/$listName/add")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to add item: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    companion object {
        private const val TAG = "NetworkRepository"
    }
}

data class SyncResponse(
    val success: Boolean,
    val message: String,
    val factsExtracted: Int
)
