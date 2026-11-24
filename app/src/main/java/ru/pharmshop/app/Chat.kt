package ru.pharmshop.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Неверный код администратора. */
class ChatAuthException(msg: String) : Exception(msg)

/** Чат на технических работах (включает администратор): сервер отвечает 503 и присылает причину. */
class ChatMaintException(val reason: String) :
    Exception(if (reason.isEmpty()) "Чат на технических работах" else "Чат на технических работах: " + reason)

/** Слишком частые сообщения в общий чат: сервер сказал, через сколько миллисекунд можно писать снова. */
class ChatCooldownException(msg: String, val retryMs: Long) : Exception(msg)

/** Клиент чата поддержки. Сервер: server/worker.js (Cloudflare Workers). */
object ChatApi {
    /**
     * Адрес вашего сервера чата, без слеша в конце, например:
     * "https://pharmshop-chat.ваш-аккаунт.workers.dev"
     */
    const val BASE_URL = "https://flat-violet-e617pharmshop-chat.soulszlord.workers.dev"

    /** Время, до которого собеседник прочитал наши сообщения (из последнего ответа сервера). */
    @Volatile var lastReadTs = 0L

    /** Статус обращения ("new" / "work" / "done") и оценка текущего «Решено» (0 — не оценено): из последнего ответа сервера. */
    @Volatile var lastStatus = ""
    @Volatile var lastRating = 0

    private fun post(path: String, body: JSONObject, adminToken: String = "", adminCode: String = ""): JSONObject {
        if (BASE_URL.isEmpty()) throw IllegalStateException("not_configured")
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (adminToken.isNotEmpty()) c.setRequestProperty("X-Admin-Token", adminToken)
            if (adminCode.isNotEmpty()) c.setRequestProperty("X-Admin-Code", adminCode)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw Exception("Сервер чата ответил неожиданно (код $code)")
            }
            if (code == 401) throw ChatAuthException(json.optString("error", "Неверный код"))
            if (code == 429 && json.has("retry_ms")) {
                throw ChatCooldownException(json.optString("error", "Слишком часто"), json.optLong("retry_ms"))
            }
            if (code == 503 && json.optBoolean("maintenance")) throw ChatMaintException(json.optString("reason"))
            if (code !in 200..299) {
                AppLog.e("chat", "$path → HTTP $code: ${json.optString("error").take(120)}")
                throw Exception(json.optString("error", "Ошибка $code"))
            }
            return json
        } finally {
            c.disconnect()
        }
    }

    /** Модель, Android и версия приложения — уходит вместе с каждым обращением в поддержку. */
    fun deviceInfo(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} " +
            "(API ${android.os.Build.VERSION.SDK_INT}), приложение ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // ----- клиент -----
    fun userSend(
        device: String, secret: String, nick: String, order: String, version: String,
        text: String, image: String = ""
    ) {
        val body = JSONObject().put("device", device).put("secret", secret).put("nick", nick)
            .put("order", order).put("version", version).put("text", text).put("info", deviceInfo())
        // диагностика и аналитика использования: описаны в Настройки → Прочее → «Какие данные собирает приложение»
        try { body.put("diag", Diag.payload().toString()) } catch (e: Exception) { /* без блока тоже можно */ }
        if (image.isNotEmpty()) body.put("image", image)
        post("/api/chat/send", body)
        Store.chatStarted = true
        Push.registerUser()   // после первого сообщения серверу уже есть кому слать ответы
    }

    /** Передаёт серверу токен FCM этого устройства (подтверждается секретом устройства). */
    fun pushRegister(device: String, secret: String, token: String) {
        post("/api/push/register", JSONObject().put("device", device).put("secret", secret).put("token", token))
    }

    fun adminPushRegister(adminToken: String, fcmToken: String) {
        post("/api/admin/push_register", JSONObject().put("token", fcmToken), adminToken)
    }

    fun adminPushUnregister(adminToken: String, fcmToken: String) {
        post("/api/admin/push_unregister", JSONObject().put("token", fcmToken), adminToken)
    }

    /** Фоновый опрос: {unread, last_id, text} — непрочитанные ответы поддержки. Ничего не помечает прочитанным. */
    fun userPoll(device: String, secret: String): JSONObject =
        post("/api/chat/poll", JSONObject().put("device", device).put("secret", secret)).also { noteSolved(it) }

    /** Сервер сообщает, сколько обращений администратор отметил решёнными: запоминаем для достижений. */
    private fun noteSolved(r: JSONObject) {
        if (r.has("solved")) Store.noteSupportSolved(r.optInt("solved"))
    }

    fun userImage(device: String, secret: String, id: Long): String =
        post("/api/chat/image", JSONObject().put("device", device).put("secret", secret).put("id", id))
            .optString("image")

    fun userMessages(device: String, secret: String, after: Long): JSONArray {
        val r = post("/api/chat/messages", JSONObject().put("device", device).put("secret", secret).put("after", after))
        lastReadTs = r.optLong("read_ts")
        lastStatus = r.optString("status")
        lastRating = r.optInt("rating")
        noteSolved(r)
        return r.optJSONArray("messages") ?: JSONArray()
    }

    /** Оценка решённого обращения: 1–5 и короткий отзыв. Одна оценка на каждое «Решено», её можно исправить. */
    fun userRate(device: String, secret: String, stars: Int, comment: String) {
        post("/api/chat/rate", JSONObject().put("device", device).put("secret", secret).put("stars", stars).put("comment", comment))
        lastRating = stars
    }

    /** Объявление, настройки разделов и минимальная версия (публично, без кода). */
    fun appConfig(versionCode: Int): JSONObject =
        post("/api/app/config", JSONObject().put("version_code", versionCode))

    // ----- администратор -----
    /** Вход по коду: сервер возвращает токен сессии (30 дней). Сам код дальше нигде не хранится и не пересылается. */
    fun adminLogin(code: String): String =
        post("/api/admin/login", JSONObject(), adminCode = code).optString("token")
            .ifEmpty { throw Exception("Сервер не выдал токен") }

    /** Завершает сессию на сервере (ошибки сети не важны — токен всё равно стирается на устройстве). */
    fun adminLogout(token: String) {
        try { post("/api/admin/logout", JSONObject(), token) } catch (e: Exception) { /* не критично */ }
    }

    /** Ответ: {threads: [...], counts: {new, work, done, unread}}. status: "", "new", "work", "done". */
    fun adminThreads(code: String, status: String = "", unread: Boolean = false, query: String = ""): JSONObject {
        val body = JSONObject()
        if (status.isNotEmpty()) body.put("status", status)
        if (unread) body.put("unread", true)
        if (query.isNotEmpty()) body.put("q", query)
        return post("/api/admin/threads", body, code)
    }

    /** {ratings: [{device, nick, stars, comment, ts}], stats: {n, avg, dist: [1★..5★]}}. */
    fun adminRatings(token: String): JSONObject = post("/api/admin/ratings", JSONObject(), token)

    /** Фоновый опрос админа: {unread, last_ts, nick, text} — чаты с непрочитанными сообщениями. */
    fun adminPoll(token: String): JSONObject = post("/api/admin/poll", JSONObject(), token)

    fun adminMessages(code: String, device: String, after: Long): JSONArray {
        val r = post("/api/admin/messages", JSONObject().put("device", device).put("after", after), code)
        lastReadTs = r.optLong("read_ts")
        return r.optJSONArray("messages") ?: JSONArray()
    }

    fun adminReply(code: String, device: String, text: String, image: String = "") {
        val body = JSONObject().put("device", device).put("text", text)
        if (image.isNotEmpty()) body.put("image", image)
        post("/api/admin/reply", body, code)
    }

    fun adminImage(code: String, device: String, id: Long): String =
        post("/api/admin/image", JSONObject().put("device", device).put("id", id), code).optString("image")

    fun adminSetStatus(code: String, device: String, status: String) {
        post("/api/admin/status", JSONObject().put("device", device).put("status", status), code)
    }

    /** Другие устройства, писавшие с тех же IP: [{device, nick, ip, ts}]. */
    fun adminRelated(code: String, device: String): JSONArray =
        post("/api/admin/related", JSONObject().put("device", device), code).optJSONArray("related") ?: JSONArray()

    /** Диагностика и аналитика пользователя: {diag, diag_ts, created, messages, first_message, last_ip, geo}. diag = null, если приложение его ещё не присылало. */
    fun adminProfile(code: String, device: String): JSONObject =
        post("/api/admin/profile", JSONObject().put("device", device), code)

    /** kind: "device" или "ip". */
    fun adminBlock(code: String, kind: String, value: String, reason: String = "") {
        post("/api/admin/block", JSONObject().put("kind", kind).put("value", value).put("reason", reason), code)
    }

    fun adminUnblock(code: String, kind: String, value: String) {
        post("/api/admin/unblock", JSONObject().put("kind", kind).put("value", value), code)
    }

    /** Приложение присылает на сервер свои заказы, чтобы админ видел их список. */
    fun reportOrders(device: String, secret: String, orders: JSONArray) {
        post("/api/orders/report", JSONObject().put("device", device).put("secret", secret).put("orders", orders))
    }

    /** Список заказов для админа: {orders, counts}. filter: all | active | done | failed. */
    fun adminOrders(token: String, filter: String, before: Long): JSONObject =
        post("/api/admin/orders", JSONObject().put("filter", filter).put("before", before), token)

    fun adminBlocks(code: String): JSONArray =
        post("/api/admin/blocks", JSONObject(), code).optJSONArray("blocks") ?: JSONArray()

    fun adminSettings(code: String): JSONObject = post("/api/admin/settings", JSONObject(), code)

    /** patch: любые из announcement, sections, min_version_code, update_message. Возвращает сохранённые настройки. */
    fun adminSaveSettings(code: String, patch: JSONObject): JSONObject =
        post("/api/admin/settings_save", patch, code)
    // ----- общий чат и личные сообщения -----
    private fun dev() = JSONObject().put("device", Store.deviceId).put("secret", Store.deviceSecret)

    /** {registered, uid, nick, badge, muted_until, nick_change_at}. registered = false — ник ещё не выбран. */
    fun chatMe(): JSONObject = post("/api/chat/me", dev()).also {
        if (it.optBoolean("registered")) { Store.chatNick = it.optString("nick"); noteMsgs(it) }
    }

    /**
     * Занять ник или сменить свой. Сервер отвечает ошибкой, если ник занят, зарезервирован или не подходит.
     * Новому пользователю нужен токен hCaptcha ([captcha]). check = true — только проверить ник (занят ли), ничего не создавая:
     * так капча не тратится зря на занятый ник.
     */
    fun chatRegister(nick: String, captcha: String = "", check: Boolean = false): JSONObject {
        val body = dev().put("nick", nick)
        if (captcha.isNotEmpty()) body.put("captcha", captcha)
        if (check) body.put("check", true)
        return post("/api/chat/register", body).also { if (!check) Store.chatNick = it.optString("nick", nick) }
    }

    /** Сообщить серверу, собраны ли все достижения (зелёная корона рядом с ником в чате). */
    fun crownSet(all: Boolean) { post("/api/chat/crown", dev().put("crown", all)) }

    /** {messages, rev, reset, me}. rev — ревизия ленты: если сервер вернул другую, ленту нужно перечитать (reset = true). */
    fun roomMessages(after: Long, rev: Long): JSONObject = post("/api/room/messages", dev().put("after", after).put("rev", rev))

    /** replyTo — номер сообщения, на которое отвечаем (0 — обычное сообщение). */
    fun roomSend(text: String, replyTo: Long = 0L) {
        val body = dev().put("text", text)
        if (replyTo > 0) body.put("reply_to", replyTo)
        noteMsgs(post("/api/room/send", body))
    }

    /** Реакция на сообщение (та же реакция второй раз снимает её). Ответ: {reactions: [{m, e, n, me}]}. */
    fun roomReact(id: Long, emoji: String): JSONObject = post("/api/room/react", dev().put("id", id).put("emoji", emoji))

    /** Сервер присылает, сколько всего сообщений написано в чате: запоминаем для достижения. */
    private fun noteMsgs(r: JSONObject) { if (r.has("msgs")) Store.noteChatMsgs(r.optInt("msgs")) }

    /** Загрузить на сервер аватарку профиля (пустая строка — убрать): чтобы её видели в чате. Возвращает версию. */
    fun avatarSet(image: String): Long = post("/api/chat/avatar_set", dev().put("image", image)).optLong("av")

    /** Аватарка другого пользователя: {av, image}. */
    fun avatarGet(uid: Long): JSONObject = post("/api/chat/avatar_get", dev().put("uid", uid))

    fun roomDelete(id: Long) { post("/api/room/delete", dev().put("id", id)) }

    /** Модератор («галочка»): minutes = 0 снимает ограничение, иначе 60 / 1440 / 10080. */
    fun roomMute(uid: Long, minutes: Int) { post("/api/room/mute", dev().put("uid", uid).put("minutes", minutes)) }

    /** Найти пользователя по нику: {uid, nick, badge}. */
    fun dmFind(nick: String): JSONObject = post("/api/dm/find", dev().put("nick", nick))

    /** {chats: [{uid, nick, badge, text, ts, sender, unread}], unread}. */
    fun dmList(): JSONObject = post("/api/dm/list", dev())

    /** {messages, peer: {uid, nick, badge}, peer_read}. Открытие переписки помечает сообщения собеседника прочитанными. */
    fun dmMessages(peer: Long, after: Long): JSONObject = post("/api/dm/messages", dev().put("with", peer).put("after", after))

    fun dmSend(peer: Long, text: String) { noteMsgs(post("/api/dm/send", dev().put("to", peer).put("text", text))) }

    // ----- администратор: пользователи, галочки, личные чаты -----
    /** {users: [{id, nick, badge, muted_until, created, last_seen, last_ip, room_msgs, dm_msgs, blocked_device}], more, total, badges}. */
    fun adminUsers(token: String, query: String, offset: Int): JSONObject =
        post("/api/admin/users", JSONObject().put("q", query).put("offset", offset), token)

    fun adminUserBadge(token: String, uid: Long, badge: Boolean) {
        post("/api/admin/user_badge", JSONObject().put("uid", uid).put("badge", badge), token)
    }

    /** minutes: 0 — снять, -1 — навсегда. */
    fun adminUserMute(token: String, uid: Long, minutes: Int) {
        post("/api/admin/user_mute", JSONObject().put("uid", uid).put("minutes", minutes), token)
    }

    /** Освобождает ник и удаляет пользователя вместе с его сообщениями в общем чате. */
    fun adminUserReset(token: String, uid: Long) {
        post("/api/admin/user_reset", JSONObject().put("uid", uid), token)
    }

    fun adminRoomMessages(token: String, after: Long, rev: Long): JSONObject =
        post("/api/admin/room_messages", JSONObject().put("after", after).put("rev", rev), token)

    fun adminRoomDelete(token: String, id: Long) {
        post("/api/admin/room_delete", JSONObject().put("id", id), token)
    }

    /** {chats: [{a, b, nick_a, nick_b, text, ts, n}]} — все личные переписки. */
    fun adminDmThreads(token: String, query: String): JSONObject =
        post("/api/admin/dm_threads", JSONObject().put("q", query), token)

    fun adminDmMessages(token: String, a: Long, b: Long, after: Long): JSONObject =
        post("/api/admin/dm_messages", JSONObject().put("a", a).put("b", b).put("after", after), token)
}
