package ru.pharmshop.app

import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

// Вход по PIN / отпечатку: экран блокировки поверх всего приложения и ввод нового PIN.

/** Цифровая клавиатура с четырьмя точками. */
internal class PinPad(
    val root: LinearLayout,
    val title: TextView,
    val sub: TextView,
    private val dots: List<View>,
    private val onColor: Int,
    private val offColor: Int
) {
    val buf = StringBuilder()
    /** Пока true (блокировка после неверных попыток) — ввод игнорируется. */
    var locked = false

    fun refresh() {
        for (i in dots.indices) {
            dots[i].background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(if (i < buf.length) onColor else offColor)
            }
        }
    }

    fun clear() { buf.setLength(0); refresh() }

    fun shake() {
        val d = root.resources.displayMetrics.density
        ObjectAnimator.ofFloat(root, View.TRANSLATION_X, 0f, -14f * d, 14f * d, -10f * d, 10f * d, -5f * d, 0f)
            .setDuration(360).start()
    }
}

internal fun MainActivity.lockEnabled(): Boolean = Store.lockMode != 0 && Store.hasPin

internal fun MainActivity.showLockIfNeeded() {
    if (lockEnabled() && !AppState.unlocked) showLock()
}

internal fun MainActivity.biometricAvailable(): Boolean =
    BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** Системное окно отпечатка. onFail — окно закрыто без успеха (отмена, «Ввести PIN», ошибка). */
internal fun MainActivity.askBiometric(title: String, sub: String, onFail: () -> Unit = {}, onOk: () -> Unit) {
    val prompt = BiometricPrompt(
        this, ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFail() }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(sub)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .setNegativeButtonText("Ввести PIN")
        .build()
    try { prompt.authenticate(info) } catch (e: Exception) { onFail() }
}

/** Строит клавиатуру PIN внутри parent. onComplete вызывается, когда введены все 4 цифры. */
internal fun MainActivity.pinPad(
    parent: LinearLayout, titleText: String, subText: String, bio: (() -> Unit)?,
    onComplete: (String, PinPad) -> Unit
): PinPad {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    val title = txt(titleText, 22f, TEXT, true).apply { gravity = Gravity.CENTER; layoutParams = lp(6) }
    val sub = txt(subText, 13f, MUTE).apply { gravity = Gravity.CENTER; layoutParams = lp(22) }
    root.addView(title)
    root.addView(sub)

    val dotRow = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutParams = lp(26) }
    val dots = ArrayList<View>()
    repeat(4) {
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(dp(9), 0, dp(9), 0) }
        }
        dots.add(dot)
        dotRow.addView(dot)
    }
    root.addView(dotRow)

    val pad = PinPad(root, title, sub, dots, BLUE, LINE)
    pad.refresh()

    fun press(d: String) {
        if (pad.locked || pad.buf.length >= 4) return
        pad.buf.append(d)
        pad.refresh()
        if (pad.buf.length == 4) main.postDelayed({ onComplete(pad.buf.toString(), pad) }, 120)
    }

    fun key(label: String, onClick: (() -> Unit)?): View = TextView(this).apply {
        text = label
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        if (onClick != null) {
            background = ripple(CARD, 36, LINE)
            isClickable = true
            setOnClickListener { onClick() }
            pressable(this)
        }
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(10), dp(6), dp(10), dp(6)) }
    }

    for (r in 0 until 3) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (k in 1..3) {
            val digit = (r * 3 + k).toString()
            row.addView(key(digit) { press(digit) })
        }
        root.addView(row)
    }
    val last = LinearLayout(this).apply { gravity = Gravity.CENTER }
    last.addView(if (bio != null) key("☝") { bio() } else key("", null))
    last.addView(key("0") { press("0") })
    last.addView(key("⌫") {
        if (!pad.locked && pad.buf.isNotEmpty()) {
            pad.buf.setLength(pad.buf.length - 1)
            pad.refresh()
        }
    })
    root.addView(last)
    parent.addView(root)
    return pad
}

/** Экран входа: закрывает всё приложение, пока не введён PIN или не приложен палец. */
internal fun MainActivity.showLock() {
    if (lockView != null) return
    val root = FrameLayout(this).apply {
        setBackgroundColor(BG)
        isClickable = true
        isFocusable = true
    }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(56), dp(24), dp(24))
    }
    root.addView(
        ScrollView(this).apply { isFillViewport = true; addView(col) },
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
    col.addView(LogoView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(20) }
    })

    val base = "Введите PIN, чтобы продолжить"
    val bioOn = Store.lockMode == 2 && biometricAvailable()
    lateinit var pad: PinPad

    fun unlock() {
        AppState.unlocked = true
        bioBusy = false
        val fails = Store.pinFails()
        Store.pinFailsReset()
        if (fails > 0 && Store.showFailedPins) {
            main.postDelayed({
                infoSheet(
                    "Были неудачные попытки входа",
                    "С прошлого входа PIN вводили неверно: $fails. Если это были не вы, смените PIN в настройках."
                )
            }, 500)
        } else {
            main.postDelayed({ maybeShowWhatsNew() }, 700)
        }
        (window.decorView as ViewGroup).removeView(root)
        if (lockView === root) lockView = null
    }

    fun bio() {
        if (bioBusy) return
        bioBusy = true
        askBiometric("Вход в PharmShop", "Приложите палец к сканеру", { bioBusy = false }) {
            bioBusy = false
            unlock()
        }
    }

    fun tick() {
        val left = Store.pinLockLeftMs()
        if (left > 0) {
            pad.locked = true
            pad.sub.setTextColor(RED)
            pad.sub.text = "Слишком много попыток. Подождите ${(left + 999) / 1000} с"
            main.postDelayed({ if (lockView === root) tick() }, 1000)
        } else if (pad.locked) {
            pad.locked = false
            pad.sub.setTextColor(MUTE)
            pad.sub.text = base
        }
    }

    val bioAction: (() -> Unit)? = if (bioOn) ({ bio() }) else null
    pad = pinPad(col, "PharmShop", base, bioAction) { pin, p ->
        if (Store.pinLockLeftMs() > 0) {
            p.clear()
            tick()
        } else if (Store.checkPin(pin)) {
            unlock()
        } else {
            Store.pinFailed()
            p.shake()
            p.clear()
            if (Store.pinLockLeftMs() > 0) {
                tick()
            } else {
                p.sub.setTextColor(RED)
                p.sub.text = "Неверный PIN"
            }
        }
    }
    col.addView(txt("Забыли PIN?", 14f, MUTE).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true
        setOnClickListener { forgotPin() }
    })

    (window.decorView as ViewGroup).addView(
        root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
    lockView = root
    tick()
    if (bioOn) main.postDelayed({ if (lockView === root) bio() }, 300)
}

/**
 * Просит PIN (или отпечаток) перед важным действием в VPN, если это включено в Настройки → Безопасность.
 * Если защита выключена или вход в приложение не настроен — просто выполняет действие.
 */
internal fun MainActivity.requirePin(onOk: () -> Unit) {
    if (!Store.vpnPinGuard || !lockEnabled()) { onOk(); return }
    sheet("Подтвердите действие", scroll = true) { box, close ->
        val bio: (() -> Unit)? = if (Store.lockMode == 2 && biometricAvailable()) ({
            if (!bioBusy) {
                bioBusy = true
                askBiometric("Подтверждение", "Приложите палец к сканеру", { bioBusy = false }) {
                    bioBusy = false
                    close()
                    onOk()
                }
            }
        }) else null
        pinPad(box, "Введите PIN", "Он нужен для этого действия", bio) { pin, p ->
            val left = Store.pinLockLeftMs()
            if (left > 0) {
                p.clear()
                p.sub.setTextColor(RED)
                p.sub.text = "Слишком много попыток. Подождите ${(left + 999) / 1000} с"
            } else if (Store.checkPin(pin)) {
                close()
                onOk()
            } else {
                Store.pinFailed()
                p.shake()
                p.clear()
                p.sub.setTextColor(RED)
                p.sub.text = "Неверный PIN"
            }
        }
    }
}

/** Без PIN войти нельзя — единственный выход это сброс данных приложения. */
internal fun MainActivity.forgotPin() {
    confirmSheet(
        "Сбросить данные приложения?",
        "Восстановить PIN нельзя. Если сбросить данные, на этом устройстве пропадут история заказов, " +
            "ник и настройки. Заказы на сервере не удаляются.",
        "Сбросить", danger = true
    ) {
        Store.wipeAll()
        PollWorker.cancel(this)
        UpdateWorker.cancel(this)
        AppState.unlocked = true
        recreate()
    }
}

/**
 * Экран создания PIN: [текущий PIN →] новый PIN → повтор.
 * done(true) — PIN сохранён, done(false) — пользователь вышел.
 */
internal fun MainActivity.showPinSetup(requireOld: Boolean, done: (Boolean) -> Unit) {
    onHome = false
    val col = screen()
    backRow(col, "PIN-код") { done(false) }
    backAction = { done(false) }
    val holder = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(24), 0, 0)
    }
    col.addView(holder)

    var stage = if (requireOld && Store.hasPin) 0 else 1   // 0 — текущий PIN, 1 — новый, 2 — повтор нового
    var first = ""
    val startTitle = if (stage == 0) "Текущий PIN" else "Новый PIN"
    val startSub = if (stage == 0) "Введите PIN, который используете сейчас" else "Придумайте 4 цифры"

    pinPad(holder, startTitle, startSub, null) { pin, p ->
        when (stage) {
            0 -> {
                if (Store.checkPin(pin)) {
                    stage = 1
                    p.clear()
                    p.title.text = "Новый PIN"
                    p.sub.setTextColor(MUTE)
                    p.sub.text = "Придумайте 4 цифры"
                } else {
                    p.shake()
                    p.clear()
                    p.sub.setTextColor(RED)
                    p.sub.text = "Неверный PIN"
                }
            }
            1 -> {
                first = pin
                stage = 2
                p.clear()
                p.title.text = "Повторите PIN"
                p.sub.setTextColor(MUTE)
                p.sub.text = "Введите тот же PIN ещё раз"
            }
            else -> {
                if (pin == first) {
                    Store.setPin(pin)
                    toast("PIN сохранён")
                    done(true)
                } else {
                    stage = 1
                    first = ""
                    p.shake()
                    p.clear()
                    p.title.text = "Новый PIN"
                    p.sub.setTextColor(RED)
                    p.sub.text = "PIN не совпал, попробуйте снова"
                }
            }
        }
    }
}
