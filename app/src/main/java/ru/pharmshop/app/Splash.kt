package ru.pharmshop.app

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

// Стартовая заставка: показывается один раз, при самом первом запуске.

/** Фон заставки: дрейфующие светящиеся пятна, расходящиеся кольца и мерцающие точки. */
private class SplashBg(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = System.nanoTime()
    private val rnd = java.util.Random(7)
    private val sx = FloatArray(36) { rnd.nextFloat() }
    private val sy = FloatArray(36) { rnd.nextFloat() }
    private val sp = FloatArray(36) { 0.4f + rnd.nextFloat() * 1.2f }
    private val sr = FloatArray(36) { (1f + rnd.nextFloat() * 1.6f) * d }
    private val ph = FloatArray(36) { rnd.nextFloat() * 6.28f }

    /** 0..1 — насколько проявился фон (плавный вход). */
    var appear = 0f

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float) {
        val c0 = ColorUtils.setAlphaComponent(color, (a * 255).toInt().coerceIn(0, 255))
        glow.shader = RadialGradient(cx, cy, r, c0, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val t = (System.nanoTime() - t0) / 1e9f
        val acc = Theme.accent
        val a = appear

        c.drawColor(Theme.bg)
        val k = if (Theme.light) 0.55f else 1f
        blob(c, w * (0.25f + 0.10f * sin(t * 0.6f)), h * (0.30f + 0.06f * cos(t * 0.5f)), w * 0.85f, acc, 0.32f * a * k)
        blob(c, w * (0.80f + 0.08f * cos(t * 0.45f)), h * (0.72f + 0.07f * sin(t * 0.7f)), w * 0.80f,
            ColorUtils.blendARGB(acc, Color.parseColor("#8B5CF6"), 0.6f), 0.26f * a * k)

        // кольца-«пульс» вокруг логотипа
        val cx = w / 2f; val cy = h * 0.40f
        for (i in 0 until 3) {
            val p = ((t * 0.32f) + i / 3f) % 1f
            val r = (50f + p * 190f) * d
            ring.strokeWidth = 1.5f * d
            ring.color = ColorUtils.setAlphaComponent(acc, ((1f - p) * 90f * a * k).toInt().coerceIn(0, 255))
            c.drawCircle(cx, cy, r, ring)
        }

        // мерцающие точки
        dot.color = if (Theme.light) acc else Color.WHITE
        for (i in sx.indices) {
            val y = ((sy[i] - t * 0.015f * sp[i]) % 1f + 1f) % 1f
            val tw = 0.5f + 0.5f * sin(t * sp[i] * 2f + ph[i])
            dot.alpha = (tw * 150f * a * k).toInt().coerceIn(0, 255)
            c.drawCircle(sx[i] * w, y * h, sr[i], dot)
        }
        postInvalidateOnAnimation()
    }
}


/**
 * Текст, у которого каждая буква «выпрыгивает» снизу волной (с отскоком, масштабом и лёгким поворотом),
 * после чего по строке пробегает блик. Уход — буквы по очереди взлетают вверх и растворяются.
 */
private class WaveText(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private class Ch(val s: String, val w: Float, val accent: Boolean) { var x = 0f; var line = 0; var idx = -1 }
    private val chars = ArrayList<Ch>()
    private var total = 1
    private var lineH = 0f
    private var lines = 1
    private var textStr = ""
    private var accentWord: String? = null
    private var textColor = Color.WHITE
    private var accentColor = Color.BLUE
    private var shineColor = Color.WHITE
    private var laidOutW = -1

    private var inP = 0f
    private var outP = 0f
    private var shine = -1f
    private var shader: LinearGradient? = null
    private val sm = Matrix()
    private var animIn: ValueAnimator? = null
    private var animOut: ValueAnimator? = null
    private var animShine: ValueAnimator? = null

    fun setLine(text: String, sizeSp: Float, accent: String?, color: Int, accentCol: Int, shineCol: Int) {
        cancelAll()
        textStr = text; accentWord = accent
        textColor = color; accentColor = accentCol; shineColor = shineCol
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        inP = 0f; outP = 0f; shine = -1f
        laidOutW = -1
        requestLayout(); invalidate()
    }

    private fun build(maxW: Float) {
        chars.clear()
        val fm = paint.fontMetrics
        lineH = (fm.descent - fm.ascent) * 1.12f
        val ai = accentWord?.let { textStr.indexOf(it) } ?: -1
        val al = accentWord?.length ?: 0
        val sw = paint.measureText(" ")
        // слова с индексами букв
        val words = ArrayList<List<Ch>>()
        var cur = ArrayList<Ch>()
        for (i in textStr.indices) {
            val c = textStr[i]
            if (c == ' ') { if (cur.isNotEmpty()) { words.add(cur); cur = ArrayList() } }
            else cur.add(Ch(c.toString(), paint.measureText(c.toString()), ai >= 0 && i >= ai && i < ai + al))
        }
        if (cur.isNotEmpty()) words.add(cur)
        // перенос по словам
        val rows = ArrayList<ArrayList<List<Ch>>>()
        var row = ArrayList<List<Ch>>()
        var rw = 0f
        for (w in words) {
            val ww = w.sumOf { it.w.toDouble() }.toFloat()
            if (row.isNotEmpty() && rw + sw + ww > maxW) { rows.add(row); row = ArrayList(); rw = 0f }
            rw += (if (row.isEmpty()) 0f else sw) + ww
            row.add(w)
        }
        if (row.isNotEmpty()) rows.add(row)
        // координаты по центру строки
        var n = 0
        for ((li, r) in rows.withIndex()) {
            val lw = r.sumOf { w -> w.sumOf { it.w.toDouble() } }.toFloat() + sw * (r.size - 1)
            var x = (maxW - lw) / 2f
            for ((wi, w) in r.withIndex()) {
                if (wi > 0) x += sw
                for (ch in w) { ch.x = x; ch.line = li; ch.idx = n++; chars.add(ch); x += ch.w }
            }
        }
        total = max(1, n)
        lines = max(1, rows.size)
        laidOutW = maxW.toInt()
        val band = 90f * d
        shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(textColor, shineColor, textColor),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        build((w - paddingLeft - paddingRight).toFloat())
        setMeasuredDimension(w, ceil(lines * lineH + 10f * d).toInt())
    }

    private fun backOut(t: Float): Float { val c1 = 2.2f; val c3 = c1 + 1f; val u = t - 1f; return 1f + c3 * u * u * u + c1 * u * u }

    override fun onDraw(c: Canvas) {
        if (laidOutW < 0) return
        val fm = paint.fontMetrics
        val band = 90f * d
        val sh = shader
        if (sh != null && shine in 0f..1f) sm.setTranslate(-band + shine * (width + band * 2f), 0f)
        for (ch in chars) {
            val f = ch.idx.toFloat() / max(1, total - 1)
            val qi = ((inP - f * 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (qi <= 0f) continue
            val qo = ((outP - f * 0.5f) / 0.5f).coerceIn(0f, 1f)
            val e = backOut(qi)
            val alpha = min(1f, qi * 2.2f) * (1f - qo)
            if (alpha <= 0f) continue
            val dy = (1f - e) * 30f * d - qo * qo * 28f * d
            val sc = (0.5f + 0.5f * e) * (1f + 0.08f * qo)
            val rot = (1f - e) * (if (ch.idx % 2 == 0) -16f else 16f)
            val baseY = ch.line * lineH - fm.ascent * 1.06f
            paint.shader = if (!ch.accent && sh != null && shine in 0f..1f) sh else null
            paint.color = if (ch.accent) accentColor else textColor
            paint.alpha = (alpha * 255f).toInt()
            c.save()
            c.translate(ch.x + ch.w / 2f, baseY + dy)
            c.rotate(rot)
            c.scale(sc, sc)
            // при блике шейдер задан в координатах вида, поэтому компенсируем сдвиг
            c.translate(-ch.w / 2f, 0f)
            if (paint.shader != null) {
                // шейдер живёт в системе координат символа — сдвигаем блик обратно к системе вида
                sh!!.setLocalMatrix(Matrix().apply { set(sm); postTranslate(-ch.x, 0f) })
            }
            c.drawText(ch.s, 0f, 0f, paint)
            c.restore()
        }
        paint.shader = null
    }

    private fun anim(from: Float, to: Float, dur: Long, interp: android.animation.TimeInterpolator, onUpd: (Float) -> Unit, onEnd: (() -> Unit)? = null): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = dur; interpolator = interp
            addUpdateListener { onUpd(it.animatedValue as Float); invalidate() }
            if (onEnd != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) onEnd() }
            })
            start()
        }

    fun playIn(onEnd: () -> Unit) {
        animIn = anim(0f, 1f, 1000, android.view.animation.LinearInterpolator(), { inP = it }) {
            animShine = anim(0f, 1f, 1100, android.view.animation.AccelerateDecelerateInterpolator(), { shine = it }) { shine = -1f }
            onEnd()
        }
    }

    fun playOut(onEnd: () -> Unit) {
        animOut = anim(0f, 1f, 520, android.view.animation.LinearInterpolator(), { outP = it }, onEnd)
    }

    fun cancelAll() { animIn?.cancel(); animOut?.cancel(); animShine?.cancel() }
}

/**
 * Заставка при первом запуске: логотип с анимацией и фразы, появляющиеся по очереди.
 * Нажатие или «назад» — пропустить. Показывается один раз (Store.introSeen).
 */
internal fun MainActivity.showSplashIfFirstRun() {
    if (Store.introSeen) return
    showSplash()
}

internal fun MainActivity.showSplash() {
    val root = FrameLayout(this).apply { isClickable = true; isFocusable = true }
    val bg = SplashBg(this)
    root.addView(bg, FrameLayout.LayoutParams(-1, -1))

    val logo = LogoView(this).apply {
        alpha = 0f; scaleX = 0.2f; scaleY = 0.2f; rotation = -25f
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    column.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)))

    // область текста фиксированной высоты, чтобы логотип не прыгал при смене фраз
    val line = WaveText(this)
    val lineBox = FrameLayout(this)
    lineBox.addView(line, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    column.addView(lineBox, LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(30) })

    root.addView(column, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER_VERTICAL })
    column.translationY = -dp(70).toFloat()

    val beta = txt("STABLE", 11f, BLUE, true).apply {
        letterSpacing = 0.25f
        background = shape(ColorUtils.setAlphaComponent(BLUE, 36), 12, ColorUtils.setAlphaComponent(BLUE, 120))
        setPadding(dp(12), dp(5), dp(12), dp(5))
        alpha = 0f
    }
    root.addView(beta, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })
    val hint = txt("Нажмите, чтобы пропустить", 12f, MUTE).apply { alpha = 0f }
    root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })

    (window.decorView as ViewGroup).addView(root, FrameLayout.LayoutParams(-1, -1))

    // ---- сценарий ----
    class Step(val text: String, val accent: String?, val size: Float, val hold: Long)
    val steps = listOf(
        Step("Привет!", null, 40f, 700),
        Step("Это бета-версия нашего приложения", "бета-версия", 24f, 1100),
        Step("Разработка и дизайн - starboy", "starboy", 24f, 1100),
        Step("Выпущено Pharm", "Pharm", 28f, 1000)
    )
    val shineCol = if (Theme.light) BLUE else Color.WHITE

    var finished = false
    var idx = -1

    fun finish() {
        if (finished) return
        finished = true
        splashSkip = null
        Store.introSeen = true
        main.removeCallbacksAndMessages(SPLASH_TOKEN)
        line.cancelAll()
        root.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(0).setDuration(450)
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                (window.decorView as ViewGroup).removeView(root)
            }.start()
    }
    splashSkip = { finish() }
    root.setOnClickListener { finish() }

    fun next() {
        if (finished || !root.isAttachedToWindow) return
        idx++
        if (idx >= steps.size) { finish(); return }
        val s = steps[idx]
        val last = idx == steps.size - 1
        line.setLine(s.text, s.size, s.accent, TEXT, BLUE, shineCol)
        line.playIn {
            main.postAtTime({
                if (finished) return@postAtTime
                if (last) finish() else line.playOut { next() }
            }, SPLASH_TOKEN, android.os.SystemClock.uptimeMillis() + s.hold)
        }
    }

    // вход: фон, логотип, затем фразы
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        addUpdateListener { bg.appear = it.animatedValue as Float }
    }.start()
    logo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
        .setStartDelay(150).setDuration(850).setInterpolator(OvershootInterpolator(1.8f))
        .withEndAction { floatForever(logo) }.start()
    beta.animate().alpha(1f).setStartDelay(700).setDuration(600).start()
    hint.animate().alpha(0.8f).setStartDelay(1800).setDuration(700).start()
    main.postAtTime({ next() }, SPLASH_TOKEN, android.os.SystemClock.uptimeMillis() + 950)
}

private val SPLASH_TOKEN = Any()
