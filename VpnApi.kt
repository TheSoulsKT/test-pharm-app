package ru.pharmshop.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Личный кабинет VPN (pvpn.vitrinka.net): вход через Telegram, тарифы, оплата, подписки.
 * Те же запросы, что делает сам сайт.
 *
 * Короткий токен (Bearer, живёт около часа) держим только в памяти. Долгий ключ входа
 * (cookie client_refresh, 30 дней) хранится на устройстве в зашифрованном виде (Store.vpnRefresh).
 * Все суммы сервер отдаёт в копейках.
 */
object VpnApi {
    private const val BASE = "https://pvpn.vitrinka.net"
    private const val AUTH = "/api/client/web"

    /** Сессия недействительна — нужно войти заново. */
    class AuthException(msg: String) : Exception(msg)

    private class Resp(val code: Int, val text: String, val cookies: List<String>)

    @Volatile private var token = ""
    @Volatile private var tokenExp = 0L

    private fun raw(method: String, path: String, body: String?, bearer: String?, cookie: String?): Resp {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "PharmShopApp/1.0")
            c.setRequestProperty("Origin", BASE)
            c.setRequestProperty("Referer", "$BASE/app/plans")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (cookie != null) c.setRequestProperty("Cookie", "client_refresh=$cookie")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val cookies = ArrayList<String>()
            for ((k, v) in c.headerFields) {
                if (k != null && k.equals("Set-Cookie", true)) cookies.addAll(v)
            }
            return Resp(code, text, cookies)
        } finally {
            c.disconnect()
        }
    }

    private fun errOf(r: Resp): String {
        val t = r.text.trim()
        if (t.startsWith("{")) {
            try {
                val j = JSONObject(t)
                for (k in listOf("detail", "error", "message")) {
                    val v = j.opt(k)
                    if (v != null && v != JSONObject.NULL) return "HTTP ${r.code}: ${v.toString().take(200)}"
                }
            } catch (e: Exception) {
                // не JSON — отдадим только код
            }
        }
        return "HTTP ${r.code}"
    }

    // ---------- сессия ----------

    fun loggedIn(): Boolean =
        Store.vpnRefresh.isNotEmpty() || (token.isNotEmpty() && System.currentTimeMillis() < tokenExp)

    fun logout() {
        token = ""
        tokenExp = 0L
        Store.vpnRefresh = ""
        Store.vpnPay = ""
    }

    /** Берёт токен из ответа входа/продления и новый ключ входа из Set-Cookie. */
    private fun saveSession(j: JSONObject, cookies: List<String>) {
        val s = j.optJSONObject("session") ?: j
        val tok = s.optString("access_token")
        if (tok.isEmpty()) throw Exception("Сервер не вернул токен")
        token = tok
        tokenExp = System.currentTimeMillis() + s.optLong("expires_in", 3600L) * 1000L
        for (ck in cookies) {
            if (ck.startsWith("client_refresh=")) {
                val v = ck.substringAfter("client_refresh=").substringBefore(";").trim()
                if (v.isNotEmpty()) Store.vpnRefresh = v
            }
        }
    }

    /** Продлевает токен по ключу входа. Как сайт шлёт этот запрос (POST или GET) — из адресов не видно, пробуем по очереди. */
    @Synchronized
    private fun refresh() {
        val rc = Store.vpnRefresh
        if (rc.isEmpty()) throw AuthException("Нужно войти заново")
        var last = 0
        val tries = listOf("POST" to null, "POST" to "{}", "GET" to null)
        for ((m, b) in tries) {
            val r = raw(m, "$AUTH/auth/refresh", b, null, rc)
            if (r.code in 200..299) {
                saveSession(JSONObject(r.text), r.cookies)
                return
            }
            last = r.code
            if (r.code == 401 || r.code == 403) {
                logout()
                throw AuthException("Сессия истекла, войдите заново")
            }
        }
        throw Exception("Не удалось продлить сессию (код $last)")
    }

    @Synchronized
    private fun ensureToken(): String {
        if (token.isNotEmpty() && System.currentTimeMillis() < tokenExp - 60_000L) return token
        refresh()
        return token
    }

    private fun authed(method: String, path: String, body: String? = null): String {
        var r = raw(method, path, body, ensureToken(), null)
        if (r.code == 401) {
            tokenExp = 0L
            r = raw(method, path, body, ensureToken(), null)
            if (r.code == 401) {
                logout()
                throw AuthException("Сессия истекла, войдите заново")
            }
        }
        if (r.code !in 200..299) throw Exception(errOf(r))
        return r.text
    }

    // ---------- вход через Telegram ----------

    /** Начало входа. В ответе: poll_id, bot_url (ссылка на бота), number, expires_in (секунды). */
    fun startLogin(): JSONObject {
        val r = raw("POST", "$AUTH/telegram-login", JSONObject().put("slug", JSONObject.NULL).toString(), null, null)
        if (r.code !in 200..299) throw Exception(errOf(r))
        return JSONObject(r.text)
    }

    /** Опрос входа: "pending", "confirmed" (сессия уже сохранена) или другое состояние (например, истёк срок). */
    fun pollLogin(pollId: String): String {
        val r = raw("GET", "$AUTH/telegram-login/$pollId", null, null, null)
        if (r.code !in 200..299) throw Exception(errOf(r))
        val j = JSONObject(r.text)
        val st = j.optString("state")
        if (st == "confirmed") saveSession(j, r.cookies)
        return st
    }

    // ---------- тарифы, оплата, подписки ----------

    fun plans(): JSONArray = JSONArray(authed("GET", "/api/client/plans"))

    /** {providers: [id…], provider_fee_percent: {id: %}} */
    fun providers(): JSONObject = JSONObject(authed("GET", "/api/client/payments/providers"))

    /** Цена тарифа; provider_totals — итог по каждому способу оплаты (с комиссией), в копейках. */
    fun quote(planUuid: String): JSONObject =
        JSONObject(authed("GET", "/api/client/payments/quote?plan_uuid=$planUuid"))

    /** Создаёт платёж. В ответе: payment_id и invoice_url (страница оплаты). */
    fun intent(planUuid: String, provider: String): JSONObject {
        // один и тот же ключ при повторе — сервер не создаст дубль платежа
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        val key = b.joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("plan_uuid", planUuid).put("provider", provider)
            .put("idempotency_key", key).toString()
        var last: java.io.IOException? = null
        for (attempt in 0 until 2) {
            try {
                return JSONObject(authed("POST", "/api/client/payments/intent", body))
            } catch (e: java.io.IOException) {
                last = e   // таймаут / обрыв сети — пробуем ещё раз
            }
        }
        throw last ?: Exception("Не удалось создать платёж")
    }

    /** {status: "paid", fulfillment: "done", subscription_id …} */
    fun paymentStatus(id: String): JSONObject =
        JSONObject(authed("GET", "/api/client/payments/$id/status"))

    /** Подписки аккаунта; subscription_url — ссылка-ключ для VPN-клиента. */
    fun subscriptions(): JSONArray = JSONArray(authed("GET", "/api/client/me/subscriptions"))
}
