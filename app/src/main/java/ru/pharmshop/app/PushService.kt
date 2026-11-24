package ru.pharmshop.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Принимает push от нашего сервера (data-сообщения) и показывает их теми же уведомлениями, что и фоновый опрос. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.onToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val c = applicationContext
        Store.init(c)
        // как и опрос: выключено в профиле или приложение уже открыто — молчим
        if (!Store.pushEnabled || AppState.visible) return
        val d = message.data
        when (d["type"]) {
            "support" -> {
                // запоминаем id: фоновый опрос не покажет то же сообщение ещё раз
                d["id"]?.toLongOrNull()?.let { Store.notifiedSupportId = it }
                Notifier.support(c, 1, d["text"] ?: "")
            }
            "admin" -> if (Store.adminToken.isNotEmpty()) {
                d["ts"]?.toLongOrNull()?.let { Store.notifiedAdminTs = it }
                Notifier.admin(c, 1, d["nick"] ?: "", d["text"] ?: "")
            }
        }
    }
}
