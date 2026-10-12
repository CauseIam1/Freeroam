package com.freeroam.tactical.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Intent
import android.app.Service
import android.os.IBinder
import android.util.Log
import com.freeroam.tactical.MainActivity

/**
 * Tactical Background Service - Free Roam Core
 * 
 * Foreground service that promotes the Free Roam session so speech captures
 * (watch push-to-talk / tap-to-toggle mic) survive with the screen locked.
 * 
 * This service runs continuously in the background with a persistent silent
 * notification, ensuring the Free Roam system remains operational even when
 * the app UI is not visible.
 */
class TacticalBackgroundService : Service() {

    companion object {
        private const val TAG = "TacticalBackgroundService"
        
        /**
         * Notification channel ID
         */
        const val CHANNEL_ID = "tactical_service_channel"
        
        /**
         * Notification ID
         */
        const val NOTIFICATION_ID = 1001
    }

    /**
     * Called when service is first created
     * Initialize all components and start monitoring
     */
    override fun onCreate() {
        super.onCreate()
        
        Log.i(TAG, "=== TACTICAL BACKGROUND SERVICE CREATING ===")
        
        // Create the notification channel; foreground promotion itself
        // happens in onStartCommand (idempotent per start).
        createNotificationChannel()

        Log.i(TAG, "=== TACTICAL SERVICE FULLY OPERATIONAL ===")
    }

    /**
     * Create the notification channel for the foreground service
     * Required for Android 8.0+ (API 26+)
     */
    private fun createNotificationChannel() {
        val channelId = CHANNEL_ID
        val channelName = "Free Roam Service"
        val description = "Tactical background monitoring service"
        val importance = android.app.NotificationManager.IMPORTANCE_LOW
        
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            this.description = description  // Use 'this.' to disambiguate
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }

        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        
        Log.d(TAG, "Notification channel created: $channelId")
    }

    /**
     * Start the service in foreground mode with persistent notification
     */
    private fun startForegroundServiceNotification() {
        val channelId = CHANNEL_ID
        
        // Create intent to open main activity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // Build silent notification
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Free Roam Active")
            .setContentText("Voice capture + tactical monitoring active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground notification started")
    }

    /**
     * Called when another component wants to bind to this service
     * We return null as we don't support binding
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when service is started (for Android 8.0+ compatibility)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground promotion on every start: MainActivity.onStart()
        // calls startForegroundService() on each foreground entry, and every
        // such call demands a matching startForeground() even when already
        // running. This keeps the mic-typed FGS alive so locked-screen watch
        // push-to-talk captures survive screen-off. (Watch media buttons
        // arrive through the Media3 MediaSession - WatchSessionPlayer in
        // MediaSessionManager - not through intents here.)
        try {
            startForegroundServiceNotification()
        } catch (t: Throwable) {
            // A sticky restart while fully backgrounded can be denied by the
            // API 31+ FGS-start restrictions; degrade gracefully, don't crash.
            Log.e(TAG, "Foreground promotion failed: ${t.message}")
        }

        // Return START_STICKY to restart service if killed
        return START_STICKY
    }

    /**
     * Called when service is destroyed
     * Clean up all resources
     */
    override fun onDestroy() {
        Log.i(TAG, "Tactical Background Service destroying...")
        
        Log.i(TAG, "=== TACTICAL SERVICE DESTROYED ===")
        
        super.onDestroy()
    }

}