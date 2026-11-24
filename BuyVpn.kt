package ru.pharmshop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

// Раздел «VPN»: вход через Telegram, покупка подписки и выдача ключей.
// Устроен как экраны звёзд и Premium: форма, панель оплаты, оплата на странице платёжки.
// Сайт: pvpn.vitrinka.net (запросы — в VpnApi). Подписки всегда берём с сервера, в «Мои заказы» они не попадают.

private const val V_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Номер проверки платежа: новая проверка гасит старую, чтобы не крутились две сразу. */
private var vpnPollGen = 0

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
    val col = screen(bottomBar = true)
    val sid = screenId
    backRow(col, "VPN")
    if (!VpnApi.loggedIn()) {
        buildVpnLogin(col, sid)
        riseIn(col)
        return
    }
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    io.execute {
        try {
            val subs = VpnApi.subscriptions()
            val plans = VpnApi.plans()
            val prov = VpnApi.providers()
            ui { if (sid == screenId) { status.visibility = View.GONE; buildVpnForm(col, sid, subs, plans, prov) } }
        } catch (e: VpnApi.AuthException) {
            ui { if (sid == screenId) showVpn() }   // сессия кончилась — откроется экран входа
        } catch (e: Exception) {
            ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
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

private fun MainActivity.buildVpnForm(col: LinearLayout, sid: Int, subs: JSONArray, plans: JSONArray, prov: JSONObject) {
    val firstNew = col.childCount

    // платёж создан, но ещё не подтверждён
    val pending = Store.vpnPay
    val waitInfo = txt("⏳ Ждём подтверждение оплаты…", 14f, TEXT)
    if (pending.isNotEmpty()) {
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(0).apply { topMargin = dp(10) }
        }
        actions.addView(chip("Проверить сейчас", true) {
            vpnPollGen++
            waitInfo.text = "⏳ Проверяем оплату…"
            pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo)
        })
        actions.addView(chip("Не жду", false) { Store.vpnPay = ""; vpnPollGen++; showVpn() })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(mix(BLUE, 0.14f), 18, BLUE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(12)
            addView(waitInfo)
            addView(actions)
        })
    }

    // мои подписки с ключами
    if (subs.length() > 0) {
        col.addView(sectionLabel("Мои подписки", pending.isEmpty()))
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            col.addView(vpnSubCard(s))
        }
    }

    // тарифы (бессрочные не берём): по сроку, потом по цене
    val list = ArrayList<JSONObject>()
    for (i in 0 until plans.length()) {
        val p = plans.optJSONObject(i) ?: continue
        if (!p.optBoolean("is_lifetime")) list.add(p)
    }
    list.sortWith(compareBy<JSONObject>({ it.optInt("days") }, { it.optLong("total_price_display") }))

    val methods = ArrayList<Pair<String, String>>()
    prov.optJSONArray("providers")?.let { arr ->
        for (i in 0 until arr.length()) {
            val id = arr.optString(i)
            if (id.isNotEmpty()) methods.add(Pair(id, vpnProviderName(id)))
        }
    }
    if (list.isEmpty() || methods.isEmpty()) {
        col.addView(tv("Покупка VPN сейчас недоступна", 14f, MUTE))
        riseIn(col, firstNew)
        if (pending.isNotEmpty()) { vpnPollGen++; pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo) }
        return
    }

    var plan: JSONObject = list.firstOrNull { it.optBoolean("is_popular") } ?: list[0]
    var method = methods[0].first
    var quote: JSONObject? = null
    var qTok = 0

    // закреплённая панель: сумма и кнопка
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

    // тарифы: карточки, сгруппированные по сроку
    col.addView(sectionLabel(if (subs.length() > 0) "Купить новую подписку" else "Тариф", subs.length() == 0 && pending.isEmpty()))
    val planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(planBox)
    val rows = ArrayList<Pair<String, LinearLayout>>()
    fun paintRows() {
        for ((id, r) in rows) {
            val sel = id == plan.optString("uuid")
            r.background = ripple(if (sel) mix(BLUE, 0.16f) else CARD, 16, if (sel) BLUE else LINE)
        }
    }
    var lastDays = -1
    for (p in list) {
        val d = p.optInt("days")
        if (d != lastDays) {
            lastDays = d
            planBox.addView(txt(vpnDays(d), 13f, MUTE).apply { layoutParams = lp(6).apply { topMargin = dp(6) } })
        }
        val name = p.optString("name")
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(txt(name.take(1).uppercase() + name.drop(1), 16f, TEXT, true))
            if (p.optBoolean("is_special")) addView(vpnPill("Особый"))
            else if (p.optBoolean("is_popular")) addView(vpnPill("Популярный"))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, V_WRAP, 1f)
            addView(title)
            addView(txt(vpnDevices(p.optInt("devices")), 13f, MUTE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { plan = p; paintRows(); requestQuote() }
            pressable(this)
            addView(left)
            addView(txt(vpnRub(p.optLong("total_price_display")), 16f, TEXT, true))
        }
        planBox.addView(row)
        rows.add(Pair(p.optString("uuid"), row))
    }

    // способ оплаты (комиссия уже учтена в сумме внизу)
    col.addView(sectionLabel("Способ оплаты"))
    val methodBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(methodBox)
    methodPicker(methodBox, methods, method) { id, _ -> method = id; paintTotal() }

    col.addView(txt("Выйти из аккаунта VPN", 13f, MUTE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
        layoutParams = lp(4)
        isClickable = true
        setOnClickListener { VpnApi.logout(); vpnPollGen++; showVpn() }
    })

    attachBar(col, bar.root)
    riseIn(col, firstNew)
    paintRows()
    requestQuote()
    if (pending.isNotEmpty()) { vpnPollGen++; pollVpnPayment(sid, pending, 0, vpnPollGen, waitInfo) }
}

private fun MainActivity.vpnPill(t: String): TextView =
    txt(t, 11f, android.graphics.Color.BLACK, true).apply {
        background = shape(BLUE, 8)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        layoutParams = LinearLayout.LayoutParams(V_WRAP, V_WRAP).apply { leftMargin = dp(8) }
    }

// ---------- карточка подписки с ключом ----------

private fun MainActivity.vpnSubCard(s: JSONObject): LinearLayout {
    val active = s.optBoolean("is_active")
    val url = s.optString("subscription_url")
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, if (active) edge(BLUE) else LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(10)
    }
    val name = s.optString("plan_name").ifEmpty { "Подписка" }
    card.addView(txt("${name.take(1).uppercase() + name.drop(1)} · ${vpnDevices(s.optInt("devices"))}", 16f, TEXT, true))
    val end = vpnDate(s.optString("end_date"))
    val line = when {
        s.optBoolean("is_frozen") -> "Заморожена · до $end"
        active -> "Активна · осталось ${vpnDays(s.optInt("days_left"))} · до $end"
        else -> "Не активна · до $end"
    }
    card.addView(txt(line, 13f, if (active) GREEN else MUTE).apply { layoutParams = lp(10) })
    if (url.startsWith("https://")) {
        card.addView(txt(url, 12f, MUTE).apply {
            setTextIsSelectable(true)
            layoutParams = lp(10)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(0)
        }
        actions.addView(chip("Скопировать ссылку", true) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vpn", url))
            toast("Ссылка подписки скопирована")
        })
        actions.addView(chip("Открыть в клиенте", false) { openInVpnClient(url) })
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })
    }
    return card
}

/** Добавляет подписку в установленный VPN-клиент по его ссылке-схеме. Если клиента нет — просим скопировать ссылку. */
private fun MainActivity.openInVpnClient(url: String) {
    val apps = listOf(
        Pair("Happ", "happ://add/$url"),
        Pair("v2rayTun", "v2raytun://import/$url"),
        Pair("Hiddify", "hiddify://import/$url")
    )
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Открыть в приложении")
        .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apps[i].second)))
            } catch (e: Exception) {
                toast("${apps[i].first} не установлен. Скопируйте ссылку и добавьте подписку вручную.")
            }
        }
        .show()
}

// ---------- оплата ----------

private fun MainActivity.payVpn(planUuid: String, provider: String, info: TextView) {
    if (busy) return
    busy = true
    info.text = "Создаём платёж…"
    io.execute {
        try {
            val r = VpnApi.intent(planUuid, provider)
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
