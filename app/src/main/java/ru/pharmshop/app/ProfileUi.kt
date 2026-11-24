package ru.pharmshop.app

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// Оформление профиля: карточка с обложкой, фото и ником, достижения, плитки статистики и аккуратные окна редактирования.

/** Обложки карточки профиля. Номер хранится в Store.profileCover, 0 — цвет берётся из темы. */
internal class ProfileCover(val id: Int, val title: String, val c1: Int, val c2: Int)

internal val PROFILE_COVERS = listOf(
    ProfileCover(1, "Закат", Color.parseColor("#FF7E5F"), Color.parseColor("#FEB47B")),
    ProfileCover(2, "Океан", Color.parseColor("#2193B0"), Color.parseColor("#6DD5ED")),
    ProfileCover(3, "Лес", Color.parseColor("#11998E"), Color.parseColor("#38EF7D")),
    ProfileCover(4, "Ночь", Color.parseColor("#0F2027"), Color.parseColor("#2C5364")),
    ProfileCover(5, "Сирень", Color.parseColor("#8E2DE2"), Color.parseColor("#4A00E0")),
    ProfileCover(6, "Малина", Color.parseColor("#F953C6"), Color.parseColor("#B91D73"))
)

/** Пара цветов выбранной обложки (по умолчанию — акцент темы, уходящий в цвет фона). */
private fun MainActivity.coverColors(): Pair<Int, Int> {
    val c = PROFILE_COVERS.firstOrNull { it.id == Store.profileCover }
    return if (c != null) c.c1 to c.c2 else BLUE to ColorUtils.blendARGB(BLUE, BG, 0.5f)
}

private fun MainActivity.coverDrawable(radius: Int = 0): GradientDrawable {
    val (a, b) = coverColors()
    return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(radius).toFloat() }
}

private fun ovalRipple(fill: Int, ring: Int, ringPx: Int, rippleColor: Int): RippleDrawable {
    val base = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (ringPx > 0) setStroke(ringPx, ring)
    }
    val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask)
}

/** Круглая кнопка с иконкой-эмодзи (значок на фото, карандаш у ника). */
private fun MainActivity.roundBtn(icon: String, sizeDp: Int, fill: Int, ring: Int, desc: String, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = icon
        textSize = (sizeDp * 0.42f).coerceIn(11f, 18f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = ovalRipple(fill, ring, dp(2), Theme.rippleColor)
        contentDescription = desc
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

private fun MainActivity.ovalOutline(v: View) {
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
    }
    v.clipToOutline = true
}

/** Фото или кружок с первой буквой ника. sizeDp — диаметр. */
internal fun MainActivity.avatarView(sizeDp: Int, user: String, letterSize: Float): View {
    val photo = if (hiddenData()) null else Avatar.bitmap()
    return if (photo != null) ImageView(this).apply {
        setImageBitmap(photo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        ovalOutline(this)
        contentDescription = "Аватарка"
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    } else TextView(this).apply {
        text = if (hiddenData()) "•" else user.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        textSize = letterSize
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BLUE) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}

/**
 * Полученные достижения плавно плавают на обложке. Из каждой «лесенки» берётся высший значок, всего не больше восьми.
 * Если анимированный фон выключен в настройках — значки просто лежат на месте. При скрытых данных не показываются.
 */
private fun MainActivity.floatBadges(cover: FrameLayout) {
    if (hiddenData()) return
    val orders = Store.orders()
    val top = badgeList(orders, paidStars(orders)).filter { it.got }.groupBy { it.group }.values.map { it.last() }
    val icons = top.reversed().take(8)           // сначала самые «личные»: помощь приложению, звёзды
    if (icons.isEmpty()) return

    val size = dp(26)
    val views = icons.map { b ->
        TextView(this).apply {
            text = b.emoji
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 0.92f
            contentDescription = b.title
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(Color.WHITE, 52)) }
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.TOP)
        }
    }
    views.forEach { cover.addView(it) }

    var phase = 0f
    val n = views.size
    val loopMs = 16000L
    fun place() {
        val w = cover.width
        if (w == 0) return
        for ((i, v) in views.withIndex()) {
            val baseX = (i + 0.5f) / n * w - size / 2f
            val baseY = if (i % 2 == 0) dp(6).toFloat() else dp(24).toFloat()      // два «этажа» над фото
            val shift = i * 0.37f
            // целое число колебаний за круг, поэтому движение не дёргается при перезапуске
            val tx = sin(2 * PI * (phase * (1 + i % 2) + shift)).toFloat()
            val ty = sin(2 * PI * (phase * (2 + i % 3) + shift * 1.3f)).toFloat()
            v.translationX = baseX + dp(9) * tx
            v.translationY = baseY + dp(5) * ty
            v.rotation = 9f * sin(2 * PI * (phase + shift)).toFloat()
        }
    }
    cover.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    place()

    if (!Store.fxEnabled) return
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = loopMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; place() }
    }
    cover.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (!anim.isStarted) anim.start() }
        override fun onViewDetachedFromWindow(v: View) { anim.cancel() }
    })
    if (cover.isAttachedToWindow) anim.start()
}

/**
 * Главная карточка профиля: обложка, фото с кнопкой камеры, ник с карандашом, описание.
 * Если включено «Скрывать суммы и ники» — нажатие на карточку показывает и прячет данные.
 */
internal fun MainActivity.profileHero(body: LinearLayout, sid: Int, user: String): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 22, LINE)
        clipToOutline = true
        layoutParams = lp(14)
    }

    // обложка + кнопка смены обложки
    val cover = FrameLayout(this).apply {
        background = coverDrawable()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
    }
    cover.addView(View(this).apply {   // лёгкий узор: большой полупрозрачный круг
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(Color.WHITE, 36)) }
        layoutParams = FrameLayout.LayoutParams(dp(150), dp(150), Gravity.END or Gravity.TOP).apply {
            topMargin = -dp(60); rightMargin = -dp(30)
        }
    })
    cover.addView(View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(Color.WHITE, 24)) }
        layoutParams = FrameLayout.LayoutParams(dp(90), dp(90), Gravity.START or Gravity.BOTTOM).apply {
            bottomMargin = -dp(46); leftMargin = dp(20)
        }
    })
    floatBadges(cover)
    cover.addView(roundBtn("🎨", 36, ColorUtils.setAlphaComponent(Color.BLACK, 110), Color.TRANSPARENT, "Сменить обложку") {
        coverSheet(body, sid)
    }.apply {
        layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.TOP).apply { topMargin = dp(12); rightMargin = dp(12) }
    })
    card.addView(cover)

    // фото поверх обложки, на нём значок камеры
    val ring = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD) }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER_HORIZONTAL)
        isClickable = true
        setOnClickListener { photoSheet(body, sid, user) }
        pressable(this)
    }
    ring.addView(avatarView(88, user, 34f).apply {
        layoutParams = FrameLayout.LayoutParams(dp(88), dp(88))
    })
    val photoWrap = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(112), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(48) }
        addView(ring)
        addView(roundBtn("📷", 32, BLUE, CARD, "Изменить фото") { photoSheet(body, sid, user) }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END or Gravity.BOTTOM).apply { bottomMargin = dp(2) }
        })
    }
    card.addView(photoWrap)

    // ник и карандаш
    val nameRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); leftMargin = dp(16); rightMargin = dp(16)
        }
    }
    nameRow.addView(txt(if (user.isEmpty()) "Telegram не указан" else hiddenNick(user), 20f, TEXT, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
    })
    nameRow.addView(roundBtn("✏️", 30, mix(BLUE, 0.22f), edge(BLUE), "Изменить ник") { editUsername(body, sid) })
    card.addView(nameRow)

    card.addView(txt(
        if (user.isEmpty()) "Укажите ник: он подставится в заказы" else "Ник указан вручную, ботом не подтверждён",
        12f, MUTE
    ).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2); leftMargin = dp(16); rightMargin = dp(16)
        }
    })

    // ник в чате: тот же человек, что и в общем чате (нажатие открывает чат, если ника ещё нет — выбор ника)
    val chatNick = Store.chatNick
    card.addView(txt(
        if (chatNick.isNotEmpty()) "💬 Ник в чате: " + (if (hiddenData()) "••••••" else chatNick) else "💬 Выбрать ник в чате",
        13f, if (chatNick.isNotEmpty()) TEXT else BLUE, chatNick.isEmpty()
    ).apply {
        gravity = Gravity.CENTER
        background = ripple(mix(BLUE, 0.10f), 12)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        isClickable = true
        setOnClickListener { showCommunity() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
        }
    })

    // описание: текст или приглашение его добавить
    val hasBio = Store.bio.isNotEmpty()
    card.addView(txt(
        if (hasBio) (if (hiddenData()) "••••••" else Store.bio) else "＋ Добавить пару слов о себе",
        14f, if (hasBio) TEXT else BLUE, !hasBio
    ).apply {
        gravity = Gravity.CENTER
        background = if (hasBio) ripple(Color.TRANSPARENT, 12) else ripple(mix(BLUE, 0.14f), 12, edge(BLUE))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        setOnClickListener { editBio(body, sid) }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(18)
            leftMargin = dp(16); rightMargin = dp(16)
        }
    })

    if (Store.hideSensitive) {
        card.isClickable = true
        card.setOnClickListener { AppState.revealed = !AppState.revealed; renderProfile(body, sid) }
    }
    return card
}

/** Плитка статистики: цветной значок, число и подпись. */
internal fun MainActivity.statCard(emoji: String, value: String, label: String, color: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = shape(mix(color, 0.12f), 18, edge(color))
        setPadding(dp(6), dp(14), dp(6), dp(12))
        addView(txt(emoji, 22f).apply { gravity = Gravity.CENTER; layoutParams = lp(4) })
        addView(txt(value, 17f, TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
        addView(txt(label, 11f, MUTE).apply { gravity = Gravity.CENTER })
    }

/** Одно достижение. [group] — «лесенка» значков одного вида (звёзды, отчёты…): на обложке плавает только высший из полученных. */
/** [cur] из [need] — прогресс для достижений со счётчиком (cur = -1 — счётчика нет). */
internal class BadgeInfo(
    val emoji: String, val title: String, val hint: String, val got: Boolean, val group: String,
    val cur: Int = -1, val need: Int = 0
)

private fun fmtNum(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

internal fun paidOrders(orders: List<org.json.JSONObject>): List<org.json.JSONObject> =
    orders.filter { val k = statusKind(effStatus(it)); k == 1 || k == 2 }

/** Сколько звёзд куплено в оплаченных заказах (Premium не считается). */
internal fun paidStars(orders: List<org.json.JSONObject>): Int =
    paidOrders(orders).filter { it.optString("product") != "premium" }.sumOf { it.optInt("quantity") }

/** Все достижения. Открываются сами по тому, что уже есть в приложении; внутри «лесенки» идут по возрастанию. */
internal fun MainActivity.badgeList(orders: List<org.json.JSONObject>, stars: Int): List<BadgeInfo> {
    val paid = paidOrders(orders)
    val paidCount = paid.size
    val premiumBought = paid.any { it.optString("product") == "premium" }
    val reports = Store.bugReports
    val solved = Store.supportSolved
    val list = ArrayList<BadgeInfo>()

    // покупки
    list += BadgeInfo("🛒", "Первый заказ", "Оформите любой заказ", orders.isNotEmpty(), "order")
    list += BadgeInfo("🧾", "5 заказов", "Оплатите 5 заказов · сейчас $paidCount", paidCount >= 5, "order", paidCount, 5)
    list += BadgeInfo("📦", "15 заказов", "Оплатите 15 заказов · сейчас $paidCount", paidCount >= 15, "order", paidCount, 15)
    for ((emoji, need) in listOf("⭐" to 500, "🌟" to 1000, "💫" to 2500, "✨" to 5000, "🏆" to 10000)) {
        list += BadgeInfo(emoji, "${fmtNum(need)} звёзд", "Купите звёзд на ${fmtNum(need)} и больше · сейчас ${fmtNum(stars)}", stars >= need, "stars", stars, need)
    }
    list += BadgeInfo("👑", "Premium", "Оплатите подписку Premium", premiumBought, "premium")

    // приложение
    list += BadgeInfo("🛡", "VPN", "Войдите в личный кабинет VPN", VpnApi.loggedIn(), "vpn")
    list += BadgeInfo("🔐", "Под защитой", "Включите PIN или отпечаток", lockEnabled(), "lock")
    list += BadgeInfo("📸", "С фото", "Добавьте фото профиля", Avatar.has(), "photo")
    list += BadgeInfo("🎨", "Свой стиль", "Выберите обложку профиля, оформление или свой цвет", Store.profileCover != 0 || Store.skin != 0 || Store.accentId != 0, "style")
    list += BadgeInfo("✏️", "О себе", "Добавьте пару слов о себе в профиль", Store.bio.isNotEmpty(), "bio")

    // помощь приложению
    for ((emoji, title, need) in listOf(Triple("🐞", "Охотник за багами", 1), Triple("🔧", "Тестировщик", 3), Triple("🏅", "Золотой тестер", 10))) {
        list += BadgeInfo(emoji, title,
            "Отправьте отчёт о реальной ошибке: Настройки → Прочее → «Сообщить об ошибке». Засчитывается не чаще раза в сутки · сейчас $reports из $need",
            reports >= need, "bugs", reports, need)
    }
    for ((emoji, title, need) in listOf(Triple("💬", "Решённый вопрос", 1), Triple("🙌", "Друг проекта", 3), Triple("💎", "Опора проекта", 5))) {
        list += BadgeInfo(emoji, title,
            "Задайте вопрос в поддержке: когда его решат, значок откроется · сейчас $solved из $need",
            solved >= need, "help", solved, need)
    }

    // общение в чате (число сообщений приходит с сервера)
    val chatMsgs = Store.chatMsgs
    for ((emoji, title, need) in listOf(Triple("🗨", "Первое слово", 1), Triple("🎙", "Общительный", 25), Triple("🔥", "Душа чата", 100))) {
        list += BadgeInfo(emoji, title,
            "Пишите в общем чате и личных сообщениях: вкладка «Чат» · сейчас $chatMsgs из $need",
            chatMsgs >= need, "chat", chatMsgs, need)
    }
    return list
}

/** Собраны ли все достижения до одного: за это в чате выдаётся зелёная корона. */
internal fun MainActivity.allBadgesGot(): Boolean {
    val orders = Store.orders()
    val all = badgeList(orders, paidStars(orders))
    return all.isNotEmpty() && all.all { it.got }
}

/** Заголовок раздела: «Достижения · 7 из 20». */
internal fun MainActivity.achievementsTitle(orders: List<org.json.JSONObject>, stars: Int): String {
    val all = badgeList(orders, stars)
    return "Достижения · ${all.count { it.got }} из ${all.size}"
}

/** Достижения: полученные первыми. Нажатие подсказывает, как получить остальные. */
internal fun MainActivity.achievementsRow(orders: List<org.json.JSONObject>, stars: Int): View {
    val all = badgeList(orders, stars)
    val list = all.sortedByDescending { it.got }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (b in list) {
        val col = if (b.got) GREEN else MUTE
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(if (b.got) mix(GREEN, 0.12f) else Color.TRANSPARENT, 16, if (b.got) edge(GREEN) else LINE)
            setPadding(dp(8), dp(12), dp(8), dp(10))
            alpha = if (b.got) 1f else 0.55f
            isClickable = true
            setOnClickListener { showBadgeSheet(b, all) }
            pressable(this)
            layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            addView(txt(b.emoji, 24f).apply { gravity = Gravity.CENTER; layoutParams = lp(4) })
            addView(txt(b.title, 12f, if (b.got) TEXT else MUTE, b.got).apply { gravity = Gravity.CENTER; maxLines = 2 })
            addView(txt(if (b.got) "✓ есть" else "закрыто", 10f, col).apply { gravity = Gravity.CENTER })
        })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = lp(18)
        addView(row)
    }
}

/** Крупная строка-ссылка: значок, заголовок и пояснение. */
internal fun MainActivity.linkTile(emoji: String, title: String, sub: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(CARD, 18, LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(18)
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
        addView(txt(emoji, 20f).apply {
            gravity = Gravity.CENTER
            background = shape(mix(BLUE, 0.16f), 12)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(this@linkTile).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(txt(title, 15f, TEXT, true))
            addView(txt(sub, 12f, MUTE))
        })
        addView(txt("›", 22f, MUTE))
    }

// ---------- окна редактирования ----------

/** Ник Telegram: 5–32 символа, латиница, цифры и «_». Подсказка только предупреждает, сохранить можно любой. */
private val TG_NICK = Regex("^[A-Za-z0-9_]{5,32}$")

/** Приводит ввод к нику: убирает @, пробелы и ссылку t.me/. */
internal fun cleanNick(s: String): String =
    s.trim().substringAfterLast('/').removePrefix("@").replace(" ", "").take(32)

internal fun MainActivity.editUsername(body: LinearLayout, sid: Int) {
    sheet("Ваш Telegram", focusInput = true) { box, close ->
        box.addView(txt("Ник подставится в заказы. Можно вставить @ник или ссылку t.me, лишнее уберём сами.", 13f, MUTE).apply {
            layoutParams = lp(12)
        })
        val field = EditText(this).apply {
            hint = "username"
            setText(Store.lastUser)
            setSelection(text.length)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            setTextColor(TEXT)
            setHintTextColor(MUTE)
            textSize = 17f * Theme.textScale
            background = null
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hintLine = txt("", 12f, MUTE).apply { layoutParams = lp(14) }
        fun check() {
            val n = cleanNick(field.text.toString())
            when {
                n.isEmpty() -> { hintLine.text = "Оставьте пустым, чтобы убрать ник"; hintLine.setTextColor(MUTE) }
                TG_NICK.matches(n) -> { hintLine.text = "✓ Ник выглядит правильно"; hintLine.setTextColor(GREEN) }
                else -> { hintLine.text = "У ников Telegram обычно 5–32 символа: латиница, цифры и «_»"; hintLine.setTextColor(AMBER) }
            }
        }
        val clear = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTE)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { field.setText("") }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(CARD, 14, edge(BLUE))
            setPadding(dp(14), 0, dp(8), 0)
            layoutParams = lp(8)
            addView(txt("@", 18f, BLUE, true).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) } })
            addView(field)
            addView(clear)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE; check() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        clear.visibility = if (field.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        check()
        box.addView(inputRow)
        box.addView(hintLine)
        box.addView(btnGrid(listOf(
            gbtn("Отмена", false) { close() },
            gbtn("Сохранить", true) {
                Store.lastUser = cleanNick(field.text.toString())
                close()
                if (sid == screenId) renderProfile(body, sid)
            }
        ), 0))
        field.requestFocus()
    }
}

/** Окно фото профиля: большой предпросмотр, выбор из галереи и удаление. */
internal fun MainActivity.photoSheet(body: LinearLayout, sid: Int, user: String) {
    sheet("Фото профиля") { box, close ->
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(12)
            background = coverDrawable(18)
            setPadding(dp(0), dp(22), dp(0), dp(22))
            addView(FrameLayout(this@photoSheet).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BG) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(avatarView(104, user, 40f))
            })
        })
        box.addView(txt("Фото обрезается по центру до квадрата. Оригинал остаётся на телефоне; если вы выбрали ник в чате, маленькая копия будет видна в чате как ваша аватарка.", 13f, MUTE).apply {
            layoutParams = lp(14)
        })
        val btns = ArrayList<TextView>()
        btns.add(gbtn(if (Avatar.has()) "Выбрать другое" else "Выбрать из галереи", true) { close(); pickAvatar(body, sid) })
        if (Avatar.has()) btns.add(gbtn("Убрать фото", false) {
            close()
            Avatar.remove()
            syncMyAvatar()
            if (sid == screenId) renderProfile(body, sid)
        })
        box.addView(btnGrid(btns, 0))
    }
}

/** Выбор обложки: образцы в два ряда, выбранная отмечена галочкой. */
internal fun MainActivity.coverSheet(body: LinearLayout, sid: Int) {
    sheet("Обложка профиля") { box, close ->
        box.addView(txt("Цвет верхней части карточки профиля. «По теме» подстраивается под выбранное оформление.", 13f, MUTE).apply { layoutParams = lp(12) })
        val options = listOf(ProfileCover(0, "По теме", BLUE, ColorUtils.blendARGB(BLUE, BG, 0.5f))) + PROFILE_COVERS
        var i = 0
        while (i < options.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(10) }
            for (k in 0 until 2) {
                val c = options.getOrNull(i + k)
                if (c == null) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f)); continue }
                val sel = Store.profileCover == c.id
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c.c1, c.c2)).apply {
                        cornerRadius = corner(16)
                        setStroke(dp(if (sel) 3 else 1), if (sel) TEXT else LINE)
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        Store.profileCover = c.id
                        close()
                        if (sid == screenId) renderProfile(body, sid)
                    }
                    pressable(this)
                    layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                        if (k == 0) rightMargin = dp(5) else leftMargin = dp(5)
                    }
                    addView(txt(c.title + if (sel) "  ✓" else "", 13f, Color.WHITE, true))
                })
            }
            box.addView(row)
            i += 2
        }
    }
}

/** Описание профиля: поле с живым счётчиком символов. */
internal fun MainActivity.editBio(body: LinearLayout, sid: Int) {
    sheet("О себе", focusInput = true) { box, close ->
        box.addView(txt("Пара слов в карточке профиля. Видно только вам, хранится на этом телефоне.", 13f, MUTE).apply { layoutParams = lp(12) })
        val field = input("Например: беру звёзды для друзей", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setText(Store.bio)
            setSelection(text.length)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(120))
            layoutParams = lp(6)
        }
        val counter = txt("", 12f, MUTE).apply { gravity = Gravity.END; layoutParams = lp(14) }
        fun count() { counter.text = "${field.text.length} / 120" }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { count() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        count()
        box.addView(field)
        box.addView(counter)
        val btns = arrayListOf(gbtn("Отмена", false) { close() },
            gbtn("Сохранить", true) {
                Store.bio = field.text.toString().trim()
                close()
                if (sid == screenId) renderProfile(body, sid)
            })
        box.addView(btnGrid(btns, 0))
        field.requestFocus()
    }
}

/** Заголовок блока профиля с необязательной подписью справа. */
internal fun MainActivity.profileTitle(t: String, right: String = ""): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(10)
        addView(txt(t, 18f, TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        if (right.isNotEmpty()) addView(txt(right, 12f, MUTE))
    }

internal fun starsLabel(n: Int): String = String.format(Locale.US, "%,d", n).replace(',', ' ')


// ---------- подробное окно достижения ----------

/** Что значит значок, как его получить и зачем он нужен: по «лесенкам» значков одного вида. */
private class BadgeStory(val story: String, val steps: List<String>, val perk: String)

private fun badgeStory(b: BadgeInfo): BadgeStory = when (b.group) {
    "order" -> BadgeStory(
        "Покупки в PharmShop: от первого заказа до постоянного клиента. Значки идут лесенкой, на обложке профиля показывается высший из полученных.",
        listOf("Выберите звёзды или Premium на главном экране", "Оформите заказ и оплатите его", "Для «5 заказов» и «15 заказов» считаются только оплаченные и выполненные заказы"),
        "Заказы считаются на этом телефоне: удалите историю заказов, и счётчик станет меньше."
    )
    "stars" -> BadgeStory(
        "Звёзды Telegram, купленные через приложение. Считается сумма звёзд во всех оплаченных заказах, Premium в счёт не идёт. Лесенка: 500, 1 000, 2 500, 5 000 и 10 000.",
        listOf("Купите звёзды на главном экране", "Оплатите заказ: звёзды из оплаченных заказов складываются", "Когда сумма дойдёт до нужной отметки, значок откроется сам"),
        "Достаточно нескольких заказов: звёзды из разных покупок суммируются."
    )
    "premium" -> BadgeStory(
        "Знак тех, кто пользуется Telegram Premium по подписке, купленной через приложение.",
        listOf("Откройте «Premium» на главном экране", "Выберите срок и оплатите подписку"),
        "Значок откроется, когда заказ на Premium будет оплачен."
    )
    "vpn" -> BadgeStory(
        "Вы подключили личный кабинет VPN: подписки, устройства и оплата теперь под рукой.",
        listOf("Откройте раздел VPN", "Войдите в личный кабинет или зарегистрируйтесь"),
        "Значок остаётся, пока вы вошли в аккаунт VPN."
    )
    "lock" -> BadgeStory(
        "Приложение под защитой: заказы и профиль не откроет никто посторонний.",
        listOf("Откройте Настройки → Безопасность", "Включите вход по PIN-коду или отпечатку пальца"),
        "Если выключить защиту, значок снова закроется."
    )
    "photo" -> BadgeStory(
        "У вас есть своё лицо в чате: фото профиля видят другие участники.",
        listOf("Откройте Профиль", "Нажмите на аватарку и выберите фото"),
        "Фото можно сменить или убрать в любой момент."
    )
    "style" -> BadgeStory(
        "Приложение оформлено под вас: цвета, обложка или тема не такие, как у всех.",
        listOf("Откройте Настройки → Оформление", "Смените тему, акцентный цвет или обложку профиля"),
        "Любого одного изменения достаточно: значок откроется сразу."
    )
    "bio" -> BadgeStory(
        "Пара слов о себе делает профиль живым: их видят в вашем профиле.",
        listOf("Откройте Профиль", "Нажмите на строку «О себе» и напишите пару слов"),
        "Текст можно поменять когда угодно."
    )
    "bugs" -> BadgeStory(
        "Награда тем, кто помогает сделать приложение лучше: вы находите настоящие ошибки и сообщаете о них. Лесенка: 1, 3 и 10 отчётов.",
        listOf("Откройте Настройки → Прочее → «Сообщить об ошибке»", "Опишите, что пошло не так", "Отчёт засчитывается не чаще одного раза в сутки"),
        "Засчитываются только отчёты о реальных ошибках, а не пустые или шуточные."
    )
    "help" -> BadgeStory(
        "Награда за вопросы, которые удалось решить вместе со службой поддержки. Лесенка: 1, 3 и 5 решённых обращений.",
        listOf("Откройте «Поддержка» и опишите вопрос", "Дождитесь ответа: когда обращение отметят решённым, значок обновится"),
        "Оценка помощи в поддержке по желанию: нам важно ваше мнение."
    )
    "chat" -> BadgeStory(
        "Награда за живое общение. Считаются ваши сообщения в общем чате и в личных. Лесенка: 1, 25 и 100 сообщений.",
        listOf("Откройте вкладку «Чат» и выберите ник", "Пишите в общий чат и в личные сообщения", "Число берётся с сервера и только растёт"),
        "Спам не поощряется: за нарушения доступ к чату могут закрыть."
    )
    else -> BadgeStory(b.hint, listOf(b.hint), "")
}

/** Красивое окно достижения: что это, как получить, прогресс, лесенка значков и путь к зелёной короне. */
internal fun MainActivity.showBadgeSheet(b: BadgeInfo, all: List<BadgeInfo>) {
    val tone = if (b.got) GREEN else BLUE
    val story = badgeStory(b)
    val ladder = all.filter { it.group == b.group }
    val idx = ladder.indexOfFirst { it.title == b.title }
    val collected = all.count { it.got }
    sheet("Достижение", scroll = true) { box, close ->
        // шапка: большой значок, название и статус
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(mix(tone, 0.12f), 22, edge(tone))
            setPadding(dp(16), dp(20), dp(16), dp(18))
            layoutParams = lp(12)
            addView(txt(b.emoji, 40f).apply {
                gravity = Gravity.CENTER
                background = shape(mix(tone, 0.22f), 42, tone)
                alpha = if (b.got) 1f else 0.8f
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(12) }
            })
            addView(txt(b.title, 20f, TEXT, true).apply { gravity = Gravity.CENTER; layoutParams = lp(8) })
            addView(txt(if (b.got) "✓ Получено" else "🔒 Пока закрыто", 13f, tone, true).apply {
                gravity = Gravity.CENTER
                background = shape(CARD, 12, tone)
                setPadding(dp(14), dp(6), dp(14), dp(6))
            })
        })
        // что это
        box.addView(txt(story.story, 14f, TEXT).apply { layoutParams = lp(12) })

        // прогресс
        if (b.need > 0 && b.cur >= 0) {
            val pct = (minOf(b.cur, b.need) * 100 / b.need)
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(CARD, 18, LINE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = lp(12)
                addView(txt("Прогресс: " + minOf(b.cur, b.need) + " из " + b.need, 14f, TEXT, true).apply { layoutParams = lp(8) })
                addView(LinearLayout(this@showBadgeSheet).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = shape(LINE, 4)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
                    addView(android.view.View(this@showBadgeSheet).apply {
                        background = shape(tone, 4)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct.toFloat())
                    })
                    addView(android.view.View(this@showBadgeSheet).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - pct).toFloat())
                    })
                })
            })
        }

        // как получить
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(CARD, 18, LINE)
            setPadding(dp(16), dp(14), dp(16), dp(10))
            layoutParams = lp(12)
            addView(txt(if (b.got) "Как это открывается" else "Как получить", 14f, TEXT, true).apply { layoutParams = lp(8) })
            story.steps.forEachIndexed { i, step ->
                addView(txt((i + 1).toString() + ".  " + step, 13f, MUTE).apply { layoutParams = lp(6) })
            }
            if (!b.got && b.hint.isNotEmpty()) addView(txt("➜ " + b.hint, 13f, tone, true).apply { layoutParams = lp(4) })
        })

        // лесенка значков одного вида
        if (ladder.size > 1) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(CARD, 18, LINE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = lp(12)
                addView(txt("Ступень " + (idx + 1) + " из " + ladder.size, 14f, TEXT, true).apply { layoutParams = lp(10) })
                addView(LinearLayout(this@showBadgeSheet).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    ladder.forEach { l ->
                        addView(txt(l.emoji, if (l.title == b.title) 26f else 20f).apply {
                            alpha = if (l.got) 1f else 0.35f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            gravity = Gravity.CENTER
                        })
                    }
                })
                val next = ladder.firstOrNull { !it.got }
                if (next != null) addView(txt("Следующая ступень: " + next.title, 12f, MUTE).apply {
                    layoutParams = lp(0).apply { topMargin = dp(10) }
                })
                else addView(txt("Вся лесенка пройдена 🎉", 12f, GREEN).apply { layoutParams = lp(0).apply { topMargin = dp(10) } })
            })
        }

        // награда и путь к короне
        if (story.perk.isNotEmpty()) box.addView(txt("💡 " + story.perk, 12f, MUTE).apply { layoutParams = lp(10) })
        box.addView(txt(
            "👑 Соберите все достижения (" + collected + " из " + all.size + "): в чате рядом с ником появится зелёная корона.",
            12f, MUTE
        ).apply { layoutParams = lp(14) })
        box.addView(btnGrid(listOf(gbtn("Понятно", true) { close() }), 0))
    }
}
