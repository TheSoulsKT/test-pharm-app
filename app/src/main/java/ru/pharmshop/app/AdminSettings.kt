package ru.pharmshop.app

import android.text.InputFilter
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// Админ-панель: объявление, разделы, принудительное обновление, блокировки.

// ---------- админ: объявление, разделы, обновление, блокировки ----------

/** Экран настроек: загружает текущие настройки с сервера и строит форму. */
internal fun MainActivity.adminSettingsScreen(title: String, build: (LinearLayout, JSONObject) -> Unit) {
    onHome = false
    val col = screen()
    val sid = screenId
    backAction = { showAdminThreads() }
    backRow(col, title) { showAdminThreads() }
    val status = tv("Загружаем…", 13f, MUTE)
    col.addView(status)
    io.execute {
        try {
            val s = ChatApi.adminSettings(Store.adminToken)
            ui { if (sid == screenId) { status.visibility = View.GONE; build(col, s) } }
        } catch (e: ChatAuthException) {
            Store.adminToken = ""
            ui { if (sid == screenId) showAdminLogin() }
        } catch (e: Exception) {
            ui { if (sid == screenId) status.text = errText(e) }
        }
    }
}

internal fun MainActivity.adminSave(patch: JSONObject, done: (JSONObject) -> Unit) {
    io.execute {
        try {
            val saved = ChatApi.adminSaveSettings(Store.adminToken, patch)
            ui { done(saved) }
        } catch (e: ChatAuthException) {
            Store.adminToken = ""
            ui { showAdminLogin() }
        } catch (e: Exception) {
            ui { toast(errText(e)) }
        }
    }
}

internal fun MainActivity.showAdminAnnouncement() {
    adminSettingsScreen("Объявление") { col, s ->
        val a = s.optJSONObject("announcement") ?: JSONObject()
        var active = a.optBoolean("active")
        var level = if (a.optString("level") == "warn") "warn" else "info"
        col.addView(tv("Плашка на главном экране у всех пользователей, например «Технические работы до 18:00».", 13f, MUTE))
        val text = input(
            "Текст объявления (до 300 символов)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ).apply {
            setText(a.optString("text"))
            minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(300))
        }
        col.addView(text)
        val toggles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(12)
        }
        fun paint() {
            toggles.removeAllViews()
            toggles.addView(chip(if (active) "● Показывается" else "○ Скрыто", active) { active = !active; paint() })
            toggles.addView(chip("Информация", level == "info") { level = "info"; paint() })
            toggles.addView(chip("Важно", level == "warn") { level = "warn"; paint() })
        }
        paint()
        col.addView(toggles)
        col.addView(btn("Сохранить", true) {
            val patch = JSONObject().put(
                "announcement",
                JSONObject().put("active", active).put("text", text.text.toString().trim()).put("level", level)
            )
            adminSave(patch) { toast("Сохранено. Пользователи увидят при следующем открытии главного экрана") }
        })
    }
}

/** Разделы меню: (ключ, название, включён по умолчанию). */
private val sectionDefs = listOf(
    Triple("stars", "Звёзды", true),
    Triple("premium", "Premium", true),
    Triple("vpn", "VPN", true),
    Triple("gifts", "Обычные подарки", false),
    Triple("nft_buy", "Купить NFT", false),
    Triple("nft_rent", "Аренда NFT", false),
    Triple("stars_sell", "Продать звёзды", false),
    Triple("nft_sell", "Скупка NFT", false)
)

internal fun MainActivity.editSection(key: String, name: String, secs: JSONObject, done: () -> Unit) {
    val cur = secs.optJSONObject(key) ?: JSONObject()
    formSheet(
        name,
        listOf(
            "Название (пусто — «$name»)" to cur.optString("title"),
            "Подпись под названием" to cur.optString("sub"),
            "Ссылка при нажатии (https://…)" to cur.optString("url"),
            "Сообщение, если раздел выключен" to cur.optString("message")
        )
    ) { vals ->
        val o = secs.optJSONObject(key) ?: JSONObject().also { secs.put(key, it) }
        for ((k, raw) in listOf("title", "sub", "url", "message").zip(vals)) {
            val v = raw.trim()
            if (v.isEmpty()) o.remove(k) else o.put(k, v)
        }
        done()
        true
    }
}

internal fun MainActivity.showAdminSections() {
    adminSettingsScreen("Разделы") { col, s ->
        val secs = s.optJSONObject("sections") ?: JSONObject()
        col.addView(tv(
            "Включённые разделы показываются в блоке «Купить», выключенные — в «Скоро». " +
                "Для включённого раздела без своего экрана укажите ссылку (иначе откроется бот). " +
                "Изменения применяются без выпуска новой версии.", 13f, MUTE
        ))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box)
        var render: () -> Unit = {}
        fun save() { adminSave(JSONObject().put("sections", secs)) { render() } }
        render = {
            box.removeAllViews()
            for ((key, name, defOn) in sectionDefs) {
                val cur = secs.optJSONObject(key) ?: JSONObject()
                val on = if (cur.has("enabled")) cur.optBoolean("enabled") else defOn
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(CARD, 18, LINE)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams = lp(10)
                }
                card.addView(txt(cur.optString("title").ifEmpty { name }, 16f, TEXT, true))
                val details = listOf("sub" to "подпись", "url" to "ссылка", "message" to "сообщение")
                    .filter { cur.optString(it.first).isNotEmpty() }
                    .joinToString(", ") { "${it.second}: ${cur.optString(it.first)}" }
                if (details.isNotEmpty()) card.addView(txt(details, 12f, MUTE))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                }
                row.addView(chip(if (on) "● Включён" else "○ Выключен", on) {
                    val o = secs.optJSONObject(key) ?: JSONObject().also { secs.put(key, it) }
                    o.put("enabled", !on)
                    save()
                })
                row.addView(chip("Изменить", false) { editSection(key, name, secs) { save() } })
                card.addView(row)
                box.addView(card)
            }
        }
        render()
    }
}

internal fun MainActivity.showAdminForceUpdate() {
    adminSettingsScreen("Обновление") { col, s ->
        val cur = s.optInt("min_version_code")
        col.addView(tv(
            "Ваша сборка: ${BuildConfig.VERSION_CODE}. Сейчас требуется: " +
                (if (cur > 0) "сборка $cur и выше" else "ничего не требуется") + ".", 14f, TEXT
        ))
        col.addView(tv(
            "Приложения с меньшей сборкой попросят обновиться и не будут работать, пока человек не обновится. " +
                "Сначала убедитесь, что релиз build-N уже опубликован на GitHub, иначе людям будет нечего скачать.", 12f, MUTE
        ))
        val num = input("Минимальная сборка (0 — не требовать)", InputType.TYPE_CLASS_NUMBER).apply {
            setText(cur.toString())
        }
        val msg = input(
            "Сообщение пользователям (необязательно)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ).apply {
            setText(s.optString("update_message"))
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        col.addView(num)
        col.addView(msg)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(12)
            addView(chip("Моя сборка (${BuildConfig.VERSION_CODE})", false) { num.setText(BuildConfig.VERSION_CODE.toString()) })
            addView(chip("Отключить требование", false) { num.setText("0") })
        })
        col.addView(btn("Сохранить", true) {
            val n = num.text.toString().toIntOrNull() ?: 0
            val patch = JSONObject().put("min_version_code", n).put("update_message", msg.text.toString().trim())
            if (n <= 0) {
                adminSave(patch) { toast("Требование отключено") }
            } else {
                confirmSheet(
                    "Требовать сборку $n?",
                    "Все, у кого сборка ниже $n, не смогут пользоваться приложением, пока не обновятся. " +
                        "Убедитесь, что релиз build-$n уже опубликован на GitHub.",
                    "Требовать"
                ) { adminSave(patch) { toast("Сохранено") } }
            }
        })
    }
}

internal fun MainActivity.showAdminBlocks() {
    onHome = false
    val col = screen()
    val sid = screenId
    backAction = { showAdminThreads() }
    backRow(col, "Блокировки") { showAdminThreads() }
    val status = tv("Загружаем…", 13f, MUTE)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(status)
    col.addView(list)
    val timeFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    var reload: () -> Unit = {}

    fun render(arr: JSONArray) {
        list.removeAllViews()
        status.text = if (arr.length() == 0) "Заблокированных нет" else ""
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val kind = b.optString("kind")
            val value = b.optString("value")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(CARD, 18, LINE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = lp(10)
            }
            card.addView(txt(if (kind == "ip") "IP $value" else "Устройство ${value.take(8)}…", 16f, TEXT, true))
            val nick = b.optString("nick")
            if (nick.isNotEmpty()) card.addView(txt("@$nick", 13f, MUTE))
            val reason = b.optString("reason")
            if (reason.isNotEmpty()) card.addView(txt(reason, 13f, MUTE))
            card.addView(txt("с " + timeFmt.format(java.util.Date(b.optLong("ts"))), 11f, MUTE))
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                addView(chip("Разблокировать", false) {
                    io.execute {
                        try {
                            ChatApi.adminUnblock(Store.adminToken, kind, value)
                            ui { toast("Разблокировано"); reload() }
                        } catch (e: Exception) {
                            ui { toast(errText(e)) }
                        }
                    }
                })
            })
            list.addView(card)
        }
    }

    fun load() {
        io.execute {
            try {
                val arr = ChatApi.adminBlocks(Store.adminToken)
                ui { if (sid == screenId) render(arr) }
            } catch (e: ChatAuthException) {
                Store.adminToken = ""
                ui { if (sid == screenId) showAdminLogin() }
            } catch (e: Exception) {
                ui { if (sid == screenId) status.text = errText(e) }
            }
        }
    }
    reload = { load() }
    load()
}


/** Админка → «🛠 Тех. работы чата»: закрывает общий чат и личные сообщения красивым окном с причиной. Работает сразу, без обновления приложения. */
internal fun MainActivity.showAdminChatMaint() {
    adminSettingsScreen("Тех. работы чата") { col, s ->
        val cur = s.optJSONObject("chat_maint") ?: JSONObject()
        var on = cur.optBoolean("on")
        col.addView(tv(
            "Общий чат и личные сообщения закроются у всех и покажут окно с причиной. " +
                "Поддержка, заказы и VPN работают как обычно. Включается и выключается сразу, без выпуска новой версии. " +
                "Когда работы закончатся, у пользователей чат откроется сам в течение 15 секунд.", 13f, MUTE
        ))
        val state = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(12) }
        col.addView(state)
        val reason = input(
            "Причина для пользователей (до 300 символов, необязательно)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ).apply {
            setText(cur.optString("reason"))
            minLines = 2
            filters = arrayOf(InputFilter.LengthFilter(300))
        }
        col.addView(reason)
        val quick = listOf("Обновляем чат", "Чиним ошибки", "Плановые работы, скоро вернёмся")
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lp(12)
            addView(LinearLayout(this@showAdminChatMaint).apply {
                orientation = LinearLayout.HORIZONTAL
                for (q in quick) addView(chip(q, false) { reason.setText(q); reason.setSelection(q.length) })
            })
        })
        col.addView(tv("Так это увидят пользователи:", 12f, MUTE))
        val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(preview)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actions)

        fun renderPreview() {
            preview.removeAllViews()
            preview.addView(maintCard(reason.text.toString().trim()))
        }
        fun save(newOn: Boolean, render: () -> Unit) {
            val r = reason.text.toString().trim()
            adminSave(JSONObject().put("chat_maint", JSONObject().put("on", newOn).put("reason", r))) { saved ->
                on = saved.optJSONObject("chat_maint")?.optBoolean("on") ?: newOn
                toast(if (on) "Чат закрыт на технические работы" else "Чат снова открыт")
                render()
            }
        }
        fun render() {
            state.removeAllViews()
            actions.removeAllViews()
            val c = if (on) AMBER else GREEN
            state.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(mix(c, 0.14f), 18, edge(c))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(txt(if (on) "🛠" else "✅", 22f).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) } })
                addView(LinearLayout(this@showAdminChatMaint).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(txt(if (on) "Чат на технических работах" else "Чат работает", 16f, TEXT, true))
                    addView(txt(if (on) "Пользователи видят окно с причиной" else "Всё открыто для пользователей", 12f, MUTE))
                })
            })
            if (!on) {
                actions.addView(gradBtn("🛠  Включить техработы", MAINT_A, MAINT_B, MAINT_ON) {
                    confirmSheet(
                        "Закрыть чат на техработы?",
                        "Общий чат и личные сообщения станут недоступны всем, пока вы не выключите техработы. Поддержка и заказы не затронуты.",
                        "Закрыть чат"
                    ) { save(true) { render() } }
                })
            } else {
                actions.addView(gradBtn("✅  Открыть чат (выключить техработы)", GREEN, GREEN, Color.BLACK) { save(false) { render() } })
                actions.addView(btn("Обновить причину") { save(true) { render() } })
            }
        }
        reason.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { renderPreview() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        renderPreview()
        render()
    }
}

