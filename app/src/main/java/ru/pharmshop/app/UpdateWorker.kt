package ru.pharmshop.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Обновления в фоне (Настройки → Обновления): раз в несколько часов, только на Wi-Fi (безлимитная сеть),
 * не при низком заряде, проверяем GitHub-релиз и качаем APK в кэш приложения. Контрольная сумма проверяется
 * при скачивании. Установить без подтверждения Android не позволяет, поэтому показываем уведомление
 * «Обновление готово» — нажатие открывает системное окно установки.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        Store.init(c)
        if (!Store.wifiUpdate || !Updater.configured || AppState.visible) return Result.success()
        try {
            val info = Updater.check() ?: return Result.success()
            val apk = File(c.cacheDir, "updates/update.apk")
            // эту версию уже скачали и сообщили — второй раз не надо
            if (Store.pendingUpdateCode == info.versionCode && apk.exists()) return Result.success()
            Updater.download(c, info) { }
            Store.pendingUpdateCode = info.versionCode
            Store.pendingUpdateName = info.versionName
            Notifier.update(c, info.versionName)
        } catch (e: Exception) {
            // нет сети, лимит GitHub и т. п. — попробуем в следующий раз
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "pharmshop_update"

        /** Безопасно вызывать при каждом запуске: уже запланированная задача не перезапускается. */
        fun schedule(c: Context) {
            val req = PeriodicWorkRequest.Builder(UpdateWorker::class.java, 6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(c.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(c: Context) {
            WorkManager.getInstance(c.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
