package com.kakaobot

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.RemoteInput

class KakaoNotificationService : NotificationListenerService() {

    private lateinit var engine: BotEngine
    private lateinit var config: BotConfig

    override fun onCreate() {
        super.onCreate()
        config = BotConfig(this)
        engine = BotEngine(config) { msg ->
            (application as KakaoBotApp).addLog(msg)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KAKAO_PACKAGE) return

        val extras = sbn.notification.extras
        // EXTRA_TITLE = 채팅방 이름 (1:1이면 상대방 이름)
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        val (roomId, sender, message) = parseNotification(title, text)
        val replyAction = findReplyAction(sbn.notification) ?: return

        engine.handle(roomId, sender, message) { reply ->
            sendReply(replyAction, reply)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }

    /**
     * 그룹 채팅: text = "발신자: 메시지"
     * 1:1 채팅:  text = "메시지", title = 상대방 이름
     */
    private fun parseNotification(title: String, text: String): Triple<String, String, String> {
        val colonIdx = text.indexOf(": ")
        return if (colonIdx in 1..29) {
            Triple(title, text.substring(0, colonIdx), text.substring(colonIdx + 2))
        } else {
            Triple(title, title, text)
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

    private fun sendReply(action: Notification.Action, text: String) {
        val intent = Intent()
        val results = Bundle()
        for (remoteInput in action.remoteInputs) {
            results.putString(remoteInput.resultKey, text)
        }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        try {
            action.actionIntent.send(this, 0, intent)
        } catch (e: Exception) {
            (application as KakaoBotApp).addLog("전송 실패: ${e.message}")
        }
    }

    companion object {
        const val KAKAO_PACKAGE = "com.kakao.talk"
    }
}
