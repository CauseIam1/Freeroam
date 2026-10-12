package com.freeroam.tactical.config

import android.util.Log

/**
 * Global configuration for the FreeRoam Tactical app.
 * Handles user identification and server configuration based on device IP.
 */
object AppConfig {
    /**
     * User identification based on device IP address.
     * Each user has their own behavior library and workspace folder.
     */
    enum class User(val displayName: String, val deviceIp: String, val behaviorLib: String) {
        RICH("Rich", "192.168.50.42", "behavior_lib_rich"),
        MATT("Matt", "192.168.50.98", "behavior_lib_matt");

        companion object {
            fun fromDeviceIp(deviceIp: String): User {
                return values().find { it.deviceIp == deviceIp } ?: RICH
            }

            fun fromCurrentIp(currentIp: String): User {
                Log.d("AppConfig", "Detecting user from IP: $currentIp")
                return values().find { it.deviceIp == currentIp } ?: RICH
            }
        }
    }

    /**
     * Polaris Gateway (Dell Mainframe) static IP
     */
    const val POLARIS_SERVER_IP = "192.168.50.51"
    const val POLARIS_SERVER_PORT = 8082

    /**
     * GEV GPS ingest (Burst Receiver on the same Mainframe — host port 8083
     * maps to container 5001). The phone POSTs live fixes to /api/gps; the
     * server resolves operator identity from the source IP (LAN
     * 192.168.50.42 → rich, WG tunnel 10.20.30.3 → rich after the docker-proxy
     * hairpin), so no device key is sent from the phone.
     * WG_FALLBACK: the Mainframe's WireGuard-side tunnel address (10.20.30.1
     * per wireguard/wg0.conf) for off-LAN roaming operators.
     */
    const val BURST_RECEIVER_PORT = 8083
    const val GPS_ENDPOINT = "/api/gps"
    const val POLARIS_WG_SERVER_IP = "10.20.30.1"

    /** GPS fix POST cadence (ms) — one /api/gps request per interval. */
    const val GPS_REPORT_INTERVAL_MS = 15_000L

    /** LocationManager minimum update interval (ms) — matches the POST cadence. */
    const val GPS_LISTENER_MIN_MS = 15_000L

    /**
     * Get the current user based on device IP address.
     * The device IP is determined at runtime by checking the network interface.
     */
    fun getCurrentUser(deviceIpAddress: String): User {
        return User.fromCurrentIp(deviceIpAddress)
    }

    /**
     * Default user (Rich) for fallback
     */
    val DEFAULT_USER = User.RICH
}