package com.kakaobot

import java.util.LinkedList

data class Message(val role: String, val content: String)

class ConversationHistory(private val maxSize: Int = 20) {
    private val histories = mutableMapOf<String, LinkedList<Message>>()

    fun add(roomId: String, role: String, content: String) {
        val history = histories.getOrPut(roomId) { LinkedList() }
        history.add(Message(role, content))
        while (history.size > maxSize) history.removeFirst()
    }

    fun get(roomId: String): List<Message> = histories[roomId]?.toList() ?: emptyList()

    fun clear(roomId: String) {
        histories.remove(roomId)
    }
}
