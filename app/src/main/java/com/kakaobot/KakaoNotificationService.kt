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

        val (roomId, sender, message) = parseNotification(sbn.notification) ?: return
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
     * adb logcat으로 실측 확인된 카톡 알림 구조 (MessagingStyle 아님, BigTextStyle):
     *   android.title = 1:1이면 상대방 이름, 그룹이면 방 이름
     *   android.text  = 1:1이면 "발신자: " 접두어 없이 메시지 원문 그대로
     *   android.extras.EXTRA_IS_GROUP_CONVERSATION = 그룹 여부 (신뢰 가능한 플래그)
     * 그룹 채팅 포맷("발신자: 메시지")은 미검증이라 isGroupConversation일 때만 콜론 분리를 시도.
     */
    private fun parseNotification(notification: Notification): Triple<String, String, String>? {
        val extras = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null

        if (!extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
            return Triple(title, title, text)
        }

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
