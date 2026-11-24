package ru.pharmshop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Встроенный VPN: поднимает TUN-интерфейс и отдаёт его ядру Xray.
 * Сервер и ссылка подписки берутся из Store (их сохраняет экран VPN при нажатии «Подключить»).
 */
class PharmVpnService : VpnService() {
    companion object {
        const val ACTION_START = "ru.pharmshop.app.VPN_START"
        const val ACTION_STOP = "ru.pharmshop.app.VPN_STOP"
        private const val CHANNEL = "pharm_vpn"
        private const val NID = 4101

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PharmVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PharmVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            goForeground("Отключение…")
            worker.execute {
                teardown()
                VpnState.set(VpnState.S.OFF)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // ACTION_START, а также перезапуск системой («Всегда включённый VPN») — тогда intent == null
        goForeground("Подключение…")
        VpnState.set(VpnState.S.CONNECTING)
        worker.execute { connect() }
        return START_STICKY
    }

    private fun connect() {
        try {
            teardown()   // переподключение (например, после смены сервера)
            val url = Store.vpnSubUrl
            if (url.isEmpty()) throw VpnSub.SubException("Нет ссылки подписки. Откройте раздел VPN и нажмите «Подключить»")
            val server = VpnSub.pick(VpnSub.load(url))
            val config = VpnSub.buildConfig(server)

            val b = Builder()
                .setSession("PharmShop VPN")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            // само приложение (запросы к панели, обновления) и сокеты ядра идут мимо туннеля — иначе трафик ядра зациклится
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) { }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

            val pfd = b.establish() ?: throw IllegalStateException("Система не создала VPN-интерфейс: разрешение отозвано")
            tun = pfd
            VpnCore.start(this, config, pfd.fd)

            VpnState.set(VpnState.S.ON, server.name)
            goForeground("Подключено · ${server.name}")
        } catch (e: Throwable) {   // Throwable: ошибки загрузки нативной библиотеки — не Exception
            teardown()
            VpnState.set(VpnState.S.ERROR, e.message ?: e.javaClass.simpleName)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun teardown() {
        VpnCore.stop()
        try { tun?.close() } catch (e: Exception) { }
        tun = null
    }

    /** Система или другой VPN забрали туннель. */
    override fun onRevoke() {
        worker.execute {
            teardown()
            VpnState.set(VpnState.S.OFF)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        teardown()
        if (VpnState.state != VpnState.S.ERROR) VpnState.set(VpnState.S.OFF)
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- уведомление (для foreground-сервиса обязательно) ----------

    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val off = PendingIntent.getService(this, 1, Intent(this, PharmVpnService::class.java).setAction(ACTION_STOP), flags)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle("PharmShop VPN")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Отключить", off)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // тип сервиса обязателен только с Android 14; на старых версиях систему он не интересует
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NID, n)
    }
}
