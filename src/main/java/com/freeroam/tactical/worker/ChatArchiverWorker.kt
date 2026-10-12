package com.freeroam.tactical.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.freeroam.tactical.data.ChatMessage
import com.freeroam.tactical.data.ChatRepository
import com.freeroam.tactical.network.PolarisGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker that runs at midnight to:
 * 1. Save the day's chat to storage with date appended
 * 2. Send full chat to Polaris Gateway for memory distillation
 * 3. Reset chat keeping only last message from each participant
 */
class ChatArchiverWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ChatArchiverWorker"
        const val WORK_NAME = "midnight_chat_archive"
    }

    private val chatRepository = ChatRepository.getInstance(context)
    private val polarisGateway = PolarisGateway()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting midnight chat archival...")

            // Get current chat messages
            val messages = chatRepository.getAllMessages()
            if (messages.isEmpty()) {
                Log.d(TAG, "No messages to archive")
                return@withContext Result.success()
            }

            // Create date stamp for yesterday (the day being archived)
            val yesterday = Date(System.currentTimeMillis() - 86400000)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateStamp = dateFormat.format(yesterday)

            // Save chat to file
            val chatFile = saveChatToFile(messages, dateStamp)
            if (chatFile != null) {
                Log.d(TAG, "Chat saved to: ${chatFile.absolutePath}")
            }

            // Send to Polaris Gateway for memory distillation
            val sentToPolaris = sendToPolaris(messages, dateStamp)
            if (sentToPolaris) {
                Log.d(TAG, "Chat sent to Polaris Gateway for memory distillation")
            } else {
                Log.w(TAG, "Failed to send chat to Polaris Gateway")
            }

            // Reset chat keeping only last messages
            resetChat(messages)
            Log.d(TAG, "Chat reset - kept only last messages from each participant")

            Log.d(TAG, "Midnight chat archival completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during chat archival: ${e.message}", e)
            Result.retry()
        }
    }

    private fun saveChatToFile(messages: List<ChatMessage>, dateStamp: String): File? {
        return try {
            // Use app-private storage directory (no permissions required)
            val storageDir = File(applicationContext.filesDir, "stored_chat")
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            val fileName = "chat_$dateStamp.txt"
            val chatFile = File(storageDir, fileName)

            val sb = StringBuilder()
            sb.append("=== FreeRoam Tactical Comms Chat Log ===\n")
            sb.append("Date: $dateStamp\n")
            sb.append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            sb.append("========================================\n\n")

            for (message in messages) {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(Date(message.timestamp))
                val sender = if (message.isUser) "Rich" else "Polaris"
                sb.append("[$timestamp] $sender: ${message.text}\n\n")
            }

            chatFile.writeText(sb.toString())
            chatFile
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat to file: ${e.message}", e)
            null
        }
    }

    private suspend fun sendToPolaris(messages: List<ChatMessage>, dateStamp: String): Boolean {
        return try {
            val sb = StringBuilder()
            sb.append("=== Chat Log: $dateStamp ===\n")

            for (message in messages) {
                val sender = if (message.isUser) "USER" else "AI"
                sb.append("[$sender]: ${message.text}\n")
            }

            val chatLog = sb.toString()

            // Call Polaris Gateway distillation endpoint
            polarisGateway.distillMemory(chatLog)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to Polaris: ${e.message}", e)
            false
        }
    }

    private suspend fun resetChat(messages: List<ChatMessage>) {
        // Find last user message and last AI message
        var lastUserMessage: ChatMessage? = null
        var lastAiMessage: ChatMessage? = null

        for (i in messages.lastIndex downTo 0) {
            val message = messages[i]
            if (message.isUser && lastUserMessage == null) {
                lastUserMessage = message
            } else if (!message.isUser && lastAiMessage == null) {
                lastAiMessage = message
            }

            if (lastUserMessage != null && lastAiMessage != null) break
        }

        // Clear all messages
        chatRepository.clearAllMessages()

        // Add back only the last messages
        lastUserMessage?.let { chatRepository.saveMessage(it) }
        lastAiMessage?.let { chatRepository.saveMessage(it) }
    }
}