package ru.pharmshop.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// Кнопка «Подключить / Отключить» встроенного VPN, выбор сервера и запрос системного разрешения.
// Сам туннель — в PharmVpnService, ядро Xray — в VpnCore.

/** Включает или выключает VPN. Первый раз система покажет окно «Разрешить подключение». */
internal fun MainActivity.vpnToggle(url: String, live: Boolean) {
    when (VpnState.state) {
        VpnState.S.ON, VpnState.S.CONNECTING -> PharmVpnService.stop(this)
        else -> {
            if (!live) { toast("Подписка не активна"); return }
            Store.vpnSubUrl = url
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)   // без него уведомление «VPN включён» не видно
            }
            val ask = VpnService.prepare(this)
            if (ask == null) {
                PharmVpnService.start(this)
            } else {
                vpnPermOk = { PharmVpnService.start(this) }
                vpnPermission.launch(ask)
            }
        }
    }
}

/** Список серверов подписки; выбранный запоминается, а если VPN уже включён — переподключаемся к нему. */
internal fun MainActivity.vpnPickServer(url: String, onDone: () -> Unit) {
    toast("Загружаю список серверов…")
    io.execute {
        try {
            val list = VpnSub.load(url)
            ui {
                val names = listOf("Автоматически (первый в списке)") + list.map { it.name }
                listSheet("Сервер", names) { i ->
                    Store.vpnServer = if (i == 0) "" else list[i - 1].name
                    onDone()
                    if (VpnState.state == VpnState.S.ON) PharmVpnService.start(this)
                }
            }
        } catch (e: Exception) {
            ui { toast("Не удалось получить серверы: ${e.message}") }
        }
    }
}

/** Большая кнопка, строка состояния и выбор сервера. Подписывается на VpnState, пока находится на экране. */
internal fun MainActivity.vpnConnectBlock(url: String, live: Boolean, onExternal: () -> Unit): LinearLayout {
    val btn = vpnPrimary("Подключить", VIc.BOLT) { vpnToggle(url, live) }
    val label = btn.getChildAt(btn.childCount - 1) as TextView

    val status = txt("", 12.5f, MUTE).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        layoutParams = lp(6)
    }
    val serverLink = txt("", 12.5f, BLUE, true).apply {
        gravity = Gravity.CENTER
        isClickable = true
        setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = lp(0)
    }
    fun serverText() = "Сервер: " + Store.vpnServer.ifEmpty { "авто" } + " ▾"
    serverLink.text = serverText()
    serverLink.setOnClickListener { vpnPickServer(url) { serverLink.text = serverText() } }

    val external = txt("Открыть в Happ / v2rayTun", 12f, MUTE).apply {
        gravity = Gravity.CENTER
        isClickable = true
        setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = lp(0)
        setOnClickListener { onExternal() }
    }

    val paint: (VpnState.S, String) -> Unit = { s, msg ->
        when (s) {
            VpnState.S.OFF -> { label.text = "Подключить"; status.visibility = View.GONE }
            VpnState.S.CONNECTING -> {
                label.text = "Подключение…"
                status.text = "Загружаю серверы и поднимаю туннель"
                status.setTextColor(MUTE); status.visibility = View.VISIBLE
            }
            VpnState.S.ON -> {
                label.text = "Отключить"
                status.text = "Защита включена" + if (msg.isNotEmpty()) " · $msg" else ""
                status.setTextColor(GREEN); status.visibility = View.VISIBLE
            }
            VpnState.S.ERROR -> {
                label.text = "Подключить"
                status.text = "Не удалось подключиться: $msg"
                status.setTextColor(RED); status.visibility = View.VISIBLE
            }
        }
    }
    btn.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            VpnState.listen(paint)
            paint(VpnState.state, VpnState.message)
        }
        override fun onViewDetachedFromWindow(v: View) { VpnState.unlisten(paint) }
    })

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(0)
        addView(btn)
        addView(status)
        addView(serverLink)
        addView(external)
    }
}
