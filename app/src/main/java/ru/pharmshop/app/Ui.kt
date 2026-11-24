package ru.pharmshop.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils

// Общие помощники интерфейса: анимации, виджеты, каркас экрана.

/** Раздел главного меню с учётом удалённых настроек. */
internal class SecView(val title: String, val sub: String, val enabled: Boolean, val url: String, val message: String)

internal class MethodRow(val id: String, val name: String, val row: LinearLayout, val dot: View, val tint: Int = 0)

/** ScrollView, который умеет «потянуть вниз, чтобы обновить»: в самом верху тянется содержимое, отпустили далеко — вызывается onPull. */
internal class PullScrollView(ctx: Context) : ScrollView(ctx) {
    var onPull: (() -> Unit)? = null
    private var startY = 0f
    private var pulling = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        val content = getChildAt(0)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startY = ev.rawY; pulling = false }
            MotionEvent.ACTION_MOVE -> if (onPull != null) {
                if (scrollY > 0) {
                    startY = ev.rawY
                } else {
                    val dy = ev.rawY - startY
                    if (dy > 12 * d) {
                        pulling = true
                        content?.translationY = minOf(dy * 0.35f, 70 * d)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val fire = pulling && ev.actionMasked == MotionEvent.ACTION_UP && (ev.rawY - startY) * 0.35f >= 45 * d
                if (pulling) content?.animate()?.translationY(0f)?.setDuration(200)?.start()
                pulling = false
                if (fire) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onPull?.invoke()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** Включает на текущем экране «потянуть вниз, чтобы обновить». */
internal fun MainActivity.setPullRefresh(f: () -> Unit) { pullView?.onPull = f }

// ---------- анимации ----------
/** Лёгкое «вдавливание» при касании. Клики не перехватывает (возвращает false). */
internal fun MainActivity.pressable(v: View) {
    v.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Store.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                view.animate().scaleX(0.97f).scaleY(0.97f)
                .setStartDelay(0).setDuration(110).setInterpolator(DecelerateInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                .setStartDelay(0).setDuration(240).setInterpolator(OvershootInterpolator(2.5f)).start()
        }
        false
    }
}

/** Поочерёдное появление дочерних элементов (снизу вверх с проявлением), начиная с индекса from. */
internal fun MainActivity.riseIn(col: LinearLayout, from: Int = 0) {
    if (!Store.uiAnim) return
    var n = 0
    // анимируем только то, что помещается на первый экран; остальное (внизу длинных экранов) не трогаем —
    // раньше невидимые элементы десятками стартовали разом и давали рывок при открытии экрана
    for (i in from until minOf(col.childCount, from + 10)) {
        val v = col.getChildAt(i)
        v.alpha = 0f
        v.translationY = dp(14).toFloat()
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(minOf(n, 10) * 55L).setDuration(420)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        n++
    }
}

/** Бесконечная анимация, которая живёт, пока вид на экране, и останавливается при его удалении. */
internal fun MainActivity.loopWhileAttached(v: View, a: ObjectAnimator) {
    a.repeatMode = ValueAnimator.REVERSE
    a.repeatCount = ValueAnimator.INFINITE
    v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
        override fun onViewDetachedFromWindow(x: View) { a.cancel() }
    })
}

/** Логотип слегка «парит». */
internal fun MainActivity.floatForever(v: View) {
    loopWhileAttached(v, ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -dp(3).toFloat()).apply {
        duration = 2200
        interpolator = AccelerateDecelerateInterpolator()
    })
}

/** Мягкое мигание бейджа заказа, который ещё не завершён. */
internal fun MainActivity.pulse(v: View) {
    loopWhileAttached(v, ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.55f).apply {
        duration = 900
        interpolator = AccelerateDecelerateInterpolator()
    })
}

/** Смена текста с небольшим «всплеском» (для суммы к оплате). */
internal fun MainActivity.popText(t: TextView, s: String) {
    if (t.text.toString() == s) return
    t.text = s
    t.pivotX = 0f
    t.pivotY = t.height / 2f
    t.scaleX = 0.94f; t.scaleY = 0.94f; t.alpha = 0.4f
    t.animate().scaleX(1f).scaleY(1f).alpha(1f)
        .setStartDelay(0).setDuration(280).setInterpolator(OvershootInterpolator(2f)).start()
}

/** Радио-точка «выскакивает» в момент выбора. */
internal fun MainActivity.dotState(dot: View, selected: Boolean) {
    val was = dot.tag == "on"
    dot.tag = if (selected) "on" else null
    if (selected && !was) {
        dot.scaleX = 0.5f; dot.scaleY = 0.5f
        dot.animate().scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }
}

// ---------- helpers ----------
internal fun MainActivity.ui(r: () -> Unit) { main.post { r() } }
internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
private var toastView: View? = null

/** Уведомление-«таблетка» в цветах выбранной темы (вместо серого системного Toast). */
internal fun MainActivity.toast(t: String) {
    main.post {
        try {
            val host = findViewById<ViewGroup>(android.R.id.content)
            toastView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(CARD, 18, edge(BLUE))
                elevation = dp(8).toFloat()
                setPadding(dp(14), dp(12), dp(18), dp(12))
                addView(View(this@toast).apply {
                    background = shape(BLUE, 30)
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) }
                })
                addView(txt(t, 14f, TEXT).apply { maxLines = 5 })
            }
            host.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(96); leftMargin = dp(24); rightMargin = dp(24) })
            toastView = pill
            pill.alpha = 0f
            pill.translationY = dp(16).toFloat()
            pill.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
            main.postDelayed({
                pill.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(220)
                    .withEndAction { (pill.parent as? ViewGroup)?.removeView(pill) }.start()
            }, if (t.length > 60) 3600L else 2300L)
        } catch (e: Exception) {
            Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Радиус угла с учётом настройки «Скругление карточек». Большие радиусы (круглые кнопки) не меняем. */
internal fun MainActivity.corner(radius: Int): Float =
    dp(radius).toFloat() * (if (radius >= 30) 1f else Theme.corner)

internal fun MainActivity.shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = corner(radius)
    if (stroke != 0) setStroke(dp(1), stroke)
}

internal fun MainActivity.ripple(fill: Int, radius: Int, stroke: Int = 0) = RippleDrawable(
    ColorStateList.valueOf(Theme.rippleColor),
    shape(fill, radius, stroke),
    shape(Color.WHITE, radius)
)

internal fun MainActivity.lp(bottom: Int = 10) = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
).apply { setMargins(0, 0, 0, dp(bottom)) }

internal fun MainActivity.txt(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = t
        textSize = size * Theme.textScale
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal fun MainActivity.tv(t: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
    txt(t, size, color, bold).apply { layoutParams = lp(8) }

internal fun MainActivity.btn(t: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        text = t
        setAllCaps(false)
        textSize = 16f * Theme.textScale
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(if (primary) Color.BLACK else TEXT)
        background = ripple(if (primary) BLUE else CARD, 18, if (primary) BLUE else LINE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp()
        setOnClickListener { onClick() }
        pressable(this)
    }

internal fun MainActivity.chip(t: String, primary: Boolean, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = t
        textSize = 13f * Theme.textScale
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else TEXT)
        background = ripple(if (primary) BLUE else Color.TRANSPARENT, 12, if (primary) BLUE else LINE)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
    }

/** Цвет, подмешанный к фону карточки (для выбранных строк). */
internal fun MainActivity.mix(c: Int, amount: Float) = ColorUtils.blendARGB(CARD, c, amount)

internal fun MainActivity.edge(accent: Int) = ColorUtils.blendARGB(LINE, accent, 0.55f)

internal fun MainActivity.gradient(c1: Int, c2: Int, radius: Int) =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
        cornerRadius = corner(radius)
    }

/** Цветная плашка-заголовок экрана покупки. */
internal fun MainActivity.banner(ic: Ic, title: String, sub: String, c1: Int, c2: Int, on: Int): LinearLayout {
    val iconBox = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = shape(ColorUtils.setAlphaComponent(on, 40), 14)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) }
        addView(IconView(this@banner, ic, on).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(txt(title, 20f, on, true))
        addView(txt(sub, 13f, ColorUtils.setAlphaComponent(on, 205)))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = gradient(c1, c2, 20)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp(16)
        addView(iconBox)
        addView(texts)
    }
}

/** Кнопка «Оплатить» с градиентом. */
internal fun MainActivity.gradBtn(t: String, c1: Int, c2: Int, textColor: Int, onClick: () -> Unit): Button =
    Button(this).apply {
        text = t
        setAllCaps(false)
        textSize = 16f * Theme.textScale
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = RippleDrawable(
            ColorStateList.valueOf(Theme.rippleColor), gradient(c1, c2, 18), shape(Color.WHITE, 18)
        )
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp()
        setOnClickListener { onClick() }
        pressable(this)
    }

/** Чип с цветной обводкой и тонировкой. */
internal fun MainActivity.chipTint(t: String, color: Int, onClick: () -> Unit): TextView =
    chip(t, false, onClick).apply {
        setTextColor(color)
        background = ripple(mix(color, 0.16f), 12, color)
        setPadding(dp(14), dp(9), dp(14), dp(9))
    }

internal fun MainActivity.input(hintText: String, type: Int): EditText = EditText(this).apply {
    hint = hintText
    inputType = type
    setTextColor(TEXT)
    setHintTextColor(MUTE)
    textSize = 16f * Theme.textScale
    background = shape(CARD, 14, LINE)
    setPadding(dp(14), dp(14), dp(14), dp(14))
    layoutParams = lp()
}

/** Вкладки нижней панели: 0 — ТГ (главная), 1 — Поддержка, 2 — Профиль, 3 — Настройки, 4 — VPN, 5 — Чат (общий и личные). */
internal fun MainActivity.openTab(tab: Int) {
    when (tab) {
        0 -> showHome()
        1 -> showSupport()
        2 -> showProfile()
        3 -> showSettings()
        5 -> showCommunity()
        4 -> {
            // раздел можно выключить удалённо (админ-панель → «Разделы»)
            val sv = sec("vpn", "VPN", "", true, "Временно недоступно")
            if (sv.enabled) showVpn() else toast(sv.message.ifEmpty { "Скоро будет доступно" })
        }
    }
}

/** Порядок вкладок слева направо. Поддержка (1) в панели не показывается: её открывают кнопкой в профиле и настройках. */
private val TAB_ORDER = listOf(0, 4, 5, 2, 3)

internal fun MainActivity.bottomNav(active: Int): LinearLayout {
    val items = listOf(
        Triple(Ic.BAG, "Telegram", 0),
        Triple(Ic.KEY, "VPN", 4),
        Triple(Ic.GROUP, "Чат", 5),
        Triple(Ic.USER, "Профиль", 2),
        Triple(Ic.GEAR, "Настройки", 3)
    )
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(CARD)
        setPadding(dp(6), dp(8), dp(6), dp(8))
    }
    for ((icon, label, idx) in items) {
        val on = idx == active
        val color = if (on) BLUE else MUTE
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                if (!on) { navBack = TAB_ORDER.indexOf(idx) < TAB_ORDER.indexOf(active); openTab(idx) }
            }
            pressable(this)
            addView(IconView(this@bottomNav, icon, color).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(2) }
            })
            addView(txt(label, 10f, color, on).apply { gravity = Gravity.CENTER; textSize = 10f; maxLines = 1 })
        }
        row.addView(item)
    }
    // тонкая линия сверху + сама панель
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(this@bottomNav).apply {
            setBackgroundColor(LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        addView(row)
    }
}

/** Оборачивает экран: сверху содержимое, снизу панель вкладок (прячется, пока открыта клавиатура). */
internal fun MainActivity.withNav(content: View, tab: Int): View {
    if (tab < 0) return content
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
    }
    content.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    wrap.addView(content)
    val bar = bottomNav(tab)
    wrap.addView(bar)
    // Слушатель вешаем на окно только пока экран на нём и снимаем при уходе. Раньше он оставался в окне навсегда
    // и держал в памяти каждый старый экран: чем дольше пользуешься приложением, тем больше мусора и тормозов.
    val onLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        val ime = androidx.core.view.ViewCompat.getRootWindowInsets(wrap)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ?: false
        val want = if (ime) View.GONE else View.VISIBLE
        if (bar.visibility != want) bar.visibility = want
    }
    wrap.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        private var vto: android.view.ViewTreeObserver? = null
        override fun onViewAttachedToWindow(v: View) {
            vto = v.viewTreeObserver.also { it.addOnGlobalLayoutListener(onLayout) }
        }
        override fun onViewDetachedFromWindow(v: View) {
            vto?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(onLayout)
            vto = null
        }
    })
    return wrap
}

/**
 * Заголовок экрана-вкладки (без стрелки «назад»: переходы — через нижнюю панель).
 * supportFrom != 0 — справа вверху кнопка «Поддержка» (профиль = 2, настройки = 3).
 */
internal fun MainActivity.titleRow(col: LinearLayout, title: String, supportFrom: Int = 0) {
    if (supportFrom == 0) {
        col.addView(txt(title, 24f, TEXT, true).apply { layoutParams = lp(16) })
        return
    }
    col.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(16)
        addView(txt(title, 24f, TEXT, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(LinearLayout(this@titleRow).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(CARD, 20, LINE)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            contentDescription = "Поддержка"
            isClickable = true
            setOnClickListener { ru.pharmshop.app.supportFrom = supportFrom; showSupport() }
            pressable(this)
            addView(IconView(this@titleRow, Ic.CHAT, BLUE).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(6) }
            })
            addView(txt("Поддержка", 13f, TEXT, true))
        })
    })
}

/**
 * Каркас экрана. bottomBar = true — внизу появляется закреплённая панель (её содержимое
 * ставится позже через attachBar, когда загрузятся данные). Панель поднимается над клавиатурой.
 */
internal fun MainActivity.screen(tab: Int = -1, bottomBar: Boolean = false): LinearLayout {
    screenId++
    if (tab >= 0) Diag.tab(tab)   // аналитика: какие вкладки открывают
    backAction = null
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(28), dp(18), dp(24))
    }
    val sv = PullScrollView(this).apply {
        setBackgroundColor(BG)
        isFillViewport = true
        addView(col)
    }
    pullView = sv
    val host = if (bottomBar) FrameLayout(this).apply {
        setBackgroundColor(BG)
        setPadding(dp(18), dp(8), dp(18), dp(12))
    } else null
    col.tag = host
    val body: View = if (host == null) sv else LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    setContentView(withNav(body, tab))
    // переход между экранами: вперёд — выезд справа, назад — слева
    if (Store.uiAnim) {
        sv.alpha = 0f
        sv.translationX = if (firstScreen) 0f else dp(28).toFloat() * (if (navBack) -1 else 1)
        sv.animate().alpha(1f).translationX(0f).setDuration(320)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }
    firstScreen = false
    navBack = false
    addFx()
    return col
}

/** Подвал экрана: «Приложение в активной разработке», версия и кнопка «Проверить обновления». Одинаков на всех вкладках. */
internal fun MainActivity.appFooter(col: LinearLayout) {
    col.addView(txt("Приложение в активной разработке\nВерсия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})", 12f, MUTE).apply {
        gravity = Gravity.CENTER
        layoutParams = lp(0).apply { topMargin = dp(14) }
    })
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER
        layoutParams = lp(0).apply { topMargin = dp(10) }
        addView(chip("Проверить обновления", false) { checkForUpdate(false) }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        })
    })
}

/** Анимированный фон выбранного оформления (снег, листья, звёзды…). У «Стандарта» его нет. */
internal fun MainActivity.addFx() {
    val kind = Theme.skin.fx
    if (kind == Fx.NONE || !Store.fxEnabled || !FxView.allowed(this)) return
    findViewById<ViewGroup>(android.R.id.content).addView(
        FxView(this, kind),
        android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
}

internal fun MainActivity.backRow(col: LinearLayout, title: String, onBack: () -> Unit = { navBack = true; showHome() }) {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(16)
    }
    val icon = IconView(this, Ic.BACK, TEXT).apply {
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
    }
    val b = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = ripple(CARD, 22, LINE)
        isClickable = true
        setOnClickListener { onBack() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) }
        addView(icon)
    }
    row.addView(b)
    row.addView(txt(title, 24f, TEXT, true))
    col.addView(row)
}

internal fun MainActivity.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        toast("Не удалось открыть ссылку")
    }
}
