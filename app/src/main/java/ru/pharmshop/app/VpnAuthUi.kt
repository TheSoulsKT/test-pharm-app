package ru.pharmshop.app

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Вход, регистрация и сброс пароля по почте для раздела «VPN» (запросы — в VpnApi).
// Рядом на экране остаётся вход через Telegram (BuyVpn.kt).

private const val MODE_LOGIN = 0
private const val MODE_REGISTER = 1
private const val MODE_RESET = 2

private fun looksLikeEmail(s: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(s)

internal fun MainActivity.buildVpnEmailAuth(col: LinearLayout, sid: Int) {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(12)
    }
    col.addView(card)

    var working = false
    // что человек уже ввёл — переживает смену вкладки внутри карточки
    var savedEmail = ""

    fun field(hintText: String, type: Int, initial: String = ""): EditText =
        input(hintText, type).apply { setText(initial) }

    val emailType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    val passType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    val codeType = InputType.TYPE_CLASS_NUMBER

    fun infoView(): TextView = txt("", 13f, MUTE).apply {
        visibility = View.GONE
        layoutParams = lp(10)
    }

    /** Запрос в фоне: пока идёт — показывает подсказку, ошибку пишет красным, успех отдаёт в ok. */
    fun task(info: TextView, wait: String, work: () -> Unit, ok: () -> Unit) {
        if (working) return
        working = true
        info.visibility = View.VISIBLE
        info.setTextColor(MUTE)
        info.text = wait
        io.execute {
            try {
                work()
                ui {
                    working = false
                    if (sid == screenId) ok()
                }
            } catch (e: Exception) {
                ui {
                    working = false
                    if (sid == screenId) {
                        info.setTextColor(RED)
                        info.text = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить запрос"
                    }
                }
            }
        }
    }

    fun fail(info: TextView, msg: String) {
        info.visibility = View.VISIBLE
        info.setTextColor(RED)
        info.text = msg
    }

    lateinit var render: (Int) -> Unit
    lateinit var registerStep2: (String) -> Unit

    fun tabs(mode: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(12)
        }
        row.addView(chip("Войти", mode == MODE_LOGIN) { if (!working) render(MODE_LOGIN) })
        row.addView(chip("Регистрация", mode == MODE_REGISTER) { if (!working) render(MODE_REGISTER) })
        card.addView(row)
    }

    fun link(t: String, onClick: () -> Unit): TextView =
        txt(t, 14f, ACC_TEXT).apply {
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { if (!working) onClick() }
        }

    // ---------- вход ----------
    fun renderLogin() {
        card.addView(txt("Вход по почте", 18f, TEXT, true).apply { layoutParams = lp(12) })
        val email = field("Почта", emailType, savedEmail)
        val pass = field("Пароль", passType)
        val info = infoView()
        card.addView(email)
        card.addView(pass)
        card.addView(btn("Войти", true) {
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            savedEmail = e
            if (!looksLikeEmail(e)) return@btn fail(info, "Проверьте адрес почты")
            if (p.isEmpty()) return@btn fail(info, "Введите пароль")
            task(info, "Входим…", { VpnApi.loginEmail(e, p) }) { showVpn() }
        })
        card.addView(info)
        card.addView(link("Забыли пароль?") {
            savedEmail = email.text.toString().trim()
            render(MODE_RESET)
        })
    }

    // ---------- регистрация ----------
    fun renderRegister() {
        card.addView(txt("Регистрация", 18f, TEXT, true).apply { layoutParams = lp(6) })
        card.addView(txt("Укажите почту и придумайте пароль — пришлём код подтверждения.", 14f, MUTE).apply { layoutParams = lp(12) })
        val info = infoView()
        val email = field("Почта", emailType, savedEmail)
        val pass = field("Пароль", passType)
        card.addView(email)
        card.addView(pass)
        card.addView(btn("Получить код", true) {
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            savedEmail = e
            if (!looksLikeEmail(e)) return@btn fail(info, "Проверьте адрес почты")
            if (p.length < 6) return@btn fail(info, "Пароль слишком короткий")
            task(info, "Проверка и отправка кода… это займёт несколько секунд", { VpnApi.registerRequest(e, p) }) {
                registerStep2(p)
            }
        })
        card.addView(info)
    }

    fun clearAndTabs(mode: Int) {
        card.removeAllViews()
        if (mode != MODE_RESET) tabs(mode)
    }

    // ---------- сброс пароля ----------
    fun renderReset(codeSent: Boolean) {
        card.addView(txt("Сброс пароля", 18f, TEXT, true).apply { layoutParams = lp(6) })
        val info = infoView()
        if (!codeSent) {
            card.addView(txt("Пришлём код на почту — по нему можно задать новый пароль.", 14f, MUTE).apply { layoutParams = lp(12) })
            val email = field("Почта", emailType, savedEmail)
            card.addView(email)
            card.addView(btn("Получить код", true) {
                val e = email.text.toString().trim()
                savedEmail = e
                if (!looksLikeEmail(e)) return@btn fail(info, "Проверьте адрес почты")
                task(info, "Проверка и отправка кода… это займёт несколько секунд", { VpnApi.resetRequest(e) }) {
                    clearAndTabs(MODE_RESET)
                    renderReset(true)
                }
            })
            card.addView(info)
        } else {
            card.addView(txt("Код отправлен на $savedEmail", 14f, MUTE).apply { layoutParams = lp(12) })
            val code = field("Код из письма", codeType)
            val pass = field("Новый пароль", passType)
            card.addView(code)
            card.addView(pass)
            card.addView(btn("Сменить пароль", true) {
                val c = code.text.toString().trim()
                val p = pass.text.toString()
                if (c.isEmpty()) return@btn fail(info, "Введите код из письма")
                if (p.length < 6) return@btn fail(info, "Пароль слишком короткий")
                var signedIn = false
                task(info, "Меняем пароль…", { signedIn = VpnApi.resetConfirm(savedEmail, c, p) }) {
                    if (signedIn) {
                        showVpn()
                    } else {
                        toast("Пароль изменён — войдите с новым")
                        render(MODE_LOGIN)
                    }
                }
            })
            card.addView(info)
            card.addView(link("Отправить код ещё раз") {
                task(info, "Проверка и отправка кода…", { VpnApi.resetRequest(savedEmail) }) {
                    info.setTextColor(MUTE)
                    info.text = "Код отправлен ещё раз"
                }
            })
        }
        card.addView(link("Назад ко входу") { render(MODE_LOGIN) })
    }

    // шаг 2 регистрации: ввод кода из письма (пароль держим только в памяти этого экрана)
    registerStep2 = { pwd ->
        clearAndTabs(MODE_REGISTER)
        card.addView(txt("Код из письма", 18f, TEXT, true).apply { layoutParams = lp(6) })
        card.addView(txt("Код отправлен на $savedEmail", 14f, MUTE).apply { layoutParams = lp(12) })
        val info = infoView()
        val code = field("Код из письма", codeType)
        card.addView(code)
        card.addView(btn("Подтвердить", true) {
            val c = code.text.toString().trim()
            if (c.isEmpty()) return@btn fail(info, "Введите код из письма")
            task(info, "Подтверждаем…", { VpnApi.registerConfirm(savedEmail, c, pwd) }) { showVpn() }
        })
        card.addView(info)
        card.addView(link("Отправить код ещё раз") {
            task(info, "Проверка и отправка кода… это займёт несколько секунд", { VpnApi.registerRequest(savedEmail, pwd) }) {
                info.setTextColor(MUTE)
                info.text = "Код отправлен ещё раз"
            }
        })
        card.addView(link("Изменить почту или пароль") { render(MODE_REGISTER) })
    }

    render = { mode ->
        clearAndTabs(mode)
        when (mode) {
            MODE_LOGIN -> renderLogin()
            MODE_REGISTER -> renderRegister()
            else -> renderReset(false)
        }
    }
    render(MODE_LOGIN)
}
