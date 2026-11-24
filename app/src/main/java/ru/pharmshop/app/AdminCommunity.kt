package ru.pharmshop.app

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// Админ-панель: пользователи чата («галочка» = модератор), личные переписки, общий чат.

/** Ошибка запроса админа: просрочена сессия — на экран входа, иначе текст ошибки в [status]. */
private fun MainActivity.adminFail(e: Exception, sid: Int, status: TextView? = null) {
    if (e is ChatAuthException) {
        Store.adminToken = ""
        ui { if (sid == screenId) showAdminLogin() }
    } else {
        ui {
            if (sid == screenId) { if (status != null) status.text = errText(e) else toast(errText(e)) }
        }
    }
}

private fun MainActivity.searchBox(hintText: String, onQuery: (String) -> Unit): android.widget.EditText {
    val box = input(hintText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
        setSingleLine()
        layoutParams = lp(10)
    }
    val run = Runnable { onQuery(box.text.toString().trim()) }
    box.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            main.removeCallbacks(run)
            main.postDelayed(run, 400L)
        }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    return box
}

// ---------- пользователи ----------

internal fun MainActivity.showAdminUsers() {
    onHome = false
    val col = screen()
    val sid = screenId
    backAction = { showAdminThreads() }
    backRow(col, "Пользователи") { showAdminThreads() }

    val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    var query = ""
    var offset = 0
    var gen = 0
    var load: (Boolean) -> Unit = {}

    val summary = tv("Загружаем…", 13f, MUTE)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val moreRow = LinearLayout(this).apply { visibility = android.view.View.GONE }
    moreRow.addView(chip("Показать ещё", false) { load(false) })

    col.addView(tv("Галочка даёт право модерировать общий чат: удалять сообщения и ограничивать отправку.", 12f, MUTE))
    col.addView(searchBox("Поиск по нику") { q -> query = q; load(true) })
    col.addView(summary)
    col.addView(list)
    col.addView(moreRow)

    fun mutedText(until: Long) =
        if (until >= 4_000_000_000_000L) "навсегда" else "до " + timeFmt.format(java.util.Date(until))

    /** Заполняет карточку пользователя; действия меняют данные на месте, без перезагрузки списка. */
    fun fill(card: LinearLayout, u: JSONObject) {
        card.removeAllViews()
        val uid = u.optLong("id")
        val nick = u.optString("nick")
        val badge = u.optInt("badge") == 1
        val muted = u.optLong("muted_until")

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(nameRow(nick, badge, 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (badge) top.addView(txt("модератор", 11f, BLUE, true).apply {
            background = shape(mix(BLUE, 0.16f), 8, BLUE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        card.addView(top)
        val seen = u.optLong("last_seen")
        card.addView(txt(
            "С ${dateFmt.format(java.util.Date(u.optLong("created")))}  •  был ${if (seen > 0) timeFmt.format(java.util.Date(seen)) else "—"}",
            11f, MUTE
        ))
        val ip = u.optString("last_ip")
        card.addView(txt(
            "Сообщений: общий чат ${u.optInt("room_msgs")}, личные ${u.optInt("dm_msgs")}" + if (ip.isNotEmpty()) "  •  IP $ip" else "",
            11f, MUTE
        ))
        if (muted > 0) card.addView(txt("🔇 Отправка в общий чат закрыта ${mutedText(muted)}", 11f, AMBER))
        if (u.optBoolean("blocked_device")) card.addView(txt("⛔ Устройство заблокировано", 11f, RED))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(chipTint(if (badge) "Снять галочку" else "✓ Выдать галочку", BLUE) {
            io.execute {
                try {
                    ChatApi.adminUserBadge(Store.adminToken, uid, !badge)
                    ui {
                        u.put("badge", if (badge) 0 else 1)
                        fill(card, u)
                        toast(if (badge) "Галочка снята" else "Галочка выдана")
                    }
                } catch (e: Exception) { adminFail(e, sid) }
            }
        })
        fun setMute(minutes: Int, done: String) {
            io.execute {
                try {
                    ChatApi.adminUserMute(Store.adminToken, uid, minutes)
                    ui {
                        u.put("muted_until", when {
                            minutes == 0 -> 0L
                            minutes < 0 -> 4_102_444_800_000L
                            else -> System.currentTimeMillis() + minutes * 60_000L
                        })
                        fill(card, u)
                        toast(done)
                    }
                } catch (e: Exception) { adminFail(e, sid) }
            }
        }
        if (muted > 0) {
            tools.addView(chip("🔊 Снять заглушку", false) { setMute(0, "Ограничение снято") })
        } else {
            tools.addView(chip("🔇 Заглушить", false) {
                listSheet("Заглушить $nick", listOf("На час", "На сутки", "На неделю", "Навсегда")) { i ->
                    when (i) {
                        0 -> setMute(60, "Заглушён на час")
                        1 -> setMute(1440, "Заглушён на сутки")
                        2 -> setMute(10080, "Заглушён на неделю")
                        else -> setMute(-1, "Заглушён навсегда")
                    }
                }
            })
        }
        tools.addView(chip("💬 Личные", false) { showAdminDmThreads(nick) })
        tools.addView(chipTint("Освободить ник", RED) {
            confirmSheet(
                "Освободить ник?",
                "Пользователь $nick будет удалён из чата вместе с сообщениями в общем чате, ник снова станет свободным. " +
                    "Его личные переписки останутся в админ-панели.",
                "Освободить", danger = true
            ) {
                io.execute {
                    try {
                        ChatApi.adminUserReset(Store.adminToken, uid)
                        ui { (card.parent as? ViewGroup)?.removeView(card); toast("Ник освобождён") }
                    } catch (e: Exception) { adminFail(e, sid) }
                }
            }
        })
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            addView(tools)
        })
    }

    load = { fresh ->
        if (sid == screenId) {
            if (fresh) offset = 0
            val my = ++gen
            val q = query
            val off = offset
            io.execute {
                try {
                    val r = ChatApi.adminUsers(Store.adminToken, q, off)
                    ui {
                        if (sid != screenId || my != gen) return@ui
                        if (fresh) list.removeAllViews()
                        val arr = r.optJSONArray("users") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                background = shape(CARD, 18, LINE)
                                setPadding(dp(16), dp(14), dp(16), dp(14))
                                layoutParams = lp(10)
                            }
                            fill(card, arr.getJSONObject(i))
                            list.addView(card)
                        }
                        offset = off + arr.length()
                        moreRow.visibility = if (r.optBoolean("more")) android.view.View.VISIBLE else android.view.View.GONE
                        summary.text = if (list.childCount == 0) {
                            if (q.isEmpty()) "Пока никто не выбрал ник" else "Ничего не найдено"
                        } else "Всего: ${r.optInt("total")}  •  с галочкой: ${r.optInt("badges")}"
                    }
                } catch (e: Exception) { adminFail(e, sid, summary) }
            }
        }
    }
    load(true)
}

// ---------- личные переписки (видны только администрации) ----------

internal fun MainActivity.showAdminDmThreads(initialQuery: String = "") {
    onHome = false
    val col = screen()
    val sid = screenId
    backAction = { showAdminThreads() }
    backRow(col, "Личные чаты") { showAdminThreads() }
    col.addView(tv("Все личные переписки пользователей. Видны только администрации.", 12f, MUTE))

    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    var query = initialQuery
    var gen = 0
    val status = tv("Загружаем…", 13f, MUTE)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun load() {
        if (sid != screenId) return
        val my = ++gen
        val q = query
        io.execute {
            try {
                val r = ChatApi.adminDmThreads(Store.adminToken, q)
                ui {
                    if (sid != screenId || my != gen) return@ui
                    val arr = r.optJSONArray("chats") ?: JSONArray()
                    status.text = if (arr.length() == 0) (if (q.isEmpty()) "Личных переписок пока нет" else "Ничего не найдено") else ""
                    list.removeAllViews()
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        val na = c.optString("nick_a")
                        val nb = c.optString("nick_b")
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            background = ripple(CARD, 18, LINE)
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            layoutParams = lp(10)
                            isClickable = true
                            setOnClickListener { showAdminDm(c.optLong("a"), c.optLong("b"), na, nb) }
                            pressable(this)
                        }
                        card.addView(txt("$na  ⇄  $nb", 16f, TEXT, true))
                        card.addView(txt(c.optString("text"), 14f, MUTE).apply {
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        card.addView(txt("${timeFmt.format(java.util.Date(c.optLong("ts")))}  •  сообщений: ${c.optInt("n")}", 11f, MUTE))
                        list.addView(card)
                    }
                }
            } catch (e: Exception) { adminFail(e, sid, status) }
        }
    }

    val box = searchBox("Поиск по нику") { q -> query = q; load() }
    box.setText(initialQuery)
    col.addView(box)
    col.addView(status)
    col.addView(list)
    load()
}

internal fun MainActivity.showAdminDm(a: Long, b: Long, nickA: String, nickB: String) {
    onHome = false
    val root = chatScreen()
    val sid = screenId
    backAction = { showAdminDmThreads() }
    backRow(root, "Личный чат") { showAdminDmThreads() }
    root.addView(tv("$nickA  ⇄  $nickB", 13f, MUTE))
    root.addView(msgPane(
        sid, a, "Сообщений нет.", showNames = true, namesOnOwn = true,
        fetch = { after, _ -> ChatApi.adminDmMessages(Store.adminToken, a, b, after) },
        send = null
    ))
}

// ---------- общий чат: просмотр и удаление ----------

internal fun MainActivity.showAdminRoom() {
    onHome = false
    val root = chatScreen()
    val sid = screenId
    backAction = { showAdminThreads() }
    backRow(root, "Общий чат") { showAdminThreads() }
    root.addView(tv("Чтобы удалить сообщение, нажмите на него и удерживайте.", 12f, MUTE))
    root.addView(msgPane(
        sid, 0L, "Сообщений нет.", showNames = true,
        fetch = { after, rev -> ChatApi.adminRoomMessages(Store.adminToken, after, rev) },
        send = null,
        onLong = { m, _ ->
            confirmSheet("Удалить сообщение?", "${m.optString("nick")}: ${m.optString("text").take(120)}", "Удалить", danger = true) {
                io.execute {
                    try {
                        ChatApi.adminRoomDelete(Store.adminToken, m.optLong("id"))
                        ui { toast("Удалено") }
                    } catch (e: Exception) { adminFail(e, sid) }
                }
            }
        }
    ))
}
