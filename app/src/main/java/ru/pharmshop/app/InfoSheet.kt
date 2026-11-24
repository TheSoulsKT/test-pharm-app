package ru.pharmshop.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Красивое окно-карточка с пояснением (вместо системного окна или тоста): крупный значок в цветном круге,
 * название, ярлык-статус, текст и необязательная полоса прогресса «сейчас X из N».
 * Используется для достижений, галочки модератора и зелёной короны.
 */
internal fun MainActivity.infoSheet(
    sheetTitle: String,
    emoji: String,
    color: Int,
    title: String,
    pill: String = "",
    pillColor: Int = color,
    caption: String = "",       // мелкая надпись над названием (например, раздел достижений)
    text: String = "",
    textLabel: String = "",     // подпись над текстом («Как получить»)
    progress: Pair<Int, Int>? = null,
    button: String = "Понятно"
) {
    sheet(sheetTitle) { box, close ->
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(mix(color, 0.10f), 22, edge(color))
            setPadding(dp(18), dp(22), dp(18), dp(20))
            layoutParams = lp(12)
        }
        // значок в круге с кольцом
        hero.addView(txt(emoji, 42f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(mix(color, 0.22f))
                setStroke(dp(2), color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply { bottomMargin = dp(14) }
        })
        if (caption.isNotEmpty()) hero.addView(txt(caption.uppercase(), 11f, MUTE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            layoutParams = lp(4)
        })
        hero.addView(txt(title, 21f, TEXT, true).apply { gravity = Gravity.CENTER; layoutParams = lp(10) })
        if (pill.isNotEmpty()) hero.addView(txt(pill, 13f, pillColor, true).apply {
            gravity = Gravity.CENTER
            background = shape(mix(pillColor, 0.18f), 14, pillColor)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(if (progress != null || text.isNotEmpty()) 14 else 0) }
        })
        if (progress != null && progress.second > 0) {
            val (cur, need) = progress
            val pct = (cur.coerceAtMost(need) * 100f / need).coerceIn(0f, 100f)
            hero.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 100f
                background = shape(mix(MUTE, 0.18f), 4)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(6) }
                addView(View(this@infoSheet).apply {
                    background = shape(color, 4)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct)
                })
            })
            hero.addView(txt("Сейчас $cur из $need", 12f, MUTE).apply {
                gravity = Gravity.CENTER
                layoutParams = lp(if (text.isNotEmpty()) 12 else 0)
            })
        }
        if (textLabel.isNotEmpty()) hero.addView(txt(textLabel, 11f, MUTE, true).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(3)
        })
        if (text.isNotEmpty()) hero.addView(txt(text, 14f, TEXT).apply { gravity = Gravity.CENTER })
        box.addView(hero)
        box.addView(btnGrid(listOf(gbtn(button, true) { close() }), 0))
    }
}

/** Окно про галочку: нажатие на «✓» рядом с ником в чате. */
internal fun MainActivity.showModeratorInfo(nick: String) {
    infoSheet(
        sheetTitle = "Галочка",
        emoji = "✓", color = BLUE,
        title = "Модератор чата",
        pill = nick,
        text = "Следит за порядком в чате: может удалять сообщения и ограничивать тех, кто нарушает правила. " +
            "Галочку выдаёт администратор приложения."
    )
}

/** Окно про зелёную корону: нажатие на корону рядом с ником. */
internal fun MainActivity.showCrownInfo(nick: String) {
    infoSheet(
        sheetTitle = "Корона",
        emoji = "👑", color = GREEN,
        title = "Зелёная корона",
        pill = nick,
        text = "Этот человек собрал все достижения в приложении, до единого."
    )
}
