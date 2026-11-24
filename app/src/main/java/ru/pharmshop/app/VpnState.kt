package ru.pharmshop.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Состояние встроенного VPN. Меняет его только PharmVpnService; экраны подписываются и перерисовываются на главном потоке. */
object VpnState {
    enum class S { OFF, CONNECTING, ON, ERROR }

    @Volatile var state = S.OFF
        private set
    @Volatile var message = ""
        private set
    /** Когда подключились (SystemClock.elapsedRealtime) — для таймера. */
    @Volatile var since = 0L
        private set

    private val listeners = CopyOnWriteArrayList<(S, String) -> Unit>()
    private val ui = Handler(Looper.getMainLooper())

    fun listen(l: (S, String) -> Unit) { listeners.addIfAbsent(l) }
    fun unlisten(l: (S, String) -> Unit) { listeners.remove(l) }

    fun set(s: S, msg: String = "") {
        state = s
        message = msg
        if (s == S.ON) since = SystemClock.elapsedRealtime()
        ui.post { for (l in listeners) l(s, msg) }
    }
}
