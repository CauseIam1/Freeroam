package com.freeroam.tactical.network

import android.util.Log
import com.freeroam.tactical.config.AppConfig
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URI

/**
 * PolarisWebSocketClient - Real-time bidirectional communication with Polaris Gateway
 * 
 * Provides:
 * - Socket.IO connection to http://192.168.50.51:8082
 * - User identity management (Rich/Matt)
 * - Streaming AI response handling
 * - Automatic reconnection with exponential backoff
 */
class PolarisWebSocketClient private constructor(
    private val serverIp: String = AppConfig.POLARIS_SERVER_IP,
    private val serverPort: Int = AppConfig.POLARIS_SERVER_PORT
) {
    companion object {
        private const val TAG = "PolarisWebSocketClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        // LGI status room task lifecycle events (gateway_feed.py registrations)
        private val TASK_EVENTS = listOf(
            "task_registered", "task_started", "task_progress",
            "task_completed", "task_failed"
        )
        
        @Volatile
        private var instance: PolarisWebSocketClient? = null
        
        fun getInstance(): PolarisWebSocketClient {
            return instance ?: synchronized(this) {
                instance ?: PolarisWebSocketClient().also { instance = it }
            }
        }
    }
    
    // LGI status room: one listener per task lifecycle event, registered in
    // connect() and unregistered in disconnect() by event name.
    private val taskListeners = HashMap<String, Emitter.Listener>()
    private var socket: Socket? = null
    private var listener: PolarisWebSocketListener? = null
    private var isConnected = false
    private var reconnectAttempt = 0
    private var currentUserIdentity: String? = null
    private var currentUserIp: String? = null
    
    /**
     * Connect to Polaris Gateway Socket.IO with user identity
     */
    fun connect(userIdentity: String, userIp: String, listener: PolarisWebSocketListener) {
        if (isConnected) {
            Log.d(TAG, "Already connected, skipping")
            return
        }
        
        this.listener = listener
        this.currentUserIdentity = userIdentity
        this.currentUserIp = userIp
        
        val serverUrl = "http://$serverIp:$serverPort"
        Log.d(TAG, "Connecting to Socket.IO: $serverUrl as $userIdentity from $userIp")
        
        try {
            val opts = IO.Options.builder()
                .setTransports(arrayOf("websocket", "polling"))
                .setReconnection(true)
                .setReconnectionDelay(RECONNECT_DELAY_MS)
                .setReconnectionDelayMax(MAX_RECONNECT_DELAY_MS.toLong())
                .setForceNew(false)
                .build()
            
            socket = IO.socket(URI(serverUrl), opts)
            
            socket?.on(Socket.EVENT_CONNECT, onConnect)
                ?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
                ?.on(Socket.EVENT_DISCONNECT, onDisconnect)
                ?.on("chat_message", onChatMessage)
                ?.on("chat_history", onChatHistory)
                ?.on("error", onError)
                ?.on("message", onServerMessage)

            // LGI status room (gateway_feed.py mirror): the gateway broadcasts
            // task lifecycle + tool runs ONLY to members of room "status"; the
            // join itself rides onConnect below. Additive — chat paths untouched.
            socket?.on("active_tasks", onActiveTasksListener)
            socket?.on("tool_execution", onToolReportListener)
            socket?.on("chat_tool_report", onToolReportListener)
            for (task_event in TASK_EVENTS) {
                socket?.on(task_event, makeTaskListener(task_event))
            }
            
            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Socket.IO: ${e.message}")
            listener?.onError("Socket.IO initialization failed: ${e.message}")
        }
    }
    
    private val onConnect = Emitter.Listener { args ->
        Log.d(TAG, "Socket.IO connected")
        isConnected = true
        reconnectAttempt = 0
        listener?.onConnectionStatusChanged(true)
        
        currentUserIdentity?.let { identity ->
            currentUserIp?.let { ip ->
                val payload = JSONObject()
                payload.put("type", "identity")
                payload.put("identity", identity)
                payload.put("ip", ip)
                socket?.emit("message", payload)
                Log.d(TAG, "Sent identity: $identity from $ip")
                
                // Request rolling 10-message history on connect
                val historyPayload = JSONObject()
                historyPayload.put("device_id", identity)
                socket?.emit("request_history", historyPayload)
                Log.d(TAG, "Requested chat history for $identity")

                // LGI status room — task lifecycle events broadcast to members
                // only; every (re)connect re-joins (gateway_feed.py pattern).
                val taskRoomPayload = JSONObject()
                taskRoomPayload.put("room", "status")
                socket?.emit("join_tasks", taskRoomPayload)
                Log.d(TAG, "Joined gateway status room for task feed")
            }
        }
    }
    
    private val onConnectError = Emitter.Listener { args ->
        val error = args.getOrNull(0)?.toString() ?: "Unknown connection error"
        Log.e(TAG, "Socket.IO connection error: $error")
        listener?.onError("Connection error: $error")
    }
    
    private val onDisconnect = Emitter.Listener { args ->
        val reason = args.getOrNull(0)?.toString() ?: "Unknown reason"
        Log.d(TAG, "Socket.IO disconnected: $reason")
        isConnected = false
        listener?.onConnectionStatusChanged(false)
        
        // Only attempt reconnect if this wasn't an intentional disconnect
        // and we still have a valid listener
        if (listener != null && !reason.contains("io client disconnect", ignoreCase = true)) {
            Log.d(TAG, "Scheduling reconnection in $RECONNECT_DELAY_MS ms")
            // Use a shorter delay for transient network issues (like app sleep)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                reconnect()
            }, RECONNECT_DELAY_MS)
        }
    }
    
    private val onChatMessage = Emitter.Listener { args ->
        val message = args.getOrNull(0)
        Log.d(TAG, "Received chat_message: $message")
        
        if (message != null) {
            try {
                val json = if (message is JSONObject) {
                    message
                } else {
                    JSONObject(message.toString())
                }
                handleChatMessage(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing chat_message: ${e.message}")
                listener?.onError("Chat message parsing error: ${e.message}")
            }
        }
    }
    
    private val onChatHistory = Emitter.Listener { args ->
        val message = args.getOrNull(0)
        Log.d(TAG, "Received chat_history: $message")
        
        if (message != null) {
            try {
                val json = if (message is JSONObject) {
                    message
                } else {
                    JSONObject(message.toString())
                }
                handleChatHistory(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing chat_history: ${e.message}")
                listener?.onError("History parsing error: ${e.message}")
            }
        }
    }
    
    private val onError = Emitter.Listener { args ->
        val error = args.getOrNull(0)
        val errorMessage = if (error is JSONObject) {
            error.optString("message", "Unknown error")
        } else {
            error?.toString() ?: "Unknown error"
        }
        Log.e(TAG, "Server error: $errorMessage")
        listener?.onError(errorMessage)
    }
    private fun handleMessage(json: JSONObject) {
        val type = json.optString("type", "")
        when (type) {
            "stream" -> handleChatStream(json)
            "user_chat" -> handleUserMessage(json)
            "error" -> handleError(json)
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }
    
    private fun handleChatStream(json: JSONObject) {
        val content = json.optString("content", "")
        val streamId = json.optString("stream_id", "")
        val isComplete = json.optBoolean("is_complete", false)
        val sender = json.optString("sender", "polaris")
        
        if (isComplete) {
            listener?.onStreamComplete(streamId)
        } else {
            listener?.onStreamingMessage(content, sender, streamId)
        }
    }
    
    private fun handleUserMessage(json: JSONObject) {
        val content = json.optString("content", "")
        val sender = json.optString("sender", "unknown")
        val timestamp = json.optString("timestamp", null)
        
        Log.d(TAG, "User message from $sender: $content")
        listener?.onUserMessage(content, sender, timestamp)
    }
    
    private fun handleChatMessage(json: JSONObject) {
        val sender = json.optString("sender", "unknown")
        val message = json.optString("message", "")
        val timestamp = json.optString("timestamp", null)
        
        Log.d(TAG, "Chat message from $sender: $message")
        listener?.onChatMessage(sender, message, timestamp)
    }
    
    private fun handleError(json: JSONObject) {
        val content = json.optString("content", "Unknown error")
        listener?.onError(content)
    }
    
    private fun handleChatHistory(json: JSONObject) {
        val messagesArray = json.optJSONArray("messages") ?: return
        val messages = mutableListOf<com.freeroam.tactical.data.ChatMessage>()
        
        for (i in 0 until messagesArray.length()) {
            val msgJson = messagesArray.getJSONObject(i)
            try {
                val msg = com.freeroam.tactical.data.ChatMessage(
                    id = msgJson.optLong("id", System.currentTimeMillis() + i),
                    text = if (msgJson.has("content")) {
                        msgJson.optString("content", "")
                    } else {
                        msgJson.optString("message", "")
                    },
                    isUser = parseHistorySenderPolarity(
                        msgJson.optString("role", ""),
                        msgJson.optString("sender", "")
                    ),
                    timestamp = try {
                        // Try to parse ISO timestamp if present
                        val ts = msgJson.optString("timestamp", "")
                        if (ts.isNotEmpty()) {
                            java.time.Instant.parse(ts).toEpochMilli()
                        } else {
                            System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    },
                    audioFilePath = null
                )
                messages.add(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message at index $i: ${e.message}")
            }
        }
        
        Log.d(TAG, "Parsed ${messages.size} historical messages")
        listener?.onChatHistory(messages)
    }
    
    /**
     * Sender polarity for history rows: role is authoritative when present
     * ("user" vs assistant/polaris/ai), then sender, CASE-INSENSITIVE — the
     * gateway's master timeline stores "Polaris" with a capital P, and the
     * old lowercase-only check flipped every wake-reconnect re-seed to the
     * user side (whole window rendered "You"; operator-observed Oct 3 2026).
     * Bare legacy rows (neither field) keep the legacy default (user side);
     * unknown senders resolve to the AI side — a mislabeled "You" bubble is
     * worse than a mislabeled Polaris bubble.
     */
    private fun parseHistorySenderPolarity(role: String, sender: String): Boolean {
        val r = role.trim().lowercase()
        val s = sender.trim().lowercase()
        if (r == "user") return true
        if (r == "assistant" || r == "polaris" || r == "ai") return false
        if (s == "polaris" || s == "assistant" || s == "ai") return false
        if (s == "user" || s == "self") return true
        currentUserIdentity?.let { identity ->
            if (s == identity.trim().lowercase()) return true
        }
        return r.isEmpty() && s.isEmpty()
    }
    
    // ── LGI status room listeners (▼ TACTICAL OPERATIONS feed) ─────────────
    /** active_tasks snapshot: delivered when we join room "status". */
    private val onActiveTasksListener = Emitter.Listener { args ->
        val data = args?.getOrNull(0)
        try {
            val tasks = when (data) {
                is org.json.JSONArray -> data
                is JSONObject -> org.json.JSONArray().put(data)
                else -> org.json.JSONArray()
            }
            listener?.onActiveTasks(tasks)
        } catch (e: Exception) {
            Log.e(TAG, "active_tasks parsing error: ${e.message}")
        }
    }

    /** Gateway tool runs → the LGI ⚙ CHAT_TOOL row. */
    private val onToolReportListener = Emitter.Listener { args ->
        val json = args?.getOrNull(0) as? JSONObject ?: return@Listener
        listener?.onToolReport(json)
    }

    /** One Emitter.Listener per task kind, cached so disconnect() can off() it. */
    private fun makeTaskListener(kind: String): Emitter.Listener {
        val existing = taskListeners[kind]
        if (existing != null) return existing
        val taskListener = object : Emitter.Listener {
            override fun call(vararg args: Any?) {
                val json = args.getOrNull(0) as? JSONObject ?: return
                listener?.onTaskEvent(kind, json)
            }
        }
        taskListeners[kind] = taskListener
        return taskListener
    }

    // Gateway "message" events (type-dispatched). The streaming deltas of
    // Polaris's replies ride here (type "chat_stream") - previously this
    // socket event was never registered, so her streamed chunks were dropped
    // and the app waited for the final whole-text "chat_message" burst
    // instead (v3.5 opens this ear for speak-while-she-writes playback).
    private val onServerMessage = Emitter.Listener { args ->
        val json = args?.getOrNull(0) as? JSONObject ?: return@Listener
        when (json.optString("type", "")) {
            "chat_stream" -> {
                val streamId = json.optString("stream_id", "")
                if (json.optBoolean("is_complete", false)) {
                    listener?.onStreamComplete(streamId)
                } else {
                    val content = json.optString("content", "")
                    if (content.isNotEmpty()) {
                        listener?.onStreamingMessage(
                            content,
                            json.optString("sender", "polaris"),
                            streamId
                        )
                    }
                }
            }
            "user_chat" -> listener?.onUserMessage(
                json.optString("content", ""),
                json.optString("sender", ""),
                json.optString("timestamp")
            )
            "error" -> listener?.onError(json.optString("content", "Gateway error"))
            "connection" -> Log.d(TAG, "Gateway: ${json.optString("message", "")}")
        }
    }

    /**
     * Send a text message to Polaris Gateway
     */
    fun sendMessage(message: String) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send message - not connected")
            listener?.onError("Not connected to Polaris Gateway")
            return
        }
        
        val json = JSONObject().apply {
            put("type", "polaris")
            put("content", message)
            put("sender", currentUserIdentity ?: "user")
            put("timestamp", System.currentTimeMillis().toString())
        }
        
        socket?.emit("message", json)
        Log.d(TAG, "Message sent successfully: '$message' from $currentUserIdentity")
        listener?.onMessageSent(message)
    }
    
    /**
     * Disconnect from Socket.IO
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting Socket.IO")
        socket?.off(Socket.EVENT_CONNECT, onConnect)
        socket?.off(Socket.EVENT_CONNECT_ERROR, onConnectError)
        socket?.off(Socket.EVENT_DISCONNECT, onDisconnect)
        socket?.off("chat_message", onChatMessage)
        socket?.off("chat_history", onChatHistory)
        socket?.off("error", onError)
        socket?.off("message", onServerMessage)
        // LGI status room listeners (symmetric with connect()'s registrations)
        socket?.off("active_tasks", onActiveTasksListener)
        socket?.off("tool_execution", onToolReportListener)
        socket?.off("chat_tool_report", onToolReportListener)
        taskListeners.forEach { (task_event, task_listener) ->
            socket?.off(task_event, task_listener)
        }
        taskListeners.clear()
        socket?.disconnect()
        socket = null
        isConnected = false
        listener = null
        currentUserIdentity = null
        currentUserIp = null
    }
    
    fun isConnected(): Boolean = isConnected
    
    fun reconnect(userIdentity: String, userIp: String) {
        // Capture the listener BEFORE disconnect() - it nulls the field, so
        // reconnecting via listener!! was guaranteed to throw an NPE.
        val currentListener = listener ?: run {
            Log.w(TAG, "Cannot reconnect - no listener available (never connected)")
            return
        }
        disconnect()
        connect(userIdentity, userIp, currentListener)
    }
    
    private fun reconnect() {
        val identity = currentUserIdentity ?: run {
            Log.w(TAG, "No stored identity for reconnection")
            return
        }
        val ip = currentUserIp ?: run {
            Log.w(TAG, "No stored IP for reconnection")
            return
        }
        val currentListener = listener ?: run {
            Log.w(TAG, "No listener available for reconnection")
            return
        }
        Log.d(TAG, "Auto-reconnecting as $identity from $ip")
        disconnect()
        connect(identity, ip, currentListener)
    }
}
