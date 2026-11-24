package ru.pharmshop.app

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Проверка пинга и скорости. Пинг — время TCP-соединения (без DNS) до сервера VPN и до интернета.
 * Скорость — скачивание и отдача через speed.cloudflare.com (публичный сервис Cloudflare для таких тестов).
 * Если VPN на телефоне включён, тест показывает скорость через него.
 */
object SpeedTest {
    private const val VPN_HOST = "pvpn.vitrinka.net"     // сайт личного кабинета VPN
    private const val NET_HOST = "speed.cloudflare.com"
    private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=100000000"
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val DOWN_SECONDS = 8
    private const val UP_SECONDS = 6

    class Result {
        @Volatile var vpnPing = -1
        @Volatile var vpnJitter = -1
        @Volatile var netPing = -1
        @Volatile var netJitter = -1
        @Volatile var down = -1.0     // Мбит/с
        @Volatile var up = -1.0
    }

    /** Пинг: 6 замеров, берём медиану; дрожание — средняя разница соседних замеров. Возвращает (пинг, дрожание) в мс или null. */
    private fun ping(host: String, cancel: AtomicBoolean): Pair<Int, Int>? {
        val addr = try { InetAddress.getByName(host) } catch (e: Exception) { return null }
        val times = ArrayList<Long>()
        repeat(6) {
            if (cancel.get()) return null
            val s = Socket()
            try {
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(addr, 443), 3000)
                times.add((System.nanoTime() - t0) / 1_000_000)
            } catch (e: Exception) {
                // этот замер не удался — пропускаем
            } finally {
                try { s.close() } catch (e: Exception) { }
            }
            try { Thread.sleep(80) } catch (e: InterruptedException) { return null }
        }
        if (times.size < 3) return null
        val sorted = times.sorted()
        val median = sorted[sorted.size / 2].toInt()
        var j = 0L
        for (i in 1 until times.size) j += Math.abs(times[i] - times[i - 1])
        return Pair(median, (j / (times.size - 1)).toInt())
    }

    private fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)

    /** Параллельные потоки сильнее загружают канал, чем один. Первую секунду не считаем — это разгон. */
    private fun transfer(seconds: Int, threads: Int, cancel: AtomicBoolean, report: (Double) -> Unit, work: (AtomicLong, () -> Boolean) -> Unit): Double {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val stop = AtomicBoolean(false)
        val warmEnd = start + 1000
        var warmBytes = -1L
        val workers = List(threads) { Thread { try { work(total) { stop.get() || cancel.get() } } catch (e: Exception) { } } }
        workers.forEach { it.start() }
        var result = 0.0
        while (true) {
            Thread.sleep(250)
            val now = System.currentTimeMillis()
            if (warmBytes < 0 && now >= warmEnd) warmBytes = total.get()
            if (warmBytes >= 0) {
                result = mbps(total.get() - warmBytes, now - warmEnd)
                report(result)
            }
            if (cancel.get() || now - start >= seconds * 1000L) break
            if (workers.none { it.isAlive }) break
        }
        stop.set(true)
        workers.forEach { it.join(1500) }
        return result
    }

    private fun download(cancel: AtomicBoolean, report: (Double) -> Unit): Double =
        transfer(DOWN_SECONDS, 3, cancel, report) { total, stopped ->
            val buf = ByteArray(65536)
            while (!stopped()) {
                val c = URL(DOWN_URL).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "PharmShopApp/1.0")
                    c.inputStream.use { ins ->
                        while (!stopped()) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total.addAndGet(n.toLong())
                        }
                    }
                } finally {
                    c.disconnect()
                }
            }
        }

    private fun upload(cancel: AtomicBoolean, report: (Double) -> Unit): Double =
        transfer(UP_SECONDS, 2, cancel, report) { total, stopped ->
            val chunk = ByteArray(65536) { (it * 31).toByte() }
            val size = 2_000_000
            while (!stopped()) {
                val c = URL(UP_URL).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setFixedLengthStreamingMode(size)
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    c.setRequestProperty("User-Agent", "PharmShopApp/1.0")
                    c.outputStream.use { out ->
                        var sent = 0
                        while (sent < size && !stopped()) {
                            val n = minOf(chunk.size, size - sent)
                            out.write(chunk, 0, n)
                            sent += n
                            total.addAndGet(n.toLong())
                        }
                    }
                    if (!stopped()) c.responseCode
                } finally {
                    c.disconnect()
                }
            }
        }

    /** Запускает весь тест. onUpdate вызывается из фонового потока — обновление экрана делает вызывающий. */
    fun run(cancel: AtomicBoolean, onStage: (String) -> Unit, onUpdate: (Result) -> Unit): Result {
        val r = Result()
        onStage("Пинг до сервера VPN…")
        ping(VPN_HOST, cancel)?.let { r.vpnPing = it.first; r.vpnJitter = it.second }
        onUpdate(r)
        if (cancel.get()) return r
        onStage("Пинг до интернета…")
        ping(NET_HOST, cancel)?.let { r.netPing = it.first; r.netJitter = it.second }
        onUpdate(r)
        if (cancel.get()) return r
        onStage("Скачивание…")
        try { r.down = download(cancel) { r.down = it; onUpdate(r) } } catch (e: Exception) { AppLog.e("speed", e) }
        onUpdate(r)
        if (cancel.get()) return r
        onStage("Отдача…")
        try { r.up = upload(cancel) { r.up = it; onUpdate(r) } } catch (e: Exception) { AppLog.e("speed", e) }
        onUpdate(r)
        return r
    }
}

/** Окно «Скорость и пинг». Закрытие окна останавливает тест. */
internal fun MainActivity.openSpeedTest() {
    Diag.feature("speed_test")
    val cancel = AtomicBoolean(false)
    var running = false
    lateinit var status: TextView
    lateinit var start: TextView
    lateinit var vVpn: TextView
    lateinit var vNet: TextView
    lateinit var vDown: TextView
    lateinit var vUp: TextView

    fun tile(title: String): Pair<LinearLayout, TextView> {
        val value = txt("—", 20f, TEXT, true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(CARD, 14, LINE)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            addView(txt(title, 12f, MUTE).apply { gravity = Gravity.CENTER_HORIZONTAL; layoutParams = lp(4) })
            addView(value)
        }
        return Pair(box, value)
    }

    fun ms(p: Int, j: Int) = if (p < 0) "—" else "$p мс" + if (j >= 0) "\n±$j" else ""
    fun speed(v: Double) = if (v < 0) "—" else if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

    val dlg = sheet("Скорость и пинг", scroll = true) { box, close ->
        status = txt("Нажмите «Запустить». Тест займёт около 20 секунд.", 13f, MUTE).apply { layoutParams = lp(12) }
        box.addView(status)
        val t1 = tile("Пинг до VPN-сервера"); vVpn = t1.second
        val t2 = tile("Пинг до интернета"); vNet = t2.second
        val t3 = tile("Скачивание, Мбит/с"); vDown = t3.second
        val t4 = tile("Отдача, Мбит/с"); vUp = t4.second
        for (pair in listOf(listOf(t1, t2), listOf(t3, t4))) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(8)
                pair.forEachIndexed { i, t ->
                    t.first.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) }
                    addView(t.first)
                }
            })
        }
        box.addView(txt(
            "Скорость меряется через speed.cloudflare.com. Если VPN на телефоне включён, увидите скорость через него. " +
                "Пинг до сервера VPN — это время соединения с сайтом личного кабинета.",
            11f, MUTE
        ).apply { layoutParams = lp(14) })
        start = gbtn("Запустить", true) {
            if (running) return@gbtn
            running = true
            cancel.set(false)
            start.text = "Идёт тест…"
            vVpn.text = "—"; vNet.text = "—"; vDown.text = "—"; vUp.text = "—"
            Thread {
                val res = SpeedTest.run(
                    cancel,
                    { st -> ui { status.text = st } },
                    { r ->
                        ui {
                            vVpn.text = ms(r.vpnPing, r.vpnJitter)
                            vNet.text = ms(r.netPing, r.netJitter)
                            vDown.text = speed(r.down)
                            vUp.text = speed(r.up)
                        }
                    }
                )
                ui {
                    running = false
                    start.text = "Запустить ещё раз"
                    status.text = when {
                        cancel.get() -> "Тест остановлен."
                        res.down < 0 && res.up < 0 && res.vpnPing < 0 && res.netPing < 0 -> "Не получилось: проверьте интернет и повторите."
                        res.down < 0 -> "Скорость измерить не удалось, пинг показан выше."
                        else -> "Готово."
                    }
                }
            }.start()
        }
        box.addView(btnGrid(listOf(gbtn("Закрыть", false) { close() }, start), 0))
    }
    dlg.setOnDismissListener { cancel.set(true) }
}
