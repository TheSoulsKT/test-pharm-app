package ru.pharmshop.app

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * ЕДИНСТВЕННОЕ место, где приложение знает про ядро Xray (app/libs/libv2ray.aar, сборка AndroidLibXrayLite).
 * Если у вашей версии .aar другие имена или сигнатуры (initCoreEnv / newCoreController / startLoop) — правится только этот файл.
 */
internal object VpnCore {
    private var ctl: CoreController? = null
    private var inited = false

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }

    /** Запускает Xray с конфигом; tunFd — дескриптор интерфейса, который вернул VpnService.Builder.establish(). */
    @Synchronized
    fun start(ctx: Context, config: String, tunFd: Int) {
        if (!inited) {
            Libv2ray.initCoreEnv(ctx.filesDir.absolutePath, Store.vpnHwid)
            inited = true
        }
        stop()
        val c = Libv2ray.newCoreController(handler)
        c.startLoop(config, tunFd)
        ctl = c
    }

    @Synchronized
    fun stop() {
        try { ctl?.stopLoop() } catch (e: Exception) { }
        ctl = null
    }

    val running: Boolean get() = try { ctl?.isRunning == true } catch (e: Exception) { false }
}
