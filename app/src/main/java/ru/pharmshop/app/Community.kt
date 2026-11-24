package ru.pharmshop.app

import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaException
import com.hcaptcha.sdk.HCaptchaTokenResponse
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// Вкладка «Чат»: общий чат (одна комната) и личные сообщения. Каждый пользователь выбирает уникальный ник.
// Сервер: server/worker.js (таблицы users, room_messages, dm_messages).

/** Правила приватности личных чатов (Настройки → Прочее → «Приватность чатов», регистрация ника, экран переписки). */
internal const val CHAT_PRIVACY_TEXT =
    "3-тие лица не видят ваши личные чаты, они остаются в пределах приложения и администрация может получить к ним доступ."

/** Состояние вкладки «Чат», которое переживает переходы между экранами. */
internal object CommunityState {
    var tab = 0            // 0 — общий чат, 1 — личные
    var pane = 0           // номер текущей ленты: устаревшие ленты перестают опрашивать сервер
    var myUid = 0L
    var roomNextAt = 0L    // когда снова можно писать в общий чат (время телефона, мс): пауза не сбрасывается при смене вкладки
}

/** Пауза между сообщениями в общем чате (сервер проверяет то же самое; у модераторов с «галочкой» паузы нет). */
internal const val ROOM_COOLDOWN_MS = 10_000L

/** Реакции на сообщения общего чата. Список должен совпадать с REACTIONS в server/worker.js. */
internal val REACTIONS = listOf("👍", "❤️", "😂", "🔥", "😮", "😢")

/** «❤» и «❤️» считаем одной реакцией (как и сервер). */
internal fun noVs(s: String): String = s.replace("\uFE0F", "")

/** Что можно сделать с сообщением по долгому нажатию: ответить и поставить реакцию. */
internal class MsgTools(
    val canReply: Boolean,
    val canReact: Boolean,
    val reply: () -> Unit,
    val react: (String) -> Unit,
    val mine: () -> String      // моя реакция на это сообщение ("" — нет)
)

/** Ключ сайта hCaptcha для входа в чат (публичный; секрет хранится только на сервере). */
private const val CHAT_CAPTCHA_SITEKEY = "3d88c4d4-c8f4-4e0a-8db6-21e62e840604"

/** Открывает проверку hCaptcha. Колбэки приходят в основном потоке. */
private fun MainActivity.chatCaptcha(onToken: (String) -> Unit, onFail: () -> Unit) {
    val h = HCaptcha.getClient(this)
    h.addOnSuccessListener { r: HCaptchaTokenResponse -> onToken(r.tokenResult) }
    h.addOnFailureListener { _: HCaptchaException -> onFail() }
    h.setup(HCaptchaConfig.builder().siteKey(CHAT_CAPTCHA_SITEKEY).build())
    h.verifyWithHCaptcha()
}

/**
 * Зелёная корона: если у человека собраны все достижения, а сервер этого не знает (или наоборот) — сообщаем серверу.
 * [serverCrown] — что сервер знает сейчас. Достижения считаются на телефоне, поэтому корона — только украшение.
 */
internal fun MainActivity.syncCrown(serverCrown: Boolean) {
    io.execute {
        try {
            val all = allBadgesGot()
            if (all != serverCrown) ChatApi.crownSet(all)
        } catch (e: Exception) { /* не вышло сейчас — повторится при следующем заходе */ }
    }
}

internal fun MainActivity.showChatPrivacy() {
    sheet("Правила приватности") { box, close ->
        box.addView(txt(CHAT_PRIVACY_TEXT, 14f, TEXT).apply { layoutParams = lp(16) })
        box.addView(btnGrid(listOf(gbtn("Понятно", true) { close() }), 0))
    }
}

/** Цвет ника в ленте: у одного человека всегда один и тот же. */
internal fun MainActivity.nickColor(nick: String): Int {
    val palette = listOf(BLUE, GREEN, AMBER, PINK, VIOLET_L, CYAN, ORANGE)
    return palette[(nick.hashCode() and 0x7fffffff) % palette.size]
}

/** Зелёная корона рядом с ником: собраны все достижения. Нажатие объясняет, что это. */
internal fun MainActivity.crownIcon(size: Float): View {
    val px = dp((size + 3f).toInt())
    return IconView(this, Ic.CROWN, GREEN).apply {
        layoutParams = LinearLayout.LayoutParams(px, px).apply { leftMargin = dp(4) }
        contentDescription = "Зелёная корона: собраны все достижения"
        isClickable = true
        setOnClickListener {
            infoSheet(
                "👑 Зелёная корона",
                "Эту корону получают те, кто собрал все достижения PharmShop: покупки, VPN, защиту входа, оформление профиля, " +
                    "помощь приложению и общение в чате. Значит, перед вами настоящий ветеран.\n\n" +
                    "Хотите такую же? Загляните в Профиль → Достижения: там видно, чего не хватает."
            )
        }
    }
}

/** Ник с зелёной короной (все достижения) и галочкой (модератор), если они есть. */
internal fun MainActivity.nameRow(nick: String, badge: Boolean, size: Float = 12f, crown: Boolean = false): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(txt(nick, size, nickColor(nick), true))
        if (crown) addView(crownIcon(size))
        if (badge) addView(txt(" ✓", size, BLUE, true))
    }

// ---------- вкладка ----------

internal fun MainActivity.showCommunity() {
    onHome = false
    val root = chatScreen(5)
    val sid = screenId
    titleRow(root, "Чат")
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    root.addView(body)
    body.addView(tv("Загрузка…", 14f, MUTE))
    io.execute {
        try {
            val me = ChatApi.chatMe()
            // тех. работы: показываем красивое окно вместо чата
            val maint = try { Remote.refresh(); Remote.chatMaint() } catch (e: Exception) { null }
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                if (maint != null) {
                    maintenanceView(body, maint.optString("reason"))
                    watchChatMaint(sid)
                    return@ui
                }
                if (me.optBoolean("registered")) {
                    syncCrown(me.optInt("crown") == 1)
                    communityMain(body, sid, me)
                } else {
                    registerView(body)
                }
            }
        } catch (e: ChatMaintException) {
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                maintenanceView(body, e.reason)
                watchChatMaint(sid)
            }
        } catch (e: Exception) {
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                body.addView(tv(errText(e), 14f, RED))
                body.addView(LinearLayout(this).apply { addView(chip("Повторить", false) { showCommunity() }) })
            }
        }
    }
}

/** Первый вход: выбор ника и проверка «я не робот» (hCaptcha). Занятый ник сервер не отдаёт. */
private fun MainActivity.registerView(body: LinearLayout) {
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // зачем этот чат
    inner.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(14)
        addView(txt("🤝 Чат создан, чтобы помогать друг другу", 15f, TEXT, true).apply { layoutParams = lp(4) })
        addView(txt(
            "Задавайте вопросы, делитесь опытом и подсказывайте другим пользователям. Будьте вежливы: за спам и оскорбления доступ к чату могут закрыть.",
            13f, MUTE
        ))
    })
    inner.addView(txt("Выберите ник", 18f, TEXT, true).apply { layoutParams = lp(6) })
    inner.addView(txt(
        "Под ним вас увидят в общем чате и личных сообщениях. Ник уникален: двух одинаковых быть не может.",
        14f, MUTE
    ).apply { layoutParams = lp(8) })
    inner.addView(txt(
        "3–20 символов: латиница или кириллица (не вперемешку), цифры и «_». Менять ник можно раз в 7 дней. " +
            "Перед входом нужно пройти короткую проверку, что вы не робот.",
        12f, MUTE
    ).apply { layoutParams = lp(14) })
    val field = input("Ник", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(21))
    }
    val info = txt("", 13f, RED).apply { layoutParams = lp(8) }
    var working = false
    fun fail(sid: Int, msg: String) {
        working = false
        if (sid == screenId) { info.setTextColor(RED); info.text = msg }
    }
    inner.addView(field)
    inner.addView(info)
    inner.addView(btn("Занять ник", true) {
        if (working) return@btn
        val nick = field.text.toString().trim().removePrefix("@")
        if (nick.isEmpty()) return@btn
        working = true
        info.setTextColor(MUTE)
        info.text = "Проверяем ник…"
        val sid = screenId
        io.execute {
            try {
                // сначала ник: чтобы капча не пропадала зря, если он занят или не подходит
                ChatApi.chatRegister(nick, check = true)
            } catch (e: Exception) {
                ui { fail(sid, errText(e)) }
                return@execute
            }
            ui {
                if (sid != screenId) { working = false; return@ui }
                info.text = "Пройдите проверку…"
                chatCaptcha(
                    onToken = { token ->
                        info.text = "Входим в чат…"
                        io.execute {
                            try {
                                ChatApi.chatRegister(nick, captcha = token)
                                ui { working = false; if (sid == screenId) showCommunity() }
                            } catch (e: Exception) {
                                ui { fail(sid, errText(e)) }
                            }
                        }
                    },
                    onFail = { fail(sid, "Проверка не пройдена. Нажмите «Занять ник» и попробуйте ещё раз") }
                )
            }
        }
    })
    body.addView(ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(inner)
    })
}

private fun MainActivity.changeNickSheet(current: String, done: (String) -> Unit) {
    inputSheet(
        "Сменить ник", "Новый ник", current, "Сменить",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    ) { v ->
        val nick = v.trim().removePrefix("@")
        if (nick.isEmpty()) {
            false
        } else {
            io.execute {
                try {
                    val r = ChatApi.chatRegister(nick)
                    ui { done(r.optString("nick", nick)); toast("Ник изменён") }
                } catch (e: Exception) {
                    ui { toast(errText(e)) }
                }
            }
            true
        }
    }
}

private fun MainActivity.communityMain(body: LinearLayout, sid: Int, me: JSONObject) {
    val myUid = me.optLong("uid")
    var myNick = me.optString("nick")
    var myBadge = me.optInt("badge") == 1
    var myCrown = me.optInt("crown") == 1
    CommunityState.myUid = myUid
    var unread = 0
    var renderChats: ((JSONObject) -> Unit)? = null
    var dmStatus: TextView? = null
    var lastChats: JSONObject? = null
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    val openDm: (Long, String, Long) -> Unit = { uid, nick, av -> showDm(uid, nick, av) }

    // шапка: своя аватарка (та же, что в профиле), ник и его смена
    syncMyAvatar()
    val whoNick = txt("", 15f, TEXT, true)
    val whoCrown = crownIcon(15f)
    fun renderWho() {
        whoNick.text = myNick + if (myBadge) "  ✓" else ""
        whoCrown.visibility = if (myCrown) View.VISIBLE else View.GONE
    }
    renderWho()
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = lp(10)
        addView(chatAvatar(myUid, myNick, 0, 38).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10) })
        addView(LinearLayout(this@communityMain).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(this@communityMain).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(whoNick)
                addView(whoCrown)
            })
            addView(txt("Ваш ник в чате", 11f, MUTE))
        })
        addView(chip("Сменить", false) { changeNickSheet(myNick) { myNick = it; renderWho() } }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        })
    })

    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(10) }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    var selectTab: (Int) -> Unit = {}
    fun renderTabs() {
        tabs.removeAllViews()
        tabs.addView(chip("Общий чат", CommunityState.tab == 0) { selectTab(0) })
        tabs.addView(chip(if (unread > 0) "Личные ($unread)" else "Личные", CommunityState.tab == 1) { selectTab(1) })
    }

    // ----- общий чат -----
    fun roomPane(alive: () -> Boolean): LinearLayout = msgPane(
        sid, myUid, "Здесь пока тихо. Напишите первым!", showNames = true,
        fetch = { after, rev -> ChatApi.roomMessages(after, rev) },
        send = { t -> ChatApi.roomSend(t) },
        alive = alive,
        onName = { m -> if (m.optLong("uid") != myUid) openDm(m.optLong("uid"), m.optString("nick"), m.optLong("av")) },
        onLong = { m, tools -> roomActions(m, myUid, myBadge, openDm, tools) },
        onReact = { id, e -> ChatApi.roomReact(id, e) },
        sendReply = { t, id -> ChatApi.roomSend(t, id) },
        onData = { r ->
            r.optJSONObject("me")?.let {
                val b = it.optInt("badge") == 1
                val c = it.optInt("crown") == 1
                if (b != myBadge || c != myCrown) { myBadge = b; myCrown = c; renderWho() }
            }
        },
        cooldownMs = { if (myBadge) 0L else ROOM_COOLDOWN_MS },
        nextAt = { CommunityState.roomNextAt },
        setNextAt = { CommunityState.roomNextAt = it }
    )

    // ----- личные чаты -----
    fun dmPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        pane.addView(LinearLayout(this).apply {
            layoutParams = lp(10)
            addView(chip("✉ Написать по нику", false) {
                inputSheet(
                    "Написать по нику", "Ник", "", "Найти",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                ) { v ->
                    if (v.isEmpty()) {
                        false
                    } else {
                        io.execute {
                            try {
                                val r = ChatApi.dmFind(v)
                                ui { openDm(r.optLong("uid"), r.optString("nick"), r.optLong("av")) }
                            } catch (e: Exception) {
                                ui { toast(errText(e)) }
                            }
                        }
                        true
                    }
                }
            })
        })
        val status = txt("Загружаем…", 13f, MUTE).apply { layoutParams = lp(8) }
        dmStatus = status
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(status)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        renderChats = { r ->
            val arr = r.optJSONArray("chats") ?: JSONArray()
            status.text = if (arr.length() == 0) "Личных чатов пока нет. Нажмите на ник в общем чате или найдите человека по нику." else ""
            list.removeAllViews()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val un = c.optInt("unread")
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ripple(CARD, 18, if (un > 0) BLUE else LINE)
                    setPadding(dp(12), dp(10), dp(14), dp(10))
                    layoutParams = lp(10)
                    isClickable = true
                    setOnClickListener { openDm(c.optLong("uid"), c.optString("nick"), c.optLong("av")) }
                    pressable(this)
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                card.addView(chatAvatar(c.optLong("uid"), c.optString("nick"), c.optLong("av"), 42).apply {
                    (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
                })
                card.addView(col)
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(nameRow(c.optString("nick"), c.optInt("badge") == 1, 16f, c.optInt("crown") == 1).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (un > 0) {
                    top.addView(txt("$un", 12f, Color.BLACK, true).apply {
                        background = shape(BLUE, 10)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    })
                }
                col.addView(top)
                val mine = c.optLong("sender") == myUid
                col.addView(txt((if (mine) "Вы: " else "") + c.optString("text"), 14f, MUTE).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                col.addView(txt(timeFmt.format(java.util.Date(c.optLong("ts"))), 11f, MUTE))
                list.addView(card)
            }
        }
        lastChats?.let { renderChats?.invoke(it) }
        return pane
    }

    selectTab = { i ->
        CommunityState.tab = i
        val my = ++CommunityState.pane
        renderTabs()
        content.removeAllViews()
        if (i == 0) {
            renderChats = null
            content.addView(roomPane { CommunityState.pane == my })
        } else {
            content.addView(dmPane())
        }
    }

    // непрочитанные личные: обновляются раз в несколько секунд, пока открыта вкладка
    fun pollChats() {
        if (sid != screenId) return
        io.execute {
            try {
                val r = ChatApi.dmList()
                ui {
                    if (sid != screenId) return@ui
                    lastChats = r
                    unread = r.optInt("unread")
                    renderTabs()
                    renderChats?.invoke(r)
                }
            } catch (e: ChatMaintException) {
                ui { if (sid == screenId) showCommunity() }   // чат закрыли на техработы, пока он был открыт
                return@execute
            } catch (e: Exception) {
                ui { if (sid == screenId) dmStatus?.text = errText(e) }
            }
            main.postDelayed({ pollChats() }, 6000L)
        }
    }

    body.addView(tabs)
    body.addView(content)
    selectTab(CommunityState.tab)
    pollChats()
}

/** Меню по долгому нажатию на сообщение в общем чате: реакции, ответ и действия с сообщением. */
private fun MainActivity.roomActions(
    m: JSONObject, myUid: Long, myBadge: Boolean, openDm: (Long, String, Long) -> Unit, tools: MsgTools
) {
    val uid = m.optLong("uid")
    val nick = m.optString("nick")
    val own = uid == myUid
    val items = ArrayList<Pair<String, () -> Unit>>()
    fun run(done: String, call: () -> Unit) {
        io.execute {
            try { call(); ui { toast(done) } } catch (e: Exception) { ui { toast(errText(e)) } }
        }
    }
    if (tools.canReply) items.add("↩ Ответить" to tools.reply)
    if (!own) items.add("✉ Написать в личку" to { openDm(uid, nick, m.optLong("av")) })
    if (own || myBadge) items.add("🗑 Удалить сообщение" to { run("Сообщение удалено") { ChatApi.roomDelete(m.optLong("id")) } })
    if (myBadge && !own && m.optInt("badge") != 1) {
        items.add("🔇 Заглушить на час" to { run("$nick заглушён на час") { ChatApi.roomMute(uid, 60) } })
        items.add("🔇 Заглушить на сутки" to { run("$nick заглушён на сутки") { ChatApi.roomMute(uid, 1440) } })
        items.add("🔇 Заглушить на неделю" to { run("$nick заглушён на неделю") { ChatApi.roomMute(uid, 10080) } })
        items.add("🔊 Снять ограничение" to { run("Ограничение снято") { ChatApi.roomMute(uid, 0) } })
    }
    if (items.isEmpty() && !tools.canReact) return
    sheet(nick, scroll = true) { box, close ->
        if (tools.canReact) {
            val mine = noVs(tools.mine())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(10)
            }
            for (e in REACTIONS) {
                val on = noVs(e) == mine
                row.addView(TextView(this).apply {
                    text = e
                    textSize = 26f
                    gravity = Gravity.CENTER
                    contentDescription = "Реакция $e"
                    background = if (on) shape(mix(BLUE, 0.25f), 14, BLUE) else null
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener { close(); tools.react(e) }
                    pressable(this)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            box.addView(row)
        }
        for ((label, act) in items) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(CARD, 14, LINE)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = lp(8)
                isClickable = true
                setOnClickListener { close(); act() }
                pressable(this)
                addView(txt(label, 16f, TEXT).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            })
        }
    }
}

/** Цитата в пузыре ответа: ник и начало исходного сообщения (или «удалено»). */
private fun MainActivity.quoteView(m: JSONObject, own: Boolean, maxW: Int, onClick: () -> Unit): View {
    val gone = m.optLong("q_id") == 0L
    val barColor = if (own) Color.argb(150, 0, 0, 0) else BLUE
    val nickColor = if (own) Color.BLACK else BLUE
    val textColor = if (own) Color.argb(200, 0, 0, 0) else MUTE
    val textMax = (maxW - dp(22) - dp(14)).coerceAtLeast(dp(80))
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(if (own) Color.argb(28, 0, 0, 0) else mix(BLUE, 0.10f), 8)
        setPadding(dp(6), dp(4), dp(8), dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(4) }
        isClickable = true
        setOnClickListener { onClick() }
        addView(View(this@quoteView).apply {
            setBackgroundColor(barColor)
            layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(6) }
        })
        addView(LinearLayout(this@quoteView).apply {
            orientation = LinearLayout.VERTICAL
            addView(txt(if (gone) "Сообщение" else m.optString("q_nick"), 11f, nickColor, true).apply {
                maxLines = 1; maxWidth = textMax; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(txt(if (gone) "удалено" else m.optString("q_text"), 12f, textColor).apply {
                maxLines = 2; maxWidth = textMax; ellipsize = android.text.TextUtils.TruncateAt.END
                if (gone) setTypeface(typeface, android.graphics.Typeface.ITALIC)
            })
        })
    }
}

// ---------- личная переписка ----------

internal fun MainActivity.showDm(peer: Long, nick: String, av: Long = 0L) {
    onHome = false
    val root = chatScreen(5)
    val sid = screenId
    CommunityState.tab = 1
    backAction = { showCommunity() }
    // шапка: назад, аватарка собеседника и его ник
    root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(12)
        addView(LinearLayout(this@showDm).apply {
            gravity = Gravity.CENTER
            background = ripple(CARD, 20, LINE)
            isClickable = true
            setOnClickListener { showCommunity() }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
            addView(IconView(this@showDm, Ic.BACK, TEXT).apply { layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)) })
        })
        addView(chatAvatar(peer, nick, av, 38).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10) })
        addView(txt(nick, 20f, TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    })
    root.addView(msgPane(
        sid, CommunityState.myUid, "Переписки пока нет. Напишите первым.", showNames = false, receipts = true,
        fetch = { after, _ -> ChatApi.dmMessages(peer, after) },
        send = { t -> ChatApi.dmSend(peer, t) }
    ))
}

// ---------- лента сообщений ----------

/**
 * Лента + поле ввода для общего чата, личных переписок и админского просмотра.
 * fetch(after, rev) возвращает {messages: [{id, uid, nick, badge, text, ts}], reset?, rev?, peer_read?};
 * send == null — режим просмотра (поля ввода нет). Свои сообщения (uid == myUid) выводятся справа.
 */
internal fun MainActivity.msgPane(
    sid: Int, myUid: Long, hint: String, showNames: Boolean,
    fetch: (Long, Long) -> JSONObject,
    send: ((String) -> Unit)?,
    receipts: Boolean = false,
    namesOnOwn: Boolean = false,
    alive: () -> Boolean = { true },
    onName: ((JSONObject) -> Unit)? = null,
    onLong: ((JSONObject, MsgTools) -> Unit)? = null,
    onData: (JSONObject) -> Unit = {},
    onReact: ((Long, String) -> JSONObject)? = null,   // реакции: (номер сообщения, эмодзи) → {reactions: [...]}
    sendReply: ((String, Long) -> Unit)? = null,       // ответ на сообщение: (текст, номер сообщения)
    cooldownMs: () -> Long = { 0L },          // пауза между сообщениями (0 — без паузы)
    nextAt: () -> Long = { 0L },              // когда можно писать снова (время телефона, мс)
    setNextAt: (Long) -> Unit = {}
): LinearLayout {
    val pane = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(8))
    }
    val emptyHint = tv(hint, 14f, MUTE)
    list.addView(emptyHint)
    val scroll = ScrollView(this).apply {
        addView(list)
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val status = txt("", 12f, MUTE).apply { layoutParams = lp(4) }
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    var lastId = 0L
    var rev = -1L           // ревизия ленты (общий чат): при удалении сообщений сервер меняет её, и лента перечитывается
    var peerRead = 0L       // до какого сообщения собеседник прочитал переписку
    val marks = ArrayList<Triple<Long, TextView, String>>()

    fun updateMarks() {
        for ((id, v, base) in marks) {
            paintReceipt(v, base, peerRead >= id)
        }
    }

    var lastUid = -1L       // автор предыдущего сообщения: подряд идущие сообщения одного человека склеиваются в серию
    var lastDay = ""

    val rows = HashMap<Long, View>()                      // номер сообщения → его строка в ленте (для перехода к цитате)
    val reactHost = HashMap<Long, LinearLayout>()         // номер сообщения → ряд реакций под ним
    val reactData = HashMap<Long, List<JSONObject>>()     // номер сообщения → [{e, n, me}]
    var startReply: (JSONObject) -> Unit = {}             // включает режим ответа (назначается вместе с полем ввода)
    var doReact: (Long, String) -> Unit = { _, _ -> }     // поставить / снять реакцию (назначается ниже)

    fun fillReactions(host: LinearLayout, msgId: Long) {
        host.removeAllViews()
        val list0 = reactData[msgId].orEmpty()
        host.visibility = if (list0.isEmpty()) View.GONE else View.VISIBLE
        for (o in list0) {
            val mineR = o.optInt("me") == 1
            val e = o.optString("e")
            host.addView(txt("$e ${o.optInt("n")}", 12f, if (mineR) TEXT else MUTE, mineR).apply {
                background = shape(if (mineR) mix(BLUE, 0.25f) else mix(MUTE, 0.10f), 12, if (mineR) BLUE else 0)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                setOnClickListener { doReact(msgId, e) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(4) }
            })
        }
    }

    /** Применяет список реакций с сервера: он охватывает последние 100 сообщений ленты, старые не трогаем. */
    fun renderReactions(arr: JSONArray?) {
        if (arr == null) return
        val ids = rows.keys.sorted()
        val from = if (ids.size > 100) ids[ids.size - 100] else 0L
        reactData.keys.removeAll { it >= from }
        val by = HashMap<Long, ArrayList<JSONObject>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            by.getOrPut(o.optLong("m")) { ArrayList() }.add(o)
        }
        reactData.putAll(by)
        for ((id, host) in reactHost) if (id >= from) fillReactions(host, id)
    }

    doReact = { id, emoji ->
        val call = onReact
        if (call != null) io.execute {
            try {
                val r = call(id, emoji)
                ui { if (sid == screenId) renderReactions(r.optJSONArray("reactions")) }
            } catch (e: Exception) {
                ui { toast(errText(e)) }
            }
        }
    }

    fun add(m: JSONObject) {
        val id = m.optLong("id")
        if (id <= lastId) return
        lastId = id
        if (emptyHint.parent != null) list.removeView(emptyHint)
        val ts = m.optLong("ts")
        val dk = dayKey(ts)
        if (dk != lastDay) { list.addView(dateChip(ts)); lastDay = dk; lastUid = -1L }
        val uid = m.optLong("uid")
        val own = uid == myUid
        val cont = uid == lastUid
        lastUid = uid
        val nick = m.optString("nick")
        val head = if (showNames && (!own || namesOnOwn) && !cont) {
            nameRow(nick, m.optInt("badge") == 1, crown = m.optInt("crown") == 1).apply {
                (this as View).setPadding(0, 0, 0, dp(1))
                if (onName != null && !own) {
                    isClickable = true
                    setOnClickListener { onName(m) }
                }
            }
        } else null
        val avatarOn = showNames && !own
        val avatar: View? = when {
            !avatarOn -> null
            cont -> View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)) }
            else -> chatAvatar(uid, nick, m.optLong("av"), 30)
        }
        val timeStr = clockFmt.format(java.util.Date(ts))
        val maxW = bubbleMax(avatarOn)
        val quote: View? = if (m.optLong("reply_to") > 0) {
            quoteView(m, own, maxW) {
                val target = rows[m.optLong("q_id")]
                if (target != null) scroll.smoothScrollTo(0, (target.top - dp(8)).coerceAtLeast(0))
                else toast("Исходное сообщение удалено или осталось выше в ленте")
            }
        } else null
        val top: View? = if (head != null && quote != null) {
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(head); addView(quote) }
        } else head ?: quote
        val (bubble, timeView) = messageBubble(m.optString("text"), own, timeStr, top, maxW)
        if (onLong != null) {
            bubble.setOnLongClickListener {
                onLong(m, MsgTools(
                    canReply = sendReply != null, canReact = onReact != null,
                    reply = { startReply(m) },
                    react = { e -> doReact(id, e) },
                    mine = { reactData[id].orEmpty().firstOrNull { it.optInt("me") == 1 }?.optString("e").orEmpty() }
                ))
                true
            }
        }
        if (receipts && own) {
            marks.add(Triple(id, timeView, timeStr))
            paintReceipt(timeView, timeStr, peerRead >= id)
        }
        val content: View = if (onReact != null) {
            val host = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(3) }
            }
            reactHost[id] = host
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (own) Gravity.END else Gravity.START
                addView(bubble)
                addView(host)
            }
        } else bubble
        val row = messageRow(own, avatar, content, if (cont) 2 else 8)
        rows[id] = row
        list.addView(row)
    }

    fun fetchOnce() {
        io.execute {
            try {
                val r = fetch(lastId, rev)
                ui {
                    if (sid != screenId || !alive()) return@ui
                    onData(r)
                    val reset = r.optBoolean("reset")
                    val before = lastId
                    if (reset) {
                        list.removeAllViews()
                        list.addView(emptyHint)
                        lastId = 0
                        lastUid = -1L
                        lastDay = ""
                        marks.clear()
                        rows.clear(); reactHost.clear(); reactData.clear()
                    }
                    rev = r.optLong("rev", rev)
                    peerRead = r.optLong("peer_read", peerRead)
                    val arr = r.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                    renderReactions(r.optJSONArray("reactions"))
                    updateMarks()
                    status.text = ""
                    if (lastId != before) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: ChatAuthException) {
                Store.adminToken = ""
                ui { if (sid == screenId) showAdminLogin() }
            } catch (e: ChatMaintException) {
                ui { if (sid == screenId && alive()) showCommunity() }
            } catch (e: Exception) {
                ui { if (sid == screenId && alive()) status.text = errText(e) }
            }
        }
    }

    fun loop() {
        if (sid != screenId || !alive()) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) fetchOnce()
        main.postDelayed({ loop() }, 4000L)
    }

    pane.addView(scroll)
    pane.addView(status)
    if (send != null) {
        val field = chatField()
        var replyTo: JSONObject? = null
        val replyText = txt("", 13f, MUTE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = shape(CARD, 12, LINE)
            setPadding(dp(12), dp(8), dp(6), dp(8))
            layoutParams = lp(4)
        }
        fun setReply(m: JSONObject?) {
            replyTo = m
            if (m == null) { replyBar.visibility = View.GONE; return }
            replyText.text = "↩ ${m.optString("nick")}: ${m.optString("text").replace('\n', ' ').take(90)}"
            replyBar.visibility = View.VISIBLE
        }
        replyBar.addView(replyText)
        replyBar.addView(txt("✕", 16f, MUTE).apply {
            contentDescription = "Отменить ответ"
            setPadding(dp(12), dp(2), dp(12), dp(2))
            isClickable = true
            setOnClickListener { setReply(null) }
        })
        startReply = { m ->
            setReply(m)
            field.requestFocus()
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(field, 0)
        }
        var sendBtn: View? = null
        // кнопка отправки во время паузы показывает, сколько секунд осталось
        fun tickCooldown() {
            val btn = sendBtn ?: return
            if (sid != screenId || !alive()) return
            val left = nextAt() - System.currentTimeMillis()
            if (left <= 0) { setSendCooldown(btn, 0); return }
            setSendCooldown(btn, ((left + 999) / 1000).toInt())
            main.postDelayed({ tickCooldown() }, 250L)
        }
        fun sendNow() {
            val wait = nextAt() - System.currentTimeMillis()
            if (wait > 0) {
                toast("Следующее сообщение можно отправить через ${(wait + 999) / 1000} с")
                return
            }
            val text = field.text.toString().trim()
            if (text.isEmpty()) return
            field.setText("")
            val replied = replyTo                       // на какое сообщение отвечаем (null — обычное)
            val replyId = replied?.optLong("id") ?: 0L
            setReply(null)
            val limit = cooldownMs()
            if (limit > 0) { setNextAt(System.currentTimeMillis() + limit); tickCooldown() }   // пауза идёт сразу: двойное нажатие не пройдёт
            io.execute {
                try {
                    if (replyId > 0 && sendReply != null) sendReply(text, replyId) else send(text)
                    ui { fetchOnce() }
                } catch (e: Exception) {
                    ui {
                        if (e is ChatCooldownException) setNextAt(System.currentTimeMillis() + e.retryMs)
                        else if (limit > 0) setNextAt(0L)   // сообщение не ушло по другой причине — паузу не навязываем
                        if (sid == screenId) {
                            field.setText(text)
                            if (replied != null) setReply(replied)
                            toast(errText(e))
                            tickCooldown()
                        }
                    }
                }
            }
        }
        val btn = sendButton { sendNow() }
        sendBtn = btn
        pane.addView(replyBar)
        pane.addView(composerRow(null, field, btn))
        tickCooldown()   // вернулись на вкладку посреди паузы — отсчёт продолжается
    }
    loop()
    return pane
}
