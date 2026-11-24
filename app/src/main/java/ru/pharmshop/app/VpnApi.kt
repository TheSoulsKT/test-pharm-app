package ru.pharmshop.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    private fun raw(method: String, path: String, body: String?, bearer: String?, cookie: String?, referer: String = "$BASE/app/plans"): Resp {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "PharmShopApp/1.0")
            c.setRequestProperty("Origin", BASE)
            c.setRequestProperty("Referer", referer)
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
        Store.vpnCache = ""
        Store.vpnNotified = ""
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

    // ---------- вход, регистрация и сброс пароля по почте ----------

    // Адреса: вход и подтверждение сброса видны на скринах точно, остальные выведены по ним.
    // Если сервер ответит 404/405 — ошибка покажет путь, поправить его нужно только здесь.
    private const val P_LOGIN = "$AUTH/auth/login"
    private const val P_REG_REQUEST = "$AUTH/auth/register/request"
    private const val P_REG_CONFIRM = "$AUTH/auth/register/confirm"
    private const val P_RESET_REQUEST = "$AUTH/auth/password/reset/request"
    private const val P_RESET_CONFIRM = "$AUTH/auth/password/reset/confirm"

    /** Ошибка сервера в виде, понятном человеку. captcha = true, если сервер требует пройти капчу. */
    private class ApiFail(msg: String, val captcha: Boolean) : Exception(msg)

    private fun failOf(r: Resp, path: String): ApiFail {
        AppLog.e("vpn", "$path → HTTP ${r.code}")
        if (r.code == 429) return ApiFail("Слишком много попыток. Подождите немного.", false)
        var code = ""
        var reason = ""
        var msg = ""
        try {
            val j = JSONObject(r.text.trim())
            code = j.optString("code")
            reason = j.optJSONObject("context")?.optString("reason") ?: ""
            for (k in listOf("message", "detail", "error")) {
                val v = j.opt(k)
                if (v is String && v.isNotEmpty()) { msg = v; break }
            }
        } catch (e: Exception) {
            // не JSON — отдадим только код
        }
        if (code == "app.captcha") {
            val t = when (reason) {
                "captcha_replay" -> "Проверка устарела. Попробуйте ещё раз."
                "captcha_missing" -> "Проверка ещё не завершилась. Подождите секунду и повторите."
                else -> "Проверка не пройдена. Попробуйте ещё раз."
            }
            return ApiFail(t, true)
        }
        if (r.code == 404 || r.code == 405) return ApiFail("Сервер не знает адрес $path (HTTP ${r.code})", false)
        return ApiFail(if (msg.isNotEmpty()) "${msg.take(200)} (HTTP ${r.code})" else "Ошибка сервера (HTTP ${r.code})", false)
    }

    /** Вырезает из JSON-текста объект по ключу «как есть», чтобы не менять порядок полей (подпись капчи считается по ним). */
    private fun rawObject(text: String, key: String): String {
        val k = text.indexOf("\"$key\"")
        if (k < 0) throw Exception("Капча: в ответе нет поля $key")
        val start = text.indexOf('{', k)
        if (start < 0) throw Exception("Капча: не удалось разобрать ответ")
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                }
            }
        }
        throw Exception("Капча: не удалось разобрать ответ")
    }

    private fun hexToBytes(h: String): ByteArray {
        if (h.length % 2 != 0) throw Exception("Капча: неверное hex-значение")
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }

    /** PBKDF2 (RFC 2898) на HMAC. Пароль здесь — сырые байты, поэтому SecretKeyFactory не подходит. */
    private fun pbkdf2(macName: String, pwd: ByteArray, salt: ByteArray, iters: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(pwd, macName))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 2..iters) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    /**
     * Проходит капчу сайта (ALTCHA, подбор по PBKDF2) и возвращает строку для поля "captcha".
     * Пустая строка — капча на сервере выключена. Каждая капча одноразовая: на каждый запрос берём новую.
     * Занимает несколько секунд, поэтому вызывать только не в потоке интерфейса.
     */
    fun solveCaptcha(referer: String): String {
        val r = raw("GET", "/api/captcha/challenge", null, null, null, referer)
        if (r.code !in 200..299) throw failOf(r, "/api/captcha/challenge")
        val j = JSONObject(r.text)
        if (j.has("enabled") && !j.optBoolean("enabled", true)) return ""
        val p = j.getJSONObject("parameters")
        val sig = j.getString("signature")
        val rawParams = rawObject(r.text, "parameters")

        val algo = p.optString("algorithm", "PBKDF2/SHA-256")
        val macName = when (algo) {
            "PBKDF2/SHA-256" -> "HmacSHA256"
            "PBKDF2/SHA-384" -> "HmacSHA384"
            "PBKDF2/SHA-512" -> "HmacSHA512"
            else -> throw Exception("Капча: неизвестный алгоритм $algo")
        }
        val cost = p.getInt("cost")
        val keyLen = p.optInt("keyLength", 32)
        val prefix = p.getString("keyPrefix").lowercase()
        val nonce = hexToBytes(p.getString("nonce"))
        val salt = hexToBytes(p.getString("salt"))

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val found = AtomicReference<Pair<Int, String>?>(null)
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 90_000L
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val jobs = (0 until workers).map { w ->
                pool.submit {
                    val pwd = ByteArray(nonce.size + 4)
                    System.arraycopy(nonce, 0, pwd, 0, nonce.size)
                    var counter = w
                    while (found.get() == null && System.currentTimeMillis() < deadline) {
                        // счётчик дописывается к nonce четырьмя байтами, старший первым
                        pwd[nonce.size] = (counter ushr 24).toByte()
                        pwd[nonce.size + 1] = (counter ushr 16).toByte()
                        pwd[nonce.size + 2] = (counter ushr 8).toByte()
                        pwd[nonce.size + 3] = counter.toByte()
                        val hex = bytesToHex(pbkdf2(macName, pwd, salt, cost, keyLen))
                        if (hex.startsWith(prefix)) {
                            found.compareAndSet(null, counter to hex)
                            break
                        }
                        counter += workers
                    }
                }
            }
            for (job in jobs) job.get()
        } finally {
            pool.shutdownNow()
        }
        val (counter, derived) = found.get() ?: throw Exception("Не удалось пройти проверку за 90 секунд")
        val took = System.currentTimeMillis() - t0
        val solution = JSONObject().put("counter", counter).put("derivedKey", derived).put("time", took)
        val payload = "{\"challenge\":{\"parameters\":$rawParams,\"signature\":${JSONObject.quote(sig)}},\"solution\":$solution}"
        return Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** POST в /auth/...; при withCaptcha перед отправкой проходит капчу. */
    private fun authPost(path: String, body: JSONObject, referer: String, withCaptcha: Boolean): Resp {
        if (withCaptcha) {
            val cap = solveCaptcha(referer)
            if (cap.isNotEmpty()) body.put("captcha", cap)
        }
        val r = raw("POST", path, body.toString(), null, null, referer)
        if (r.code !in 200..299) throw failOf(r, path)
        return r
    }

    /** Вход по почте и паролю. Капчу сайт при входе не просил; если потребует — пройдём и повторим. */
    fun loginEmail(email: String, password: String) {
        fun body() = JSONObject().put("email", email).put("password", password)
        val ref = "$BASE/login"
        val r = try {
            authPost(P_LOGIN, body(), ref, false)
        } catch (f: ApiFail) {
            if (!f.captcha) throw f
            authPost(P_LOGIN, body(), ref, true)
        }
        saveSession(JSONObject(r.text), r.cookies)
    }

    /** Регистрация, шаг 1: сервер шлёт код на почту. */
    fun registerRequest(email: String, password: String) {
        authPost(P_REG_REQUEST,
            JSONObject().put("email", email).put("password", password).put("slug", JSONObject.NULL),
            "$BASE/register", true)
    }

    /** Регистрация, шаг 2: код из письма. После успеха пользователь уже вошёл. */
    fun registerConfirm(email: String, code: String, password: String) {
        val r = authPost(P_REG_CONFIRM,
            JSONObject().put("email", email).put("code", code).put("password", password).put("slug", JSONObject.NULL),
            "$BASE/register", false)
        saveSession(JSONObject(r.text), r.cookies)
    }

    /** Сброс пароля, шаг 1: сервер шлёт код на почту. */
    fun resetRequest(email: String) {
        authPost(P_RESET_REQUEST, JSONObject().put("email", email), "$BASE/reset", true)
    }

    /** Сброс пароля, шаг 2: код из письма и новый пароль. Сайт сразу входит в аккаунт — если токен пришёл, сохраняем. */
    fun resetConfirm(email: String, code: String, newPassword: String): Boolean {
        val r = authPost(P_RESET_CONFIRM,
            JSONObject().put("email", email).put("code", code).put("new_password", newPassword),
            "$BASE/reset", false)
        val j = try { JSONObject(r.text) } catch (e: Exception) { return false }
        val s = j.optJSONObject("session") ?: j
        if (s.optString("access_token").isEmpty()) return false
        saveSession(j, r.cookies)
        return true
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

    // ---------- продление и устройства ----------

    /** id подписки в адресах API. Поле в ответе subscriptions() называется по-разному — берём первое найденное. */
    fun subId(s: JSONObject): String {
        for (k in listOf("id", "uuid", "subscription_id", "subscription_uuid")) {
            val v = s.optString(k)
            if (v.isNotEmpty() && v != "null") return v
        }
        return ""
    }

    /** Цена продления на тот же срок: provider_totals по способам оплаты (копейки), new_end_date. */
    fun renewQuote(subId: String, days: Int? = null): JSONObject =
        JSONObject(authed("GET", "/api/client/subscriptions/$subId/renew-quote" + (if (days != null) "?days=$days" else "")))

    /** Создаёт платёж на продление. В ответе: payment_id, invoice_url. */
    fun renew(subId: String, provider: String, days: Int? = null): JSONObject {
        val b = JSONObject().put("provider", provider)
        if (days != null) b.put("days", days)   // без days сайт продлевает на «1 месяц»
        return JSONObject(authed("POST", "/api/client/subscriptions/$subId/renew", b.toString()))
    }

    /** Устройства: id, hwid, name, os, model, os_version, ip, first_seen, last_seen. */
    fun devices(subId: String): JSONArray = JSONArray(authed("GET", "/api/client/subscriptions/$subId/devices"))

    /** Отключённые устройства: hwid, name, blocked_at. */
    fun deviceBlocks(subId: String): JSONArray = JSONArray(authed("GET", "/api/client/subscriptions/$subId/device-blocks"))

    /** Отключает устройство (оно попадает в список заблокированных). deviceId — числовой id из devices(). */
    fun disconnectDevice(subId: String, deviceId: Long) {
        authed("DELETE", "/api/client/subscriptions/$subId/devices/$deviceId")
    }

    /** Возвращает отключённое устройство. */
    fun unblockDevice(subId: String, hwid: String) {
        authed("POST", "/api/client/subscriptions/$subId/device-blocks/unblock", JSONObject().put("hwid", hwid).toString())
    }

    /** Новая ссылка подписки; старая перестаёт работать. В ответе: id, subscription_url, rotated_at. */
    fun rotateLink(subId: String): JSONObject =
        JSONObject(authed("POST", "/api/client/subscriptions/$subId/rotate-link", "{}"))

    /** Замораживает подписку. В ответе: id, is_active, is_frozen, auto_renew_enabled, auto_renew_days. */
    fun freeze(subId: String): JSONObject =
        JSONObject(authed("POST", "/api/client/subscriptions/$subId/freeze", "{}"))

    fun unfreeze(subId: String): JSONObject =
        JSONObject(authed("POST", "/api/client/subscriptions/$subId/unfreeze", "{}"))

    /** Пакеты докупки трафика: gb, total_display (копейки, с комиссией), currency. */
    fun trafficPacks(subId: String): JSONArray =
        JSONObject(authed("GET", "/api/client/subscriptions/$subId/traffic/packs")).optJSONArray("packs") ?: JSONArray()

    /** Платёж за пакет трафика. В ответе: payment_id, invoice_url. */
    fun trafficIntent(subId: String, gb: Int, provider: String): JSONObject =
        JSONObject(authed("POST", "/api/client/subscriptions/$subId/traffic-intent",
            JSONObject().put("gb_amount", gb).put("provider", provider).toString()))

    /** Пополнение баланса аккаунта; сумма в копейках. В ответе: payment_id, invoice_url. */
    fun topup(amountMinor: Long, provider: String): JSONObject =
        JSONObject(authed("POST", "/api/client/payments/topup",
            JSONObject().put("amount_display_minor", amountMinor).put("provider", provider).toString()))

    /** Профиль: balance_display_minor — баланс в копейках. */
    fun profile(): JSONObject =
        JSONObject(authed("GET", "/api/client/me/profile"))

    /** Расчёт смены тарифа: charged_display_minor (доплата, копейки), enough_balance, remaining_days, devices, plan_name. */
    fun upgradeQuote(subId: String, newPlanUuid: String): JSONObject {
        val body = JSONObject().put("new_plan_uuid", newPlanUuid).toString()
        return JSONObject(authed("POST", "/api/client/subscriptions/$subId/upgrade/quote", body))
    }

    /** Меняет тариф: доплата сразу списывается с баланса. В ответе: plan_uuid, devices, end_date, charged_display_minor. */
    fun upgrade(subId: String, newPlanUuid: String): JSONObject =
        JSONObject(authed("POST", "/api/client/subscriptions/$subId/upgrade", JSONObject().put("new_plan_uuid", newPlanUuid).toString()))
}
