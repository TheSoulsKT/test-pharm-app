package ru.pharmshop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Наблюдение за подпиской VPN в фоне:
 *  • кэш коротких данных подписок (для виджета; ссылки подписки в кэш не попадают);
 *  • уведомления «подписка скоро закончится» (за 3 дня и за 1 день), «подписка закончилась»,
 *    «трафик на исходе» (90%) и «трафик закончился».
 * Проверка идёт из PollWorker не чаще раза в 6 часов и только если пользователь вошёл в VPN.
 */
object VpnWatch {
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** Сохраняет краткие данные подписок и обновляет виджеты. */
    fun store(c: Context, subs: JSONArray) {
        val out = JSONArray()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            out.put(JSONObject()
                .put("id", VpnApi.subId(s))
                .put("plan", s.optString("plan_name"))
                .put("active", s.optBoolean("is_active"))
                .put("frozen", s.optBoolean("is_frozen"))
                .put("lifetime", s.optBoolean("is_lifetime"))
                .put("days", s.optInt("days_left"))
                .put("end", s.optString("end_date"))
                .put("limit", s.optLong("traffic_limit_bytes"))
                .put("used", s.optLong("used_traffic_bytes")))
        }
        Store.vpnCache = out.toString()
        Store.vpnCacheAt = System.currentTimeMillis()
        try { VpnWidget.updateAll(c) } catch (e: Exception) { AppLog.e("widget", e) }
    }

    fun cached(): JSONArray = try { JSONArray(Store.vpnCache) } catch (e: Exception) { JSONArray() }

    /** Загружает подписки с сервера и обновляет кэш и виджеты. Бросает исключение при сбое сети или входа. */
    fun refresh(c: Context): JSONArray {
        val subs = VpnApi.subscriptions()
        store(c, subs)
        return subs
    }

    /** «2026-12-05T18:27:16Z» → миллисекунды (0, если не разобрали). */
    internal fun parseIso(s: String): Long {
        if (s.length < 19) return 0L
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s.substring(0, 19))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun gb(bytes: Long): String {
        val v = bytes / 1073741824.0
        return if (v >= 10) "%.0f ГБ".format(Locale.US, v) else "%.1f ГБ".format(Locale.US, v)
    }

    private fun daysWord(n: Int): String {
        val m100 = n % 100; val m10 = n % 10
        return if (m100 in 11..14) "дней" else when (m10) { 1 -> "день"; 2, 3, 4 -> "дня"; else -> "дней" }
    }

    fun check(c: Context) {
        if (!VpnApi.loggedIn()) return
        if (!Store.vpnNotifyExpiry && !Store.vpnNotifyTraffic) return
        val now = System.currentTimeMillis()
        if (now - Store.vpnCheckedAt < CHECK_EVERY_MS) return
        val subs = try {
            refresh(c)
        } catch (e: VpnApi.AuthException) {
            return   // сессия кончилась — приложение попросит войти заново, когда его откроют
        } catch (e: Exception) {
            AppLog.e("vpn", e)
            return
        }
        Store.vpnCheckedAt = now

        val prev = Store.vpnNotified.split('\n').filter { it.isNotEmpty() }.toSet()
        val cur = LinkedHashSet<String>()
        val toSend = ArrayList<Triple<String, String, String>>()   // ключ, заголовок, текст
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            if (s.optBoolean("is_lifetime") || s.optBoolean("is_frozen")) continue
            val id = VpnApi.subId(s).ifEmpty { "sub$i" }
            val end = s.optString("end_date")
            val plan = s.optString("plan_name").ifEmpty { "Подписка" }.let { it.take(1).uppercase() + it.drop(1) }
            val endMs = parseIso(end)

            if (Store.vpnNotifyExpiry) {
                val active = s.optBoolean("is_active")
                val days = s.optInt("days_left")
                val endLabel = end.take(10).split("-").let { if (it.size == 3) "${it[2]}.${it[1]}.${it[0]}" else end }
                if (active && days <= 1) {
                    cur.add("$id|e3|$end"); cur.add("$id|e1|$end")
                    if ("$id|e1|$end" !in prev) toSend.add(Triple(
                        "$id|e1", "VPN: подписка заканчивается",
                        if (days <= 0) "«$plan» закончится в ближайшие сутки (до $endLabel). Продлите, чтобы не остаться без связи."
                        else "«$plan»: остался 1 день (до $endLabel). Продлите, чтобы не остаться без связи."
                    ))
                } else if (active && days <= 3) {
                    cur.add("$id|e3|$end")
                    if ("$id|e3|$end" !in prev) toSend.add(Triple(
                        "$id|e3", "VPN: подписка скоро закончится",
                        "«$plan»: осталось $days ${daysWord(days)} (до $endLabel). Продлите заранее."
                    ))
                } else if (!active && endMs > 0 && now - endMs in 0..(2 * 24 * 60 * 60 * 1000L)) {
                    cur.add("$id|x|$end")
                    if ("$id|x|$end" !in prev) toSend.add(Triple(
                        "$id|x", "VPN: подписка закончилась", "«$plan» закончилась $endLabel. Продлите её в приложении."
                    ))
                }
            }
            if (Store.vpnNotifyTraffic) {
                val limit = s.optLong("traffic_limit_bytes")
                val used = s.optLong("used_traffic_bytes")
                if (limit > 0) {
                    val ratio = used.toDouble() / limit
                    if (ratio >= 1.0) {
                        cur.add("$id|t90|$end"); cur.add("$id|t100|$end")
                        if ("$id|t100|$end" !in prev) toSend.add(Triple(
                            "$id|t100", "VPN: трафик закончился", "«$plan»: использовано ${gb(used)} из ${gb(limit)}. Докупите трафик в приложении."
                        ))
                    } else if (ratio >= 0.9) {
                        cur.add("$id|t90|$end")
                        if ("$id|t90|$end" !in prev) toSend.add(Triple(
                            "$id|t90", "VPN: трафик на исходе", "«$plan»: использовано ${gb(used)} из ${gb(limit)}."
                        ))
                    }
                }
            }
        }
        // запоминаем то, что верно сейчас: когда условие пропадёт (продлили, докупили), о нём можно будет сообщить снова
        Store.vpnNotified = cur.joinToString("\n")
        for ((key, title, text) in toSend.take(3)) Notifier.vpn(c, key, title, text)
    }
}
