package ru.pharmshop.app

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Админка → «Заказы»: все заказы, оформленные через приложение (присылаются на сервер чата). */
internal fun MainActivity.showAdminOrders() {
    onHome = false
    val col = screen()
    val sid = screenId
    backAction = { showAdminThreads() }
    backRow(col, "Заказы") { showAdminThreads() }

    var filter = "all"
    var oldest = Long.MAX_VALUE
    var load: (Boolean) -> Unit = {}
    val counts = tv("", 13f, MUTE)
    val status = tv("Загружаем…", 13f, MUTE)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val moreBtn = chip("Показать ещё", false) { load(false) }
    moreBtn.visibility = View.GONE
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    fun drawFilters() {
        filters.removeAllViews()
        for ((key, title) in listOf("all" to "Все", "active" to "Активные", "done" to "Выполнены", "failed" to "Отменены")) {
            filters.addView(chip(if (key == filter) "● $title" else title, key == filter) {
                filter = key
                drawFilters()
                load(true)
            })
        }
    }
    drawFilters()

    col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filters) })
    col.addView(counts)
    col.addView(status)
    col.addView(list)
    col.addView(moreBtn)

    load = { reset ->
        if (reset) { oldest = Long.MAX_VALUE; list.removeAllViews() }
        status.text = "Загружаем…"
        moreBtn.visibility = View.GONE
        io.execute {
            try {
                val r = ChatApi.adminOrders(Store.adminToken, filter, oldest)
                ui {
                    if (sid == screenId) {
                        val arr = r.optJSONArray("orders") ?: JSONArray()
                        val c = r.optJSONObject("counts")
                        if (c != null) {
                            counts.text = "Всего ${c.optInt("all")} · активных ${c.optInt("active")} · " +
                                "выполнено ${c.optInt("done")} · отменено ${c.optInt("failed")}"
                        }
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val st = o.optString("status")
                            val color = statusColor(st)
                            val premium = o.optString("product") == "premium"
                            val badge = txt(statusLabel(st), 12f, color, true).apply {
                                background = shape((color and 0x00FFFFFF) or 0x33000000, 10)
                                setPadding(dp(10), dp(4), dp(10), dp(4))
                            }
                            val top = LinearLayout(this@showAdminOrders).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutParams = lp(8)
                                addView(txt("Заказ ${o.optString("order_number")}", 16f, TEXT, true).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                })
                                addView(badge)
                            }
                            val what = if (premium) "Premium · ${o.optInt("quantity")} мес." else "${o.optInt("quantity")} ★"
                            val card = LinearLayout(this@showAdminOrders).apply {
                                orientation = LinearLayout.VERTICAL
                                background = shape(CARD, 18, LINE)
                                setPadding(dp(16), dp(14), dp(16), dp(14))
                                layoutParams = lp(10)
                            }
                            card.addView(top)
                            card.addView(tv("$what  →  @${o.optString("username")}", 15f, TEXT))
                            card.addView(tv(
                                listOf(money(o.optString("total"), o.optString("currency")), o.optString("method"),
                                    timeFmt.format(Date(o.optLong("created"))))
                                    .filter { it.isNotBlank() }.joinToString("  •  "), 12f, MUTE
                            ))
                            val nick = o.optString("nick")
                            if (nick.isNotEmpty()) card.addView(tv("Чат поддержки: @$nick", 12f, MUTE))
                            list.addView(card)
                            oldest = minOf(oldest, o.optLong("created"))
                        }
                        status.text = if (list.childCount == 0) "Заказов пока нет" else ""
                        moreBtn.visibility = if (arr.length() >= 40) View.VISIBLE else View.GONE
                    }
                }
            } catch (e: Exception) {
                ui { if (sid == screenId) status.text = errText(e) }
            }
        }
    }
    load(true)
}
