package com.freeroam.tactical.network

/**
 * Listener interface for WebSocket events
 */
interface PolarisWebSocketListener {
    fun onConnectionStatusChanged(connected: Boolean)
    fun onStreamingMessage(content: String, sender: String, streamId: String)
    fun onStreamComplete(streamId: String)
    fun onUserMessage(content: String, sender: String, timestamp: String?)
    fun onChatHistory(messages: List<com.freeroam.tactical.data.ChatMessage>)
    fun onError(error: String)
    fun onMessageSent(message: String) {} // Optional callback for send confirmation
    fun onChatMessage(sender: String, message: String, timestamp: String?) {} // Callback for broadcast chat messages

    // ── LGI status room (gateway task lifecycle + tool runs, room "status") ──
    /** Task lifecycle rows: task_registered/started/progress/completed/failed. */
    fun onTaskEvent(kind: String, task: org.json.JSONObject) {}
    /** active_tasks snapshot delivered right after joining the status room. */
    fun onActiveTasks(tasks: org.json.JSONArray) {}
    /** Gateway tool runs — rendered as the LGI ⚙ CHAT_TOOL row. */
    fun onToolReport(tool: org.json.JSONObject) {}
}