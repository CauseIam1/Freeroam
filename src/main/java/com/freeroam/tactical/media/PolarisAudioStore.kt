package com.freeroam.tactical.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persists Kokoro TTS PCM output as playable WAV media files so every Polaris
 * reply becomes a permanent, replayable artifact.
 *
 * API 29+ (scoped storage): files are inserted via MediaStore into
 * Music/Polaris/ on the primary external volume - visible to the system media
 * scanner and any external audio player, no storage permissions required.
 *
 * API 26-28 (pre-scoped storage): files land in the app-scoped external Music
 * directory (also permission-free) and are addressed via file:// URIs that
 * ExoPlayer can read directly.
 */
object PolarisAudioStore {

    private const val TAG = "PolarisAudioStore"
    private const val POLARIS_DIR = "Polaris"
    private const val FILE_PREFIX = "polaris_"
    private const val WAV_HEADER_SIZE = 44

    /**
     * Build a complete WAV container (44-byte RIFF header + PCM payload).
     * Kokoro output is 16-bit PCM, single channel.
     */
    fun buildWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val dataLen = pcmData.size

        val buffer = ByteBuffer.allocate(WAV_HEADER_SIZE + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)                                   // PCM chunk size
        buffer.putShort(1)                                  // audio format = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataLen)
        buffer.put(pcmData)
        return buffer.array()
    }

    /**
     * Save Kokoro PCM as a playable WAV file named after the message id.
     * Returns a content:// (API 29+) or file:// (API 26-28) Uri, or null on
     * failure - callers must treat null as "no audio available".
     */
    fun savePcmAsWav(context: Context, pcmData: ByteArray, messageId: Long, sampleRate: Int): Uri? {
        if (pcmData.isEmpty()) {
            Log.w(TAG, "Empty PCM payload for message $messageId - nothing to save")
            return null
        }
        val fileName = "$FILE_PREFIX${messageId}.wav"
        val wavBytes = try {
            buildWav(pcmData, sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "WAV header build failed for message $messageId: ${t.message}")
            return null
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, wavBytes, fileName)
            } else {
                saveViaAppDir(context, wavBytes, fileName)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save WAV for message $messageId: ${t.message}")
            null
        }
    }

    /**
     * Look up a previously saved WAV for the given message id.
     * Returns the Uri, or null when no artifact exists yet.
     */
    fun findExistingWav(context: Context, messageId: Long): Uri? {
        val fileName = "$FILE_PREFIX${messageId}.wav"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                findViaMediaStore(context, fileName)
            } else {
                findViaAppDir(context, fileName)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "WAV lookup failed for message $messageId: ${t.message}")
            null
        }
    }

    /**
     * v3.6 legacy sweep: text-only playback writes no new artifacts - delete
     * every polaris_*.wav from both the MediaStore collection and the legacy
     * app dir. Idempotent and safe to call on every launch; returns the
     * number of files deleted.
     */
    fun pruneAllWavs(context: Context): Int {
        var pruned = 0
        // MediaStore (API 29+): delete every Polaris WAV row.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // MediaStore normalizes RELATIVE_PATH with a trailing slash
                // ("Music/Polaris/") - an exact-path comparison matched zero
                // rows on device. Match by DISPLAY_NAME prefix instead: it is
                // unique to this app and immune to path formatting.
                val rows = resolver.query(
                    collection,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf("$FILE_PREFIX%.wav"),
                    null
                )
                rows?.use { cursor ->
                    val toDelete = ArrayList<Uri>()
                    while (cursor.moveToNext()) {
                        toDelete.add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                    }
                    for (uri in toDelete) {
                        try {
                            if (resolver.delete(uri, null, null) > 0) pruned++
                        } catch (t: Throwable) {
                            Log.w(TAG, "Prune failed for $uri: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MediaStore prune failed: ${t.message}")
            }
        }
        // Legacy app dir (API 26-28 path): delete every polaris_*.wav file.
        try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), POLARIS_DIR)
            dir?.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".wav") }?.forEach { f ->
                if (f.delete()) pruned++
            }
        } catch (t: Throwable) {
            Log.e(TAG, "App dir prune failed: ${t.message}")
        }
        if (pruned > 0) Log.i(TAG, "Pruned $pruned legacy WAV artifact(s)")
        return pruned
    }

    // ---- API 29+ : MediaStore (scoped storage) ----

    private fun saveViaMediaStore(context: Context, wavBytes: ByteArray, fileName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + POLARIS_DIR)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
        if (uri == null) {
            Log.e(TAG, "MediaStore insert returned null for $fileName")
            return null
        }

        val out = resolver.openOutputStream(uri)
        if (out == null) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Could not open output stream for $fileName")
            return null
        }
        out.use {
            it.write(wavBytes)
            it.flush()
        }

        val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        Log.d(TAG, "Saved $fileName via MediaStore: $uri")
        return uri
    }

    private fun findViaMediaStore(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val rows = resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(fileName),
            null
        )
        rows?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return collection.buildUpon()
                    .appendPath(id.toString())
                    .build()
            }
        }
        return null
    }

    // ---- API 26-28 : app-scoped external directory ----

    private fun saveViaAppDir(context: Context, wavBytes: ByteArray, fileName: String): Uri {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), POLARIS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ${dir.absolutePath}")
        }
        val file = File(dir, fileName)
        FileOutputStream(file).use {
            it.write(wavBytes)
            it.flush()
        }
        Log.d(TAG, "Saved $fileName to app dir: ${file.absolutePath}")
        return Uri.fromFile(file)
    }

    private fun findViaAppDir(context: Context, fileName: String): Uri? {
        val file = File(
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), POLARIS_DIR),
            fileName
        )
        return if (file.exists() && file.length() > WAV_HEADER_SIZE) Uri.fromFile(file) else null
    }
}