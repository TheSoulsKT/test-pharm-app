package ru.pharmshop.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils

// Настройки по категориям: Оформление, Безопасность, Прочее (уведомления, обновления, помощь, данные приложения).

internal fun MainActivity.showSettings() {
    onHome = false
    val col = screen(3)
    val sid = screenId
    titleRow(col, "Настройки", 3)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(body)
    renderSettings(body, sid)
    appFooter(col)
    riseIn(body)
}

/** Перерисовать экран целиком (после смены темы: поменялись все цвета). Только плавное появление, без выезда. */
internal fun MainActivity.reopenSettings() {
    firstScreen = true
    showSettings()
}

/** Открытая категория настроек: 0 — Оформление, 1 — Безопасность, 2 — Прочее. Помнится, пока открыто приложение. */
private var settingsTab = 0

internal fun MainActivity.renderSettings(body: LinearLayout, sid: Int) {
    body.removeAllViews()
    val again: () -> Unit = { if (sid == screenId) renderSettings(body, sid) }
    body.addView(segmented(listOf("Оформление", "Безопасность", "Прочее"), settingsTab) { i ->
        settingsTab = i
        again()
    })
    when (settingsTab) {
        0 -> {
            appearanceBlock(body)
            accentBlock(body)
            interfaceBlock(body, again)
            sectionsBlock(body)
            iconBlock(body, again)
            effectsBlock(body, again)
            ordersBlock(body, again)
        }
        1 -> {
            securityBlock(body, again)
            dataProtectionBlock(body, again)
        }
        else -> {
            notificationsBlock(body)
            updatesBlock(body, again)
            helpBlock(body)
            resetBlock(body)
            dataBlock(body)
        }
    }
}

// ---------- общие элементы ----------

private fun MainActivity.sectionLabel(body: LinearLayout, t: String) {
    body.addView(txt(t, 20f, TEXT, true).apply { layoutParams = lp(10).apply { topMargin = dp(10) } })
}

private fun MainActivity.groupCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = shape(CARD, 18, LINE)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = lp(12)
}

private fun MainActivity.caption(t: String): TextView = txt(t, 13f, MUTE).apply { layoutParams = lp(8) }

/** Переключатель из нескольких вариантов («таблетка»). */
internal fun MainActivity.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(BG, 14, LINE)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = lp(12)
    }
    options.forEachIndexed { i, label ->
        val on = i == selected
        box.addView(TextView(this).apply {
            text = label
            textSize = 13f * Theme.textScale
            gravity = Gravity.CENTER
            setTextColor(if (on) Color.BLACK else TEXT)
            if (on) setTypeface(typeface, Typeface.BOLD)
            background = if (on) shape(BLUE, 11) else ripple(Color.TRANSPARENT, 11)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            setOnClickListener { if (!on) onSelect(i) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    return box
}

/** Строка с выключателем. */
internal fun MainActivity.switchRow(
    title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit
): LinearLayout {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    val sw = SwitchCompat(this).apply {
        isChecked = checked
        isEnabled = enabled
        thumbTintList = ColorStateList(states, intArrayOf(BLUE, MUTE))
        trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(BLUE, 110), LINE))
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) }
    }
    texts.addView(txt(title, 16f, if (enabled) TEXT else MUTE, true))
    if (sub.isNotEmpty()) texts.addView(txt(sub, 12f, MUTE))
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(8)
        setPadding(0, dp(4), 0, dp(4))
    }
    row.addView(texts)
    row.addView(sw)
    sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    if (enabled) {
        row.isClickable = true
        row.setOnClickListener { sw.toggle() }
    }
    return row
}

// ---------- оформление ----------

private fun MainActivity.appearanceBlock(body: LinearLayout) {
    sectionLabel(body, "Тема")
    val c = groupCard()

    c.addView(caption("Режим"))
    val modes = listOf(1, 2, 0)   // значения Store.theme: тёмная, светлая, как в системе
    c.addView(segmented(listOf("🌙 Тёмная", "☀️ Светлая", "🌓 Система"), modes.indexOf(Store.theme).coerceAtLeast(0)) { i ->
        Store.theme = modes[i]
        applyAppearance()
    })

    c.addView(caption("Тема"))
    val all = Skin.values().toList()
    var i = 0
    while (i < all.size) {
        val a = skinCard(all[i])
        val b: View = if (i + 1 < all.size) skinCard(all[i + 1]) else View(this).apply { visibility = View.INVISIBLE }
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) }
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(10)
            addView(a)
            addView(b)
        })
        i += 2
    }
    if (!FxView.allowed(this)) {
        c.addView(txt("Анимации отключены в системе телефона, поэтому фон темы неподвижен.", 12f, MUTE))
    }
    body.addView(c)
}

/** Применяет выбранные режим и тему сразу, без перезапуска приложения. */
private fun MainActivity.applyAppearance() {
    Theme.update(this)
    applyWindowTheme()
    // смена тёмного/светлого режима может пересоздать экран — тогда вернёмся в настройки
    AppState.reopen = "settings"
    main.postDelayed({ AppState.reopen = null }, 2000)   // флаг нужен только для мгновенного пересоздания
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(Theme.nightMode())
    reopenSettings()
}

private fun MainActivity.swatch(color: Int, ring: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(5) }
    background = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
        setStroke(dp(1), ring)
    }
}

private fun MainActivity.skinCard(s: Skin): LinearLayout {
    val sel = Theme.skin == s
    val p = if (Theme.light) s.light else s.dark
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = RippleDrawable(
            ColorStateList.valueOf(Theme.rippleColor),
            GradientDrawable().apply {
                setColor(p.bg)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(if (sel) 2 else 1), if (sel) p.accent else LINE)
            },
            shape(Color.WHITE, 16)
        )
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener {
            if (!sel) {
                Store.skin = s.id
                applyAppearance()
            }
        }
        pressable(this)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(4)
    }
    top.addView(txt(s.emoji, 22f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
    if (sel) top.addView(txt("✓", 16f, p.accent, true))
    box.addView(top)
    box.addView(txt(s.title, 14f, TEXT, true).apply { layoutParams = lp(6) })
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(swatch(p.card, p.line))
        addView(swatch(p.line, p.line))
        addView(swatch(p.accent, p.accent))
    })
    return box
}

// ---------- шаги заказа ----------

private fun MainActivity.ordersBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Заказы")
    val c = groupCard()
    c.addView(caption("Как показывать шаги заказа"))
    c.addView(segmented(listOf("Значок", "Полоса"), Store.stepsStyle.coerceIn(0, 1)) { i ->
        Store.stepsStyle = i
        again()
    })
    c.addView(caption("Так будет выглядеть заказ, который оплачен и выдаётся:"))
    if (Store.stepsStyle == 1) {
        c.addView(stepsView(1).apply { layoutParams = lp(0) })
    } else {
        val color = BLUE
        c.addView(txt("Оплачен · выдаём", 12f, color, true).apply {
            background = shape((color and 0x00FFFFFF) or 0x33000000, 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }
    body.addView(c)
}

// ---------- оформление: интерфейс ----------

private fun MainActivity.interfaceBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Интерфейс")
    val c = groupCard()
    c.addView(caption("Размер текста"))
    c.addView(segmented(listOf("Мелкий", "Обычный", "Крупный"), Store.textSize.coerceIn(0, 2)) { i ->
        Store.textSize = i
        Theme.update(this)
        reopenSettings()   // экран строится заново уже с новым размером
    })
    c.addView(caption("Скругление карточек"))
    c.addView(segmented(listOf("Меньше", "Обычное", "Больше"), Store.cornerStyle.coerceIn(0, 2)) { i ->
        Store.cornerStyle = i
        Theme.update(this)
        reopenSettings()
    })
    c.addView(caption("Карточки разделов на главной"))
    c.addView(segmented(listOf("Обычные", "Компактные"), Store.cardSize.coerceIn(0, 1)) { i ->
        Store.cardSize = i
        again()
    })
    c.addView(caption("Какая вкладка открывается при запуске"))
    val tabs = listOf(0, 4, 5, 2)
    // раньше «Чат» здесь означал поддержку (1): теперь это общий чат
    val cur = if (Store.startTab == 1) 5 else Store.startTab
    c.addView(segmented(listOf("Telegram", "VPN", "Чат", "Профиль"), tabs.indexOf(cur).coerceAtLeast(0)) { i ->
        Store.startTab = tabs[i]
        again()
    })
    c.addView(switchRow("Скрывать поздравление", "Убрать цветную плашку на главном экране", Store.hideBanner) { on ->
        Store.hideBanner = on
    })
    body.addView(c)
}

// ---------- оформление: свой акцентный цвет ----------

private fun MainActivity.accentBlock(body: LinearLayout) {
    sectionLabel(body, "Акцентный цвет")
    val c = groupCard()
    val p = if (Theme.light) Theme.skin.light else Theme.skin.dark
    val cur = Theme.ACCENTS.firstOrNull { it.id == Store.accentId }
    c.addView(caption("Цвет кнопок и значков. Выбрано: ${cur?.title ?: "по теме"}"))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    // первый кружок — цвет самой темы (сбрасывает свой выбор)
    val options = listOf(Triple(0, "По теме", p.accent)) +
        Theme.ACCENTS.map { Triple(it.id, it.title, if (Theme.light) it.light else it.dark) }
    for ((optId, optTitle, optColor) in options) {
        val sel = Store.accentId == optId
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) }
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(optColor)
                setStroke(dp(if (sel) 3 else 1), if (sel) TEXT else LINE)
            }
            contentDescription = optTitle
            isClickable = true
            setOnClickListener {
                if (Store.accentId != optId) {
                    Store.accentId = optId
                    Theme.update(this@accentBlock)
                    reopenSettings()
                }
            }
            pressable(this)
        })
    }
    c.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(4)
        addView(row)
    })
    body.addView(c)
}

// ---------- оформление: разделы на главной ----------

/** Разделы главного меню: ключ из админ-панели и название. */
private val HOME_SECTIONS = listOf(
    "stars" to "Звёзды",
    "premium" to "Купить Premium",
    "gifts" to "Обычные подарки",
    "nft_buy" to "Купить NFT",
    "nft_rent" to "Аренда NFT",
    "stars_sell" to "Продать звёзды",
    "nft_sell" to "Скупка NFT"
)

private fun MainActivity.sectionsBlock(body: LinearLayout) {
    sectionLabel(body, "Разделы на главной")
    val c = groupCard()
    c.addView(txt("Скройте то, чем не пользуетесь. Раздел снова появится, как только вы его включите.", 12f, MUTE).apply {
        layoutParams = lp(8)
    })
    val hidden = Store.hiddenSections.split(',').filter { it.isNotEmpty() }
    for ((key, title) in HOME_SECTIONS) {
        c.addView(switchRow(title, "", key !in hidden) { on ->
            val set = Store.hiddenSections.split(',').filter { it.isNotEmpty() }.toMutableSet()
            if (on) set.remove(key) else set.add(key)
            Store.hiddenSections = set.joinToString(",")
        })
    }
    c.addView(switchRow("Скрыть блок «Скоро»", "Не показывать разделы, которые ещё не открыты", Store.hideSoon) { on ->
        Store.hideSoon = on
    })
    body.addView(c)
}

// ---------- оформление: иконка приложения ----------

private fun MainActivity.iconBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Иконка приложения")
    val c = groupCard()
    c.addView(txt("Выберите кота для значка на рабочем столе", 12f, MUTE).apply { layoutParams = lp(10) })
    val cur = AppIcon.current(this)
    for (row in 0 until 2) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(if (row == 0) 8 else 0)
        }
        for (col in 0 until 3) {
            val i = row * 3 + col
            val e = AppIcon.all[i]
            val sel = i == cur
            val preview = android.widget.FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(resources.getColor(e.bgColor, theme))
                }
                clipToOutline = true
                addView(android.widget.ImageView(this@iconBlock).apply {
                    setImageResource(e.fg)
                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    scaleX = 1.45f
                    scaleY = 1.45f
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(64), dp(64))
                })
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = shape(BG, 16, if (sel) edge(BLUE) else LINE)
                setPadding(dp(6), dp(10), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                addView(preview)
                addView(txt(e.title + if (sel) " ✓" else "", 12f, if (sel) TEXT else MUTE, sel).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                })
                isClickable = true
                setOnClickListener {
                    if (sel) return@setOnClickListener
                    confirmSheet(
                        "Сменить иконку?",
                        "Значок «${e.title}» появится на рабочем столе через несколько секунд. На некоторых телефонах приложение при этом закроется — просто откройте его снова.",
                        "Сменить"
                    ) {
                        AppIcon.set(this@iconBlock, i)
                        toast("Иконка «${e.title}» выбрана")
                        again()
                    }
                }
                pressable(this)
            }
            r.addView(tile)
        }
        c.addView(r)
    }
    body.addView(c)
}

// ---------- оформление: анимации и отклик ----------

private fun MainActivity.effectsBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Анимации и отклик")
    val c = groupCard()
    c.addView(switchRow("Анимированный фон", "Снег, листья, звёзды и другие эффекты выбранной темы", Store.fxEnabled) { on ->
        Store.fxEnabled = on
        reopenSettings()   // фон рисуется поверх экрана — пересоздаём экран, чтобы он сразу появился или пропал
    })
    if (Store.fxEnabled) {
        c.addView(caption("Сколько частиц на фоне"))
        c.addView(segmented(listOf("Меньше", "Обычно", "Больше"), Store.fxDensity.coerceIn(0, 2)) { i ->
            Store.fxDensity = i
            reopenSettings()
        })
    }
    c.addView(switchRow("Плавные переходы", "Экраны и карточки появляются с анимацией", Store.uiAnim) { on ->
        Store.uiAnim = on
    })
    c.addView(switchRow("Вибрация при нажатии", "Лёгкий отклик на касание кнопок и карточек", Store.haptics) { on ->
        Store.haptics = on
        if (on) c.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    })
    body.addView(c)
}

// ---------- безопасность: защита данных ----------

private fun MainActivity.dataProtectionBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Защита данных")
    val c = groupCard()
    val lock = lockEnabled()
    c.addView(switchRow("Скрывать экран в «Недавних»", "Запрещает скриншоты и прячет содержимое в списке приложений", Store.secureScreen) { on ->
        Store.secureScreen = on
        applySecureScreen()
    })
    c.addView(switchRow("Скрывать суммы и ники", "В профиле вместо них точки, пока не нажмёте на карточку", Store.hideSensitive) { on ->
        Store.hideSensitive = on
        AppState.revealed = false
    })
    c.addView(switchRow("Подтверждать оплату", "Окно с суммой перед переходом к оплате", Store.confirmPay) { on ->
        Store.confirmPay = on
    })
    c.addView(switchRow("Прятать ссылку подписки VPN", "Ссылка показывается только после нажатия на неё", Store.vpnMaskLink) { on ->
        Store.vpnMaskLink = on
    })
    c.addView(switchRow("Очищать буфер обмена", "Через минуту после копирования ссылки подписки", Store.clipAutoClear) { on ->
        Store.clipAutoClear = on
    })
    c.addView(switchRow(
        "PIN для выхода из VPN",
        if (lock) "Спрашивать PIN при выходе из аккаунта и смене ссылки" else "Сначала включите вход по PIN или отпечатку",
        Store.vpnPinGuard && lock, lock
    ) { on -> Store.vpnPinGuard = on })
    c.addView(switchRow(
        "Показывать неудачные попытки входа",
        if (lock) "После входа скажем, сколько раз PIN вводили неверно" else "Сначала включите вход по PIN или отпечатку",
        Store.showFailedPins && lock, lock
    ) { on -> Store.showFailedPins = on })
    body.addView(c)
}

// ---------- помощь ----------

private fun MainActivity.helpBlock(body: LinearLayout) {
    sectionLabel(body, "Помощь")
    val c = groupCard()
    c.addView(txt(
        "Что-то работает не так? Отправьте отчёт: версия, телефон и последние ошибки уйдут в поддержку.",
        12f, MUTE
    ).apply { layoutParams = lp(10) })
    c.addView(btnGrid(listOf(
        gbtn("Сообщить об ошибке", true) { openBugReport() },
        gbtn("Что нового", false) { showWhatsNew(true) }
    ), 10))
    c.addView(txt(
        "Виджет VPN: удерживайте палец на рабочем столе → «Виджеты» → PharmShop.",
        12f, MUTE
    ).apply { layoutParams = lp(0) })
    body.addView(c)
}

// ---------- сброс настроек ----------

private fun MainActivity.resetBlock(body: LinearLayout) {
    sectionLabel(body, "Сброс")
    val c = groupCard()
    c.addView(txt(
        "Вернёт тему, размер текста и переключатели оформления и безопасности к исходным. " +
            "Заказы, PIN, вход в VPN, уведомления и обновления не затрагиваются.",
        12f, MUTE
    ).apply { layoutParams = lp(10) })
    c.addView(LinearLayout(this).apply {
        addView(chipTint("Сбросить настройки", RED) {
            confirmSheet("Сбросить настройки?", "Внешний вид и переключатели вернутся к исходным значениям.", "Сбросить", danger = true) {
                Store.resetSettings()
                applySecureScreen()
                applyAppearance()
            }
        })
    })
    body.addView(c)
}

// ---------- уведомления ----------

internal fun MainActivity.openNotificationSettings() {
    try {
        startActivity(
            Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("android.provider.extra.APP_PACKAGE", packageName)
                .putExtra("app_package", packageName)
                .putExtra("app_uid", applicationInfo.uid)
        )
    } catch (e: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e2: Exception) {
            toast("Не удалось открыть настройки")
        }
    }
}

private fun MainActivity.notificationsBlock(body: LinearLayout) {
    sectionLabel(body, "Уведомления")
    val c = groupCard()
    c.addView(switchRow("Push-уведомления", "Ответы поддержки и статусы заказов", Store.pushEnabled) { on ->
        Store.pushEnabled = on
        if (on) {
            PollWorker.schedule(this)
            if (Build.VERSION.SDK_INT >= 33 && !Notifier.enabled(this)) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            toast("Сообщим об ответе поддержки и статусе заказов")
        } else {
            PollWorker.cancel(this)
            toast("Push-уведомления выключены")
        }
    })
    c.addView(switchRow("VPN: подписка заканчивается", "За 3 дня и за 1 день до конца. Нужны push и вход в VPN", Store.vpnNotifyExpiry) { on ->
        Store.vpnNotifyExpiry = on
    })
    c.addView(switchRow("VPN: трафик заканчивается", "Когда использовано 90% и когда трафик закончился", Store.vpnNotifyTraffic) { on ->
        Store.vpnNotifyTraffic = on
    })
    c.addView(switchRow("Напоминания о покупке", "Редкие напоминания про звёзды и Premium, не ночью", Reminders.enabled(this)) { on ->
        Reminders.setEnabled(this, on)
    })
    if (!Notifier.enabled(this)) {
        c.addView(txt("⚠ Уведомления выключены в системе телефона — сообщения не придут.", 12f, AMBER).apply {
            layoutParams = lp(8)
        })
    }
    c.addView(LinearLayout(this).apply {
        addView(chip("Системные настройки уведомлений", false) { openNotificationSettings() })
    })
    body.addView(c)
}

// ---------- защита входа ----------

private fun MainActivity.securityBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Вход в приложение")
    val c = groupCard()
    c.addView(txt("PIN-код и отпечаток", 16f, TEXT, true).apply { layoutParams = lp(2) })
    c.addView(txt("Защитите историю заказов и чат с поддержкой от посторонних", 12f, MUTE).apply { layoutParams = lp(10) })
    val mode = if (Store.lockMode != 0 && Store.hasPin) Store.lockMode.coerceIn(1, 2) else 0
    c.addView(segmented(listOf("Выкл", "PIN", "Отпечаток"), mode) { i -> chooseLock(i, again) })

    if (mode != 0) {
        c.addView(switchRow("Блокировать сразу при сворачивании", "Вход нужен каждый раз, без ожидания", Store.lockOnLeave) { on ->
            Store.lockOnLeave = on
            again()
        })
        c.addView(switchRow("Блокировать при выключении экрана", "Когда гасите экран телефона кнопкой", Store.lockOnScreenOff) { on ->
            Store.lockOnScreenOff = on
        })
        if (!Store.lockOnLeave) {
            c.addView(caption("Просить вход, если приложение было свёрнуто дольше"))
            val delays = listOf(15, 60, 300)
            val di = delays.indexOf(Store.lockDelaySec).let { if (it < 0) 1 else it }
            c.addView(segmented(listOf("15 сек", "1 мин", "5 мин"), di) { i ->
                Store.lockDelaySec = delays[i]
                again()
            })
        }
        c.addView(LinearLayout(this).apply {
            addView(chip("Сменить PIN", false) { showPinSetup(true) { _ -> reopenSettings() } })
        })
        if (mode == 2) c.addView(txt("PIN остаётся запасным входом: если палец не распознан, введите его.", 12f, MUTE).apply {
            layoutParams = lp(0).apply { topMargin = dp(8) }
        })
    }
    body.addView(c)
}

private fun MainActivity.chooseLock(i: Int, again: () -> Unit) {
    when (i) {
        0 -> {
            Store.lockMode = 0
            Store.clearPin()
            AppState.unlocked = true
            again()
        }
        1 -> {
            if (Store.hasPin) {
                Store.lockMode = 1
                AppState.unlocked = true
                again()
            } else {
                showPinSetup(false) { ok ->
                    if (ok) { Store.lockMode = 1; AppState.unlocked = true }
                    reopenSettings()
                }
            }
        }
        else -> {
            if (!biometricAvailable()) {
                confirmSheet(
                    "Отпечаток не настроен",
                    "Добавьте отпечаток пальца в настройках телефона (Безопасность → Биометрия), затем вернитесь сюда. Пока можно использовать PIN.",
                    "Открыть настройки"
                ) {
                    try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (e: Exception) { toast("Не удалось открыть настройки") }
                }
                return
            }
            // сначала запасной PIN, затем проверяем, что отпечаток действительно срабатывает
            val confirmBio = {
                askBiometric("Включить вход по отпечатку", "Приложите палец, чтобы подтвердить") {
                    Store.lockMode = 2
                    AppState.unlocked = true
                    reopenSettings()
                }
            }
            if (Store.hasPin) confirmBio()
            else showPinSetup(false) { ok ->
                reopenSettings()
                if (ok) confirmBio()
            }
        }
    }
}

// ---------- обновления ----------

private fun MainActivity.updatesBlock(body: LinearLayout, again: () -> Unit) {
    sectionLabel(body, "Обновления")
    val c = groupCard()
    val ok = Updater.configured
    c.addView(switchRow(
        "Обновлять в фоне по Wi-Fi",
        if (ok) "Новая версия скачается сама, когда телефон на Wi-Fi. Android требует подтвердить установку — останется нажать «Установить»."
        else "Доступно только в сборке, собранной на GitHub",
        Store.wifiUpdate && ok, ok
    ) { on ->
        Store.wifiUpdate = on
        if (on) UpdateWorker.schedule(this) else UpdateWorker.cancel(this)
    })
    val pending = Store.pendingUpdateCode > BuildConfig.VERSION_CODE
    if (pending) {
        c.addView(txt(
            "Готова к установке: ${Store.pendingUpdateName.ifEmpty { "сборка ${Store.pendingUpdateCode}" }}",
            13f, GREEN, true
        ).apply { layoutParams = lp(8) })
    }
    c.addView(LinearLayout(this).apply {
        if (pending) addView(chip("Установить", true) { installPendingUpdate() })
        addView(chip("Проверить обновления", false) { checkForUpdate(false) })
    })
    body.addView(c)
}

// ---------- какие данные собирает приложение ----------

/** Группа в плашке данных: цветной заголовок и список пунктов. */
private fun MainActivity.dataGroup(c: LinearLayout, emoji: String, title: String, color: Int, lines: List<String>) {
    c.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(mix(color, 0.10f), 14, edge(color))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(10)
        addView(txt("$emoji  $title", 15f, color, true).apply { layoutParams = lp(6) })
        for (l in lines) addView(txt("•  $l", 13f, TEXT).apply { layoutParams = lp(3) })
    })
}

/** Блок внизу «Прочее»: одна кнопка, по которой открывается подробный рассказ о собираемых данных. */
private fun MainActivity.dataBlock(body: LinearLayout) {
    sectionLabel(body, "Данные приложения")
    val c = groupCard()
    c.addView(txt(
        "Что приложение собирает, когда это уходит администратору и что не собирается никогда.",
        13f, MUTE
    ).apply { layoutParams = lp(12) })
    c.addView(LinearLayout(this).apply {
        addView(chip("Какие данные собирает приложение", false) { showDataInfo() })
    })
    c.addView(LinearLayout(this).apply {
        layoutParams = lp(0).apply { topMargin = dp(8) }
        addView(chip("Приватность чатов", false) { showChatPrivacy() })
    })
    body.addView(c)
}

/** Окно с подробностями: что именно собирает приложение, когда это уходит администратору и что не собирается. */
private fun MainActivity.showDataInfo() {
    sheet("Какие данные собирает приложение", scroll = true) { box, close ->
        box.addView(txt(
            "Мы собираем только то, что помогает быстрее решать проблемы и понимать, чем пользуются в приложении. " +
                "Эти сведения хранятся на телефоне и уходят администратору вместе с вашим обращением в поддержку: " +
                "сообщением в чате или отчётом об ошибке. Без обращения диагностика и аналитика не отправляются.",
            13f, MUTE
        ).apply { layoutParams = lp(12) })

        dataGroup(box, "📱", "Диагностика", BLUE, listOf(
            "версия приложения и версия Android",
            "модель телефона",
            "язык и часовой пояс",
            "дата первого и последнего запуска, число запусков",
            "ошибки и сбои приложения без личных данных: адреса, e-mail и токены из текста вырезаются"
        ))
        dataGroup(box, "📊", "Использование", GREEN, listOf(
            "сколько раз открывали приложение, сколько времени проводите в нём и в какие дни",
            "какие разделы и функции открываете: ТГ, VPN, профиль, звёзды, Premium. Только счётчики, без содержимого",
            "сколько заказов оформлено через приложение",
            "какие настройки включены: тема, защита входа, push-уведомления. Сам PIN не передаётся"
        ))
        dataGroup(box, "🌐", "Что видит сервер", AMBER, listOf(
            "IP-адрес и страна или город: определяются автоматически, когда вы пишете в поддержку",
            "ник в Telegram и номер последнего заказа, если они указаны",
            "заказы из приложения (получатель, количество, сумма, статус): отправляются, чтобы поддержка видела их в админ-панели",
            "токен push-уведомлений устройства, если они включены",
            "ник и сообщения общего и личных чатов: личные чаты остаются в приложении, администрация может получить к ним доступ",
            "аватарка профиля (уменьшенная копия 128×128), но только если вы выбрали ник в чате: её видят участники чата"
        ))
        dataGroup(box, "🚫", "Не собираем", RED, listOf(
            "PIN и пароли, ссылку подписки VPN, данные банковских карт",
            "контакты, геолокацию, список установленных приложений, рекламный идентификатор",
            "другие фото из галереи и оригинал аватарки: на сервер уходит только маленькая копия для чата"
        ))

        box.addView(btnGrid(listOf(
            gbtn("Показать, что уйдёт сейчас", false) { close(); showDataPreview() },
            gbtn("Закрыть", true) { close() }
        ), 0))
    }
}

/** Окно с тем, что в этот момент добавится к обращению в поддержку. */
private fun MainActivity.showDataPreview() {
    val text = try { Diag.render(Diag.payload(this)) } catch (e: Exception) { "Не удалось собрать данные" }
    sheet("Что уйдёт сейчас", scroll = true) { box, close ->
        box.addView(txt("Так выглядят данные, которые добавятся к вашему следующему обращению в поддержку.", 13f, MUTE).apply { layoutParams = lp(10) })
        box.addView(txt(text, 12f, TEXT).apply {
            setTextIsSelectable(true)
            background = shape(CARD, 12, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(14)
        })
        box.addView(btnGrid(listOf(gbtn("Закрыть", true) { close() }), 0))
    }
}
