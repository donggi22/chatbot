package com.kakaobot

import android.app.Application
import androidx.lifecycle.MutableLiveData

class KakaoBotApp : Application() {
    val logs = MutableLiveData<List<String>>(emptyList())

    fun addLog(msg: String) {
        val current = logs.value?.toMutableList() ?: mutableListOf()
        current.add(msg)
        if (current.size > 200) current.removeAt(0)
        logs.postValue(current)
    }
}
