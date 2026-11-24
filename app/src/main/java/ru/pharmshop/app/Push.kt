package ru.pharmshop.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executors

/**
 * Мгновенные push через Firebase Cloud Messaging. Работают, только если в сборку добавлен google-services.json;
 * иначе все методы молча ничего не делают, а уведомления приходят фоновым опросом (PollWorker).
 */
object Push {
    private val io = Executors.newSingleThreadExecutor()

    private fun available(c: Context): Boolean = try { FirebaseApp.getApps(c).isNotEmpty() } catch (e: Throwable) { false }

    /** Вызывается при запуске приложения: берём токен FCM и передаём его серверу. */
    fun init(c: Context) {
        val app = c.applicationContext
        if (!available(app)) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> onToken(app, t) }
        } catch (e: Throwable) { /* FCM недоступен на этом устройстве — остаётся опрос */ }
    }

    /** Новый токен (при первом запуске, переустановке или смене Google). */
    fun onToken(c: Context, token: String) {
        Store.init(c)
        if (token.isEmpty()) return
        Store.fcmToken = token
        registerUser()
        registerAdmin()
    }

    /** Пользователь: после первого сообщения в поддержку (раньше слать ответы некому). */
    fun registerUser() {
        val t = Store.fcmToken
        if (t.isEmpty() || !Store.chatStarted || Store.fcmUserSent == t) return
        io.execute {
            try {
                ChatApi.pushRegister(Store.deviceId, Store.deviceSecret, t)
                Store.fcmUserSent = t
            } catch (e: Exception) { /* повторим при следующем запуске */ }
        }
    }

    /** Админ: после входа и при каждом запуске, пока сессия жива. */
    fun registerAdmin() {
        val t = Store.fcmToken
        val a = Store.adminToken
        if (t.isEmpty() || a.isEmpty()) return
        io.execute { try { ChatApi.adminPushRegister(a, t) } catch (e: Exception) { } }
    }

    /** Вызывать в фоне при выходе из админки (токен сессии передаётся явно: на устройстве он уже стёрт). */
    fun unregisterAdmin(adminToken: String) {
        val t = Store.fcmToken
        if (t.isEmpty()) return
        try { ChatApi.adminPushUnregister(adminToken, t) } catch (e: Exception) { }
    }
}
