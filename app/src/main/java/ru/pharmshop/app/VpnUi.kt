package ru.pharmshop.app

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// Общие элементы раздела «VPN»: выпадающая строка выбора и ровная сетка кнопок.

private const val U_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val U_MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Выпадающая строка. set(пункты, выбранный id) обновляет список и подпись (нужно, когда список зависит от другого выбора). */
internal class DropRow(val set: (List<Pair<String, String>>, String) -> Unit)

/**
 * Строка-меню: сверху подпись, под ней текущий выбор, справа стрелка. По тапу выезжает шторка со списком
 * (та же, что у «Способа оплаты»). Пункты — пары (id, название).
 */
internal fun MainActivity.dropRow(
    box: LinearLayout, caption: String, sheetTitle: String, onPick: (String, String) -> Unit
): DropRow {
    var items: List<Pair<String, String>> = emptyList()
    var sel = ""
    val value = txt("—", 16f, TEXT, true)
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, U_WRAP, 1f)
        addView(txt(caption, 12f, MUTE))
        addView(value)
    }
    val chevron = IconView(this, Ic.BACK, MUTE).apply {
        rotation = 180f
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(CARD, 14, LINE)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = lp(8)
        isClickable = true
        contentDescription = caption
        setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            openMethodSheet(items, sel, sheetTitle) { id, name ->
                sel = id
                value.text = name
                onPick(id, name)
            }
        }
        pressable(this)
        addView(texts)
        addView(chevron)
    }
    box.addView(row)
    return DropRow { newItems, newSel ->
        items = newItems
        sel = newSel
        value.text = newItems.firstOrNull { it.first == newSel }?.second ?: "—"
    }
}

/** Кнопка для сетки: одинаковая высота и вид, текст по центру. */
internal fun MainActivity.gbtn(t: String, primary: Boolean = false, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = t
        textSize = 14f * Theme.textScale
        gravity = Gravity.CENTER
        maxLines = 2
        setTextColor(if (primary) Color.BLACK else TEXT)
        if (primary) setTypeface(typeface, Typeface.BOLD)
        background = ripple(if (primary) BLUE else Color.TRANSPARENT, 12, if (primary) BLUE else LINE)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        minHeight = dp(46)
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
    }

/** Раскладывает кнопки по две в ряд на равных долях ширины; одинокая последняя занимает всю строку. */
internal fun MainActivity.btnGrid(btns: List<TextView>, bottom: Int = 8, cols: Int = 2): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(bottom)
    }
    var i = 0
    while (i < btns.size) {
        val chunk = btns.subList(i, minOf(i + cols, btns.size))
        i += cols
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(U_MATCH, U_WRAP).apply {
                if (box.childCount > 0) topMargin = dp(8)
            }
        }
        for ((k, b) in chunk.withIndex()) {
            b.layoutParams = LinearLayout.LayoutParams(0, U_MATCH, 1f).apply { if (k > 0) leftMargin = dp(8) }
            row.addView(b)
        }
        box.addView(row)
    }
    return box
}
