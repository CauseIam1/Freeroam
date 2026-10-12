package com.freeroam.tactical.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Service
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.freeroam.tactical.MainActivity
import com.freeroam.tactical.gps.GpsReporter

/**
 * GEV GPS Foreground Service - Free Roam Core (v3.8)
 *
 * Location-typed foreground service that owns GpsReporter so the operator's
 * position feed (POST /api/gps on the Burst Receiver :8083) keeps running
 * with the screen locked or the app backgrounded.
 *
 * Android ruling: a location-typed FGS promoted while the app is in the
 * foreground and holding while-in-use location permission may continue
 * background location access (API 30+) — no ACCESS_BACKGROUND_LOCATION
 * runtime prompt. MainActivity.onStart() and the runtime-grant callback are
 * the two promotion points, so both flows are foregrounded starts by
 * construction. (The speech FGS is dataSync|microphone — not location — and
 * does not grant background location on its own; v3.5.1's foreground-only
 * GpsReporter design left the gateway aging "N min old" fossil fixes.)
 */
class GpsForegroundService : Service() {

    companion object {
        private const val TAG = "GpsForegroundService"

        /** Notification channel ID */
        const val CHANNEL_ID = "gev_gps_channel"

        /** Notification ID (1001 is taken by TacticalBackgroundService) */
        const val NOTIFICATION_ID = 1003

        /**
         * Idempotent promotion helper: repeated startForegroundService() calls
         * are safe — onStartCommand re-asserts startForeground() each time.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GpsForegroundService::class.java)
            )
        }
    }

    /**
     * Called when the service is first created. Foreground promotion itself
     * happens in onStartCommand (idempotent per start).
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== GEV GPS FOREGROUND SERVICE CREATING ===")
        createNotificationChannel()
    }

    /**
     * Create the silent notification channel for the foreground service.
     * Required for Android 8.0+ (API 26+).
     */
    private fun createNotificationChannel() {
        val channelName = "GEV GPS Feed"
        val description = "Operator position feed to the Polaris gateway"
        val importance = android.app.NotificationManager.IMPORTANCE_LOW

        val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
            this.description = description  // Use 'this.' to disambiguate
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }

        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: $CHANNEL_ID")
    }

    /** Start the service in foreground mode with persistent notification. */
    private fun startForegroundServiceNotification() {
        // Tap the notification to return to the main activity.
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Polaris GPS Feed")
            .setContentText("Operator position streaming (screen-off safe)")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground notification started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the service is started (Android 8.0+ compatibility).
     * MainActivity.onStart() and the permission-grant callback re-assert the
     * promotion on every foreground entry — each call needs its matching
     * startForeground(), handled idempotently below.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundServiceNotification()
        } catch (t: Throwable) {
            // API 34+ denies location-typed promotion when the fine/coarse
            // location runtime grant is missing (e.g. promotion racing ahead
            // of a fresh-install grant dialog); MainActivity's grant callback
            // re-asserts after the grant. Degrade gracefully, don't crash.
            Log.e(TAG, "Foreground promotion failed: ${t.message}")
        }

        // (Re-)assert the reporter on every start — GpsReporter.start() is
        // idempotent and re-seeds from last-known fixes when providers cold.
        try {
            GpsReporter.getInstance(applicationContext).start()
        } catch (t: Throwable) {
            Log.e(TAG, "GPS reporter start failed: ${t.message}")
        }

        // START_STICKY: if the process is killed mid-feed the system restarts
        // the service; a backgrounded restart whose location promotion is
        // denied degrades into the catch above until the next
        // MainActivity.onStart() promotion.
        return START_STICKY
    }

    /**
     * Called when the service is destroyed. Sole owner of GpsReporter's
     * lifecycle — the activity never stops the reporter anymore.
     */
    override fun onDestroy() {
        Log.i(TAG, "GEV GPS Foreground Service destroying — stopping GpsReporter")
        GpsReporter.getInstance(applicationContext).stop()
        Log.i(TAG, "=== GEV GPS SERVICE DESTROYED ===")
    }
}