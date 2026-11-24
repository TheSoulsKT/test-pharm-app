package ru.pharmshop.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.random.Random

/**
 * Напоминания о покупке звёзд / Premium. Локальные (без сервера): будильник системы
 * срабатывает в случайный момент, между уведомлениями всегда не меньше часа.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val PREFS = "pharmshop_reminders"
    private const val ALARM_CODE = 7001
    const val EXTRA_OPEN = "open"

    /** Минимальный перерыв между уведомлениями (1 час) и верхняя граница случайной паузы (6 часов). */
    private const val MIN_GAP_MS = 60L * 60 * 1000
    private const val MAX_GAP_MS = 6L * 60 * 60 * 1000

    /** «Тихие часы»: с 22:00 до 09:00 по местному времени не беспокоим. */
    private const val QUIET_FROM = 22
    private const val QUIET_TO = 9

    private val STARS = listOf(
        "Пора пополнить звёзды" to "Купите Telegram Stars за рубли — заказ приходит в течение 15 секунд.",
        "Звёзды для себя или в подарок" to "Оформите заказ в PharmShop за пару минут.",
        "Нужны звёзды?" to "Выберите количество и оплатите через СБП."
    )
    private val PREMIUM = listOf(
        "Telegram Premium" to "Оформите или продлите подписку: выберите срок и оплатите в приложении.",
        "Premium для любого @username" to "Подарите подписку себе или другу за пару минут.",
        "Подписка заканчивается?" to "Продлите Telegram Premium в PharmShop."
    )

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- разрешение (Android 13+) ----------
    /** Спрашиваем один раз; если пользователь отказал — больше не надоедаем. */
    fun shouldAskPermission(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        // не более 3 запросов (Android сам перестаёт показывать диалог после двух отказов)
        return prefs(c).getInt("asked_n", 0) < 3
    }

    fun markAsked(c: Context) {
        prefs(c).edit().putInt("asked_n", prefs(c).getInt("asked_n", 0) + 1).apply()
    }

    // ---------- планирование ----------
    /** Вызывается при запуске приложения и после перезагрузки телефона. */
    fun ensureScheduled(c: Context) {
        if (!enabled(c)) { cancel(c); return }
        val at = prefs(c).getLong("next_at", 0L)
        if (at > System.currentTimeMillis()) arm(c, at) else scheduleNext(c)
    }

    private fun scheduleNext(c: Context) {
        val delay = Random.nextLong(MIN_GAP_MS, MAX_GAP_MS + 1)
        val at = outOfQuietHours(System.currentTimeMillis() + delay)
        prefs(c).edit().putLong("next_at", at).apply()
        arm(c, at)
    }

    /** Пользователь может отключить напоминания в настройках. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on) ensureScheduled(c) else cancel(c)
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(c))
    }

    private fun alarmIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, ALARM_CODE, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun arm(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(c)
        // неточный будильник: разрешений не требует, может сработать чуть позже, но не раньше
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Если время попало на тихие часы — переносим на утро (+ случайные до 1,5 часа). */
    private fun outOfQuietHours(t: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour in QUIET_TO until QUIET_FROM) return t
        if (hour >= QUIET_FROM) cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, QUIET_TO)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis + Random.nextLong(0, 90L * 60 * 1000)
    }

    // ---------- показ ----------
    fun fire(c: Context) {
        if (!enabled(c)) return
        val now = System.currentTimeMillis()
        val p = prefs(c)
        // страховка: даже при двойном срабатывании будильника перерыв остаётся ≥ 1 часа
        if (now - p.getLong("last_sent", 0L) >= MIN_GAP_MS &&
            NotificationManagerCompat.from(c).areNotificationsEnabled()
        ) {
            if (post(c)) p.edit().putLong("last_sent", now).apply()
        }
        scheduleNext(c)
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context): Boolean {
        val stars = Random.nextBoolean()
        val (title, text) = (if (stars) STARS else PREMIUM).random()

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Напоминания о покупке звёзд и Telegram Premium"
                }
            )
        }
        val open = Intent(c, LauncherActivity::class.java)
            .putExtra(EXTRA_OPEN, if (stars) "stars" else "premium")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            c, if (stars) 1 else 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setColor(Color.parseColor("#3B8EF3"))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(c).notify(1001, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Reminders.fire(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) Reminders.ensureScheduled(context)
    }
}
