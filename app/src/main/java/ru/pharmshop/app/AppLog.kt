package ru.pharmshop.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Небольшой журнал ошибок на устройстве (для кнопки «Сообщить об ошибке»).
 * Хранит последние сбои сети/сервера и последнее аварийное завершение приложения.
 * Токены, e-mail и идентификаторы из текста вырезаются. Наружу сам по себе ничего не уходит.
 */
object AppLog {
    private const val MAX_BYTES = 24_000
    private const val KEEP_BYTES = 12_000
    private val lock = Any()
    private var dir: File? = null
    private var installed = false

    fun init(c: Context) {
        synchronized(lock) {
            if (dir == null) dir = c.applicationContext.filesDir
            if (installed) return
            installed = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { writeCrash(t, e) } catch (x: Throwable) { /* журнал не должен ронять приложение ещё раз */ }
                prev?.uncaughtException(t, e)
            }
        }
    }

    fun e(tag: String, msg: String) = append("E", tag, msg)

    fun e(tag: String, t: Throwable) = append("E", tag, "${t.javaClass.simpleName}: ${t.message ?: ""}")

    /** Последние строки журнала (новые в конце). */
    fun recent(max: Int): List<String> {
        val f = file("applog.txt") ?: return emptyList()
        return synchronized(lock) {
            try { if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(max) else emptyList() } catch (x: Exception) { emptyList() }
        }
    }

    /** Последнее аварийное завершение (время и начало стека) или пустая строка. */
    fun lastCrash(): String {
        val f = file("crash.txt") ?: return ""
        return synchronized(lock) { try { if (f.exists()) f.readText() else "" } catch (x: Exception) { "" } }
    }

    private fun file(name: String): File? = dir?.let { File(it, name) }

    private fun ts(): String = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())

    private fun append(level: String, tag: String, msg: String) {
        val f = file("applog.txt") ?: return
        synchronized(lock) {
            try {
                f.appendText("${ts()} $level/$tag ${redact(msg).replace('\n', ' ').take(300)}\n")
                if (f.length() > MAX_BYTES) {
                    val tail = f.readText().takeLast(KEEP_BYTES)
                    f.writeText(tail.substringAfter('\n', tail))
                }
            } catch (x: Exception) { /* нет места или прав — пропускаем */ }
        }
    }

    private fun writeCrash(t: Thread, e: Throwable) {
        val f = file("crash.txt") ?: return
        val trace = android.util.Log.getStackTraceString(e).lines().take(14).joinToString("\n") { redact(it).take(160) }
        synchronized(lock) { f.writeText("${ts()} поток ${t.name}\n$trace\n") }
        append("F", "crash", "${e.javaClass.simpleName}: ${e.message ?: ""}")
    }

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val TOKEN = Regex("[A-Za-z0-9_\\-]{28,}")

    internal fun redact(s: String): String =
        s.replace(EMAIL, "<email>").replace(UUID, "<id>").replace(TOKEN, "<token>")
}
