package ru.pharmshop.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import kotlin.math.roundToInt

// Общие элементы экранов «Купить звёзды» и «Купить Premium».
// Единственный акцент — Theme.accent выбранного оформления; на светлой теме для текста он чуть темнее.

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

/** Акцентный цвет, когда им набран текст: на светлом фоне темнее, чтобы читался. */
internal val ACC_TEXT: Int
    get() = if (Theme.light) ColorUtils.blendARGB(Theme.accent, Color.BLACK, 0.38f) else Theme.accent

internal fun afterText(f: () -> Unit) = object : TextWatcher {
    override fun afterTextChanged(s: Editable?) { f() }
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
}

/** «1190» + «RUB» → «1190 ₽». */
internal fun fmtMoney(r: JSONObject): String {
    val cur = r.optString("currency", "RUB")
    return r.optString("total") + " " + (if (cur == "RUB") "₽" else cur)
}

internal fun monthsLabel(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "месяцев" else when (m10) { 1 -> "месяц"; 2, 3, 4 -> "месяца"; else -> "месяцев" }
    return "$n $w"
}

// ---------- шапка и каркас ----------

/** Шапка экрана: стрелка «назад», заголовок и справа кнопка «Мои заказы». */
internal fun MainActivity.buyHeader(col: LinearLayout, title: String) {
    backRow(col, title)
    val row = col.getChildAt(col.childCount - 1) as LinearLayout
    (row.getChildAt(1) as TextView).layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
    val orders = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = ripple(CARD, 22, LINE)
        isClickable = true
        contentDescription = "Мои заказы"
        setOnClickListener { showProfile() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        addView(IconView(this@buyHeader, Ic.BOX, TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
    }
    row.addView(orders)
}

internal fun MainActivity.sectionLabel(t: String, first: Boolean = false): TextView =
    txt(t, 13f, MUTE).apply {
        layoutParams = lp(8).apply { topMargin = if (first) 0 else dp(8) }
    }

/** Ставит панель оплаты в закреплённое место внизу экрана (см. screen(bottomBar = true)). */
internal fun MainActivity.attachBar(col: LinearLayout, bar: View) {
    val host = col.tag as? FrameLayout ?: return
    host.removeAllViews()
    host.addView(bar)
    bar.alpha = 0f
    bar.translationY = dp(16).toFloat()
    bar.animate().alpha(1f).translationY(0f).setDuration(380)
        .setInterpolator(DecelerateInterpolator(2f)).start()
}

// ---------- получатель ----------

/** Поле «@ username»: «@» стоит внутри поля, обводка — в цвет темы. */
internal fun MainActivity.userField(): Pair<LinearLayout, EditText> {
    val et = EditText(this).apply {
        hint = "username"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(TEXT)
        setHintTextColor(MUTE)
        textSize = 16f * Theme.textScale
        background = null
        setPadding(0, dp(14), 0, dp(14))
        layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(CARD, 14, edge(BLUE))
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = lp(12)
        addView(txt("@", 16f, ACC_TEXT, true).apply { setPadding(0, 0, dp(4), 0) })
        addView(et)
    }
    return Pair(box, et)
}

// ---------- баннер «Сколько звёзд» ----------

internal class CountBanner(val root: LinearLayout, val input: EditText, val price: TextView, val note: TextView)

/** Баннер: подпись, крупное число (вводится прямо в нём), цена и строка-подсказка. Тап по баннеру открывает клавиатуру. */
internal fun MainActivity.countBanner(label: String, noteText: String): CountBanner {
    val lab = txt(label, 14f, ACC_TEXT).apply { gravity = Gravity.CENTER }
    val inp = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "0"
        setHintTextColor(ColorUtils.setAlphaComponent(TEXT, 70))
        setTextColor(TEXT)
        textSize = 40f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = null
        setPadding(0, dp(2), 0, dp(2))
        filters = arrayOf(InputFilter.LengthFilter(6))
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
    val price = txt("—", 16f, ACC_TEXT, true).apply { gravity = Gravity.CENTER }
    val note = txt(noteText, 12f, MUTE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, 0)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = shape(mix(BLUE, 0.14f), 20, BLUE)
        setPadding(dp(16), dp(16), dp(16), dp(14))
        layoutParams = lp(12).apply { topMargin = dp(4) }
        isClickable = true
        setOnClickListener {
            inp.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(inp, 0)
        }
        addView(lab)
        addView(inp, LinearLayout.LayoutParams(MP, WC))
        addView(price)
        addView(note)
    }
    return CountBanner(root, inp, price, note)
}

/** Ряд кнопок «+50 +100 …» одинаковой ширины. */
internal fun MainActivity.stepChips(steps: List<Int>, onStep: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(4)
    }
    steps.forEachIndexed { i, v ->
        row.addView(TextView(this).apply {
            text = "+$v"
            textSize = 14f * Theme.textScale
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            background = ripple(CARD, 14, LINE)
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onStep(v) }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply {
                if (i < steps.lastIndex) rightMargin = dp(8)
            }
        })
    }
    return row
}

// ---------- способ оплаты (шторка) ----------

/**
 * Строка «Способ оплаты» с текущим выбором. По тапу снизу выезжает шторка со списком способов.
 * Один и тот же компонент — на экранах звёзд и Premium.
 */
internal fun MainActivity.methodPicker(
    box: LinearLayout, items: List<Pair<String, String>>, initial: String,
    onSelect: (String, String) -> Unit
) {
    var cur = initial
    val label = txt(items.firstOrNull { it.first == cur }?.second ?: "", 16f, TEXT, true).apply {
        layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
    }
    val chevron = IconView(this, Ic.BACK, MUTE).apply {
        rotation = 180f
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(CARD, 14, LINE)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        layoutParams = lp(8)
        isClickable = true
        contentDescription = "Способ оплаты"
        setOnClickListener {
            openMethodSheet(items, cur) { id, name ->
                cur = id
                label.text = name
                onSelect(id, name)
            }
        }
        pressable(this)
        addView(label)
        addView(chevron)
    }
    box.addView(row)
}

/** Шторка выбора способа оплаты: цвета берутся из темы, выбранный пункт подсвечен акцентом. */
internal fun MainActivity.openMethodSheet(
    items: List<Pair<String, String>>, selected: String, title: String = "Способ оплаты",
    onPick: (String, String) -> Unit
) {
    // если открыта клавиатура (ввод числа/имени) — прячем, чтобы шторка не прыгала
    currentFocus?.let {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(it.windowToken, 0)
    }
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for ((id, name) in items) {
        val sel = id == selected
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(if (sel) mix(BLUE, 0.16f) else CARD, 14, if (sel) BLUE else LINE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { dlg.dismiss(); onPick(id, name) }
            pressable(this)
            addView(txt(name, 16f, TEXT, sel).apply { layoutParams = LinearLayout.LayoutParams(0, WC, 1f) })
            if (sel) addView(txt("✓", 18f, ACC_TEXT, true))
        })
    }
    val handle = View(this).apply {
        background = shape(LINE, 3)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        }
    }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(BG)
            val r = dp(24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(dp(18), dp(10), dp(18), dp(24))
        addView(handle)
        addView(txt(title, 18f, TEXT, true).apply { layoutParams = lp(12) })
        addView(ScrollView(this@openMethodSheet).apply { addView(list) })
    }
    dlg.setContentView(sheet)
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MP, WC)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.55f)
    }
    sheet.translationY = dp(400).toFloat()
    dlg.setOnShowListener {
        sheet.animate().translationY(0f).setDuration(280)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }
    dlg.show()
}

// ---------- сроки Premium ----------

private fun pillView(a: MainActivity, t: String): TextView = a.run {
    txt(t, 11f, Color.BLACK, true).apply {
        background = shape(BLUE, 8)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(8) }
    }
}

/** Широкие карточки сроков: слева срок и цена за месяц, справа цена; у выгодных сроков — бейдж со скидкой. */
internal fun MainActivity.planRows(
    box: LinearLayout, plans: List<Pair<Int, String>>, initial: Int, onSelect: (Int) -> Unit
) {
    fun num(s: String): Double? = s.replace(Regex("[^0-9.,]"), "").replace(',', '.').toDoubleOrNull()
    val minMonths = plans.minOfOrNull { it.first } ?: 0
    val basePer = plans.firstOrNull { it.first == minMonths }?.let { p -> num(p.second)?.div(p.first) }
    var cur = initial
    val rows = ArrayList<Pair<Int, LinearLayout>>()
    fun paint() {
        for ((m, r) in rows) {
            val sel = m == cur
            r.background = ripple(if (sel) mix(BLUE, 0.16f) else CARD, 16, if (sel) BLUE else LINE)
        }
    }
    for ((m, total) in plans) {
        val per = num(total)?.div(m)
        val save = if (per != null && basePer != null && m != minMonths && basePer > 0)
            (100 - per / basePer * 100).roundToInt() else 0
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(txt(monthsLabel(m), 16f, TEXT, true))
            if (save >= 3) addView(pillView(this@planRows, "−$save%"))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            addView(titleRow)
            if (per != null) addView(txt("${per.roundToInt()} ₽ в месяц", 13f, MUTE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { cur = m; paint(); onSelect(m) }
            pressable(this)
            addView(left)
            addView(txt("от $total ₽", 16f, TEXT, true))
        }
        box.addView(row)
        rows.add(Pair(m, row))
    }
    paint()
}

// ---------- закреплённая панель оплаты ----------

internal class PayBar(val root: LinearLayout, val cap: TextView, val total: TextView, val info: TextView)

/** Панель «К оплате · сумма · Оплатить». Строка статуса («Создаём заказ…») появляется над ней, только когда есть текст. */
internal fun MainActivity.payBar(onPay: (TextView) -> Unit): PayBar {
    val info = txt("", 13f, MUTE).apply {
        visibility = View.GONE
        setPadding(dp(4), 0, dp(4), dp(8))
    }
    info.addTextChangedListener(afterText {
        info.visibility = if (info.text.isEmpty()) View.GONE else View.VISIBLE
    })
    val cap = txt("К оплате", 12f, MUTE)
    val total = txt("—", 20f, TEXT, true)
    val left = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        addView(cap)
        addView(total)
    }
    val pay = Button(this).apply {
        text = "Оплатить"
        setAllCaps(false)
        textSize = 16f * Theme.textScale
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)   // на акценте темы текст чёрный
        setTypeface(typeface, Typeface.BOLD)
        background = RippleDrawable(
            ColorStateList.valueOf(Theme.rippleColor), shape(BLUE, 14), shape(Color.WHITE, 14)
        )
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        setPadding(dp(24), dp(14), dp(24), dp(14))
        layoutParams = LinearLayout.LayoutParams(WC, WC)
        setOnClickListener {
            val sum = total.text.toString()
            if (Store.confirmPay && sum.any { it.isDigit() }) {
                confirmSheet("Перейти к оплате?", "${cap.text}: $sum", "Оплатить") { onPay(info) }
            } else {
                onPay(info)
            }
        }
        pressable(this)
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(CARD, 20, LINE)
        setPadding(dp(16), dp(10), dp(10), dp(10))
        addView(left)
        addView(pay)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(info)
        addView(row)
    }
    return PayBar(root, cap, total, info)
}
