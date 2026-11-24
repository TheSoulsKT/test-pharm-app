package ru.pharmshop.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Запросы к витрине pharmshop.tgstars.tg (те же, что делает сам сайт). */
object Api {
    private const val BASE = "https://pharmshop.tgstars.tg"

    private fun call(
        method: String, path: String, body: JSONObject?,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        var url = URL(BASE + path)
        var redirects = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15000
                c.readTimeout = 20000
                // Сами обрабатываем редиректы: HttpURLConnection при 301/302 превращает POST в GET.
                c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "PharmShopApp/1.0")
                c.setRequestProperty("Origin", BASE)
                c.setRequestProperty("Referer", "$BASE/")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308) && redirects < 3) {
                    val loc = c.getHeaderField("Location")
                    if (loc != null) {
                        url = URL(url, loc)   // сохраняем метод и тело
                        redirects++
                        continue
                    }
                }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val where = "$method ${url.path}"
                if (!text.trimStart().startsWith("{")) {
                    AppLog.e("api", "$where → не JSON (код $code)")
                    throw Exception("Сервер вернул не JSON (код $code, $where). Возможно, сайт не пустил приложение.")
                }
                val json = JSONObject(text)
                if (code !in 200..299) {
                    // JSONObject сам декодирует \uXXXX, так что сообщение читается по-русски
                    val msg = json.optString("error").ifEmpty { text.take(300) }
                    AppLog.e("api", "$where → HTTP $code: ${msg.take(120)}")
                    throw Exception("HTTP $code ($where): $msg")
                }
                return json
            } finally {
                c.disconnect()
            }
        }
    }

    fun config(): JSONObject = call("GET", "/api/storefront/config", null)

    /** Текущий статус заказа (так же делает сам сайт). */
    @Volatile private var tokenMode = 0

    /**
     * Статус заказа. Сайт скрывает заказ без access_token (отвечает 404 «Заказ не найден»),
     * поэтому передаём токен. Как именно сайт его ждёт — неизвестно, пробуем несколько способов
     * и запоминаем тот, что сработал.
     */
    fun order(id: String, token: String = ""): JSONObject {
        val path = "/api/storefront/orders/$id"
        fun unwrap(r: JSONObject) = r.optJSONObject("order") ?: r.optJSONObject("data") ?: r
        if (token.isEmpty()) return unwrap(call("GET", path, null))

        val enc = java.net.URLEncoder.encode(token, "UTF-8")
        val modes = listOf<Pair<String, Map<String, String>>>(
            // 0: токен и в заголовках, и в адресе
            "$path?access_token=$enc&token=$enc" to mapOf(
                "Authorization" to "Bearer $token",
                "X-Access-Token" to token,
                "X-Order-Token" to token
            ),
            // 1: только в адресе
            "$path?access_token=$enc&token=$enc" to emptyMap(),
            // 2: только в заголовках, без Authorization
            path to mapOf("X-Access-Token" to token, "X-Order-Token" to token),
            // 3: только Bearer
            path to mapOf("Authorization" to "Bearer $token")
        )
        val start = tokenMode.coerceIn(0, modes.size - 1)
        val sequence = listOf(start) + modes.indices.filter { it != start }
        var last: Exception? = null
        for (i in sequence) {
            try {
                val r = unwrap(call("GET", modes[i].first, null, modes[i].second))
                tokenMode = i
                return r
            } catch (e: java.io.IOException) {
                throw e   // нет сети — перебирать способы бессмысленно
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("Не удалось получить статус")
    }


    fun quote(quantity: Int, methodId: String, product: String = ""): JSONObject {
        val body = JSONObject().put("quantity", quantity).put("method_id", methodId)
        if (product.isNotEmpty()) body.put("product", product)
        return call("POST", "/api/storefront/quote", body)
    }

    fun createOrder(username: String, quantity: Int, methodId: String, captchaToken: String, product: String = ""): JSONObject {
        // один и тот же ключ при повторе — сервер не создаст дубль заказа
        val key = UUID.randomUUID().toString()
        val body = JSONObject().put("username", username).put("quantity", quantity)
            .put("method_id", methodId).put("captcha_token", captchaToken)
        if (product.isNotEmpty()) body.put("product", product)   // "premium"; для звёзд поле не шлём
        var last: java.io.IOException? = null
        for (attempt in 0 until 2) {
            try {
                // сайт шлёт этот заголовок с UUID — без него сервер отвечает 422
                return call("POST", "/api/storefront/orders", body, mapOf("Idempotency-Key" to key))
            } catch (e: java.io.IOException) {
                last = e   // таймаут / обрыв сети — пробуем ещё раз
            }
        }
        throw last ?: Exception("Не удалось создать заказ")
    }
}
