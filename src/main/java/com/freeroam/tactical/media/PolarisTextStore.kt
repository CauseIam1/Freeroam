package com.freeroam.tactical.media

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persists the raw text of every Polaris reply as a private .txt artifact
 * so any response can be re-synthesized to speech later, even when no WAV
 * artifact survives.
 *
 * Mirrors PolarisAudioStore: files are named polaris_<id>.txt alongside the
 * audio store's polaris_<id>.wav, keyed by the same message id (epoch ms).
 * Text lives in filesDir/polaris_text instead of MediaStore because it is
 * TTS source material, not user-facing media - internal storage is always
 * readable, needs no permissions, and is never swept by the media scanner.
 *
 * Consumers:
 * - Watch BACK replay: re-synthesize the latest reply via Kokoro when the
 *   saved WAV is missing (app restart, media cleanup, model upgrade).
 * - Post-restart voice: speak the newest reply without waiting for a sync.
 *
 * Writes are keyed by unique message id and idempotent (concurrent writers
 * of the same message write identical content), so no locking is needed;
 * the trim sweep only ever deletes the oldest files.
 */
object PolarisTextStore {

    private const val TAG = "PolarisTextStore"
    private const val DIR_NAME = "polaris_text"
    private const val FILE_PREFIX = "polaris_"
    private const val FILE_SUFFIX = ".txt"

    // Cap on stored artifacts so filesDir cannot grow unbounded over a
    // long-lived installation. 50 messages is far beyond the rolling chat
    // window and any realistic replay depth.
    private const val MAX_FILES = 50

    /** A stored Polaris reply: its message id and full text. */
    data class StoredText(val messageId: Long, val text: String)

    /**
     * Save a Polaris reply's text, keyed by message id. Overwrites (and
     * therefore dedupes) an existing artifact for the same id, then trims
     * the store to MAX_FILES newest entries. Returns false on failure -
     * callers treat that as "text replay unavailable", never fatal.
     */
    fun saveMessageText(context: Context, messageId: Long, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            Log.w(TAG, "Empty text for message $messageId - nothing to save")
            return false
        }
        return try {
            val file = fileFor(context, messageId)
            file.writeText(trimmed)
            Log.d(TAG, "Saved ${file.name} (${trimmed.length} chars)")
            trimToCapacity(file.parentFile)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save text for message $messageId: ${t.message}")
            false
        }
    }

    /**
     * Read a stored reply's text by message id, or null when the artifact
     * is missing, blank, or unreadable.
     */
    fun findText(context: Context, messageId: Long): String? {
        return try {
            val file = fileFor(context, messageId)
            if (file.isFile) file.readText().trim().ifEmpty { null } else null
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read text for message $messageId: ${t.message}")
            null
        }
    }

    /**
     * The most recently saved reply (highest message id), for watch BACK
     * replay and post-restart speak-aloud. Null when nothing is stored.
     */
    fun findLatest(context: Context): StoredText? {
        return try {
            textDir(context)
                .listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
                ?.mapNotNull { fileToStoredText(it) }
                ?.maxByOrNull { it.messageId }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to list stored texts: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun fileToStoredText(file: File): StoredText? {
        val id = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull()
            ?: return null
        val text = try {
            file.readText().trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read ${file.name}: ${t.message}")
            null
        } ?: return null
        if (text.isEmpty()) return null
        return StoredText(id, text)
    }

    /** Delete the oldest artifacts beyond MAX_FILES (by write time). */
    private fun trimToCapacity(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return
        if (files.size <= MAX_FILES) return
        val excess = files.size - MAX_FILES
        files.sortedBy { it.lastModified() }
            .take(excess)
            .forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Trimmed old text artifact ${file.name}")
                }
            }
    }

    private fun textDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun fileFor(context: Context, messageId: Long): File {
        val dir = textDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ${dir.absolutePath}")
        }
        return File(dir, "$FILE_PREFIX${messageId}$FILE_SUFFIX")
    }
}