package ru.pharmshop.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Фоновые уведомления без Google-сервисов: раз в ~15–30 минут (минимум, который разрешает Android для периодических задач)
 * приложение коротко опрашивает сервер и показывает уведомление, если что-то произошло:
 *  • поддержка ответила в чате;
 *  • заказ стал «выполнен» или «отменён»;
 *  • (для админа) пришло новое сообщение в чат.
 * Пока приложение открыто на экране, ничего не показываем — всё видно и так.
 */
class PollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        Store.init(c)
        AppLog.init(c)
        if (!Store.pushEnabled || AppState.visible || !Notifier.enabled(c)) return Result.success()
        // каждая проверка независима: сбой одной (нет сети, 401) не мешает остальным
        try { checkSupport(c) } catch (e: Exception) { }
        try { checkOrders(c) } catch (e: Exception) { }
        try { checkAdmin(c) } catch (e: Exception) { }
        try { VpnWatch.check(c) } catch (e: Exception) { AppLog.e("vpn", e) }
        return Result.success()
    }

    private fun checkSupport(c: Context) {
        if (!Store.chatStarted) return
        val r = ChatApi.userPoll(Store.deviceId, Store.deviceSecret)
        val unread = r.optInt("unread")
        val lastId = r.optLong("last_id")
        if (unread > 0 && lastId != Store.notifiedSupportId) {
            Store.notifiedSupportId = lastId
            Notifier.support(c, unread, r.optString("text"))
        }
    }

    private fun checkOrders(c: Context) {
        Store.markOrdersSeen(onlyMissing = true)   // старые заказы без отметки: считаем уже виденными, а не «новыми»
        // только заказы, по которым ещё могут быть новости (не завершённые) — не больше 10 за раз
        val targets = Store.orders().filter { o ->
            val k = statusKind(effStatus(o))
            k != 2 && k != 3 && o.optString("id").isNotEmpty()
        }.take(10)
        for (o in targets) {
            val id = o.optString("id")
            try { Store.update(id, Api.order(id, o.optString("access_token"))) } catch (e: Exception) { continue }
        }
        // о смене статуса сообщаем один раз: seen_kind хранит то, что пользователь уже видел
        for (o in Store.orders()) {
            val k = statusKind(effStatus(o))
            if ((k == 2 || k == 3) && o.optInt("seen_kind", -1) != k) {
                Notifier.order(c, o, k)
                Store.update(o.optString("id"), org.json.JSONObject().put("seen_kind", k))
            }
        }
        OrderSync.syncAll()
    }

    private fun checkAdmin(c: Context) {
        val token = Store.adminToken
        if (token.isEmpty()) return
        val r = ChatApi.adminPoll(token)
        val ts = r.optLong("last_ts")
        if (r.optInt("unread") > 0 && ts != Store.notifiedAdminTs) {
            Store.notifiedAdminTs = ts
            Notifier.admin(c, r.optInt("unread"), r.optString("nick"), r.optString("text"))
        }
    }

    companion object {
        private const val NAME = "pharmshop_poll"

        /** Безопасно вызывать при каждом запуске: уже запланированная задача не перезапускается. */
        fun schedule(c: Context) {
            val req = PeriodicWorkRequest.Builder(PollWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(c: Context) {
            WorkManager.getInstance(c.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
