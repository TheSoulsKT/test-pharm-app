package ru.pharmshop.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Главный экран и карточки меню.

// ---------- карточки главного меню ----------
internal fun MainActivity.menuWide(icon: Ic, title: String, sub: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
    val subView = txt(sub, 13f, MUTE)
    val ic = IconView(this, icon, BLUE).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) }
    }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(txt(title, 17f, TEXT, true))
    texts.addView(subView)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(CARD, 18, LINE)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = lp(10).apply { height = CARD_H }
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
    }
    row.addView(ic)
    row.addView(texts)
    return Pair(row, subView)
}

internal fun MainActivity.tile(icon: Ic, title: String, sub: String, onClick: () -> Unit): LinearLayout {
    val ic = IconView(this, icon, BLUE).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }
    val spacer = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val t = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ripple(CARD, 18, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
    }
    t.addView(ic)
    t.addView(spacer)
    t.addView(txt(title, 16f, TEXT, true))
    if (sub.isNotEmpty()) t.addView(txt(sub, 12f, MUTE))
    return t
}

internal fun MainActivity.gridRow(a: View, b: View): LinearLayout {
    a.layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f)
        .apply { rightMargin = dp(5) }
    b.layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f)
        .apply { leftMargin = dp(5) }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(10)
        addView(a)
        addView(b)
    }
}

// ---------- главный экран ----------
internal fun MainActivity.rateText(): String {
    val r = cfg?.optString("stars_rate") ?: ""
    return if (r.isNotEmpty()) "$r ₽/шт" else "Покупка за рубли"
}

/** Новогоднее оформление «по умолчанию» включено с 15 ноября по 20 января (если не выбрана другая тема). */
internal fun MainActivity.isNewYear(): Boolean = Theme.newYearWindow()

/**
 * Раздел меню с учётом настроек из админ-панели («Разделы»): включён/выключен, свои надписи и ссылка.
 * offSub — подпись, когда раздел выключен и своей подписи не задано.
 */
internal fun MainActivity.sec(key: String, title: String, sub: String, defEnabled: Boolean, offSub: String = sub): SecView {
    val s = Remote.section(key)
    val enabled = if (s != null && s.has("enabled")) s.optBoolean("enabled") else defEnabled
    val customSub = s?.optString("sub").orEmpty()
    return SecView(
        title = s?.optString("title").orEmpty().ifEmpty { title },
        sub = customSub.ifEmpty { if (enabled) sub else offSub },
        enabled = enabled,
        url = s?.optString("url").orEmpty(),
        message = s?.optString("message").orEmpty()
    )
}

/** Заголовок раздела главного меню («Купить», «Скоро»). */
internal fun MainActivity.sectionTitle(t: String, top: Int): TextView =
    txt(t, 20f, TEXT, true).apply { layoutParams = lp(10).apply { topMargin = dp(top) } }

internal fun MainActivity.showHome() {
    onHome = true
    val col = screen(0)
    val sid = screenId

    // шапка: логотип и название (профиль, поддержка и настройки — в нижней панели)
    val logo = LogoView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) }
        setOnLongClickListener { showAdminEntry(); true }
    }
    val title = TextView(this).apply {
        textSize = 30f
        setTypeface(typeface, Typeface.BOLD)
        val sb = SpannableStringBuilder("Pharm")
        sb.setSpan(ForegroundColorSpan(TEXT), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("Shop")
        sb.setSpan(ForegroundColorSpan(BLUE), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = sb
    }
    val titles = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    titles.addView(title)
    titles.addView(txt("Telegram магазин • Stars • NFT • Подарки", 12f, MUTE))

    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(22)
        addView(logo)
        addView(titles)
    }
    col.addView(head)
    // плашка выбранной темы (у «Стандарта» — новогодняя в сезон)
    (if (Store.hideBanner) null else Theme.banner())?.let { bn ->
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(bn.c1, bn.c2, 18)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = lp(16)
            addView(txt(bn.title, 17f, bn.on, true))
            addView(txt(bn.sub, 13f, bn.onSub))
        })
    }
    // объявление из админ-панели
    Remote.announcement()?.let { a ->
        val warn = a.optString("level") == "warn"
        val accent = if (warn) AMBER else BLUE
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(mix(accent, 0.16f), 16, accent)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = lp(14)
            addView(txt(if (warn) "⚠ Важно" else "📢 Объявление", 13f, accent, true))
            addView(txt(a.optString("text"), 15f, TEXT))
        })
    }
    col.addView(tv("Выберите, что вам нужно", 15f, MUTE))

    // разделы можно включать и выключать удалённо (админ-панель → «Разделы»), а ещё скрывать у себя (Настройки → Оформление)
    val hiddenSet = Store.hiddenSections.split(',').filter { it.isNotEmpty() }.toSet()
    class Entry(val key: String, val icon: Ic, val sv: SecView, val wide: Boolean, val action: (SecView) -> Unit)
    val external = { v: SecView -> openUrl(v.url.ifEmpty { BOT_URL }) }
    val entries = listOf(
        Entry("stars", Ic.STAR, sec("stars", "Звёзды", rateText(), true, "Временно недоступно"), true, { _ -> showStars() }),
        Entry("premium", Ic.CROWN, sec("premium", "Купить Premium", "Покупка подписки", true, "Временно недоступно"), true, { _ -> showPremium() }),
        Entry("gifts", Ic.GIFT, sec("gifts", "Обычные подарки", "Покупка за рубли", false), true, external),
        Entry("nft_buy", Ic.BOX, sec("nft_buy", "Купить NFT", "", false), false, external),
        Entry("nft_rent", Ic.KEY, sec("nft_rent", "Аренда NFT", "", false), false, external),
        Entry("stars_sell", Ic.DB, sec("stars_sell", "Продать звёзды", "", false), false, external),
        Entry("nft_sell", Ic.AT, sec("nft_sell", "Скупка NFT", "", false), false, external)
    ).filter { it.key !in hiddenSet }
    var starsSub: TextView? = null
    fun click(e: Entry) {
        if (e.sv.enabled) e.action(e.sv) else toast(e.sv.message.ifEmpty { "Скоро будет доступно" })
    }
    fun wideCard(e: Entry): LinearLayout {
        val (card, subView) = menuWide(e.icon, e.sv.title, e.sv.sub) { click(e) }
        // курс подставится после загрузки конфигурации, если подпись не переопределена удалённо
        if (e.key == "stars" && e.sv.enabled && Remote.section("stars")?.optString("sub").isNullOrEmpty()) starsSub = subView
        return card
    }
    val on = entries.filter { it.sv.enabled }
    val off = entries.filter { !it.sv.enabled }
    if (on.isNotEmpty()) {
        col.addView(sectionTitle("Купить", 4))
        for (e in on) col.addView(wideCard(e))
    }
    if (off.isNotEmpty() && !Store.hideSoon) {
        col.addView(sectionTitle("Скоро", if (on.isNotEmpty()) 10 else 4))
        for (e in off.filter { it.wide }) col.addView(wideCard(e))
        val tiles = off.filter { !it.wide }
        var i = 0
        while (i < tiles.size) {
            val t1 = tiles[i]
            val first = tile(t1.icon, t1.sv.title, t1.sv.sub) { click(t1) }
            val second: View = if (i + 1 < tiles.size) {
                val t2 = tiles[i + 1]
                tile(t2.icon, t2.sv.title, t2.sv.sub) { click(t2) }
            } else View(this).apply { visibility = View.INVISIBLE }
            col.addView(gridRow(first, second))
            i += 2
        }
    }

    // кнопка бота
    val botIcon = IconView(this, Ic.SEND, Color.BLACK).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(8) }
    }
    val bot = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ripple(BLUE, 20, BLUE)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = lp(14)
        isClickable = true
        setOnClickListener { openUrl(BOT_URL) }
        pressable(this)
    }
    bot.addView(botIcon)
    bot.addView(txt("Заказать в боте", 13f, Color.BLACK))
    bot.addView(txt("@pharmaishop_bot", 20f, Color.BLACK, true))
    col.addView(bot)

    col.addView(txt("Цены на услуги могут меняться в зависимости от курса", 12f, MUTE).apply {
        gravity = Gravity.CENTER
        layoutParams = lp(0)
    })
    appFooter(col)

    floatForever(logo)
    riseIn(col)

    // «Стандарт»: в новогодний сезон — снегопад, как раньше (у остальных тем фон добавляет screen())
    if (Theme.skin == Skin.CLASSIC && isNewYear() && Store.fxEnabled && FxView.allowed(this)) {
        findViewById<ViewGroup>(android.R.id.content).addView(
            FxView(this, Fx.SNOW),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    // объявление, разделы и обязательное обновление — спрашиваем у сервера
    refreshRemote()

    // один раз за запуск тихо проверяем обновления
    if (!updateChecked) {
        updateChecked = true
        checkForUpdate(true)
    }

    // подтягиваем курс для карточки «Звёзды»
    if (cfg == null) {
        io.execute {
            try {
                val c = Api.config()
                cfg = c
                ui { if (sid == screenId) starsSub?.text = rateText() }
            } catch (e: Exception) {
                // оставляем текст по умолчанию
            }
        }
    }
}
