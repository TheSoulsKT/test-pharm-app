package ru.pharmshop.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Диалоги в стиле шторки «Способ оплаты»: цвета берутся из выбранной темы приложения.
// Заменяют серые системные AlertDialog в разделе VPN.

private const val S_MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val S_WC = ViewGroup.LayoutParams.WRAP_CONTENT

private fun MainActivity.hideKeyboardNow() {
    currentFocus?.let {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(it.windowToken, 0)
    }
}

/**
 * Каркас шторки: ручка, заголовок и содержимое, которое собирает [build].
 * [build] получает контейнер и функцию закрытия. Возвращает диалог (уже показанный).
 */
internal fun MainActivity.sheet(
    title: String,
    scroll: Boolean = false,
    focusInput: Boolean = false,
    build: (LinearLayout, () -> Unit) -> Unit
): Dialog {
    hideKeyboardNow()
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    build(content) { dlg.dismiss() }

    val handle = View(this).apply {
        background = shape(LINE, 3)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(BG)
            val r = dp(24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(dp(18), dp(10), dp(18), dp(24))
        addView(handle)
        addView(txt(title, 18f, TEXT, true).apply { layoutParams = lp(12) })
        if (scroll) addView(ScrollView(this@sheet).apply { addView(content) }) else addView(content)
    }
    dlg.setContentView(box)
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(S_MP, S_WC)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.55f)
        if (focusInput) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    box.translationY = dp(400).toFloat()
    dlg.setOnShowListener {
        box.animate().translationY(0f).setDuration(280)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }
    dlg.show()
    return dlg
}

/** Кнопка шторки. danger = true — красная (для необратимых действий). */
private fun MainActivity.sheetBtn(t: String, primary: Boolean, danger: Boolean, onClick: () -> Unit): TextView {
    val b = gbtn(t, primary, onClick)
    if (danger && primary) {
        b.setTextColor(Color.WHITE)
        b.background = ripple(RED, 12, RED)
    }
    return b
}

/**
 * Окно-вопрос: заголовок, пояснение и две кнопки. Заменяет AlertDialog с «Отмена / Подтвердить».
 */
internal fun MainActivity.confirmSheet(
    title: String, message: String, positive: String,
    negative: String = "Отмена", danger: Boolean = false, onPositive: () -> Unit
) {
    sheet(title) { box, close ->
        if (message.isNotEmpty()) box.addView(txt(message, 14f, MUTE).apply { layoutParams = lp(16) })
        box.addView(btnGrid(listOf(
            sheetBtn(negative, false, false) { close() },
            sheetBtn(positive, true, danger) { close(); onPositive() }
        ), 0))
    }
}

/** Окно с одной кнопкой «Понятно» (вместо AlertDialog с одним положительным ответом). */
internal fun MainActivity.infoSheet(title: String, message: String, button: String = "Понятно") {
    sheet(title) { box, close ->
        if (message.isNotEmpty()) box.addView(txt(message, 14f, MUTE).apply { layoutParams = lp(16) })
        box.addView(btnGrid(listOf(sheetBtn(button, true, false) { close() }), 0))
    }
}

/** Список вариантов (вместо setItems). Пункты — подписи; onPick получает номер выбранного. */
internal fun MainActivity.listSheet(title: String, items: List<String>, onPick: (Int) -> Unit) {
    sheet(title, scroll = true) { box, close ->
        items.forEachIndexed { i, name ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(CARD, 14, LINE)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = lp(8)
                isClickable = true
                setOnClickListener { close(); onPick(i) }
                pressable(this)
                addView(txt(name, 16f, TEXT).apply { layoutParams = LinearLayout.LayoutParams(0, S_WC, 1f) })
            })
        }
    }
}

/**
 * Окно с полем ввода и кнопками «Отмена / Дальше». onOk получает введённый текст;
 * если вернёт false — окно остаётся открытым (например, число вне диапазона).
 */
internal fun MainActivity.inputSheet(
    title: String, hintText: String, prefill: String = "", positive: String = "Дальше",
    type: Int = InputType.TYPE_CLASS_NUMBER, onOk: (String) -> Boolean
) {
    sheet(title, focusInput = true) { box, close ->
        val field: EditText = input(hintText, type).apply {
            setText(prefill)
            setSelection(text.length)
            layoutParams = lp(16)
            requestFocus()
        }
        box.addView(field)
        box.addView(btnGrid(listOf(
            sheetBtn("Отмена", false, false) { close() },
            sheetBtn(positive, true, false) { if (onOk(field.text.toString().trim())) close() }
        ), 0))
    }
}

/** Окно с несколькими текстовыми полями (поле = подсказка и начальное значение). onOk получает значения; false — окно остаётся. */
internal fun MainActivity.formSheet(
    title: String, fields: List<Pair<String, String>>, positive: String = "Сохранить",
    onOk: (List<String>) -> Boolean
) {
    sheet(title, scroll = true, focusInput = true) { box, close ->
        val edits = fields.map { (hint, value) ->
            input(hint, InputType.TYPE_CLASS_TEXT).apply {
                setText(value)
                setSingleLine()
                layoutParams = lp(10)
            }
        }
        edits.forEach { box.addView(it) }
        box.addView(btnGrid(listOf(
            sheetBtn("Отмена", false, false) { close() },
            sheetBtn(positive, true, false) { if (onOk(edits.map { it.text.toString() })) close() }
        ), 0))
    }
}

/** Окно с бегущим текстом (проценты загрузки). Закрыть можно только из кода. */
internal class ProgressSheet(private val dlg: Dialog, private val text: TextView) {
    fun setMessage(t: String) { text.text = t }
    fun dismiss() = dlg.dismiss()
}

internal fun MainActivity.progressSheet(title: String, initial: String): ProgressSheet {
    lateinit var tv: TextView
    val d = sheet(title) { box, _ ->
        tv = txt(initial, 16f, TEXT, true).apply { layoutParams = lp(4) }
        box.addView(tv)
    }
    d.setCancelable(false)
    return ProgressSheet(d, tv)
}

/** Если включено «Подтверждать оплату» — спрашивает с суммой, иначе сразу выполняет go. */
internal fun MainActivity.confirmPayThen(what: String, go: () -> Unit) {
    if (Store.confirmPay) confirmSheet("Перейти к оплате?", what, "Оплатить") { go() } else go()
}
