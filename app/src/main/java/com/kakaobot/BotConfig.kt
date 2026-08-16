package com.kakaobot

import android.content.Context
import android.content.SharedPreferences

class BotConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kakaobot", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "http://192.168.0.1:8000") ?: "http://192.168.0.1:8000"
        set(v) = prefs.edit().putString("server_url", v).apply()

    var triggerWord: String
        get() = prefs.getString("trigger_word", "!봇") ?: "!봇"
        set(v) = prefs.edit().putString("trigger_word", v).apply()

    // 쉼표 구분 방 이름 목록 — 비워두면 전체 허용
    var allowedRooms: String
        get() = prefs.getString("allowed_rooms", "") ?: ""
        set(v) = prefs.edit().putString("allowed_rooms", v).apply()

    var isEnabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    fun getAllowedRoomList(): List<String> =
        allowedRooms.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
