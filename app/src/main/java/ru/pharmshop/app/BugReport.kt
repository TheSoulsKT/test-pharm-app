package ru.pharmshop.app

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import ru.pharmshop.app.ChatApi

/** Отчёт об ошибке: версия, телефон, настройки и последние сбои. Уходит в чат поддержки одним сообщением (лимит сервера — 1000 знаков). */
object BugReport {
    private const val LIMIT = 980

    private fun onOff(b: Boolean) = if (b) "вкл" else "выкл"

    /** Техническая часть отчёта: без PIN, паролей, ссылки подписки и платёжных данных. */
    fun environment(c: Context): String {
        val lock = when { !Store.hasPin || Store.lockMode == 0 -> "нет"; Store.lockMode == 2 -> "отпечаток"; else -> "PIN" }
        val theme = if (Theme.light) "светлая" else "тёмная"
        return buildString {
            append("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Телефон: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            append("Язык: ${java.util.Locale.getDefault().language} · тема: $theme, ${Theme.skin.title}\n")
            append("VPN: ${if (VpnApi.loggedIn()) "вход выполнен" else "не входили"} · уведомления: ${onOff(Notifier.enabled(c))} · защита: $lock")
        }
    }

    /** Настоящий ли это отчёт: есть сбой или записи об ошибках в журнале либо пользователь описал проблему. */
    fun isReal(comment: String): Boolean =
        AppLog.lastCrash().isNotBlank() || AppLog.recent(8).isNotEmpty() || comment.trim().length >= 20

    /** Полный текст сообщения; если не влезает в лимит — сначала режем журнал, потом стек сбоя. */
    fun build(c: Context, comment: String): String {
        val head = StringBuilder("🐞 Отчёт об ошибке")
        if (comment.isNotBlank()) head.append("\n").append(comment.trim().take(300))
        val env = environment(c)
        val crash = AppLog.lastCrash().lines().filter { it.isNotBlank() }.take(3).joinToString("\n") { it.take(150) }
        val log = AppLog.recent(8).map { it.take(140) }

        val fixed = head.toString() + "\n\n" + env
        val sb = StringBuilder(fixed)
        if (crash.isNotEmpty() && sb.length + crash.length + 22 < LIMIT) sb.append("\n\nСбой приложения:\n").append(crash)
        if (log.isNotEmpty()) {
            val room = LIMIT - sb.length - 20
            if (room > 60) {
                val picked = ArrayList<String>()
                var used = 0
                for (line in log.asReversed()) {          // берём самые свежие строки, пока есть место
                    if (used + line.length + 1 > room) break
                    picked.add(0, line); used += line.length + 1
                }
                if (picked.isNotEmpty()) sb.append("\n\nПоследние ошибки:\n").append(picked.joinToString("\n"))
            }
        }
        return sb.toString().take(LIMIT)
    }
}

/** Окно «Сообщить об ошибке»: комментарий, предпросмотр того, что уйдёт, кнопка отправки в поддержку. */
internal fun MainActivity.openBugReport() {
    Diag.feature("bug_report")
    sheet("Сообщить об ошибке", scroll = true, focusInput = false) { box, close ->
        box.addView(txt(
            "Опишите, что пошло не так. К сообщению добавится техническая информация — так причину найти быстрее. " +
                "Какие данные собирает приложение: Настройки → Прочее. Ответ придёт в чат поддержки.",
            13f, MUTE
        ).apply { layoutParams = lp(10) })
        val field = input("Что случилось? (по желанию)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(300))
            layoutParams = lp(10)
        }
        box.addView(field)
        box.addView(txt("Что будет отправлено", 12f, TEXT, true).apply { layoutParams = lp(4) })
        box.addView(txt(BugReport.build(this, ""), 11f, MUTE).apply {
            setTextIsSelectable(true)
            background = shape(CARD, 12, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(6)
        })
        box.addView(txt("Не отправляются: PIN, пароли, ссылка подписки и платёжные данные.", 11f, MUTE).apply { layoutParams = lp(14) })
        var sending = false
        lateinit var send: android.widget.TextView
        send = gbtn("Отправить", true) {
            if (sending) return@gbtn
            sending = true
            send.text = "Отправляем…"
            val comment = field.text.toString()
            val text = BugReport.build(this, comment)
            val real = BugReport.isReal(comment)
            val order = Store.orders().firstOrNull()?.optString("order_number").orEmpty()
            io.execute {
                try {
                    ChatApi.userSend(Store.deviceId, Store.deviceSecret, Store.lastUser, order, BuildConfig.VERSION_NAME, text)
                    Store.noteBugReport(real)
                    ui { toast("Отчёт отправлен. Ответ придёт в чат поддержки"); close() }
                } catch (e: Exception) {
                    AppLog.e("report", e)
                    ui { sending = false; send.text = "Отправить"; toast("Не отправилось: ${e.message}") }
                }
            }
        }
        box.addView(btnGrid(listOf(gbtn("Отмена", false) { close() }, send), 0))
    }
}
