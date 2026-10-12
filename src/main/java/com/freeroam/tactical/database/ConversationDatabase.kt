package com.freeroam.tactical.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SQLite database helper for storing conversation transcripts locally on the device.
 * Supports daily rolling backup and midnight reset functionality.
 */
class ConversationDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "freeroam_conversations.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_MESSAGES = "messages"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_DATE = "date" // YYYY-MM-DD for daily grouping
        private const val COLUMN_SENDER = "sender" // "user" or "ai"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_SYNCED = "synced" // Whether message was synced to server

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TIMESTAMP TEXT NOT NULL,
                $COLUMN_DATE TEXT NOT NULL,
                $COLUMN_SENDER TEXT NOT NULL,
                $COLUMN_MESSAGE TEXT NOT NULL,
                $COLUMN_SYNCED INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        onCreate(db)
    }

    /**
     * Insert a new message into the database
     */
    fun insertMessage(sender: String, message: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestampFormat.format(Date()))
            put(COLUMN_DATE, dateFormat.format(Date()))
            put(COLUMN_SENDER, sender)
            put(COLUMN_MESSAGE, message)
            put(COLUMN_SYNCED, 0)
        }
        return db.insert(TABLE_MESSAGES, null, values)
    }

    /**
     * Get all messages for today
     */
    fun getTodayMessages(): List<MessageEntity> {
        val db = readableDatabase
        val today = dateFormat.format(Date())
        val messages = mutableListOf<MessageEntity>()

        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$COLUMN_DATE = ?",
            arrayOf(today),
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                messages.add(MessageEntity(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = it.getString(it.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    date = it.getString(it.getColumnIndexOrThrow(COLUMN_DATE)),
                    sender = it.getString(it.getColumnIndexOrThrow(COLUMN_SENDER)),
                    message = it.getString(it.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                    synced = it.getInt(it.getColumnIndexOrThrow(COLUMN_SYNCED)) == 1
                ))
            }
        }

        return messages
    }

    /**
     * Get messages for a specific date
     */
    fun getMessagesByDate(date: String): List<MessageEntity> {
        val db = readableDatabase
        val messages = mutableListOf<MessageEntity>()

        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$COLUMN_DATE = ?",
            arrayOf(date),
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                messages.add(MessageEntity(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = it.getString(it.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    date = it.getString(it.getColumnIndexOrThrow(COLUMN_DATE)),
                    sender = it.getString(it.getColumnIndexOrThrow(COLUMN_SENDER)),
                    message = it.getString(it.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                    synced = it.getInt(it.getColumnIndexOrThrow(COLUMN_SYNCED)) == 1
                ))
            }
        }

        return messages
    }

    /**
     * Get all unsynced messages
     */
    fun getUnsyncedMessages(): List<MessageEntity> {
        val db = readableDatabase
        val messages = mutableListOf<MessageEntity>()

        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$COLUMN_SYNCED = 0",
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                messages.add(MessageEntity(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = it.getString(it.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    date = it.getString(it.getColumnIndexOrThrow(COLUMN_DATE)),
                    sender = it.getString(it.getColumnIndexOrThrow(COLUMN_SENDER)),
                    message = it.getString(it.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                    synced = true
                ))
            }
        }

        return messages
    }

    /**
     * Mark messages as synced
     */
    fun markMessagesSynced(ids: List<Long>) {
        val db = writableDatabase
        for (id in ids) {
            val values = ContentValues().apply {
                put(COLUMN_SYNCED, 1)
            }
            db.update(TABLE_MESSAGES, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
        }
    }

    /**
     * Export today's transcript to a formatted text file
     */
    fun exportTodayTranscript(outputPath: String): Boolean {
        val messages = getTodayMessages()
        if (messages.isEmpty()) return false // Don't create empty files

        val sb = StringBuilder()
        sb.append("===========================================\n")
        sb.append("FreeRoam Conversation Transcript\n")
        sb.append("Date: ${dateFormat.format(Date())}\n")
        sb.append("===========================================\n\n")

        for (msg in messages) {
            val senderLabel = if (msg.sender == "user") "YOU" else "POLARIS"
            sb.append("[${msg.timestamp}] $senderLabel:\n")
            sb.append("${msg.message}\n\n")
        }

        sb.append("\n===========================================\n")
        sb.append("End of Transcript\n")
        sb.append("===========================================\n")

        try {
            val file = java.io.File(outputPath)
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Clear all messages (used for daily reset)
     */
    fun clearAllMessages() {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, null, null)
    }

    /**
     * Clear messages older than a certain date
     */
    fun clearMessagesBeforeDate(date: String) {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "$COLUMN_DATE < ?", arrayOf(date))
    }

    /**
     * Get the current date string in YYYY-MM-DD format
     */
    fun getCurrentDate(): String {
        return dateFormat.format(Date())
    }

    /**
     * Get all conversations for a specific date
     * @param date The date object to filter by
     */
    fun getAllConversationsForDate(date: Date): List<ConversationEntity> {
        val db = readableDatabase
        val dateStr = dateFormat.format(date)
        val conversations = mutableListOf<ConversationEntity>()

        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$COLUMN_DATE = ?",
            arrayOf(dateStr),
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                conversations.add(ConversationEntity(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = it.getLong(it.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    date = it.getString(it.getColumnIndexOrThrow(COLUMN_DATE)),
                    isUserMessage = it.getString(it.getColumnIndexOrThrow(COLUMN_SENDER)) == "user",
                    message = it.getString(it.getColumnIndexOrThrow(COLUMN_MESSAGE))
                ))
            }
        }

        return conversations
    }

    /**
     * Delete conversations before a specific date
     * @param date The cutoff date - conversations before this will be deleted
     * @return Number of rows deleted
     */
    fun deleteConversationsBefore(date: Date): Int {
        val db = writableDatabase
        val cutoffDate = dateFormat.format(date)
        return db.delete(TABLE_MESSAGES, "$COLUMN_DATE < ?", arrayOf(cutoffDate))
    }
}

/**
 * Data class representing a message entity
 */
data class MessageEntity(
    val id: Long,
    val timestamp: String,
    val date: String,
    val sender: String,
    val message: String,
    val synced: Boolean
)

/**
 * Data class representing a conversation entity (legacy format for backup utilities)
 */
data class ConversationEntity(
    val id: Long,
    val timestamp: Long,
    val date: String,
    val isUserMessage: Boolean,
    val message: String
)
