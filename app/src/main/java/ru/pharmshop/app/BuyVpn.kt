package ru.pharmshop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.graphics.ColorUtils
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

// Раздел «VPN»: вход через Telegram или по почте, покупка подписки и выдача ключей.
// Устроен как экраны звёзд и Premium: форма, панель оплаты, оплата на странице платёжки.
// Сайт: pvpn.vitrinka.net (запросы — в VpnApi). Подписки всегда берём с сервера, в «Мои заказы» они не попадают.

private const val V_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Номер проверки платежа: новая проверка гасит старую, чтобы не крутились две сразу. */
private var vpnPollGen = 0

/** Тарифы и способы оплаты, загруженные недавно (чтобы не ждать их при каждом открытии вкладки). owner — чей это вход. */
private class VpnCatalog(val owner: Int, val at: Long, val plans: JSONArray, val prov: JSONObject)
private const val VPN_CATALOG_TTL = 3 * 60_000L
@Volatile private var vpnCatalog: VpnCatalog? = null

/** Результат фонового запроса; настоящую ошибку (например, AuthException) отдаём как есть, а не в обёртке ExecutionException. */
private fun <T> java.util.concurrent.Future<T>.result(): T =
    try { get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

// ---------- подписи ----------

/** Копейки → «118 ₽» (с округлением вверх, как на сайте). */
private fun vpnRub(minor: Long): String = "${(minor + 99) / 100} ₽"

private fun vpnDays(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "дней" else when (m10) { 1 -> "день"; 2, 3, 4 -> "дня"; else -> "дней" }
    return "$n $w"
}

private fun vpnDevices(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "устройств" else when (m10) { 1 -> "устройство"; 2, 3, 4 -> "устройства"; else -> "устройств" }
    return "$n $w"
}

/** «2026-12-05T18:27:16Z» → «05.12.2026». */
private fun vpnDate(s: String): String {
    val d = s.take(10).split("-")
    return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else s
}

/** Название тарифа для экрана: с большой буквы, как на сайте. */
private fun vpnPlanName(name: String): String = name.take(1).uppercase() + name.drop(1)

private fun vpnProviderName(id: String): String = when (id) {
    "platega" -> "СБП"
    "aurapay" -> "СБП 2"
    "antilopay" -> "СБП 3"
    "cryptobot" -> "CryptoBot (через Telegram)"
    "xrocket" -> "xRocket (через Telegram)"
    "lzt" -> "LZT Market"
    else -> id
}

// ---------- экран ----------

internal fun MainActivity.showVpn() {
    onHome = false
    val col = screen(4, bottomBar = true)
    val sid = screenId
    titleRow(col, "VPN")
    if (!VpnApi.loggedIn()) {
        col.addView(vpnHero(2, "Войдите в VPN", "Через Telegram или по почте. Ключ подписки появится здесь", emptyList()))
        buildVpnLogin(col, sid)
        riseIn(col)
        return
    }
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    setPullRefresh { if (sid == screenId && !busy) showVpn() }   // потянуть экран вниз — загрузить заново
    io.execute {
        try {
            // Раньше три запроса шли друг за другом (каждый ждёт предыдущий), теперь — одновременно.
            // Тарифы и способы оплаты почти не меняются, поэтому их ещё и держим в памяти несколько минут.
            val owner = Store.vpnRefresh.hashCode()
            val hit = vpnCatalog?.takeIf { it.owner == owner && System.currentTimeMillis() - it.at < VPN_CATALOG_TTL }
            val fPlans = if (hit == null) net.submit(java.util.concurrent.Callable { VpnApi.plans() }) else null
            val fProv = if (hit == null) net.submit(java.util.concurrent.Callable { VpnApi.providers() }) else null
            val subs = VpnApi.subscriptions()
            VpnWatch.store(applicationContext, subs)   // краткие данные для виджета и уведомлений
            val plans = hit?.plans ?: fPlans!!.result()
            val prov = hit?.prov ?: fProv!!.result()
            if (hit == null) vpnCatalog = VpnCatalog(owner, System.currentTimeMillis(), plans, prov)
            ui { if (sid == screenId) { status.visibility = View.GONE; buildVpnForm(col, sid, subs, plans, prov) } }
        } catch (e: VpnApi.AuthException) {
            ui { if (sid == screenId) showVpn() }   // сессия кончилась — откроется экран входа
        } catch (e: Exception) {
            ui {
                AppLog.e("vpn-load", e)
                if (sid == screenId) {
                    status.text = "Не удалось загрузить данные: ${e.message}"
                    col.addView(LinearLayout(this).apply { addView(chip("Повторить", true) { showVpn() }) })
                }
            }
        }
    }
}

// ---------- вход через Telegram ----------

private fun MainActivity.buildVpnLogin(col: LinearLayout, sid: Int) {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(12)
    }
    card.addView(txt("Вход через Telegram", 18f, TEXT, true).apply { layoutParams = lp(6) })
    card.addView(txt(
        "Откроется бот Pharm VPN. Нажмите в нём Start и вернитесь в приложение — вход подтвердится сам.",
        14f, MUTE
    ).apply { layoutParams = lp(12) })
    val info = txt("", 13f, MUTE).apply {
        visibility = View.GONE
        layoutParams = lp(10)
    }
    card.addView(btn("Войти через Telegram", true) {
        if (busy) return@btn
        busy = true
        info.visibility = View.VISIBLE
        info.text = "Создаём вход…"
        io.execute {
            try {
                val r = VpnApi.startLogin()
                val pollId = r.getString("poll_id")
                val url = r.getString("bot_url")
                val num = r.optInt("number", 0)
                val until = System.currentTimeMillis() + r.optLong("expires_in", 300L) * 1000L
                ui {
                    busy = false
                    if (sid == screenId) {
                        info.text = if (num > 0) "Ждём подтверждение в Telegram… Номер входа: $num" else "Ждём подтверждение в Telegram…"
                        openUrl(url)
                        pollVpnLogin(sid, pollId, until, info)
                    }
                }
            } catch (e: Exception) {
                ui { busy = false; info.text = "Ошибка: ${e.message}" }
            }
        }
    })
    card.addView(info)
    col.addView(card)
    buildVpnEmailAuth(col, sid)   // вход, регистрация и сброс пароля по почте (VpnAuthUi.kt)
    appFooter(col)
}

private fun MainActivity.pollVpnLogin(sid: Int, pollId: String, until: Long, info: TextView) {
    if (sid != screenId) return
    if (System.currentTimeMillis() > until) {
        info.text = "Время ожидания вышло. Нажмите кнопку ещё раз."
        return
    }
    io.execute {
        val res = try { VpnApi.pollLogin(pollId) } catch (e: Exception) { "error" }   // сеть моргнула — пробуем дальше
        ui {
            if (sid != screenId) return@ui
            when (res) {
                "confirmed" -> showVpn()
                "pending", "error" -> main.postDelayed({ pollVpnLogin(sid, pollId, until, info) }, 2000L)
                else -> { info.text = "Вход не подтверждён ($res). Нажмите кнопку ещё раз." }
            }
        }
    }
}

// ---------- подписки и покупка ----------

/** Вкладка раздела «VPN»: 0 — «Моя подписка», 1 — «Купить подписку»; -1 — выбрать по наличию подписок. */
private var vpnTab = -1

/** Строка уровня подписки в меню: название, устройства, цена. */
private fun vpnPlanLabel(p: JSONObject): String {
    val tag = if (p.optBoolean("is_special")) " · особый" else if (p.optBoolean("is_popular")) " · популярный" else ""
    return "${vpnPlanName(p.optString("name"))} · ${vpnDevices(p.optInt("devices"))} — ${vpnRub(p.optLong("total_price_display"))}$tag"
}

/** Шапка раздела по состоянию подписок: защита включена / заморожена / нет подписки (в центре кот). */
private fun MainActivity.vpnStateHero(best: JSONObject?, frozen: JSONObject?): LinearLayout = when {
    best != null -> vpnHero(
        0, "Защита включена", vpnPlanName(best.optString("plan_name").ifEmpty { "Подписка" }),
        listOf(
            "Осталось" to vpnDays(best.optInt("days_left")),
            "Действует до" to vpnDate(best.optString("end_date")),
            "Устройства" to best.optInt("devices").toString()
        )
    )
    frozen != null -> vpnHero(1, "Подписка заморожена", "Разморозьте её, чтобы снова пользоваться VPN", emptyList())
    else -> vpnHero(2, "Нет активной подписки", "Выберите тариф — ключ появится здесь сразу после оплаты", emptyList())
}

private fun MainActivity.buildVpnForm(col: LinearLayout, sid: Int, subs: JSONArray, plans: JSONArray, prov: JSONObject) {
    val firstNew = col.childCount

    // шапка с котом: состояние подписки одним взглядом
    var bestSub: JSONObject? = null
    var frozenSub: JSONObject? = null
    for (i in 0 until subs.length()) {
        val s = subs.optJSONObject(i) ?: continue
        if (bestSub == null && s.optBoolean("is_active") && !s.optBoolean("is_frozen")) bestSub = s
        if (frozenSub == null && s.optBoolean("is_frozen")) frozenSub = s
    }
    col.addView(vpnStateHero(bestSub, frozenSub))

    // платёж создан, но ещё не подтверждён
    val pending = Store.vpnPay
    val waitInfo = txt("⏳ Ждём подтверждение оплаты…", 14f, TEXT)
    if (pending.isNotEmpty()) {
        val actions = btnGrid(listOf(
            gbtn("Проверить сейчас", true) {
                vpnPollGen++
                waitInfo.text = "⏳ Проверяем оплату…"
                pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo)
            },
            gbtn("Не жду", false) { Store.vpnPay = ""; vpnPollGen++; showVpn() }
        ), 0).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(mix(BLUE, 0.14f), 18, BLUE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(12)
            addView(waitInfo)
            addView(actions)
        })
    }

    val methods = ArrayList<Pair<String, String>>()
    prov.optJSONArray("providers")?.let { arr ->
        for (i in 0 until arr.length()) {
            val id = arr.optString(i)
            if (id.isNotEmpty()) methods.add(Pair(id, vpnProviderName(id)))
        }
    }

    // тарифы (бессрочные не берём): по сроку, потом по цене
    val list = ArrayList<JSONObject>()
    for (i in 0 until plans.length()) {
        val p = plans.optJSONObject(i) ?: continue
        if (!p.optBoolean("is_lifetime")) list.add(p)
    }
    list.sortWith(compareBy<JSONObject>({ it.optInt("days") }, { it.optLong("total_price_display") }))
    val canBuy = list.isNotEmpty() && methods.isNotEmpty()

    var tab = if (vpnTab in 0..1) vpnTab else if (subs.length() > 0) 0 else 1

    // состояние покупки живёт здесь, чтобы не сбрасываться при переключении вкладок
    var plan: JSONObject = list.firstOrNull { it.optBoolean("is_popular") } ?: list.firstOrNull() ?: JSONObject()
    var method = methods.firstOrNull()?.first ?: ""
    var quote: JSONObject? = null
    var qTok = 0

    // закреплённая панель: сумма и кнопка (видна только на вкладке покупки)
    val bar = payBar { info -> payVpn(plan.optString("uuid"), method, info) }

    fun paintTotal() {
        val q = quote
        val byProvider = q?.optJSONObject("provider_totals")?.optLong(method, -1L) ?: -1L
        val minor = if (byProvider >= 0) byProvider else (q?.optLong("total_display_minor", -1L) ?: -1L)
        popText(bar.total, if (minor >= 0) vpnRub(minor) else "—")
    }

    fun requestQuote() {
        qTok++
        val my = qTok
        val uuid = plan.optString("uuid")
        bar.cap.text = "К оплате · ${vpnDays(plan.optInt("days"))}"
        quote = null
        popText(bar.total, "…")
        io.execute {
            try {
                val r = VpnApi.quote(uuid)
                ui { if (my == qTok && sid == screenId) { quote = r; paintTotal() } }
            } catch (e: Exception) {
                ui { if (my == qTok && sid == screenId) popText(bar.total, "—") }
            }
        }
    }

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    lateinit var renderTab: () -> Unit

    // переключатель вкладок со скользящей капсулой
    val seg = vpnSegment(listOf("Моя подписка", "Купить"), listOf(VIc.PAW, VIc.BAG), tab) { i ->
        if (tab != i) { tab = i; vpnTab = i; renderTab(); riseIn(content) }
    }
    col.addView(seg.root)
    col.addView(content)
    appFooter(col)

    // ---------- вкладка «Моя подписка» ----------
    fun fillMine(box: LinearLayout) {
        if (subs.length() == 0) {
            box.addView(vpnEmptyCard(
                "Подписки пока нет",
                "Выберите тариф — ключ появится здесь сразу после оплаты.",
                "Выбрать тариф"
            ) {
                tab = 1; vpnTab = 1; seg.select(1, true); renderTab(); riseIn(content)
            })
        } else {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                box.addView(vpnSubCard(s, methods))
            }
        }
        val topNote = txt("", 13f, MUTE).apply { visibility = View.GONE; layoutParams = lp(8) }
        val extra = ArrayList<View>()
        if (methods.isNotEmpty()) extra.add(vpnTile(VIc.WALLET, "Пополнить баланс", GREEN) { askTopup(methods, topNote) })
        extra.add(vpnTile(VIc.SPEED, "Скорость и пинг", CYAN) { openSpeedTest() })
        extra.add(vpnTile(VIc.LOGOUT, "Выйти из аккаунта", RED) {
            requirePin { VpnApi.logout(); VpnWidget.updateAll(this); vpnTab = -1; vpnPollGen++; showVpn() }
        })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(CARD, 22, LINE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = lp(8)
            addView(vpnGrid(extra))
        })
        box.addView(topNote)
    }

    // ---------- вкладка «Купить» ----------
    fun fillBuy(box: LinearLayout) {
        if (!canBuy) {
            box.addView(vpnEmptyCard("Покупка сейчас недоступна", "Загляните позже", null) {})
            return
        }
        val dayVals = list.map { it.optInt("days") }.distinct()

        box.addView(vpnLabel(VIc.CLOCK, "На какой срок"))
        val daysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = lp(6)
            addView(daysRow)
        })

        box.addView(vpnLabel(VIc.STAR, "Тариф"))
        val planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(6) }
        box.addView(planBox)

        fun planCard(p: JSONObject, onSelect: () -> Unit): View {
            val on = p.optString("uuid") == plan.optString("uuid")
            val tag = if (p.optBoolean("is_special")) "Особый" else if (p.optBoolean("is_popular")) "Популярный" else ""
            val num = txt(p.optInt("devices").toString(), 18f, if (on) android.graphics.Color.BLACK else TEXT, true).apply {
                gravity = Gravity.CENTER
                background = if (on) shape(BLUE, 12) else shape(BG, 12, LINE)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(txt(vpnPlanName(p.optString("name")), 15f, TEXT, true))
                if (tag.isNotEmpty()) addView(vpnPill(tag))
            }
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
                addView(nameRow)
                addView(txt(vpnDevices(p.optInt("devices")), 12f, MUTE))
            }
            val check = FrameLayout(this).apply {
                background = if (on) GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(BLUE) }
                else GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(android.graphics.Color.TRANSPARENT); setStroke(dp(1), LINE) }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(10) }
                if (on) addView(VpnIconView(this@buildVpnForm, VIc.CHECK, android.graphics.Color.BLACK).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (on) ripple(mix(BLUE, 0.12f), 18, edge(BLUE)) else ripple(CARD, 18, LINE)
                setPadding(dp(12), dp(12), dp(14), dp(12))
                layoutParams = lp(8)
                isClickable = true
                setOnClickListener { onSelect() }
                pressable(this)
                addView(num)
                addView(mid)
                addView(txt(vpnRub(p.optLong("total_price_display")), 16f, TEXT, true))
                addView(check)
            }
        }

        fun paintPlans() {
            planBox.removeAllViews()
            for (p in list.filter { it.optInt("days") == plan.optInt("days") }) {
                planBox.addView(planCard(p) { plan = p; paintPlans(); requestQuote() })
            }
        }

        fun paintDays() {
            daysRow.removeAllViews()
            for (d in dayVals) {
                val on = d == plan.optInt("days")
                daysRow.addView(txt(vpnDays(d), 14f, if (on) android.graphics.Color.BLACK else TEXT, true).apply {
                    gravity = Gravity.CENTER
                    background = if (on) ripple(BLUE, 20, BLUE) else ripple(CARD, 20, LINE)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { rightMargin = dp(8) }
                    isClickable = true
                    setOnClickListener {
                        val inDays = list.filter { it.optInt("days") == d }
                        plan = inDays.firstOrNull { it.optString("name") == plan.optString("name") }
                            ?: inDays.firstOrNull { it.optBoolean("is_popular") } ?: inDays[0]
                        paintDays(); paintPlans(); requestQuote()
                    }
                    pressable(this)
                })
            }
        }

        paintDays()
        paintPlans()

        // способ оплаты — комиссия уже учтена в сумме внизу
        box.addView(vpnLabel(VIc.WALLET, "Способ оплаты"))
        val methodRow = dropRow(box, "Способ оплаты", "Способ оплаты") { id, _ -> method = id; paintTotal() }
        methodRow.set(methods, method)

        attachBar(col, bar.root)
        requestQuote()
    }

    renderTab = {
        content.removeAllViews()
        if (tab == 0) (col.tag as? android.widget.FrameLayout)?.removeAllViews()
        if (tab == 0) fillMine(content) else fillBuy(content)
    }
    renderTab()
    riseIn(col, firstNew)
    if (pending.isNotEmpty()) { vpnPollGen++; pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo) }
}

/** Метка у тарифа («Популярный», «Особый»). */
private fun MainActivity.vpnPill(t: String): TextView =
    txt(t, 10f, android.graphics.Color.BLACK, true).apply {
        background = shape(BLUE, 8)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { leftMargin = dp(8) }
    }

// ---------- карточка подписки с ключом ----------

private fun MainActivity.vpnSubCard(s: JSONObject, methods: List<Pair<String, String>>): LinearLayout {
    val active = s.optBoolean("is_active")
    val frozen = s.optBoolean("is_frozen")
    val live = active && !frozen
    val url = s.optString("subscription_url")
    val st = if (frozen) CYAN else if (active) GREEN else MUTE
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (live) shape(mix(GREEN, 0.07f), 22, edge(GREEN)) else shape(CARD, 22, LINE)
        setPadding(dp(16), dp(16), dp(16), dp(14))
        layoutParams = lp(12)
    }

    // шапка: слева название и статус, справа кольцо с днями
    val name = s.optString("plan_name").ifEmpty { "Подписка" }
    val end = vpnDate(s.optString("end_date"))
    val daysLeft = maxOf(0, s.optInt("days_left"))
    val totalDays = s.optInt("days").let { if (it > 0) it else maxOf(daysLeft, 30) }

    val devPill = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(BG, 12, LINE)
        setPadding(dp(9), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { topMargin = dp(8) }
        addView(VpnIconView(this@vpnSubCard, VIc.PHONE, MUTE).apply { layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)) })
        addView(txt(vpnDevices(s.optInt("devices")), 12f, MUTE).apply {
            layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { leftMargin = dp(5) }
        })
    }
    val dot = View(this).apply {
        background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(st) }
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) }
    }
    if (live) pulse(dot)
    val statusText = when {
        frozen -> "Заморожена · до $end"
        active -> "Активна · до $end"
        else -> "Не активна · до $end"
    }
    val statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { topMargin = dp(8) }
        addView(dot)
        addView(txt(statusText, 12.5f, st, true))
    }
    val left = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f).apply { rightMargin = dp(12) }
        addView(txt(vpnPlanName(name), 19f, TEXT, true))
        addView(devPill)
        addView(statusRow)
    }
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(14)
        addView(left)
        addView(DaysRingView(
            this@vpnSubCard, st, LINE, TEXT, MUTE, daysLeft, totalDays,
            vpnDays(daysLeft).substringAfter(' '), Store.uiAnim
        ).apply { layoutParams = LinearLayout.LayoutParams(dp(78), dp(78)) })
    })

    // трафик
    val limBytes = s.optLong("traffic_limit_bytes")
    if (limBytes > 0) {
        val used = s.optLong("used_traffic_bytes")
        val frac = (used.toFloat() / limBytes.toFloat()).coerceIn(0f, 1f)
        val hi = frac >= 0.9f
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(7)
            addView(VpnIconView(this@vpnSubCard, VIc.CHART, MUTE).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) }
            })
            addView(txt("Трафик", 12.5f, MUTE).apply { layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f) })
            addView(txt("${vpnGb(used)} из ${vpnGb(limBytes)}", 12.5f, TEXT, true))
        })
        card.addView(GradBarView(
            this, if (hi) GOLD else BLUE, if (hi) RED else GREEN, LINE, frac, Store.uiAnim
        ).apply { layoutParams = lp(14).apply { height = dp(9) } })
    }

    // ссылка подписки и подключение
    if (url.startsWith("https://")) {
        val masked = Store.vpnMaskLink
        val hidden = "https://" + url.removePrefix("https://").substringBefore('/') + "/••••••••••••"
        var shown = false
        val linkView = txt(if (masked) hidden else url, 12f, MUTE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
        }
        if (masked) {
            // ссылка спрятана: нажатие показывает/прячет её (скопировать можно и не показывая)
            linkView.isClickable = true
            linkView.setOnClickListener {
                shown = !shown
                linkView.text = if (shown) url else hidden
            }
        } else {
            linkView.setTextIsSelectable(true)
        }
        val copyBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(mix(BLUE, 0.2f)) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) }
            isClickable = true
            contentDescription = "Скопировать ссылку"
            addView(VpnIconView(this@vpnSubCard, VIc.COPY, BLUE).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
            pressable(this)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("vpn", url)
                if (Store.clipAutoClear && android.os.Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)   // не показывать текст в подсказке «скопировано»
                    }
                }
                cm.setPrimaryClip(clip)
                if (Store.clipAutoClear) {
                    toast("Ссылка скопирована. Буфер очистится через минуту")
                    main.postDelayed({
                        try {
                            // чистим, только если там всё ещё наша ссылка (метка «vpn»), а не что-то новое
                            if (cm.primaryClipDescription?.label == "vpn") {
                                if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                            }
                        } catch (e: Exception) { /* система не дала — ничего страшного */ }
                    }, 60_000L)
                } else {
                    toast("Ссылка подписки скопирована")
                }
            }
        }
        val dashed = GradientDrawable().apply {
            setColor(BG)
            cornerRadius = corner(16)
            setStroke(dp(1), ColorUtils.blendARGB(LINE, BLUE, 0.4f), dp(4).toFloat(), dp(3).toFloat())
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = dashed
            setPadding(dp(14), dp(6), dp(6), dp(6))
            layoutParams = lp(10)
            addView(linkView)
            addView(copyBtn)
        })
        if (masked) card.addView(txt("Нажмите на ссылку, чтобы показать или скрыть", 11f, MUTE).apply {
            layoutParams = lp(10).apply { topMargin = -dp(4) }
        })
        card.addView(vpnConnectBlock(url, live) { openInVpnClient(url) })
    }

    // плитки действий
    val subId = VpnApi.subId(s)
    if (subId.isNotEmpty()) {
        val note = txt("", 13f, MUTE).apply { visibility = View.GONE; layoutParams = lp(8) }
        var devToggle: () -> Unit = {}
        val pink = if (Theme.light) android.graphics.Color.parseColor("#E0489A") else android.graphics.Color.parseColor("#F472B6")
        val tiles = ArrayList<View>()
        tiles.add(vpnTile(VIc.RENEW, "Продлить", GREEN) {
            if (busy) return@vpnTile
            pickRenewDays(subId, methods, note)
        })
        if (!s.optBoolean("is_trial") && !s.optBoolean("is_lifetime")) tiles.add(vpnTile(VIc.USERS, "Больше устройств", VIOLET) {
            if (busy) return@vpnTile
            startUpgrade(s, subId, methods, note)
        })
        if (limBytes > 0) tiles.add(vpnTile(VIc.CHART, "Докупить трафик", ORANGE) {
            if (busy) return@vpnTile
            note.visibility = View.VISIBLE
            note.text = "Загружаем пакеты…"
            io.execute {
                try {
                    val packs = VpnApi.trafficPacks(subId)
                    ui { note.visibility = View.GONE; pickTrafficPack(subId, methods, packs, note) }
                } catch (e: VpnApi.AuthException) {
                    ui { showVpn() }
                } catch (e: Exception) {
                    ui { note.text = "Не удалось загрузить пакеты: ${e.message}" }
                }
            }
        })
        tiles.add(vpnTile(VIc.PHONE, "Мои устройства", BLUE) { devToggle() })
        tiles.add(vpnTile(if (frozen) VIc.SUN else VIc.SNOW, if (frozen) "Разморозить" else "Заморозить", if (frozen) GOLD else CYAN) {
            if (busy) return@vpnTile
            val go = {
                busy = true
                io.execute {
                    try {
                        if (frozen) VpnApi.unfreeze(subId) else VpnApi.freeze(subId)
                        ui { busy = false; toast(if (frozen) "Подписка разморожена" else "Подписка заморожена"); showVpn() }
                    } catch (e: VpnApi.AuthException) {
                        ui { busy = false; showVpn() }
                    } catch (e: Exception) {
                        ui { busy = false; toast("Ошибка: ${e.message}") }
                    }
                }
            }
            if (frozen) go() else confirmSheet(
                "Заморозить подписку?",
                "Подписка будет приостановлена. Разморозить её можно в любой момент.",
                "Заморозить"
            ) { go() }
        })
        tiles.add(vpnTile(VIc.LINK, "Новая ссылка", pink) {
            if (busy) return@vpnTile
            val avail = s.optString("link_rotation_available_at")
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            if (avail.length >= 19 && avail != "null" && avail.take(19) > now) {
                toast("Ссылку можно обновить с ${vpnDateTime(avail)}")
                return@vpnTile
            }
            confirmSheet(
                "Обновить ссылку?",
                "Старая ссылка перестанет работать, и по ней больше никто не подключится. Кто уже подключился по ней, останется до конца срока подписки.",
                "Обновить"
            ) {
                requirePin {
                    busy = true
                    io.execute {
                        try {
                            VpnApi.rotateLink(subId)
                            ui { busy = false; toast("Ссылка обновлена"); showVpn() }
                        } catch (e: VpnApi.AuthException) {
                            ui { busy = false; showVpn() }
                        } catch (e: Exception) {
                            ui { busy = false; toast("Ошибка: ${e.message}") }
                        }
                    }
                }
            }
        })
        card.addView(vpnGrid(tiles, 3, 0))
        card.addView(note)
        devToggle = vpnDevicesBlock(card, subId)
    }
    return card
}

/** Срок продления: как на сайте — 1 месяц, 7/14/60/90 дней или свой (от 3 до 365). null = «1 месяц». */
private fun MainActivity.pickRenewDays(subId: String, methods: List<Pair<String, String>>, note: TextView) {
    val opts = arrayOf("1 месяц", "7 дней", "14 дней", "60 дней", "90 дней", "Свой срок…")
    val vals = arrayOf<Int?>(null, 7, 14, 60, 90, -1)
    listSheet("На сколько продлить?", opts.toList()) { i ->
        if (vals[i] == -1) askCustomDays(subId, methods, note) else quoteRenew(subId, methods, note, vals[i])
    }
}

private fun MainActivity.askCustomDays(subId: String, methods: List<Pair<String, String>>, note: TextView) {
    inputSheet("Свой срок, дней", "от 3 до 365") { text ->
        val n = text.toIntOrNull()
        if (n == null || n < 3 || n > 365) {
            toast("Введите число от 3 до 365")
            false
        } else {
            quoteRenew(subId, methods, note, n)
            true
        }
    }
}

private fun MainActivity.quoteRenew(subId: String, methods: List<Pair<String, String>>, note: TextView, days: Int?) {
    note.visibility = View.VISIBLE
    note.text = "Считаем цену…"
    io.execute {
        var q: JSONObject? = null
        try {
            q = VpnApi.renewQuote(subId, days)
        } catch (e: VpnApi.AuthException) {
            ui { showVpn() }
            return@execute
        } catch (e: Exception) {
            // цену узнать не вышло — продлить всё равно можно, суммы в списке не будет
        }
        ui { note.visibility = View.GONE; pickRenewMethod(subId, methods, q, note, days) }
    }
}

/** Выбор способа оплаты для продления (с итоговой суммой, если сайт её отдал). */
private fun MainActivity.pickRenewMethod(subId: String, methods: List<Pair<String, String>>, q: JSONObject?, note: TextView, days: Int?) {
    val totals = q?.optJSONObject("provider_totals")
    val items = methods.map { (id, name) ->
        val minor = totals?.optLong(id, -1L) ?: -1L
        if (minor >= 0) "$name — ${vpnRub(minor)}" else name
    }
    val end = q?.optString("new_end_date").orEmpty()
    val title = if (end.length >= 10) "Продлить до ${vpnDate(end)}" else "Продлить подписку"
    listSheet(title, items) { i ->
        confirmPayThen("$title: ${items[i]}") {
            note.visibility = View.VISIBLE
            payVpnWith(note) { VpnApi.renew(subId, methods[i].first, days) }
        }
    }
}

// ---------- трафик и баланс ----------

/** Байты → «7,1 ГБ». */
private fun vpnGb(bytes: Long): String =
    String.format(java.util.Locale("ru"), "%.1f ГБ", bytes / 1073741824.0)

private fun MainActivity.pickMethod(title: String, methods: List<Pair<String, String>>, onPick: (String) -> Unit) {
    listSheet(title, methods.map { it.second }) { i -> onPick(methods[i].first) }
}

private fun MainActivity.pickTrafficPack(subId: String, methods: List<Pair<String, String>>, packs: JSONArray, note: TextView) {
    if (packs.length() == 0) { toast("Пакетов трафика сейчас нет"); return }
    val gbs = ArrayList<Int>()
    val items = ArrayList<String>()
    for (i in 0 until packs.length()) {
        val p = packs.optJSONObject(i) ?: continue
        gbs.add(p.optInt("gb"))
        items.add("${p.optInt("gb")} ГБ — ${vpnRub(p.optLong("total_display"))}")
    }
    listSheet("Докупить трафик", items) { i ->
        pickMethod("Способ оплаты", methods) { prov ->
            confirmPayThen("Докупить трафик: ${items[i]}") {
                note.visibility = View.VISIBLE
                payVpnWith(note) { VpnApi.trafficIntent(subId, gbs[i], prov) }
            }
        }
    }
}

private fun MainActivity.askTopup(methods: List<Pair<String, String>>, note: TextView, prefillMinor: Long = 0L) {
    val prefill = if (prefillMinor > 0) ((prefillMinor + 99) / 100).toString() else ""
    inputSheet("Пополнить баланс", "сумма, ₽", prefill) { text ->
        val rub = text.toLongOrNull()
        if (rub == null || rub < 1) {
            toast("Введите сумму в рублях")
            false
        } else {
            pickMethod("Способ оплаты", methods) { prov ->
                confirmPayThen("Пополнение баланса: $rub ₽") {
                    note.visibility = View.VISIBLE
                    payVpnWith(note) { VpnApi.topup(rub * 100, prov) }
                }
            }
            true
        }
    }
}

// ---------- смена тарифа («Больше устройств») ----------

/** Ищет тарифы с тем же сроком и бОльшим числом устройств, считает доплату по каждому и даёт выбрать. */
private fun MainActivity.startUpgrade(s: JSONObject, subId: String, methods: List<Pair<String, String>>, note: TextView) {
    note.visibility = View.VISIBLE
    note.text = "Считаем доплату…"
    io.execute {
        try {
            val days = s.optInt("days")
            val devices = s.optInt("devices")
            val curPlan = s.optString("plan_uuid")
            val plans = VpnApi.plans()
            val cands = ArrayList<JSONObject>()
            for (i in 0 until plans.length()) {
                val p = plans.optJSONObject(i) ?: continue
                if (p.optBoolean("is_lifetime")) continue
                if (p.optInt("days") == days && p.optInt("devices") > devices && p.optString("uuid") != curPlan) cands.add(p)
            }
            cands.sortBy { it.optInt("devices") }
            val ok = ArrayList<Pair<JSONObject, JSONObject>>()
            var lastErr: Exception? = null
            for (p in cands) {
                try {
                    ok.add(Pair(p, VpnApi.upgradeQuote(subId, p.optString("uuid"))))
                } catch (e: VpnApi.AuthException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            val balance = try { VpnApi.profile().optLong("balance_display_minor", -1L) } catch (e: VpnApi.AuthException) { throw e } catch (e: Exception) { -1L }
            ui {
                note.visibility = View.GONE
                when {
                    cands.isEmpty() -> toast("Тарифов с большим числом устройств нет")
                    ok.isEmpty() -> { note.visibility = View.VISIBLE; note.text = "Не удалось посчитать доплату: ${lastErr?.message}" }
                    else -> pickUpgrade(subId, ok, balance, methods, note)
                }
            }
        } catch (e: VpnApi.AuthException) {
            ui { showVpn() }
        } catch (e: Exception) {
            ui { note.text = "Не удалось загрузить тарифы: ${e.message}" }
        }
    }
}

private fun MainActivity.pickUpgrade(subId: String, opts: List<Pair<JSONObject, JSONObject>>, balance: Long, methods: List<Pair<String, String>>, note: TextView) {
    val items = opts.map { (p, q) ->
        val extra = q.optLong("charged_display_minor")
        "${vpnPlanName(p.optString("name"))} · ${vpnDevices(p.optInt("devices"))} — доплата ${vpnRub(extra)}"
    }
    listSheet("Больше устройств", items) { i ->
        val (p, q) = opts[i]
        val charge = q.optLong("charged_display_minor")
        val enough = q.optBoolean("enough_balance", true)
        val name = "${vpnPlanName(p.optString("name"))} · ${vpnDevices(p.optInt("devices"))}"
        if (!enough) {
            val have = if (balance >= 0) "На балансе ${vpnRub(balance)}." else ""
            confirmSheet("Не хватает баланса", "Нужно ${vpnRub(charge)}. $have Пополните баланс и повторите.", "Пополнить") {
                val need = if (balance >= 0) charge - balance else charge
                askTopup(methods, note, if (need > 0) need else charge)
            }
        } else {
            confirmSheet("Перейти на «$name»?", "С баланса спишется ${vpnRub(charge)}. Срок подписки не изменится.", "Перейти") {
                if (busy) return@confirmSheet
                busy = true
                io.execute {
                    try {
                        VpnApi.upgrade(subId, p.optString("uuid"))
                        ui { busy = false; toast("Тариф изменён"); showVpn() }
                    } catch (e: VpnApi.AuthException) {
                        ui { busy = false; showVpn() }
                    } catch (e: Exception) {
                        ui { busy = false; toast("Ошибка: ${e.message}") }
                    }
                }
            }
        }
    }
}

// ---------- устройства ----------

/** «2026-11-22T14:08:52…» → «22.11 14:08». */
private fun vpnDateTime(s: String): String {
    if (s.length < 16) return s
    val d = s.take(10).split("-")
    return if (d.size == 3) "${d[2]}.${d[1]} ${s.substring(11, 16)}" else s
}

/** Блок «Мои устройства» внутри карточки подписки. Возвращает переключатель: показать/спрятать список. */
private fun MainActivity.vpnDevicesBlock(card: LinearLayout, subId: String): () -> Unit {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = lp(0).apply { topMargin = dp(10) }
    }
    var loaded = false

    fun load() {
        box.removeAllViews()
        box.addView(txt("Загружаем устройства…", 13f, MUTE))
        io.execute {
            try {
                val devs = VpnApi.devices(subId)
                val blocks = try { VpnApi.deviceBlocks(subId) } catch (e: VpnApi.AuthException) { throw e } catch (e: Exception) { JSONArray() }
                ui { paintDevices(box, subId, devs, blocks) { load() } }
            } catch (e: VpnApi.AuthException) {
                ui { showVpn() }
            } catch (e: Exception) {
                ui { box.removeAllViews(); box.addView(txt("Не удалось загрузить: ${e.message}", 13f, MUTE)) }
            }
        }
    }
    card.addView(box)
    return {
        if (box.visibility == View.VISIBLE) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            if (!loaded) { loaded = true; load() }
        }
    }
}

private fun MainActivity.paintDevices(box: LinearLayout, subId: String, devs: JSONArray, blocks: JSONArray, reload: () -> Unit) {
    box.removeAllViews()
    box.addView(txt("Устройства: ${devs.length()}", 14f, TEXT, true).apply { layoutParams = lp(6) })
    if (devs.length() == 0) box.addView(txt("Пока ни одно устройство не подключено", 13f, MUTE))
    for (i in 0 until devs.length()) {
        val d = devs.optJSONObject(i) ?: continue
        val name = d.optString("name").ifEmpty { d.optString("model") }.ifEmpty { "Устройство" }
        val sub = listOf(d.optString("os"), d.optString("ip"), "был ${vpnDateTime(d.optString("last_seen"))}")
            .filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
            addView(txt(name, 14f, TEXT, true))
            addView(txt(sub, 12f, MUTE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(6)
            addView(left)
            addView(chip("Отключить", false) {
                confirmSheet(
                    "Отключить «$name»?",
                    "Устройство потеряет доступ. Вернуть его можно в списке отключённых.",
                    "Отключить", danger = true
                ) {
                    io.execute {
                        try {
                            VpnApi.disconnectDevice(subId, d.optLong("id"))
                            ui { toast("Устройство отключено"); reload() }
                        } catch (e: VpnApi.AuthException) {
                            ui { showVpn() }
                        } catch (e: Exception) {
                            ui { toast("Ошибка: ${e.message}") }
                        }
                    }
                }
            })
        }
        box.addView(row)
    }
    if (blocks.length() > 0) {
        box.addView(txt("Отключённые", 13f, MUTE).apply { layoutParams = lp(6).apply { topMargin = dp(8) } })
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val hwid = b.optString("hwid")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(6)
                addView(LinearLayout(this@paintDevices).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
                    addView(txt(b.optString("name").ifEmpty { "Устройство" }, 14f, TEXT))
                    addView(txt("отключено ${vpnDateTime(b.optString("blocked_at"))}", 12f, MUTE))
                })
                addView(chip("Вернуть", false) {
                    io.execute {
                        try {
                            VpnApi.unblockDevice(subId, hwid)
                            ui { toast("Устройство возвращено"); reload() }
                        } catch (e: VpnApi.AuthException) {
                            ui { showVpn() }
                        } catch (e: Exception) {
                            ui { toast("Ошибка: ${e.message}") }
                        }
                    }
                })
            }
            box.addView(row)
        }
    }
}

/** Добавляет подписку в установленный VPN-клиент по его ссылке-схеме. Если клиента нет — просим скопировать ссылку. */
private fun MainActivity.openInVpnClient(url: String) {
    val apps = listOf(
        Pair("Happ", "happ://add/$url"),
        Pair("v2rayTun", "v2raytun://import/$url"),
        Pair("Hiddify", "hiddify://import/$url")
    )
    listSheet("Открыть в приложении", apps.map { it.first }) { i ->
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apps[i].second)))
        } catch (e: Exception) {
            toast("${apps[i].first} не установлен. Скопируйте ссылку и добавьте подписку вручную.")
        }
    }
}

// ---------- оплата ----------

private fun MainActivity.payVpn(planUuid: String, provider: String, info: TextView) =
    payVpnWith(info) { VpnApi.intent(planUuid, provider) }

/** Создаёт платёж (покупка или продление) и открывает страницу оплаты. */
private fun MainActivity.payVpnWith(info: TextView, create: () -> JSONObject) {
    if (busy) return
    busy = true
    info.text = "Создаём платёж…"
    io.execute {
        try {
            val r = create()
            val id = r.optString("payment_id")
            val url = r.optString("invoice_url")
            if (id.isNotEmpty()) Store.vpnPay = id
            ui {
                busy = false
                if (!url.startsWith("https://")) {
                    info.text = "Сайт не вернул ссылку на оплату"
                    return@ui
                }
                info.text = "Платёж создан. Переходим к оплате…"
                try {
                    vpnReturn = true
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    vpnReturn = false
                    info.text = "Откройте ссылку вручную: $url"
                }
            }
        } catch (e: VpnApi.AuthException) {
            ui { busy = false; showVpn() }
        } catch (e: Exception) {
            ui { busy = false; info.text = "Ошибка: ${e.message}" }
        }
    }
}

/** Раз в 4 секунды спрашивает статус платежа (до 4 минут). Оплачен и выдан — открываем экран заново: ключ уже в «Мои подписки». */
private fun MainActivity.pollVpnPayment(sid: Int, id: String, tries: Int, gen: Int, info: TextView) {
    if (sid != screenId || gen != vpnPollGen) return
    io.execute {
        var res = "wait"
        try {
            val j = VpnApi.paymentStatus(id)
            val st = j.optString("status")
            res = when {
                j.optString("fulfillment") == "done" || statusKind(st) == 2 -> "done"
                statusKind(st) == 3 -> "failed"
                else -> "wait"
            }
        } catch (e: VpnApi.AuthException) {
            res = "auth"
        } catch (e: Exception) {
            res = "wait"
        }
        ui {
            if (sid != screenId || gen != vpnPollGen) return@ui
            when (res) {
                "done" -> { Store.vpnPay = ""; toast("Оплата прошла — подписка готова"); showVpn() }
                "failed" -> { Store.vpnPay = ""; toast("Платёж отменён или истёк"); showVpn() }
                "auth" -> showVpn()
                else -> if (tries >= 60) {
                    info.text = "Оплата пока не подтвердилась. Нажмите «Проверить сейчас» после оплаты."
                } else {
                    info.text = "⏳ Ждём подтверждение оплаты…"
                    main.postDelayed({ pollVpnPayment(sid, id, tries + 1, gen, info) }, 4000L)
                }
            }
        }
    }
}
