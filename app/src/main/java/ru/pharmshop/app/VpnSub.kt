package ru.pharmshop.app

import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Сервер из подписки: либо готовый Xray-конфиг целиком (формат JSON), либо один outbound, собранный из ссылки. */
class VpnServer(val name: String, val outbound: JSONObject?, val full: JSONObject?)

/**
 * Скачивание подписки и превращение её в конфиг Xray.
 * Формат ответа определяется сам: «[» или «{» — Xray-JSON, иначе base64-список ссылок (или список ссылок как есть).
 * Поддерживаются vless://, trojan://, vmess://, ss://.
 */
object VpnSub {
    /** Панель выбирает формат ответа по User-Agent; браузерный даёт веб-страницу, поэтому у нас свой. */
    const val UA = "PharmShopVPN/1.0 (Android; Xray-core)"

    class SubException(msg: String) : Exception(msg)

    private fun ascii(s: String?) = (s ?: "").filter { it.code in 32..126 }.take(64)

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            // по этим заголовкам панель считает устройства (лимит «2 / 5»); HWID постоянный, чтобы не занимать новое место при каждом запуске
            c.setRequestProperty("x-hwid", Store.vpnHwid)
            c.setRequestProperty("x-device-os", "Android")
            c.setRequestProperty("x-ver-os", ascii(Build.VERSION.RELEASE))
            c.setRequestProperty("x-device-model", ascii(Build.MODEL))
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SubException(when (code) {
                401, 403 -> "Сервер не пустил это устройство (HTTP $code). Проверьте лимит устройств"
                404 -> "Подписка не найдена. Возможно, ссылка была перевыпущена"
                429 -> "Слишком много запросов, попробуйте через минуту"
                else -> "Ошибка сервера подписки (HTTP $code)"
            })
            return body
        } finally {
            c.disconnect()
        }
    }

    /** Свежий список серверов; без сети — последний сохранённый. */
    fun load(url: String): List<VpnServer> {
        try {
            val raw = download(url)
            val list = parse(raw)
            Store.vpnSubRaw = raw
            return list
        } catch (e: java.io.IOException) {
            val cached = Store.vpnSubRaw
            if (cached.isNotEmpty()) return parse(cached)
            throw e
        }
    }

    fun pick(list: List<VpnServer>): VpnServer =
        list.firstOrNull { it.name == Store.vpnServer } ?: list.first()

    fun parse(raw: String): List<VpnServer> {
        val t = raw.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) throw SubException("Подписка вернула пустой ответ")
        if (t.startsWith("<")) throw SubException("Сервер вернул веб-страницу вместо конфига (проверьте User-Agent в VpnSub.UA)")
        val list: List<VpnServer> = try {
            when {
                t.startsWith("[") -> parseJsonArray(JSONArray(t))
                t.startsWith("{") -> listOfNotNull(parseJsonConfig(JSONObject(t), "Сервер"))
                else -> parseLinks(decodeIfBase64(t))
            }
        } catch (e: org.json.JSONException) {
            throw SubException("Не удалось разобрать подписку")
        }
        if (list.isEmpty()) throw SubException("В подписке нет поддерживаемых серверов")
        return list
    }

    // ---------- Xray-JSON ----------

    private fun parseJsonArray(a: JSONArray): List<VpnServer> {
        val out = ArrayList<VpnServer>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            parseJsonConfig(o, "Сервер ${i + 1}")?.let { out.add(it) }
        }
        return out
    }

    private fun parseJsonConfig(o: JSONObject, def: String): VpnServer? {
        if (o.optJSONArray("outbounds") == null) return null
        return VpnServer(o.optString("remarks").ifEmpty { def }, null, o)
    }

    // ---------- ссылки ----------

    private fun dec(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    private fun b64(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun decodeIfBase64(t: String): String {
        if (t.contains("://")) return t
        return try { String(b64(t), Charsets.UTF_8) } catch (e: Exception) { throw SubException("Неизвестный формат подписки") }
    }

    private fun parseLinks(text: String): List<VpnServer> {
        val out = ArrayList<VpnServer>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            try {
                val s = when {
                    l.startsWith("vless://") -> vless(l)
                    l.startsWith("trojan://") -> trojan(l)
                    l.startsWith("vmess://") -> vmess(l)
                    l.startsWith("ss://") -> ss(l)
                    else -> null
                }
                if (s != null) out.add(s)
            } catch (e: Exception) {
                // битую строку пропускаем, остальные серверы работают
            }
        }
        return out
    }

    private fun params(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to dec(it.substringAfter('=')) }

    private fun hostPort(hp: String): Pair<String, Int> {
        val host = if (hp.startsWith("[")) hp.substring(1, hp.indexOf(']')) else hp.substringBeforeLast(':')
        return host to hp.substringAfterLast(':').trim().toInt()
    }

    private class Parts(val user: String, val host: String, val port: Int, val q: Map<String, String>, val name: String)

    private fun split(l: String): Parts {
        val body = l.substringAfter("://")
        val name = dec(body.substringAfter('#', ""))
        val main = body.substringBefore('#')
        val q = params(main.substringAfter('?', ""))
        val auth = main.substringBefore('?').trimEnd('/')
        val user = dec(auth.substringBeforeLast('@', ""))
        val (host, port) = hostPort(auth.substringAfterLast('@'))
        return Parts(user, host, port, q, name.ifEmpty { "$host:$port" })
    }

    private fun vless(l: String): VpnServer {
        val p = split(l)
        val user = JSONObject().put("id", p.user).put("encryption", p.q["encryption"] ?: "none")
        p.q["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        val ob = JSONObject().put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", p.host).put("port", p.port).put("users", JSONArray().put(user))
            )))
            .put("streamSettings", stream(p.q, "none"))
        return VpnServer(p.name, ob, null)
    }

    private fun trojan(l: String): VpnServer {
        val p = split(l)
        val ob = JSONObject().put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", p.host).put("port", p.port).put("password", p.user)
            )))
            .put("streamSettings", stream(p.q, "tls"))
        return VpnServer(p.name, ob, null)
    }

    private fun vmess(l: String): VpnServer {
        val j = JSONObject(String(b64(l.removePrefix("vmess://").substringBefore('#')), Charsets.UTF_8))
        val host = j.optString("add")
        val port = j.optString("port").toInt()
        val q = HashMap<String, String>()
        q["type"] = j.optString("net").ifEmpty { "tcp" }
        q["security"] = j.optString("tls").ifEmpty { "none" }
        q["sni"] = j.optString("sni")
        q["host"] = j.optString("host")
        q["path"] = j.optString("path")
        q["alpn"] = j.optString("alpn")
        q["fp"] = j.optString("fp")
        q["headerType"] = j.optString("type")
        val user = JSONObject().put("id", j.optString("id")).put("alterId", j.optInt("aid", 0))
            .put("security", j.optString("scy").ifEmpty { "auto" })
        val ob = JSONObject().put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))
            )))
            .put("streamSettings", stream(q, "none"))
        return VpnServer(j.optString("ps").ifEmpty { "$host:$port" }, ob, null)
    }

    private fun ss(l: String): VpnServer {
        val body = l.removePrefix("ss://")
        val name = dec(body.substringAfter('#', ""))
        val main = body.substringBefore('#').substringBefore('?').trimEnd('/')
        val method: String
        val pass: String
        val hp: String
        if (main.contains('@')) {
            val ui = main.substringBeforeLast('@')
            val d = if (ui.contains(':')) dec(ui) else String(b64(ui), Charsets.UTF_8)
            method = d.substringBefore(':')
            pass = d.substringAfter(':')
            hp = main.substringAfterLast('@')
        } else {
            val d = String(b64(main), Charsets.UTF_8)
            method = d.substringBefore(':')
            pass = d.substringAfter(':').substringBeforeLast('@')
            hp = d.substringAfterLast('@')
        }
        val (host, port) = hostPort(hp)
        val ob = JSONObject().put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("method", method).put("password", pass)
            )))
        return VpnServer(name.ifEmpty { "$host:$port" }, ob, null)
    }

    /** streamSettings из параметров ссылки: транспорт (tcp/ws/grpc/httpupgrade/xhttp) и защита (tls/reality). */
    private fun stream(q: Map<String, String>, defSec: String): JSONObject {
        val net = q["type"].orEmpty().ifEmpty { "tcp" }.let { if (it == "splithttp") "xhttp" else it }
        val sec = q["security"].orEmpty().ifEmpty { defSec }
        val s = JSONObject().put("network", net).put("security", sec)
        val sni = q["sni"].orEmpty()
        val host = q["host"].orEmpty()
        val path = q["path"].orEmpty()
        when (sec) {
            "tls" -> s.put("tlsSettings", JSONObject().also { t ->
                if (sni.isNotEmpty()) t.put("serverName", sni)
                q["fp"]?.takeIf { it.isNotEmpty() }?.let { t.put("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotEmpty() }?.let { t.put("alpn", JSONArray(it.split(','))) }
                if (q["allowInsecure"] == "1") t.put("allowInsecure", true)
            })
            "reality" -> s.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q["fp"].orEmpty().ifEmpty { "chrome" })
                .put("publicKey", q["pbk"].orEmpty())
                .put("shortId", q["sid"].orEmpty())
                .put("spiderX", q["spx"].orEmpty()))
        }
        when (net) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", path.ifEmpty { "/" }).also {
                if (host.isNotEmpty()) it.put("headers", JSONObject().put("Host", host))
            })
            "grpc" -> s.put("grpcSettings", JSONObject()
                .put("serviceName", q["serviceName"].orEmpty().ifEmpty { path })
                .put("multiMode", q["mode"] == "multi"))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", path.ifEmpty { "/" }).put("host", host))
            "xhttp" -> s.put("xhttpSettings", JSONObject()
                .put("path", path.ifEmpty { "/" }).put("host", host)
                .put("mode", q["mode"].orEmpty().ifEmpty { "auto" })
                .also { x ->
                    q["extra"]?.takeIf { it.startsWith("{") }?.let { e -> try { x.put("extra", JSONObject(e)) } catch (ex: Exception) { } }
                })
            "tcp" -> if (q["headerType"] == "http") s.put("tcpSettings", JSONObject().put("header", JSONObject()
                .put("type", "http")
                .put("request", JSONObject()
                    .put("path", JSONArray().put(path.ifEmpty { "/" }))
                    .put("headers", JSONObject().put("Host", JSONArray().put(host))))))
        }
        return s
    }

    // ---------- итоговый конфиг ----------

    /** Конфиг Xray для выбранного сервера. Вход — TUN: файловый дескриптор интерфейса отдаёт VpnService. */
    fun buildConfig(s: VpnServer): String {
        val cfg: JSONObject
        if (s.full != null) {
            cfg = JSONObject(s.full.toString())   // копия: подписку не портим
            cfg.remove("remarks")
        } else {
            cfg = JSONObject()
            cfg.put("outbounds", JSONArray()
                .put(JSONObject(s.outbound.toString()).put("tag", "proxy"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            cfg.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("ip", JSONArray().put("geoip:private")).put("outboundTag", "direct")
            )))
        }
        cfg.put("log", JSONObject().put("loglevel", "warning"))
        if (!cfg.has("dns")) cfg.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        cfg.put("inbounds", JSONArray().put(JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500))
            .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic")))))
        return cfg.toString()
    }
}
