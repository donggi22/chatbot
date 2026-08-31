package com.kakaobot

import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ServerClient {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 메시지 간 간격이 길어서, 재사용된 연결이 공유기 NAT 타임아웃으로 이미 끊긴 뒤
        // 다시 쓰다가 "Software caused connection abort"가 나는 걸 막기 위해 연결 재사용 안 함
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class ChatRequest(
        val room_id: String,
        val sender: String,
        val message: String,
        val history: List<Map<String, String>>
    )

    data class ChatResponse(val reply: String)

    /**
     * 서버 POST /chat → reply 텍스트 반환
     * 실패 시 IOException throw
     */
    fun chat(
        serverUrl: String,
        roomId: String,
        sender: String,
        message: String,
        history: List<Message>
    ): String {
        val historyMaps = history.map { mapOf("role" to it.role, "content" to it.content) }
        val body = gson.toJson(ChatRequest(roomId, sender, message, historyMaps))
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$serverUrl/chat")
            .post(body)
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("서버 오류 ${resp.code}")
            val respBody = resp.body?.string() ?: throw IOException("빈 응답")
            return gson.fromJson(respBody, ChatResponse::class.java).reply
        }
    }
}
