package ru.pharmshop.app

import androidx.appcompat.app.AlertDialog

// Обновление приложения, удалённые настройки и обязательное обновление.

// ---------- обновление приложения ----------

/** silent = проверка при запуске: сообщаем только если есть новая версия. */
internal fun MainActivity.checkForUpdate(silent: Boolean) {
    if (!Updater.configured) {
        if (!silent) toast("Обновления работают в версии, собранной на GitHub")
        return
    }
    if (!silent) toast("Проверяем обновления…")
    io.execute {
        try {
            val info = Updater.check()
            ui {
                if (isFinishing || isDestroyed) return@ui
                if (info == null) {
                    if (!silent) {
                        infoSheet("Обновлений нет", Updater.lastNote.ifEmpty { "У вас последняя версия." }, "Ок")
                    }
                }
                else offerUpdate(info)
            }
        } catch (e: Exception) {
            ui { if (!silent) toast(e.message ?: "Не удалось проверить обновления") }
        }
    }
}

internal fun MainActivity.offerUpdate(info: Updater.Info) {
    val mb = if (info.size > 0) " (%.1f МБ)".format(info.size / 1048576.0) else ""
    val notes = if (info.notes.isNotEmpty()) "\n\n${info.notes.take(600)}" else ""
    confirmSheet("Доступно обновление", "${info.versionName}$mb$notes", "Обновить", "Позже") { startUpdate(info) }
}

internal fun MainActivity.startUpdate(info: Updater.Info) {
    if (!Updater.canInstall(this)) {
        confirmSheet(
            "Нужно разрешение",
            "Разрешите приложению устанавливать обновления, затем вернитесь и нажмите «Проверить обновления» ещё раз.",
            "Открыть настройки"
        ) { Updater.openInstallPermissionSettings(this) }
        return
    }
    val dialog = progressSheet("Скачиваем обновление", "0%")
    io.execute {
        try {
            val apk = Updater.download(this, info) { pct -> ui { dialog.setMessage("$pct%") } }
            ui {
                dialog.dismiss()
                try { Updater.install(this, apk) } catch (e: Exception) { toast("Не удалось запустить установку") }
            }
        } catch (e: Exception) {
            ui { dialog.dismiss(); toast(e.message ?: "Не удалось скачать обновление") }
        }
    }
}

// ---------- удалённые настройки и обязательное обновление ----------

/** Спрашивает у сервера объявление, разделы и минимальную версию (не чаще раза в 30 секунд). */
internal fun MainActivity.refreshRemote() {
    val now = System.currentTimeMillis()
    if (now - remoteAt < 30_000L) return
    remoteAt = now
    io.execute {
        try {
            val changed = Remote.refresh()
            ui {
                if (isFinishing || isDestroyed) return@ui
                if (enforceUpdate()) return@ui
                if (forceShown) {            // требование сняли — возвращаем в приложение
                    forceShown = false
                    backAction = null
                    showHome()
                    return@ui
                }
                if (changed && onHome) showHome()
            }
        } catch (e: Exception) {
            // нет связи — работаем с сохранёнными настройками
        }
    }
}

/** Если версия приложения ниже требуемой — показывает экран обновления. true — приложение заблокировано. */
internal fun MainActivity.enforceUpdate(): Boolean {
    val min = Remote.minVersion()
    // в сборке без GitHub обновиться нечем — не блокируем
    if (min <= BuildConfig.VERSION_CODE || !Updater.configured) return false
    showForceUpdate(min)
    return true
}

internal fun MainActivity.showForceUpdate(min: Int) {
    if (forceShown) return
    forceShown = true
    onHome = false
    val col = screen()
    backAction = { }   // «назад» ничего не делает, пока не обновишься
    col.addView(txt("Нужно обновление", 26f, TEXT, true).apply { layoutParams = lp(12) })
    col.addView(tv(
        Remote.updateMessage().ifEmpty { "Эта версия приложения устарела и больше не поддерживается. Обновите приложение, чтобы продолжить." },
        15f, MUTE
    ))
    col.addView(tv("Ваша сборка: ${BuildConfig.VERSION_CODE}, требуется не ниже $min", 12f, MUTE))
    val status = tv("", 13f, MUTE)
    col.addView(btn("Обновить", true) {
        status.text = "Проверяем…"
        io.execute {
            try {
                val info = Updater.check()
                ui {
                    if (info == null) status.text = Updater.lastNote.ifEmpty { "Обновление пока не найдено, попробуйте позже." }
                    else { status.text = ""; startUpdate(info) }
                }
            } catch (e: Exception) {
                ui { status.text = e.message ?: "Не удалось проверить обновления" }
            }
        }
    })
    col.addView(status)
}

/** Установка обновления, которое уже скачано в фоне по Wi-Fi (оно лежит в кэше, контрольная сумма проверена при скачивании). */
internal fun MainActivity.installPendingUpdate() {
    val apk = java.io.File(cacheDir, "updates/update.apk")
    if (!apk.exists() || Store.pendingUpdateCode <= BuildConfig.VERSION_CODE) {
        toast("Скачанного обновления нет")
        return
    }
    if (!Updater.canInstall(this)) {
        confirmSheet(
            "Нужно разрешение",
            "Разрешите приложению устанавливать обновления, затем вернитесь и нажмите «Установить» ещё раз.",
            "Открыть настройки"
        ) { Updater.openInstallPermissionSettings(this) }
        return
    }
    try { Updater.install(this, apk) } catch (e: Exception) { toast("Не удалось запустить установку") }
}
