package ru.pharmshop.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Интервалы опроса чата: пока тихо — 4 → 8 → 15 → 30 с, любое событие возвращает к 4 с. */
internal val CHAT_POLL = longArrayOf(4_000L, 8_000L, 15_000L, 30_000L)

/** Интервалы опроса списка личных переписок (вкладка «Личные» открыта). */
internal val DM_LIST_POLL = longArrayOf(6_000L, 12_000L, 25_000L)

/** Интервал счётчика непрочитанных личных, пока открыт общий чат. */
internal val DM_UNREAD_POLL = longArrayOf(45_000L)

/** Статусы заказов в профиле: 10 с, а если ничего не менялось — 15 с. */
internal val ORDERS_POLL = longArrayOf(10_000L, 15_000L)

/** Админка: список обращений. */
internal val ADMIN_LIST_POLL = longArrayOf(15_000L, 20_000L)

/**
 * Опрос с адаптивным интервалом, который не работает, пока приложение не на экране.
 *
 * - [steps] — интервалы по порядку. Запрос без изменений сдвигает на следующий шаг, запрос с изменениями (или [wake]) возвращает к первому.
 * - Пока активность не в состоянии STARTED (приложение свёрнуто, экран погас), таймер не запускается. Как только вернулись,
 *   делается один мгновенный запрос, и опрос идёт дальше.
 * - Опрос сам заканчивается, когда экран сменился ([MainActivity.screenId] != [sid]) или [alive] вернул false.
 * - Запросы не накладываются друг на друга: если [wake] пришёл во время запроса, следующий запрос идёт сразу после него.
 *
 * Всё выполняется в главном потоке. [run] запускает запрос (обычно через io.execute) и обязательно вызывает finish(changed)
 * в главном потоке (через ui { }): changed = true, если пришло что-то новое.
 */
internal class Poller(
    private val act: MainActivity,
    private val sid: Int,
    private val steps: LongArray,
    private val alive: () -> Boolean = { true },
    private val run: (finish: (Boolean) -> Unit) -> Unit
) {
    private var idx = 0
    private var busy = false
    private var again = false
    private var dead = false
    private var primed = false
    private var immediate = true
    private var pending: Runnable? = null

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                if (!primed) {
                    primed = true
                    if (immediate) wake() else schedule()
                } else wake()   // вернулись в приложение: один мгновенный запрос
            }
            Lifecycle.Event.ON_STOP -> cancel()
            else -> {}
        }
    }

    /** Запускает опрос. immediate = false — первый запрос через первый интервал (данные только что загрузили сами). */
    fun start(immediate: Boolean = true): Poller {
        this.immediate = immediate
        act.lifecycle.addObserver(observer)
        return this
    }

    /** Остановить насовсем (например, при смене вкладки). */
    fun stop() {
        if (dead) return
        dead = true
        cancel()
        act.lifecycle.removeObserver(observer)
    }

    /** Что-то произошло (человек написал, открыл экран): интервал сбрасывается и запрос идёт сразу. */
    fun wake() {
        if (isDead()) return
        idx = 0
        cancel()
        tick()
    }

    private fun visible() = act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun isDead(): Boolean {
        if (dead) return true
        if (sid != act.screenId || !alive()) { stop(); return true }
        return false
    }

    private fun cancel() {
        pending?.let { act.main.removeCallbacks(it) }
        pending = null
    }

    private fun schedule() {
        cancel()
        if (isDead() || !visible()) return
        val r = Runnable { pending = null; tick() }
        pending = r
        act.main.postDelayed(r, steps[idx.coerceIn(0, steps.lastIndex)])
    }

    private fun tick() {
        if (isDead() || !visible()) return
        if (busy) { again = true; return }
        busy = true
        try {
            run { changed -> finished(changed) }
        } catch (e: Throwable) {
            busy = false
            if (idx < steps.lastIndex) idx++
            schedule()
        }
    }

    private fun finished(changed: Boolean) {
        busy = false
        if (isDead()) return
        if (again) { again = false; idx = 0; tick(); return }
        if (changed) idx = 0 else if (idx < steps.lastIndex) idx++
        schedule()
    }
}
