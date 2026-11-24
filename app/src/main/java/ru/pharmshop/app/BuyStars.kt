package ru.pharmshop.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

// Покупка звёзд.

// ---------- покупка звёзд ----------
internal fun MainActivity.showStars() {
    onHome = false
    Diag.feature("stars")
    val col = screen(bottomBar = true)
    val sid = screenId
    buyHeader(col, "Купить звёзды")
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    io.execute {
        try {
            val c = cfg ?: Api.config().also { cfg = it }
            ui { if (sid == screenId) { status.visibility = View.GONE; buildForm(col, c) } }
        } catch (e: Exception) {
            ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
        }
    }
}

internal fun MainActivity.buildForm(col: LinearLayout, c: JSONObject) {
    val firstNew = col.childCount
    val min = c.optInt("min", 50)
    val max = c.optInt("max", 10000)
    c.optString("hcaptcha_sitekey").takeIf { it.isNotEmpty() }?.let { captcha(it) }

    // получатель
    col.addView(sectionLabel("Получатель", true))
    val (userBox, user) = userField()
    user.setText(Store.lastUser)
    col.addView(userBox)

    // баннер «Сколько звёзд»: число вводится прямо в нём
    val cb = countBanner("Сколько звёзд", "${c.optString("stars_rate")} ₽ за звезду · от $min до $max шт.")
    val qty = cb.input
    col.addView(cb.root)

    // кнопки «+N» прибавляют к числу (шаги берём из пресетов сервера)
    val steps = ArrayList<Int>()
    c.optJSONArray("presets")?.let { for (i in 0 until it.length()) steps.add(it.getInt(i)) }
    val stepList = if (steps.isEmpty()) listOf(50, 100, 500, 1000) else steps.take(4)
    col.addView(stepChips(stepList) { v ->
        val now = qty.text.toString().toIntOrNull() ?: 0
        qty.setText(minOf(now + v, max).toString())
        qty.setSelection(qty.text.length)
    })
    col.addView(txt("Сбросить", 13f, MUTE).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        layoutParams = lp(4)
        isClickable = true
        setOnClickListener { qty.setText("") }
    })

    // способ оплаты
    col.addView(sectionLabel("Способ оплаты"))
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(4)
    }
    col.addView(box)
    val shown = shownMethods(c)
    var method = shown.firstOrNull()?.first ?: c.optString("default_method")   // по умолчанию — первый («СБП 1»)
    var methodName = shown.firstOrNull()?.second ?: ""

    // закреплённая панель: сумма и кнопка
    val bar = payBar { info ->
        pay(user.text.toString(), qty.text.toString().toIntOrNull() ?: 0, method, methodName, c, info)
    }
    var token = 0

    fun setPrice(s: String) {
        popText(bar.total, s)
        popText(cb.price, s)
    }

    fun requestQuote() {
        val q = qty.text.toString().toIntOrNull() ?: 0
        token++
        val my = token
        val bad = q != 0 && (q < min || q > max)
        cb.note.setTextColor(if (bad) RED else MUTE)
        bar.cap.text = if (q in min..max) "К оплате · $q ★" else "К оплате"
        if (q < min || q > max) { setPrice("—"); return }
        main.postDelayed({
            if (my != token) return@postDelayed
            io.execute {
                try {
                    val r = Api.quote(q, method)
                    ui { if (my == token) setPrice(fmtMoney(r)) }
                } catch (e: Exception) {
                    ui { if (my == token) { popText(bar.total, "—"); popText(cb.price, "Не удалось рассчитать цену") } }
                }
            }
        }, 400L)
    }

    methodPicker(box, shown, method) { id, name ->
        method = id
        methodName = name
        requestQuote()
    }

    qty.addTextChangedListener(afterText { requestQuote() })
    if (Store.repeatQty > 0) { qty.setText(Store.repeatQty.toString()); Store.repeatQty = 0 }   // «Повторить заказ»

    attachBar(col, bar.root)
    riseIn(col, firstNew)
}

/**
 * Способы оплаты для формы: первый и второй меняются местами, подписи («СБП #1», «СБП #2»)
 * остаются по позициям, способы через сеть TON скрыты. Возвращает (id, подпись).
 */
internal fun MainActivity.shownMethods(c: JSONObject): List<Pair<String, String>> {
    val methods = c.optJSONArray("methods") ?: return emptyList()
    val all = (0 until methods.length()).toMutableList()
    if (all.size >= 2) { val t = all[0]; all[0] = all[1]; all[1] = t }
    val tonRe = Regex("(^|[^a-z])ton([^a-z]|$)|тон", RegexOption.IGNORE_CASE)
    val out = ArrayList<Pair<String, String>>()
    for (pos in all.indices) {
        val m = methods.getJSONObject(all[pos])
        if (tonRe.containsMatchIn(m.optString("name")) || tonRe.containsMatchIn(m.optString("id"))) continue
        out.add(Pair(m.getString("id"), methods.getJSONObject(pos).getString("name")))
    }
    // запоминаем подписи, чтобы заказы в профиле подписывались так же
    val names = JSONObject()
    for ((id, nm) in out) names.put(id, nm)
    Store.methodNames = names.toString()
    return out
}

/** Список с радио-точками: один выбранный пункт. */
internal fun MainActivity.selectList(
    box: LinearLayout, items: List<Pair<String, String>>, initial: String,
    palette: List<Int> = listOf(BLUE),
    onSelect: (String, String) -> Unit
) {
    val rows = ArrayList<MethodRow>()
    var cur = initial
    fun paint() {
        for (r in rows) {
            val sel = r.id == cur
            r.row.background = shape(if (sel) mix(r.tint, 0.2f) else CARD, 14, if (sel) r.tint else LINE)
            val dot = GradientDrawable()
            dot.setShape(GradientDrawable.OVAL)
            dot.setColor(if (sel) r.tint else Color.TRANSPARENT)
            dot.setStroke(dp(2), if (sel) r.tint else MUTE)
            r.dot.background = dot
            dotState(r.dot, sel)
        }
    }
    for ((id, name) in items) {
        val dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(14) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { cur = id; paint(); onSelect(id, name) }
            pressable(this)
        }
        row.addView(dotView)
        row.addView(txt(name, 16f, TEXT))
        box.addView(row)
        rows.add(MethodRow(id, name, row, dotView, palette[rows.size % palette.size]))
    }
    paint()
}
