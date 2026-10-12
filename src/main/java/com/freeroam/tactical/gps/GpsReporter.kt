package com.freeroam.tactical.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.freeroam.tactical.config.AppConfig
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phone location reporter (v3.8): feeds the gateway's GEV GPS ingest
 * (POST /api/gps on the Burst Receiver, host :8083) with the operator's live
 * position continuously — foregrounded AND screen-locked/backgrounded.
 *
 * Payload contract (polaris-gateway/burst_receiver.py `_ingest_gps_fix`):
 * { lat, lng, alt, speed, heading, accuracy } — lat/lng required, the rest
 * optional floats. The device key is deliberately OMITTED: the server
 * resolves identity from the source IP (LAN 192.168.50.42 → rich, WG tunnel
 * 10.20.30.3 → rich after docker-proxy hairpin), so the phone never names
 * itself and the same build works for any operator.
 *
 * Lifecycle (v3.8 ruling): owned by GpsForegroundService — the location-typed
 * FGS starts this reporter in onStartCommand and stops it in onDestroy. It is
 * promoted from MainActivity.onStart() while the app is foregrounded with
 * while-in-use location permission, which (API 30+) lets the location FGS
 * type continue location access screen-off — no ACCESS_BACKGROUND_LOCATION
 * runtime prompt required. (Supersedes v3.5.1's foreground-only design, which
 * left the gateway aging a "N min old" fossil fix every time the screen
 * locked; Android also freezes cached processes, so a bare cached-app
 * reporter would starve anyway.)
 *
 * Endpoints: primary is the LAN gateway; every POST failure flips to the
 * WireGuard hub address (10.20.30.1:8083) and every success flips back.
 * One POST in flight at a time; the freshest fix is reused across cycles
 * until it goes stale (providers dead / permission revoked mid-session).
 */
class GpsReporter private constructor(context: Context) {

    /** Wire shape of the /api/gps POST body (server normalizes to its own dict). */
    private data class GpsPayload(
        val lat: Double,
        val lng: Double,
        val alt: Double,
        val speed: Float,
        val heading: Float,
        val accuracy: Float
    )

    companion object {
        private const val TAG = "GpsReporter"
        // Provider fixes older than this mean GPS/network silently died
        // (location toggle off, etc.) — skip the POST instead of replaying a
        // fossil position to the GEV globe.
        private const val STALE_FIX_MS = 90_000L
        private const val MIN_DISTANCE_M = 5f

        @Volatile
        private var instance: GpsReporter? = null

        fun getInstance(context: Context): GpsReporter =
            instance ?: synchronized(this) {
                instance ?: GpsReporter(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val primaryGpsUrl =
        "http://${AppConfig.POLARIS_SERVER_IP}:${AppConfig.BURST_RECEIVER_PORT}${AppConfig.GPS_ENDPOINT}"
    private val fallbackGpsUrl =
        "http://${AppConfig.POLARIS_WG_SERVER_IP}:${AppConfig.BURST_RECEIVER_PORT}${AppConfig.GPS_ENDPOINT}"

    // True after a POST failure; every success restores the LAN primary.
    @Volatile private var usingFallback = false
    @Volatile private var running = false
    @Volatile private var inFlight = false
    @Volatile private var lastFix: Location? = null
    @Volatile private var lastFixAtMs: Long = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = location
            lastFixAtMs = System.currentTimeMillis()
        }
    }

    private val postLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                postLatestFix()
            } catch (t: Throwable) {
                Log.w(TAG, "GPS post cycle failed: ${t.message}")
            }
            mainHandler.postDelayed(this, AppConfig.GPS_REPORT_INTERVAL_MS)
        }
    }

    /**
     * Idempotent start: registers GPS + NETWORK providers and kicks off the
     * 15 s POST cadence. No-ops quietly when ACCESS_FINE_LOCATION has not
     * been granted yet (MainActivity also starts this from the grant callback,
     * so the first launch ends up running without a restart).
     */
    fun start() {
        if (running) return
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            Log.i(TAG, "start() skipped - ACCESS_FINE_LOCATION not granted yet")
            return
        }
        running = true
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!locationManager.allProviders.contains(provider)) continue
                locationManager.requestLocationUpdates(
                    provider,
                    AppConfig.GPS_LISTENER_MIN_MS,
                    MIN_DISTANCE_M,
                    locationListener,
                    mainHandler.looper
                )
                // Seed with the last known fix so cycle #1 has data instead of
                // waiting ~1 min for GPS_PROVIDER's first cold fix.
                locationManager.getLastKnownLocation(provider)?.let { seed ->
                    if (System.currentTimeMillis() - lastFixAtMs > STALE_FIX_MS) {
                        lastFix = seed
                        lastFixAtMs = System.currentTimeMillis()
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "$provider denied: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "$provider unavailable: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "$provider registration failed: ${e.message}")
            }
        }
        mainHandler.removeCallbacks(postLoop)
        mainHandler.post(postLoop)
        Log.i(
            TAG,
            "GPS reporting started - cadence ${AppConfig.GPS_REPORT_INTERVAL_MS / 1000}s " +
                "→ $primaryGpsUrl (fallback $fallbackGpsUrl)"
        )
    }

    /** Idempotent stop: unregisters providers and cancels the POST cadence. */
    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(postLoop)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(TAG, "removeUpdates failed: ${e.message}")
        }
        Log.i(TAG, "GPS reporting stopped")
    }

    /**
     * POST the freshest known fix. One in-flight request at a time; failures
     * flip to the WG fallback endpoint, successes restore the LAN primary.
     */
    private fun postLatestFix() {
        if (inFlight) {
            Log.d(TAG, "Previous POST still in flight - skipping this cycle")
            return
        }
        val fix = lastFix
        if (fix == null || System.currentTimeMillis() - lastFixAtMs > STALE_FIX_MS) {
            Log.d(TAG, "No fresh fix available - skipping this cycle")
            return
        }
        inFlight = true
        val baseUrl = if (usingFallback) fallbackGpsUrl else primaryGpsUrl
        val payload = GpsPayload(
            lat = fix.latitude,
            lng = fix.longitude,
            alt = fix.altitude,
            speed = fix.speed,
            heading = fix.bearing,
            accuracy = fix.accuracy
        )
        val request = Request.Builder()
            .url(baseUrl)
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                inFlight = false
                response.use { r ->
                    if (r.isSuccessful) {
                        usingFallback = false
                        Log.i(
                            TAG,
                            "GPS fix accepted (${payload.lat}, ${payload.lng} ±${payload.accuracy} m)"
                        )
                    } else {
                        usingFallback = true
                        Log.w(TAG, "GPS POST ${r.code} - falling back to $fallbackGpsUrl")
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                inFlight = false
                usingFallback = true
                Log.w(TAG, "GPS POST failed (${e.message}) - falling back to $fallbackGpsUrl")
            }
        })
    }
}