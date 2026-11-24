package ru.pharmshop.app

import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// Админ-панель: вход, список чатов, чат с пользователем.

// ---------- админ-панель (вход: долгое нажатие на логотип) ----------

internal fun MainActivity.showAdminEntry() {
    if (Store.adminToken.isNotEmpty()) showAdminThreads() else showAdminLogin()
}

internal fun MainActivity.showAdminLogin() {
    onHome = false
    val col = screen()
    val sid = screenId
    backRow(col, "Админ-панель")
    col.addView(tv("Введите код доступа.", 14f, MUTE))
    val code = input("Код", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    val info = tv("", 13f, RED)
    var working = false
    col.addView(code)
    col.addView(btn("Войти", true) {
        if (working) return@btn
        val c = code.text.toString().trim()
        if (c.isEmpty()) return@btn
        working = true
        info.text = ""
        io.execute {
            try {
                Store.adminToken = ChatApi.adminLogin(c)
                if (Store.adminToken.isEmpty()) throw Exception("Не удалось сохранить сессию на этом устройстве")
                Push.registerAdmin()
                ui { working = false; if (sid == screenId) showAdminThreads() }
            } catch (e: Exception) {
                ui { working = false; if (sid == screenId) info.text = errText(e) }
            }
        }
    })
    col.addView(info)
}

internal fun MainActivity.threadStatusName(s: String) = when (s) { "work" -> "В работе"; "done" -> "Решён"; else -> "Новый" }
internal fun MainActivity.threadStatusColor(s: String) = when (s) { "work" -> AMBER; "done" -> GREEN; else -> BLUE }

/** Строка быстрых разделов админки над списком чатов. */
internal fun MainActivity.adminToolsRow(): HorizontalScrollView {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(chip("📢 Объявление", false) { showAdminAnnouncement() })
    row.addView(chip("🛠 Тех. работы чата", false) { showAdminChatMaint() })
    row.addView(chip("🧩 Разделы", false) { showAdminSections() })
    row.addView(chip("⬆ Обновление", false) { showAdminForceUpdate() })
    row.addView(chip("🧾 Заказы", false) { showAdminOrders() })
    row.addView(chip("👥 Пользователи", false) { showAdminUsers() })
    row.addView(chip("💬 Личные чаты", false) { showAdminDmThreads() })
    row.addView(chip("🌐 Общий чат", false) { showAdminRoom() })
    row.addView(chip("⭐ Оценки", false) { showAdminRatings() })
    row.addView(chip("⛔ Блокировки", false) { showAdminBlocks() })
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(12)
        addView(row)
    }
}

internal fun MainActivity.showAdminThreads() {
    onHome = false
    val col = screen()
    val sid = screenId
    backRow(col, "Чаты поддержки")
    col.addView(adminToolsRow())

    var fStatus = ""        // фильтр по статусу: "", new, work, done
    var fUnread = false     // только непрочитанные
    var query = ""          // поиск по нику или номеру заказа
    var gen = 0             // номер запроса: устаревшие ответы и циклы обновления отбрасываются
    var counts = JSONObject()
    var reload: () -> Unit = {}
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    val search = input("Поиск: ник или номер заказа", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
        setSingleLine()
        layoutParams = lp(10)
    }
    val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val filtersScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(12)
        addView(filters)
    }
    val status = tv("Загружаем…", 13f, MUTE)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(search)
    col.addView(filtersScroll)
    col.addView(status)
    col.addView(list)
    col.addView(btn("Выйти из админ-панели") {
        val old = Store.adminToken
        Store.adminToken = ""
        if (old.isNotEmpty()) io.execute { Push.unregisterAdmin(old); ChatApi.adminLogout(old) }
        navBack = true
        showHome()
    })

    fun renderFilters() {
        filters.removeAllViews()
        fun n(k: String) = counts.optInt(k)
        val all = n("new") + n("work") + n("done")
        filters.addView(chip("Все ($all)", fStatus.isEmpty() && !fUnread) {
            fStatus = ""; fUnread = false; renderFilters(); reload()
        })
        for ((key, label) in listOf("new" to "Новые", "work" to "В работе", "done" to "Решённые")) {
            filters.addView(chip("$label (${n(key)})", fStatus == key) {
                fStatus = if (fStatus == key) "" else key
                renderFilters(); reload()
            })
        }
        filters.addView(chip("Непрочитанные (${n("unread")})", fUnread) {
            fUnread = !fUnread; renderFilters(); reload()
        })
    }

    fun render(r: JSONObject) {
        counts = r.optJSONObject("counts") ?: JSONObject()
        renderFilters()
        val arr = r.optJSONArray("threads") ?: JSONArray()
        list.removeAllViews()
        status.text = when {
            arr.length() > 0 -> ""
            query.isNotEmpty() || fStatus.isNotEmpty() || fUnread -> "Ничего не найдено"
            else -> "Пока нет обращений"
        }
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val unread = t.optInt("unread")
            val st = t.optString("status").ifEmpty { "new" }
            val name = t.optString("nick").let { if (it.isEmpty()) "Гость" else "@$it" }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(CARD, 18, if (unread > 0) BLUE else LINE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = lp(10)
                isClickable = true
                setOnClickListener { showAdminChat(t) }
                pressable(this)
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(txt(name, 16f, TEXT, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val stars = t.optInt("rating")   // последняя оценка пользователя (0 — не оценивал)
            if (stars in 1..5) {
                top.addView(txt("★ $stars", 12f, if (stars <= 2) RED else AMBER, true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                })
            }
            top.addView(txt(threadStatusName(st), 11f, threadStatusColor(st), true).apply {
                background = shape(mix(threadStatusColor(st), 0.16f), 8, threadStatusColor(st))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(if (unread > 0) 8 else 0) }
            })
            if (unread > 0) {
                top.addView(txt("$unread", 12f, Color.BLACK, true).apply {
                    background = shape(BLUE, 10)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                })
            }
            card.addView(top)
            card.addView(txt(t.optString("last_text").ifEmpty { "…" }, 14f, MUTE).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            val order = t.optString("last_order")
            val ip = t.optString("last_ip")
            val meta = timeFmt.format(java.util.Date(t.optLong("last_ts"))) +
                (if (order.isNotEmpty()) "  •  заказ $order" else "") +
                (if (ip.isNotEmpty()) "  •  IP $ip" else "")
            card.addView(txt(meta, 11f, MUTE))
            val shared = t.optInt("shared_ip")
            if (shared > 1) card.addView(txt("⚠ С этого IP писали разные устройства: $shared", 11f, AMBER))
            if (t.optBoolean("blocked_device") || t.optBoolean("blocked_ip")) {
                val what = when {
                    t.optBoolean("blocked_device") && t.optBoolean("blocked_ip") -> "устройство и IP"
                    t.optBoolean("blocked_device") -> "устройство"
                    else -> "IP"
                }
                card.addView(txt("⛔ Заблокирован: $what", 11f, RED))
            }
            list.addView(card)
        }
    }

    fun load() {
        if (sid != screenId) return
        val my = ++gen
        val st = fStatus
        val un = fUnread
        val q = query
        io.execute {
            try {
                val r = ChatApi.adminThreads(Store.adminToken, st, un, q)
                ui { if (sid == screenId && my == gen) render(r) }
            } catch (e: ChatAuthException) {
                Store.adminToken = ""
                ui { if (sid == screenId) showAdminLogin() }
                return@execute
            } catch (e: Exception) {
                ui { if (sid == screenId && my == gen) status.text = errText(e) }
            }
            main.postDelayed({ if (my == gen) load() }, 8000L)
        }
    }
    reload = { load() }

    // поиск: ждём 0,4 с после последней буквы
    val searchRun = Runnable { query = search.text.toString().trim(); load() }
    search.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            main.removeCallbacks(searchRun)
            main.postDelayed(searchRun, 400L)
        }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    renderFilters()
    load()
}

/** Блокировка или разблокировка устройства/IP. done получает новое состояние. */
internal fun MainActivity.toggleBlock(kind: String, value: String, currently: Boolean, done: (Boolean) -> Unit) {
    fun doBlock(block: Boolean) {
        io.execute {
            try {
                if (block) ChatApi.adminBlock(Store.adminToken, kind, value) else ChatApi.adminUnblock(Store.adminToken, kind, value)
                ui { done(block); toast(if (block) "Заблокировано" else "Разблокировано") }
            } catch (e: Exception) {
                ui { toast(errText(e)) }
            }
        }
    }
    if (currently) { doBlock(false); return }
    val what = if (kind == "ip") "IP $value" else "это устройство"
    val warn = if (kind == "ip") "\n\nОсторожно: с одного IP (например, мобильный интернет) могут сидеть разные люди." else ""
    confirmSheet(
        "Заблокировать?",
        "Пользователь с $what больше не сможет писать в поддержку.$warn",
        "Заблокировать", danger = true
    ) { doBlock(true) }
}

internal fun MainActivity.showAdminChat(t: JSONObject) {
    onHome = false
    val root = chatScreen()
    val sid = screenId
    backAction = { showAdminThreads() }
    val device = t.optString("device")
    val title = t.optString("nick").let { if (it.isEmpty()) "Гость" else "@$it" }
    backRow(root, title) { showAdminThreads() }
    val order = t.optString("last_order")
    if (order.isNotEmpty()) root.addView(tv("Последний заказ: $order", 12f, MUTE))
    val lastIp = t.optString("last_ip")
    val lastDev = t.optString("last_dev")
    if (lastIp.isNotEmpty() || lastDev.isNotEmpty()) {
        root.addView(tv("IP: ${lastIp.ifEmpty { "—" }}\n${lastDev.ifEmpty { "устройство неизвестно" }}", 12f, MUTE).apply {
            setTextIsSelectable(true)
        })
    }

    // данные о пользователе: диагностика и аналитика, которые приложение присылает вместе с обращением
    var profile: JSONObject? = null
    val deviceLine = tv("", 12f, MUTE).apply { visibility = View.GONE }
    root.addView(deviceLine)
    io.execute {
        try {
            val p = ChatApi.adminProfile(Store.adminToken, device)
            ui {
                if (sid != screenId) return@ui
                profile = p
                val d = p.optJSONObject("diag")
                if (d != null) {
                    deviceLine.text = "📱 ${d.optString("phone")} · Android ${d.optString("android")} · ${d.optString("app")}"
                    deviceLine.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            // не критично: чат работает и без этого блока
        }
    }

    // статус чата и блокировка
    var curStatus = t.optString("status").ifEmpty { "new" }
    var blockedDevice = t.optBoolean("blocked_device")
    var blockedIp = t.optBoolean("blocked_ip")
    val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    fun renderTools() {
        tools.removeAllViews()
        for (st in listOf("new", "work", "done")) {
            tools.addView(chip(threadStatusName(st), curStatus == st) {
                if (curStatus != st) {
                    val prev = curStatus
                    curStatus = st
                    renderTools()
                    io.execute {
                        try {
                            ChatApi.adminSetStatus(Store.adminToken, device, st)
                        } catch (e: Exception) {
                            ui { curStatus = prev; renderTools(); toast(errText(e)) }
                        }
                    }
                }
            })
        }
        tools.addView(chip("📊 Данные", false) { showUserData(profile, title) })
        tools.addView(chipTint(if (blockedDevice) "Разблокировать устройство" else "⛔ Устройство", RED) {
            toggleBlock("device", device, blockedDevice) { blockedDevice = it; renderTools() }
        })
        if (lastIp.isNotEmpty() && lastIp != "unknown") {
            tools.addView(chipTint(if (blockedIp) "Разблокировать IP" else "⛔ IP", RED) {
                toggleBlock("ip", lastIp, blockedIp) { blockedIp = it; renderTools() }
            })
        }
    }
    renderTools()
    root.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(8)
        addView(tools)
    })

    // подозрительные совпадения: другие устройства с тех же IP
    val related = tv("", 12f, AMBER).apply { visibility = View.GONE }
    root.addView(related)
    io.execute {
        try {
            val arr = ChatApi.adminRelated(Store.adminToken, device)
            ui {
                if (sid == screenId && arr.length() > 0) {
                    val names = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        val n = arr.getJSONObject(i).optString("nick")
                        names.add(if (n.isEmpty()) "гость" else "@$n")
                    }
                    related.text = "⚠ С этих же IP писали другие устройства: ${names.joinToString(", ")}"
                    related.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            // не критично
        }
    }

    root.addView(chatPane(
        sid, "admin", "Сообщений пока нет.",
        { after -> ChatApi.adminMessages(Store.adminToken, device, after) },
        { text, image -> ChatApi.adminReply(Store.adminToken, device, text, image) },
        { id -> ChatApi.adminImage(Store.adminToken, device, id) },
        showMeta = true,
        readTs = { ChatApi.lastReadTs }
    ))
}

/** Шторка «Данные пользователя»: диагностика и аналитика из приложения + то, что видит сервер. */
internal fun MainActivity.showUserData(p: JSONObject?, title: String) {
    sheet("Данные: $title", scroll = true) { box, close ->
        if (p == null) {
            box.addView(txt("Данные ещё загружаются или недоступны. Попробуйте через секунду.", 14f, MUTE).apply { layoutParams = lp(14) })
        } else {
            val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            val d = p.optJSONObject("diag")
            if (d == null) {
                box.addView(txt(
                    "Приложение пользователя ещё не присылало диагностику. Блок приходит вместе с новым сообщением в поддержку (нужна версия 1.0.6 и новее).",
                    14f, MUTE
                ).apply { layoutParams = lp(12) })
            } else {
                val got = p.optLong("diag_ts")
                box.addView(txt("Получено: ${if (got > 0) fmt.format(java.util.Date(got)) else "—"}", 12f, MUTE).apply { layoutParams = lp(8) })
                box.addView(txt(Diag.render(d), 13f, TEXT).apply {
                    setTextIsSelectable(true)
                    background = shape(CARD, 12, LINE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = lp(12)
                })
            }
            // то, что видит сервер сам
            val ip = p.optString("last_ip")
            val geo = p.optString("geo")
            val first = p.optLong("first_message")
            val server = buildString {
                append("Сообщений от пользователя: ${p.optInt("messages")}\n")
                if (first > 0) append("Первое обращение: ${fmt.format(java.util.Date(first))}\n")
                if (ip.isNotEmpty()) append("IP: $ip${if (geo.isNotEmpty()) " ($geo)" else ""}")
            }.trimEnd()
            box.addView(txt("С сервера", 12f, TEXT, true).apply { layoutParams = lp(4) })
            box.addView(txt(server, 13f, MUTE).apply { setTextIsSelectable(true); layoutParams = lp(14) })
        }
        box.addView(btnGrid(listOf(gbtn("Закрыть", true) { close() }), 0))
    }
}


/** Оценки поддержки: средняя, сколько раз поставили каждое число звёзд и последние отзывы. */
internal fun MainActivity.showAdminRatings() {
    val sid = screenId
    sheet("Оценки поддержки", scroll = true) { box, close ->
        val info = txt("Загружаем…", 14f, MUTE).apply { layoutParams = lp(10) }
        box.addView(info)
        val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        io.execute {
            try {
                val r = ChatApi.adminRatings(Store.adminToken)
                ui {
                    val stats = r.optJSONObject("stats")
                    val n = stats?.optInt("n") ?: 0
                    if (n == 0) { info.text = "Оценок пока нет. Пользователи ставят их, когда обращение отмечено «Решено»."; return@ui }
                    info.text = "Средняя: ${stats?.optDouble("avg")} из 5 · всего оценок: $n"
                    info.setTextColor(TEXT)
                    val dist = stats?.optJSONArray("dist")
                    for (k in 5 downTo 1) {
                        val c = dist?.optInt(k - 1) ?: 0
                        box.addView(txt("${"★".repeat(k)}${"☆".repeat(5 - k)}   $c", 14f, if (k <= 2) RED else AMBER))
                    }
                    val arr = r.optJSONArray("ratings") ?: JSONArray()
                    box.addView(sectionTitle("Последние отзывы", 14))
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val who = o.optString("nick").let { if (it.isEmpty()) "Гость" else "@$it" }
                        val st = o.optInt("stars")
                        box.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            background = shape(CARD, 14, LINE)
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                            layoutParams = lp(8)
                            addView(txt("$who   ${"★".repeat(st)}${"☆".repeat(5 - st)}", 14f, TEXT, true))
                            val c = o.optString("comment")
                            if (c.isNotEmpty()) addView(txt(c, 13f, TEXT))
                            addView(txt(timeFmt.format(java.util.Date(o.optLong("ts"))), 11f, MUTE))
                        })
                    }
                }
            } catch (e: Exception) {
                ui { info.text = errText(e); if (e is ChatAuthException) { close(); if (sid == screenId) showAdminLogin() } }
            }
        }
    }
}
