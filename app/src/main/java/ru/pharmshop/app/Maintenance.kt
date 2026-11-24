package ru.pharmshop.app

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils

// Технические работы чата: красивое окно для пользователей (вкладка «Чат»).
// Включает и выключает администратор: Админка → «🛠 Тех. работы чата» (см. AdminSettings.kt).

internal val MAINT_A: Int = Color.parseColor("#FFB020")
internal val MAINT_B: Int = Color.parseColor("#FF7A2F")
internal val MAINT_ON: Int = Color.parseColor("#2A1600")

/** Карточка «Чат на технических работах»: яркая шапка и, если задана, причина. Её видят и пользователи, и админ в предпросмотре. */
internal fun MainActivity.maintCard(reason: String): LinearLayout {
    val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val icon = txt("🛠", 44f).apply {
        gravity = Gravity.CENTER
        background = shape(ColorUtils.setAlphaComponent(MAINT_ON, 36), 40)
        layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(14) }
    }
    floatForever(icon)
    val hero = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = gradient(MAINT_A, MAINT_B, 24)
        setPadding(dp(20), dp(24), dp(20), dp(22))
        layoutParams = lp(12)
        addView(icon)
        addView(txt("Чат на технических работах", 20f, MAINT_ON, true).apply { gravity = Gravity.CENTER; layoutParams = lp(6) })
        addView(txt(
            "Мы кое-что улучшаем. Общий чат и личные сообщения скоро вернутся.",
            14f, ColorUtils.setAlphaComponent(MAINT_ON, 215)
        ).apply { gravity = Gravity.CENTER })
    }
    card.addView(hero)
    if (reason.isNotEmpty()) {
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(mix(AMBER, 0.10f), 18, edge(AMBER))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(12)
            addView(txt("📝 Что происходит", 12f, AMBER, true).apply { layoutParams = lp(4) })
            addView(txt(reason, 15f, TEXT))
        })
    }
    return card
}

/** Тело вкладки «Чат» на время техработ. */
internal fun MainActivity.maintenanceView(body: LinearLayout, reason: String, preview: Boolean = false) {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(24))
    }
    col.addView(maintCard(reason))
    col.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(10))
        layoutParams = lp(12)
        addView(txt("💬  Поддержка работает: пишите, если что-то срочно", 14f, TEXT).apply { layoutParams = lp(8) })
        addView(txt("🧾  Заказы, звёзды, Premium и VPN не затронуты", 14f, TEXT).apply { layoutParams = lp(8) })
        addView(txt("🔄  Экран обновится сам, как только чат вернётся", 14f, TEXT).apply { layoutParams = lp(4) })
    })
    if (!preview) {
        col.addView(gradBtn("Проверить снова", MAINT_A, MAINT_B, MAINT_ON) { recheckChatMaint() })
        col.addView(btn("💬  Написать в поддержку") { supportFrom = 5; showSupport() })
    }
    body.addView(ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(col)
    })
    riseIn(col)
}

/** Кнопка «Проверить снова»: если техработы закончились — открываем чат, иначе подсказываем подождать. */
internal fun MainActivity.recheckChatMaint() {
    val sid = screenId
    toast("Проверяем…")
    io.execute {
        val stillOn = try { Remote.refresh(); Remote.chatMaint() != null } catch (e: Exception) { true }
        ui {
            if (sid != screenId) return@ui
            if (stillOn) toast("Чат пока на технических работах") else showCommunity()
        }
    }
}

/** Пока открыто окно техработ, раз в 15 секунд спрашиваем сервер: как только работы закончатся, чат откроется сам. */
internal fun MainActivity.watchChatMaint(sid: Int) {
    main.postDelayed({
        if (sid != screenId) return@postDelayed
        io.execute {
            val off = try { Remote.refresh(); Remote.chatMaint() == null } catch (e: Exception) { false }
            ui {
                if (sid != screenId) return@ui
                if (off) showCommunity() else watchChatMaint(sid)
            }
        }
    }, 15000L)
}
