package ru.pharmshop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Оформление раздела «VPN»: кот-аватарка с расходящимися кольцами, кольцо дней, шкала трафика, плитки действий.
// Иконки и шкалы рисуются на Canvas; кот — те же векторные рисунки, что и у значков приложения. Логика экрана — в BuyVpn.kt.

// ---------- иконки ----------

internal enum class VIc {
    PAW, CHECK, SNOW, SUN, RENEW, USERS, CHART, PHONE, LINK,
    WALLET, BAG, LOGOUT, COPY, BOLT, SPEED, CLOCK, STAR
}

private val BOLT_P = Path().apply {
    moveTo(13f, 2.5f); lineTo(4.5f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 21.5f)
    lineTo(18.5f, 10.5f); lineTo(12f, 10.5f); close()
}

/** Рисует иконку в сетке 24×24 (холст уже промасштабирован, кисть — линейная). */
internal fun drawVIc(c: Canvas, k: VIc, p: Paint) {
    when (k) {
        VIc.PAW -> {   // кошачья лапка: подушечка и четыре пальца
            c.drawOval(RectF(7f, 12.2f, 17f, 20.6f), p)
            c.drawCircle(4.9f, 10.4f, 2f, p); c.drawCircle(9.1f, 6.1f, 2.1f, p)
            c.drawCircle(14.9f, 6.1f, 2.1f, p); c.drawCircle(19.1f, 10.4f, 2f, p)
        }
        VIc.CHECK -> {
            c.drawLine(5f, 12.5f, 9.5f, 17f, p); c.drawLine(9.5f, 17f, 19f, 7.5f, p)
        }
        VIc.SNOW -> {
            c.drawLine(12f, 2.5f, 12f, 21.5f, p)
            c.drawLine(3.8f, 7.2f, 20.2f, 16.8f, p)
            c.drawLine(20.2f, 7.2f, 3.8f, 16.8f, p)
            c.drawLine(9.5f, 4.5f, 12f, 6.5f, p); c.drawLine(12f, 6.5f, 14.5f, 4.5f, p)
            c.drawLine(9.5f, 19.5f, 12f, 17.5f, p); c.drawLine(12f, 17.5f, 14.5f, 19.5f, p)
        }
        VIc.SUN -> {
            c.drawCircle(12f, 12f, 4f, p)
            for (i in 0 until 8) {
                val a = PI / 4 * i
                c.drawLine(
                    12f + (6.8f * cos(a)).toFloat(), 12f + (6.8f * sin(a)).toFloat(),
                    12f + (9.5f * cos(a)).toFloat(), 12f + (9.5f * sin(a)).toFloat(), p
                )
            }
        }
        VIc.RENEW -> {
            c.drawArc(RectF(4f, 4f, 20f, 20f), 0f, 312f, false, p)
            c.drawLine(20f, 4f, 20f, 9f, p); c.drawLine(20f, 9f, 15f, 9f, p)
        }
        VIc.USERS -> {
            c.drawCircle(9f, 8f, 3.5f, p)
            c.drawArc(RectF(2.5f, 13.5f, 15.5f, 26.5f), 180f, 180f, false, p)
            c.drawLine(19f, 8f, 19f, 14f, p); c.drawLine(16f, 11f, 22f, 11f, p)
        }
        VIc.CHART -> {
            c.drawRoundRect(RectF(4.5f, 11f, 8.5f, 20f), 1.2f, 1.2f, p)
            c.drawRoundRect(RectF(10f, 4.5f, 14f, 20f), 1.2f, 1.2f, p)
            c.drawRoundRect(RectF(15.5f, 14f, 19.5f, 20f), 1.2f, 1.2f, p)
        }
        VIc.PHONE -> {
            c.drawRoundRect(RectF(7f, 2.5f, 17f, 21.5f), 3f, 3f, p)
            c.drawLine(11f, 18f, 13f, 18f, p)
        }
        VIc.LINK -> {
            c.save(); c.rotate(-45f, 12f, 12f)
            c.drawRoundRect(RectF(2.2f, 8.6f, 12.8f, 15.4f), 3.4f, 3.4f, p)
            c.drawRoundRect(RectF(11.2f, 8.6f, 21.8f, 15.4f), 3.4f, 3.4f, p)
            c.restore()
        }
        VIc.WALLET -> {
            c.drawRoundRect(RectF(3f, 6f, 21f, 20f), 3.5f, 3.5f, p)
            c.drawLine(3f, 10.5f, 21f, 10.5f, p); c.drawLine(16f, 15.5f, 18f, 15.5f, p)
        }
        VIc.BAG -> {
            val b = Path().apply { moveTo(5f, 8f); lineTo(19f, 8f); lineTo(18f, 20.5f); lineTo(6f, 20.5f); close() }
            c.drawPath(b, p)
            c.drawLine(9f, 8f, 9f, 6.5f, p); c.drawLine(15f, 8f, 15f, 6.5f, p)
            c.drawArc(RectF(9f, 3.5f, 15f, 9.5f), 180f, 180f, false, p)
        }
        VIc.LOGOUT -> {
            val b = Path().apply {
                moveTo(9.5f, 4f); lineTo(6.5f, 4f); quadTo(4.5f, 4f, 4.5f, 6f)
                lineTo(4.5f, 18f); quadTo(4.5f, 20f, 6.5f, 20f); lineTo(9.5f, 20f)
            }
            c.drawPath(b, p)
            c.drawLine(15.5f, 8f, 19.5f, 12f, p); c.drawLine(19.5f, 12f, 15.5f, 16f, p)
            c.drawLine(19.5f, 12f, 9.5f, 12f, p)
        }
        VIc.COPY -> {
            c.drawRoundRect(RectF(9f, 9f, 20f, 20f), 3f, 3f, p)
            val b = Path().apply { moveTo(5f, 15f); lineTo(5f, 6f); quadTo(5f, 4f, 7f, 4f); lineTo(16f, 4f) }
            c.drawPath(b, p)
        }
        VIc.BOLT -> c.drawPath(BOLT_P, p)
        VIc.SPEED -> {
            c.drawArc(RectF(3f, 5f, 21f, 23f), 180f, 180f, false, p)
            c.drawLine(12f, 14f, 16.5f, 9.5f, p)
            c.drawCircle(12f, 14f, 1.2f, p)
        }
        VIc.CLOCK -> {
            c.drawCircle(12f, 12f, 9f, p)
            c.drawLine(12f, 7f, 12f, 12.5f, p); c.drawLine(12f, 12.5f, 15.5f, 14.5f, p)
        }
        VIc.STAR -> {
            val s = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9.5f else 4.2f
                val a = PI / 5 * i - PI / 2
                val x = 12f + (r * cos(a)).toFloat()
                val y = 12.6f + (r * sin(a)).toFloat()
                if (i == 0) s.moveTo(x, y) else s.lineTo(x, y)
            }
            s.close()
            c.drawPath(s, p)
        }
    }
}

internal class VpnIconView(ctx: Context, private val kind: VIc, tint: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = tint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f
    }

    fun setTint(c: Int) { p.color = c; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        c.scale(s / 24f, s / 24f)
        c.translate(12f, 12f); c.scale(0.9f, 0.9f); c.translate(-12f, -12f)
        drawVIc(c, kind, p)
        c.restore()
    }
}

// ---------- кот ----------

/**
 * Кот в круглой «аватарке» — тот же, что выбран значком приложения (Настройки → Оформление → Иконка).
 * state: 0 — защита включена (кот бодрствует, мягко «парит», вокруг расходятся кольца, на аватарке галочка);
 *        1 — подписка заморожена (кот в инее, на аватарке снежинка);
 *        2 — подписки нет (кот серый и спит: «z Z Z»).
 * Двигаются только свойства View (смещение, масштаб, прозрачность): их считает RenderThread, без перерисовки
 * onDraw на каждом кадре. Анимации идут, только пока экран виден. Нажатие на кота — упругий отскок.
 */
internal class CatHeroView(
    ctx: Context, accent: Int, private val state: Int, private val animOn: Boolean,
    badgeBg: Int, catRes: Int, cutout: Int
) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val anims = ArrayList<ObjectAnimator>()

    private fun px(v: Float) = (v * d).toInt()

    /** Бесконечная анимация; при остановке (экран ушёл из виду) вид возвращается в исходное положение. */
    private fun loop(a: ObjectAnimator, resetAlpha: View? = null): ObjectAnimator {
        a.repeatCount = ValueAnimator.INFINITE
        if (resetAlpha != null) a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(an: Animator) { resetAlpha.alpha = 0f }
        })
        anims.add(a)
        return a
    }

    init {
        clipChildren = false
        clipToPadding = false
        val live = state != 2

        // мягкое свечение за аватаркой
        addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setGradientType(GradientDrawable.RADIAL_GRADIENT)
                setGradientRadius(76f * d)
                setColors(intArrayOf(ColorUtils.setAlphaComponent(accent, if (live) 90 else 28), ColorUtils.setAlphaComponent(accent, 0)))
            }
        }, FrameLayout.LayoutParams(px(152f), px(152f), Gravity.CENTER))

        // кольца: у включённой защиты расходятся, у замороженной одно неподвижное
        val rings = when (state) { 0 -> 3; 1 -> 1; else -> 0 }
        for (i in 0 until rings) {
            val ring = View(ctx).apply {
                background = GradientDrawable().apply {
                    setShape(GradientDrawable.OVAL)
                    setColor(Color.TRANSPARENT)
                    setStroke(px(2f), accent)
                }
            }
            addView(ring, FrameLayout.LayoutParams(px(92f), px(92f), Gravity.CENTER))
            if (state == 0 && animOn) {
                ring.alpha = 0f
                loop(ObjectAnimator.ofPropertyValuesHolder(
                    ring,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.7f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.7f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)
                ).apply {
                    duration = 3000L
                    startDelay = i * 1000L
                    interpolator = DecelerateInterpolator(1.1f)
                }, ring)
            } else {
                ring.scaleX = 1.2f + 0.25f * i
                ring.scaleY = ring.scaleX
                ring.alpha = 0.34f - 0.1f * i
            }
        }

        // кот в круглой рамке цвета своего значка
        val catBox = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val badge = FrameLayout(ctx).apply {
            clipChildren = false
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(badgeBg)
                setStroke(px(if (live) 3f else 2f), if (live) accent else ColorUtils.setAlphaComponent(accent, 90))
            }
        }
        val cat = ImageView(ctx).apply {
            setImageResource(catRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            when (state) {
                1 -> setColorFilter(PorterDuffColorFilter(ColorUtils.setAlphaComponent(accent, 80), PorterDuff.Mode.SRC_ATOP))
                2 -> { setColorFilter(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.1f) })); imageAlpha = 190 }
                else -> {}
            }
        }
        badge.addView(cat, FrameLayout.LayoutParams(px(112f), px(112f), Gravity.CENTER))
        catBox.addView(badge, FrameLayout.LayoutParams(px(88f), px(88f), Gravity.CENTER))

        // значок состояния в углу аватарки
        if (state != 2) {
            val st = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(accent); setStroke(px(3f), cutout) }
                translationX = px(31f).toFloat()
                translationY = px(31f).toFloat()
            }
            st.addView(
                VpnIconView(ctx, if (state == 0) VIc.CHECK else VIc.SNOW, Color.WHITE),
                FrameLayout.LayoutParams(px(18f), px(18f), Gravity.CENTER)
            )
            catBox.addView(st, FrameLayout.LayoutParams(px(30f), px(30f), Gravity.CENTER))
        }
        addView(catBox, FrameLayout.LayoutParams(px(88f), px(88f), Gravity.CENTER))

        // спящий кот: «z Z Z» поднимаются и растворяются; кот дышит
        if (state == 2) {
            val zs = listOf("z" to 13f, "Z" to 16f, "Z" to 20f)
            for ((k, z) in zs.withIndex()) {
                val baseY = px(-26f - k * 13f).toFloat()
                val tv = TextView(ctx).apply {
                    text = z.first
                    textSize = z.second
                    setTextColor(accent)
                    setTypeface(typeface, Typeface.BOLD)
                    translationX = px(40f + k * 11f).toFloat()
                    translationY = baseY
                    alpha = if (animOn) 0f else 0.75f
                }
                addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                if (animOn) loop(ObjectAnimator.ofPropertyValuesHolder(
                    tv,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, baseY, baseY - px(10f)),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.85f, 0f)
                ).apply {
                    duration = 2400L
                    startDelay = k * 600L
                    interpolator = LinearInterpolator()
                }, tv)
            }
            if (animOn) loop(ObjectAnimator.ofPropertyValuesHolder(
                cat,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f)
            ).apply {
                duration = 2600L
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            })
        }
        // бодрый кот слегка парит
        if (state == 0 && animOn) loop(ObjectAnimator.ofFloat(catBox, View.TRANSLATION_Y, 0f, -4f * d).apply {
            duration = 2000L
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        })

        // нажатие — упругий отскок
        catBox.isClickable = true
        catBox.setOnClickListener { v ->
            if (Store.haptics) v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            v.animate().cancel()
            v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120L).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(280L).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
        }
    }

    private fun sync() {
        val run = animOn && isAttachedToWindow && windowVisibility == View.VISIBLE
        for (a in anims) {
            if (run) { if (!a.isStarted) a.start() } else if (a.isStarted) a.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sync()
    }

    override fun onDetachedFromWindow() {
        for (a in anims) a.cancel()
        super.onDetachedFromWindow()
    }
}

// ---------- кольцо дней ----------

internal class DaysRingView(
    ctx: Context, private val color: Int, private val track: Int, private val textColor: Int, private val muteColor: Int,
    private val days: Int, total: Int, private val word: String, private val animOn: Boolean
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val frac = if (total <= 0) 0f else (days.toFloat() / total).coerceIn(0f, 1f)
    private var cur = if (animOn) 0f else frac
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f * d; strokeCap = Paint.Cap.ROUND }
    private val numP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(20f)
        this.color = textColor
    }
    private val wordP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        this.color = muteColor
    }
    private val box = RectF()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animOn && cur < frac) {
            ValueAnimator.ofFloat(cur, frac).apply {
                duration = 1300L
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { cur = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val pad = 3.5f * d + 3.5f * d
        box.set(pad, pad, width - pad, height - pad)
        arcP.color = track
        c.drawArc(box, 0f, 360f, false, arcP)
        if (cur > 0f) {
            arcP.color = color
            c.drawArc(box, -90f, 360f * cur, false, arcP)
        }
        val cx = width / 2f
        val cy = height / 2f
        c.drawText(days.toString(), cx, cy + 2f * d, numP)
        c.drawText(word, cx, cy + 15f * d, wordP)
    }
}

// ---------- шкала ----------

internal class GradBarView(
    ctx: Context, private val c1: Int, private val c2: Int, private val track: Int,
    private val frac: Float, private val animOn: Boolean
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var cur = if (animOn) 0f else frac.coerceIn(0f, 1f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val to = frac.coerceIn(0f, 1f)
        if (animOn && cur < to) {
            ValueAnimator.ofFloat(cur, to).apply {
                duration = 1200L
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { cur = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        p.shader = null
        p.color = track
        box.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(box, r, r, p)
        val w = width * cur
        if (w > 0f) {
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, c1, c2, Shader.TileMode.CLAMP)
            box.set(0f, 0f, maxOf(w, h), h)
            c.drawRoundRect(box, r, r, p)
        }
    }
}

// ---------- кнопка с «бликом» ----------

/**
 * Кнопка с бегущим бликом. Цикл 3,8 с: блик бежит первые 55%, остальное — пауза. В паузе кнопка не перерисовывается
 * (раньше перерисовка шла все 100% времени). Шейдер и контур создаются один раз, а не на каждом кадре.
 */
internal class ShineLayout(ctx: Context, private val radiusPx: Float, private val animOn: Boolean) : LinearLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val m = Matrix()
    private var shader: LinearGradient? = null
    private var shaderW = 0f
    private var t = 0f
    private var shining = false
    private var anim: ValueAnimator? = null

    init { setWillNotDraw(false) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clip.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
    }

    private fun sync() {
        val run = animOn && isAttachedToWindow && windowVisibility == View.VISIBLE
        if (run && anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    t = it.animatedValue as Float
                    val on = t <= 0.55f
                    if (on || shining) invalidate()   // один лишний кадр после блика — стереть его
                    shining = on
                }
                start()
            }
        } else if (!run && anim != null) {
            anim?.cancel()
            anim = null
            t = 1f          // пауза цикла: блика на кнопке нет
            shining = false
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sync()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!animOn || width == 0) return
        val k = t / 0.55f           // блик бежит первые 55% цикла, остальное — пауза
        if (k > 1f) return
        val bw = width * 0.34f
        val x = -bw + (width + bw * 2f) * k
        val sh = shader.takeIf { shaderW == bw } ?: LinearGradient(
            0f, 0f, bw, 0f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        ).also { shader = it; shaderW = bw; p.shader = it }
        m.setTranslate(x, 0f)
        sh.setLocalMatrix(m)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(x, 0f, x + bw, height.toFloat(), p)
        canvas.restore()
    }
}

// ---------- сборка элементов ----------

private const val H_MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val H_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun violetMix(base: Int) = ColorUtils.blendARGB(base, 0xFF8B7CFF.toInt(), 0.4f)

/** Карточка-шапка раздела. state: 0 — защита включена, 1 — заморожена, 2 — нет подписки. В центре — кот-аватарка. */
internal fun MainActivity.vpnHero(state: Int, title: String, sub: String, stats: List<Pair<String, String>>): LinearLayout {
    val sc = when (state) { 0 -> GREEN; 1 -> CYAN; else -> MUTE }
    val bg: Drawable = if (state == 2) shape(CARD, 26, LINE) else GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(mix(sc, 0.22f), CARD)
    ).apply {
        cornerRadius = corner(26)
        setStroke(dp(1), ColorUtils.blendARGB(LINE, sc, 0.4f))
    }
    // кот — тот, что выбран значком приложения: аватарка цвета значка, внутри его рисунок
    val entry = AppIcon.all[AppIcon.current(this).coerceIn(0, AppIcon.all.size - 1)]
    val base = getColor(entry.bgColor)
    val badgeBg = when (state) {
        0 -> base
        1 -> ColorUtils.blendARGB(base, CYAN, 0.45f)
        else -> ColorUtils.blendARGB(base, MUTE, 0.75f)
    }
    val cutout = if (state == 2) CARD else mix(sc, 0.11f)   // цвет карточки под значком состояния
    val anim = Store.uiAnim && FxView.allowed(this)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = bg
        setPadding(dp(16), dp(12), dp(16), dp(16))
        layoutParams = lp(14)
        addView(CatHeroView(this@vpnHero, sc, state, anim, badgeBg, entry.fg, cutout).apply {
            layoutParams = LinearLayout.LayoutParams(H_MATCH, dp(148))
            contentDescription = title
        })
        addView(txt(title, 20f, TEXT, true).apply { gravity = Gravity.CENTER })
        addView(txt(sub, 13f, MUTE).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(H_MATCH, H_WRAP).apply { topMargin = dp(4) }
        })
        if (stats.isNotEmpty()) {
            addView(LinearLayout(this@vpnHero).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(H_MATCH, H_WRAP).apply { topMargin = dp(14) }
                for ((k, st) in stats.withIndex()) {
                    addView(LinearLayout(this@vpnHero).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = shape(ColorUtils.setAlphaComponent(BG, 170), 14, LINE)
                        setPadding(dp(6), dp(9), dp(6), dp(9))
                        layoutParams = LinearLayout.LayoutParams(0, H_WRAP, 1f).apply { if (k > 0) leftMargin = dp(8) }
                        addView(txt(st.first, 11f, MUTE).apply { gravity = Gravity.CENTER; maxLines = 1 })
                        addView(txt(st.second, 14f, TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
                    })
                }
            })
        }
    }
}

/** Плитка действия: круглая цветная иконка и подпись. */
internal fun MainActivity.vpnTile(icon: VIc, label: String, color: Int, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ripple(BG, 16, LINE)
        setPadding(dp(4), dp(12), dp(4), dp(10))
        isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
        pressable(this)
        addView(FrameLayout(this@vpnTile).apply {
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(mix(color, 0.2f)) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            addView(VpnIconView(this@vpnTile, icon, color).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
        })
        addView(txt(label, 11.5f, TEXT, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(H_MATCH, H_WRAP).apply { topMargin = dp(8) }
        })
    }

/** Раскладывает плитки по cols в ряд на равных долях; неполный ряд добивается пустыми местами. */
internal fun MainActivity.vpnGrid(tiles: List<View>, cols: Int = 3, bottom: Int = 0): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(bottom)
    }
    var i = 0
    while (i < tiles.size) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(H_MATCH, H_WRAP).apply { if (box.childCount > 0) topMargin = dp(8) }
        }
        for (k in 0 until cols) {
            val v: View = if (i + k < tiles.size) tiles[i + k] else Space(this)
            v.layoutParams = LinearLayout.LayoutParams(0, H_MATCH, 1f).apply { if (k > 0) leftMargin = dp(8) }
            row.addView(v)
        }
        box.addView(row)
        i += cols
    }
    return box
}

/** Главная кнопка: градиент и бегущий блик. */
internal fun MainActivity.vpnPrimary(label: String, icon: VIc?, onClick: () -> Unit): ShineLayout {
    val r = corner(16)
    val grad = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BLUE, violetMix(BLUE))).apply { cornerRadius = r }
    return ShineLayout(this, r, Store.uiAnim).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(Theme.rippleColor), grad, shape(Color.WHITE, 16))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(8)
        isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
        pressable(this)
        if (icon != null) {
            addView(VpnIconView(this@vpnPrimary, icon, Color.BLACK).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
            })
        }
        addView(txt(label, 15f, Color.BLACK, true))
    }
}

/** Подпись раздела с маленькой иконкой. */
internal fun MainActivity.vpnLabel(icon: VIc, text: String): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(8).apply { topMargin = dp(6) }
        addView(VpnIconView(this@vpnLabel, icon, MUTE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) }
        })
        addView(txt(text, 13f, MUTE, true))
    }

/** Пустая карточка (нет подписок / покупка недоступна). btnText = null — без кнопки. */
internal fun MainActivity.vpnEmptyCard(title: String, text: String, btnText: String?, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = shape(CARD, 22, LINE)
        setPadding(dp(18), dp(20), dp(18), dp(16))
        layoutParams = lp(12)
        addView(FrameLayout(this@vpnEmptyCard).apply {
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(mix(BLUE, 0.16f)) }
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { bottomMargin = dp(12) }
            addView(VpnIconView(this@vpnEmptyCard, VIc.BAG, BLUE).apply {
                layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
            })
        })
        addView(txt(title, 17f, TEXT, true).apply { gravity = Gravity.CENTER; layoutParams = lp(4) })
        addView(txt(text, 13f, MUTE).apply { gravity = Gravity.CENTER; layoutParams = lp(14) })
        if (btnText != null) addView(vpnPrimary(btnText, null, onClick).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 })
    }

// ---------- переключатель вкладок со скользящей «капсулой» ----------

internal class VpnSeg(val root: View, val select: (Int, Boolean) -> Unit)

internal fun MainActivity.vpnSegment(names: List<String>, icons: List<VIc>, start: Int, onPick: (Int) -> Unit): VpnSeg {
    val n = names.size
    var cur = start
    val ind = View(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BLUE, violetMix(BLUE))).apply { cornerRadius = corner(14) }
        layoutParams = FrameLayout.LayoutParams(0, H_MATCH)
    }
    val labels = ArrayList<TextView>()
    val glyphs = ArrayList<VpnIconView>()
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(H_MATCH, H_WRAP)
    }
    val cells = ArrayList<View>()
    for (i in 0 until n) {
        val glyph = VpnIconView(this, icons[i], TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(7) }
        }
        val label = txt(names[i], 14f, TEXT, true)
        glyphs.add(glyph)
        labels.add(label)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, H_WRAP, 1f)
            isClickable = true
            contentDescription = names[i]
            addView(glyph)
            addView(label)
        }
        cells.add(cell)
        row.addView(cell)
    }
    val root = FrameLayout(this).apply {
        background = shape(CARD, 18, LINE)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = lp(14)
        addView(ind)
        addView(row)
    }

    fun paint(sel: Int) {
        for (k in 0 until n) {
            val col = if (k == sel) Color.BLACK else TEXT
            labels[k].setTextColor(col)
            glyphs[k].setTint(col)
        }
    }

    fun select(i: Int, animateIt: Boolean) {
        cur = i
        paint(i)
        val w = ind.layoutParams.width
        if (animateIt && Store.uiAnim) {
            ind.animate().translationX((i * w).toFloat()).setDuration(340L)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        } else {
            ind.animate().cancel()
            ind.translationX = (i * w).toFloat()
        }
    }

    root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        val w = ((r - l) - dp(8)) / n
        if (w > 0 && ind.layoutParams.width != w) {
            ind.layoutParams = ind.layoutParams.apply { width = w }
            ind.translationX = (cur * w).toFloat()
        }
    }
    for ((i, cell) in cells.withIndex()) {
        pressable(cell)
        cell.setOnClickListener { if (cur != i) { select(i, true); onPick(i) } }
    }
    paint(start)
    return VpnSeg(root, ::select)
}
