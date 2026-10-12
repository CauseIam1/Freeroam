package com.freeroam.tactical.data

import java.io.Serializable

/**
 * Represents a message in the tactical comms chat
 * 
 * Simplified data class - UI state (isExpanded) removed.
 * All intelligence lives on the Gateway; this is pure data.
 */
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isUser: Boolean, // true = Rich (user), false = Polaris (AI)
    val timestamp: Long = System.currentTimeMillis(),
    val audioFilePath: String? = null // Path to audio file for playback
) : Serializable {
    
    /**
     * Get audio file for playback
     */
    fun getAudioFile(context: android.content.Context): java.io.File? {
        return audioFilePath?.let { java.io.File(it) }
    }
}
