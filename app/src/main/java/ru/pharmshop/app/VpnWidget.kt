package ru.pharmshop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет рабочего стола: сколько дней и трафика осталось по подписке VPN.
 * Цвета берутся из выбранной темы приложения. Данные — из кэша (VpnWatch), его обновляют приложение,
 * фоновая проверка и кнопка ⟳ на самом виджете. Ссылка подписки в виджете не показывается.
 */
class VpnWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Store.init(context)
        AppLog.init(context)
        for (id in ids) manager.updateAppWidget(id, views(context))
        refreshInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            Store.init(context)
            AppLog.init(context)
            updateAll(context)
            refreshInBackground(context)
        }
    }

    private fun refreshInBackground(context: Context) {
        if (!VpnApi.loggedIn()) return
        val pending = goAsync()
        Thread {
            try {
                VpnWatch.refresh(context)   // внутри обновит и виджеты
            } catch (e: Exception) {
                AppLog.e("widget", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "ru.pharmshop.app.WIDGET_REFRESH"

        fun updateAll(c: Context) {
            Store.init(c)
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, VpnWidget::class.java))
            if (ids.isEmpty()) return
            val v = views(c)
            for (id in ids) m.updateAppWidget(id, v)
        }

        private fun daysText(n: Int): String {
            val m100 = n % 100; val m10 = n % 10
            val w = if (m100 in 11..14) "дней" else when (m10) { 1 -> "день"; 2, 3, 4 -> "дня"; else -> "дней" }
            return "$n $w"
        }

        private fun gb(bytes: Long): String {
            val v = bytes / 1073741824.0
            return if (v >= 10) "%.0f".format(Locale.US, v) else "%.1f".format(Locale.US, v)
        }

        private fun bar(fill: Float, track: Int, color: Int): Bitmap {
            val w = 480; val h = 20
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = track
            cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, p)
            val fw = (w * fill.coerceIn(0f, 1f)).coerceAtLeast(if (fill > 0f) h.toFloat() else 0f)
            if (fw > 0f) {
                p.color = color
                cv.drawRoundRect(RectF(0f, 0f, fw, h.toFloat()), h / 2f, h / 2f, p)
            }
            return bmp
        }

        fun views(c: Context): RemoteViews {
            Store.init(c)
            Theme.update(c)
            val rv = RemoteViews(c.packageName, R.layout.widget_vpn)
            val card = Theme.card
            val text = Theme.text
            val mute = Theme.mute
            val accent = Theme.accent

            rv.setInt(R.id.w_bg, "setColorFilter", card)
            for (id in intArrayOf(R.id.w_big)) rv.setTextColor(id, text)
            rv.setTextColor(R.id.w_title, accent)
            rv.setTextColor(R.id.w_sub, mute)
            rv.setTextColor(R.id.w_traffic, mute)
            rv.setTextColor(R.id.w_refresh, mute)

            // нажатие на виджет открывает раздел VPN, на ⟳ — обновляет данные
            val open = Intent(c, LauncherActivity::class.java)
                .putExtra(Reminders.EXTRA_OPEN, "vpn")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            rv.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 7001, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val refresh = Intent(c, VpnWidget::class.java).setAction(ACTION_REFRESH)
            rv.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(c, 7002, refresh, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            rv.setTextViewText(R.id.w_title, "VPN")
            rv.setViewVisibility(R.id.w_bar, android.view.View.GONE)
            rv.setTextViewText(R.id.w_traffic, "")

            val subs = VpnWatch.cached()
            if (!VpnApi.loggedIn() || subs.length() == 0) {
                rv.setTextViewText(R.id.w_big, if (VpnApi.loggedIn()) "Нет подписки" else "Войдите")
                rv.setTextViewText(R.id.w_sub, if (VpnApi.loggedIn()) "Выберите тариф в приложении" else "Откройте приложение и войдите в VPN")
                return rv
            }
            // самая «срочная» подписка: активная с наименьшим остатком, иначе первая
            var best: JSONObject? = null
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                if (s.optBoolean("active") && !s.optBoolean("lifetime") && (best == null || s.optInt("days") < best.optInt("days"))) best = s
            }
            val s = best ?: subs.optJSONObject(0) ?: return rv
            val plan = s.optString("plan").ifEmpty { "Подписка" }.let { it.take(1).uppercase() + it.drop(1) }
            val end = s.optString("end").take(10).split("-").let { if (it.size == 3) "${it[2]}.${it[1]}.${it[0]}" else "" }
            rv.setTextViewText(R.id.w_title, "VPN · $plan")
            when {
                s.optBoolean("frozen") -> {
                    rv.setTextViewText(R.id.w_big, "Заморожена")
                    rv.setTextViewText(R.id.w_sub, if (end.isNotEmpty()) "до $end" else "")
                }
                s.optBoolean("lifetime") -> {
                    rv.setTextViewText(R.id.w_big, "Бессрочно")
                    rv.setTextViewText(R.id.w_sub, "")
                }
                s.optBoolean("active") -> {
                    val days = s.optInt("days")
                    rv.setTextViewText(R.id.w_big, daysText(days))
                    rv.setTextColor(R.id.w_big, if (days <= 3) Color.parseColor("#FF5A5F") else text)
                    rv.setTextViewText(R.id.w_sub, if (end.isNotEmpty()) "осталось · до $end" else "осталось")
                }
                else -> {
                    rv.setTextViewText(R.id.w_big, "Закончилась")
                    rv.setTextColor(R.id.w_big, Color.parseColor("#FF5A5F"))
                    rv.setTextViewText(R.id.w_sub, if (end.isNotEmpty()) "была до $end" else "Продлите в приложении")
                }
            }
            val limit = s.optLong("limit")
            val used = s.optLong("used")
            if (limit > 0 && s.optBoolean("active") && !s.optBoolean("frozen")) {
                val ratio = (used.toDouble() / limit).toFloat()
                rv.setViewVisibility(R.id.w_bar, android.view.View.VISIBLE)
                rv.setImageViewBitmap(R.id.w_bar, bar(ratio, Theme.line, if (ratio >= 0.9f) Color.parseColor("#FF5A5F") else accent))
                rv.setTextViewText(R.id.w_traffic, "Трафик: ${gb(used)} из ${gb(limit)} ГБ")
            } else if (s.optBoolean("active") && limit == 0L) {
                rv.setTextViewText(R.id.w_traffic, "Трафик без лимита")
            }
            val at = Store.vpnCacheAt
            if (at > 0) rv.setTextViewText(R.id.w_traffic, rvTraffic(s, used, limit) + " · " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at)))
            return rv
        }

        private fun rvTraffic(s: JSONObject, used: Long, limit: Long): String = when {
            limit > 0 && s.optBoolean("active") && !s.optBoolean("frozen") -> "${gb(used)} из ${gb(limit)} ГБ"
            s.optBoolean("active") -> "без лимита"
            else -> "обновлено"
        }
    }
}
