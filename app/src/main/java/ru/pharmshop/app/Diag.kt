package ru.pharmshop.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Диагностика и аналитика использования (Настройки → Прочее → «Какие данные собирает приложение»).
 *
 * Всё копится только на телефоне и уходит администратору одним блоком вместе с обращением в поддержку
 * (сообщение в чате или «Сообщить об ошибке»). Без обращения ничего не отправляется.
 * Не собираются: PIN, пароли, ссылка подписки VPN, платёжные данные, контакты, геолокация, содержимое чатов и фото.
 */
object Diag {
    private var sp: SharedPreferences? = null
    private var app: Context? = null
    private var launchCounted = false     // «запуск» = старт процесса, а не пересоздание экрана при смене темы
    private var fgStart = 0L              // когда приложение вышло на экран (elapsedRealtime), 0 — не на экране
    private var lastStop = 0L             // когда ушло с экрана (elapsedRealtime), 0 — ещё не уходило в этом процессе
    private const val SESSION_GAP_MS = 30_000L
    private const val MAX_PAYLOAD = 3800

    private val TABS = mapOf(0 to "home", 1 to "support", 2 to "profile", 3 to "settings", 4 to "vpn", 5 to "community")

    /** Подписи для разделов и функций (и в окне «Что уйдёт сейчас», и в админке). */
    private val LABELS = mapOf(
        "home" to "Главная", "support" to "Поддержка", "profile" to "Профиль", "settings" to "Настройки", "vpn" to "VPN", "community" to "Чат",
        "stars" to "Звёзды", "premium" to "Premium", "bug_report" to "Отчёт об ошибке", "speed_test" to "Скорость VPN",
        "avatar" to "Фото профиля"
    )

    fun init(c: Context) {
        app = c.applicationContext
        if (sp == null) sp = c.applicationContext.getSharedPreferences("pharmshop_diag", Context.MODE_PRIVATE)
    }

    /** Один раз за запуск процесса: дата первого и последнего захода, число запусков, активные дни. */
    @Synchronized
    fun onLaunch() {
        val s = sp ?: return
        if (launchCounted) return
        launchCounted = true
        val now = System.currentTimeMillis()
        val e = s.edit()
        if (s.getLong("first", 0L) == 0L) e.putLong("first", now)
        e.putLong("last", now)
        e.putInt("launches", s.getInt("launches", 0) + 1)
        e.apply()
        markDay()
    }

    @Synchronized
    fun onStart() {
        val s = sp ?: return
        if (fgStart != 0L) return
        val now = SystemClock.elapsedRealtime()
        fgStart = now
        // новая сессия — если приложение не было на экране дольше 30 секунд (поворот и смена темы не считаются)
        if (lastStop == 0L || now - lastStop > SESSION_GAP_MS) s.edit().putInt("sessions", s.getInt("sessions", 0) + 1).apply()
        markDay()
    }

    @Synchronized
    fun onStop() {
        val s = sp ?: return
        if (fgStart == 0L) return
        val now = SystemClock.elapsedRealtime()
        val spent = (now - fgStart).coerceIn(0L, 6L * 60 * 60 * 1000)
        fgStart = 0L
        lastStop = now
        s.edit().putLong("fg_ms", s.getLong("fg_ms", 0L) + spent).apply()
    }

    /** Открыта вкладка нижней панели (номера как в openTab). */
    fun tab(tab: Int) { TABS[tab]?.let { bump("screens", it) } }

    /** Использована функция: stars, premium, bug_report, speed_test, avatar. */
    fun feature(name: String) = bump("features", name)

    @Synchronized
    private fun bump(group: String, key: String) {
        val s = sp ?: return
        val o = try { JSONObject(s.getString(group, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(key, o.optInt(key) + 1)
        s.edit().putString(group, o.toString()).apply()
    }

    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Хранит последние 30 дней, в которые приложение открывали. */
    private fun markDay() {
        val s = sp ?: return
        val today = dayKey()
        val days = (s.getString("days", "") ?: "").split(',').filter { it.length == 8 }.toMutableList()
        if (today in days) return
        days.add(today)
        s.edit().putString("days", days.takeLast(30).joinToString(",")).apply()
    }

    /** Полный сброс (вместе с «Стереть данные приложения»). */
    fun reset() {
        sp?.edit()?.clear()?.apply()
        launchCounted = false
    }

    private fun lockName(): String = when {
        !Store.hasPin || Store.lockMode == 0 -> "none"
        Store.lockMode == 2 -> "fingerprint"
        else -> "pin"
    }

    /** Блок данных, который уходит вместе с обращением в поддержку. Укладывается в ~4 КБ. */
    fun payload(c: Context? = null): JSONObject {
        val ctx = c ?: app ?: return JSONObject()
        val s = sp
        // секунды в приложении, включая текущую сессию
        var fg = s?.getLong("fg_ms", 0L) ?: 0L
        if (fgStart != 0L) fg += SystemClock.elapsedRealtime() - fgStart
        val tz = TimeZone.getDefault()
        val errors = JSONArray()
        AppLog.recent(5).forEach { errors.put(it.take(120)) }
        val crash = AppLog.lastCrash().lines().filter { it.isNotBlank() }.take(2).joinToString(" | ") { it.take(120) }
        val o = JSONObject()
            .put("v", 1)
            .put("app", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE)
            .put("android", android.os.Build.VERSION.RELEASE ?: "")
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("phone", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            .put("lang", Locale.getDefault().language)
            .put("tz", tz.id)
            .put("first", s?.getLong("first", 0L) ?: 0L)
            .put("last", s?.getLong("last", 0L) ?: 0L)
            .put("launches", s?.getInt("launches", 0) ?: 0)
            .put("sessions", s?.getInt("sessions", 0) ?: 0)
            .put("fg_min", (fg / 60_000L).toInt())
            .put("days30", (s?.getString("days", "") ?: "").split(',').count { it.length == 8 })
            .put("screens", try { JSONObject(s?.getString("screens", "{}") ?: "{}") } catch (e: Exception) { JSONObject() })
            .put("features", try { JSONObject(s?.getString("features", "{}") ?: "{}") } catch (e: Exception) { JSONObject() })
            .put("orders", Store.orders().size)
            .put("prefs", JSONObject()
                .put("theme", if (Theme.light) "light" else "dark")
                .put("skin", Theme.skin.title)
                .put("lock", lockName())
                .put("push", Store.pushEnabled)
                .put("notif", Notifier.enabled(ctx))
                .put("vpn", VpnApi.loggedIn()))
            .put("errors", errors)
            .put("crash", crash)
        // страховка от слишком длинного блока: сначала режем журнал ошибок
        if (o.toString().length > MAX_PAYLOAD) { o.put("errors", JSONArray()); o.put("crash", crash.take(120)) }
        return o
    }

    private fun fmtDate(ms: Long): String =
        if (ms <= 0L) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

    private fun counts(o: JSONObject?): String {
        if (o == null || o.length() == 0) return "—"
        val parts = ArrayList<Pair<String, Int>>()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); parts.add((LABELS[k] ?: k) to o.optInt(k)) }
        return parts.sortedByDescending { it.second }.joinToString(", ") { "${it.first} ${it.second}" }
    }

    private fun minutes(m: Int): String = if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"

    /** Читаемый вид блока: для окна «Что уйдёт сейчас» в настройках и для админки. */
    fun render(o: JSONObject): String {
        val p = o.optJSONObject("prefs") ?: JSONObject()
        val lock = when (p.optString("lock")) { "pin" -> "PIN"; "fingerprint" -> "отпечаток"; else -> "нет" }
        val errs = o.optJSONArray("errors") ?: JSONArray()
        return buildString {
            append("Приложение: ${o.optString("app")} (сборка ${o.optInt("build")})\n")
            append("Android: ${o.optString("android")} (API ${o.optInt("api")})\n")
            append("Телефон: ${o.optString("phone")}\n")
            append("Язык: ${o.optString("lang")} · часовой пояс: ${o.optString("tz")}\n\n")
            append("Первый запуск: ${fmtDate(o.optLong("first"))}\n")
            append("Последний запуск: ${fmtDate(o.optLong("last"))}\n")
            append("Запусков: ${o.optInt("launches")} · сессий: ${o.optInt("sessions")}\n")
            append("Время в приложении: ${minutes(o.optInt("fg_min"))} · активных дней за месяц: ${o.optInt("days30")}\n\n")
            append("Разделы: ${counts(o.optJSONObject("screens"))}\n")
            append("Функции: ${counts(o.optJSONObject("features"))}\n")
            append("Заказов в приложении: ${o.optInt("orders")}\n")
            append("Настройки: ${if (p.optString("theme") == "light") "светлая" else "тёмная"} тема, ${p.optString("skin")} · ")
            append("защита: $lock · push: ${if (p.optBoolean("push")) "вкл" else "выкл"} · ")
            append("уведомления в системе: ${if (p.optBoolean("notif")) "вкл" else "выкл"} · ")
            append("VPN: ${if (p.optBoolean("vpn")) "вход выполнен" else "не входили"}\n\n")
            val crash = o.optString("crash")
            if (crash.isNotEmpty()) append("Последний сбой: $crash\n")
            if (errs.length() > 0) {
                append("Последние ошибки:\n")
                for (i in 0 until errs.length()) append("• ").append(errs.optString(i)).append('\n')
            } else if (crash.isEmpty()) append("Ошибок нет")
        }.trimEnd()
    }
}
