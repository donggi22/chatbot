package com.kakaobot

import android.util.Log
import kotlinx.coroutines.*

class BotEngine(
    private val config: BotConfig,
    private val onLog: (String) -> Unit
) {
    private val history = ConversationHistory()
    private val client = ServerClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 새 메시지 처리: 필터 → 트리거 검사 → 서버 호출 → onReply 콜백
     */
    fun handle(roomId: String, sender: String, fullMessage: String, onReply: (String) -> Unit) {
        if (!config.isEnabled) return

        val allowedRooms = config.getAllowedRoomList()
        if (allowedRooms.isNotEmpty() && roomId !in allowedRooms) return

        val trigger = config.triggerWord
        if (!fullMessage.contains(trigger)) return

        val body = fullMessage.substringAfter(trigger).trim()
        if (body.isEmpty()) return

        log("[$roomId] $sender: $body")

        scope.launch {
            try {
                val hist = history.get(roomId)
                val reply = client.chat(config.serverUrl, roomId, sender, body, hist)
                history.add(roomId, "user", body)
                history.add(roomId, "assistant", reply)
                log("[$roomId] 봇: $reply")
                withContext(Dispatchers.Main) { onReply(reply) }
            } catch (e: Exception) {
                log("오류: ${e.message}")
                withContext(Dispatchers.Main) { onReply("(오류: ${e.message})") }
            }
        }
    }

    fun clearHistory(roomId: String) = history.clear(roomId)

    fun destroy() = scope.cancel()

    private fun log(msg: String) {
        Log.d("BotEngine", msg)
        onLog(msg)
    }
}
