package ru.pharmshop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Анимированный фон оформления поверх экрана: снег, листья, лепестки, солнечные блики, звёзды, «рэп-символы».
 * Касания пропускает к экрану под собой.
 */
class FxView(ctx: Context, private val kind: Fx) : View(ctx) {
    private class P(
        var x: Float, var y: Float, val s: Float, val v: Float, val sway: Float, val ph: Float,
        val a: Int, var rot: Float, val vr: Float, val col: Int, val ch: String
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rnd = java.util.Random()
    private val ps = ArrayList<P>()
    private val path = Path()
    private var last = 0L
    private var t = 0f

    // падающая звезда (только для «Космоса»)
    private var shoot = -1f
    private var shootIn = 2.5f
    private var sx = 0f
    private var sy = 0f

    private fun pick(vararg c: String): Int = Color.parseColor(c[rnd.nextInt(c.size)])

    private fun make(w: Int, h: Int, anywhere: Boolean): P {
        val d = resources.displayMetrics.density
        val s: Float; val v: Float; val sway: Float; val a: Int; val col: Int
        var vr = 0f
        var ch = ""
        when (kind) {
            Fx.SNOW -> {
                s = (1.5f + rnd.nextFloat() * 2.5f) * d
                v = (28f + s / d * 9f) * d
                sway = (6f + rnd.nextFloat() * 14f) * d
                a = 110 + rnd.nextInt(110); col = Theme.snowColor
            }
            Fx.LEAVES -> {
                s = (5f + rnd.nextFloat() * 4f) * d
                v = (32f + rnd.nextFloat() * 30f) * d
                sway = (16f + rnd.nextFloat() * 26f) * d
                a = 190 + rnd.nextInt(60)
                col = pick("#E8833A", "#C2410C", "#F2B33D", "#9A3412", "#D97706")
                vr = (rnd.nextFloat() - 0.5f) * 140f
            }
            Fx.PETALS -> {
                s = (3.5f + rnd.nextFloat() * 3f) * d
                v = (24f + rnd.nextFloat() * 26f) * d
                sway = (18f + rnd.nextFloat() * 28f) * d
                a = 170 + rnd.nextInt(70)
                col = pick("#F9A8D4", "#F472B6", "#FBCFE8", "#FFFFFF")
                vr = (rnd.nextFloat() - 0.5f) * 100f
            }
            Fx.SUN -> {
                s = (3f + rnd.nextFloat() * 9f) * d
                v = -(14f + rnd.nextFloat() * 22f) * d
                sway = (8f + rnd.nextFloat() * 14f) * d
                a = (if (Theme.light) 70 else 50) + rnd.nextInt(70)
                col = pick("#FFD54F", "#FFB300", "#FF8A3D", "#FFFFFF")
            }
            Fx.STARS -> {
                s = (0.6f + rnd.nextFloat() * 1.6f) * d
                v = 0f; sway = 0f
                a = 120 + rnd.nextInt(135)
                col = if (Theme.light) pick("#7C6CF5", "#9A8CFF") else pick("#FFFFFF", "#CFD3FF", "#B9A8FF")
            }
            Fx.RAP -> {
                s = (9f + rnd.nextFloat() * 9f) * d
                v = (34f + rnd.nextFloat() * 36f) * d
                sway = (8f + rnd.nextFloat() * 14f) * d
                a = 90 + rnd.nextInt(110)
                col = if (rnd.nextInt(3) == 0) Theme.accent else pick("#FFD700", "#FFFFFF", "#FFD700")
                vr = (rnd.nextFloat() - 0.5f) * 40f
                ch = arrayOf("$", "◆", "$", "✦")[rnd.nextInt(4)]
            }
            Fx.NONE -> { s = d; v = 0f; sway = 0f; a = 0; col = Color.TRANSPARENT }
        }
        val y = when {
            anywhere -> rnd.nextFloat() * h
            v < 0 -> h + s * 2
            else -> -s * 2
        }
        return P(rnd.nextFloat() * w, y, s, v, sway, rnd.nextFloat() * 6.28f, a, rnd.nextFloat() * 360f, vr, col, ch)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        ps.clear()
        if (w <= 0 || h <= 0) return
        val n = when (kind) {
            Fx.SNOW -> 45; Fx.LEAVES -> 26; Fx.PETALS -> 30; Fx.SUN -> 22
            Fx.STARS -> 70; Fx.RAP -> 22; Fx.NONE -> 0
        }
        val k = when (Store.fxDensity) { 0 -> 0.5f; 2 -> 1.7f; else -> 1f }
        repeat(if (n > 0) maxOf(1, (n * k).toInt()) else 0) { ps.add(make(w, h, true)) }
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        t += dt
        for (i in ps.indices) {
            val p = ps[i]
            if (kind != Fx.STARS) {
                p.y += p.v * dt
                p.rot += p.vr * dt
                if (p.v > 0 && p.y > height + p.s * 2 || p.v < 0 && p.y < -p.s * 2) {
                    val n = make(width, height, false)
                    p.x = n.x
                    p.y = n.y
                }
            }
            val x = p.x + sin(t * 0.8f + p.ph) * p.sway
            paint.style = Paint.Style.FILL
            paint.color = p.col
            when (kind) {
                Fx.SNOW -> { paint.alpha = p.a; c.drawCircle(x, p.y, p.s, paint) }
                Fx.LEAVES, Fx.PETALS -> {
                    val k = if (kind == Fx.PETALS) 0.6f else 0.85f
                    path.reset()
                    path.moveTo(-p.s, 0f)
                    path.quadTo(0f, -p.s * k, p.s, 0f)
                    path.quadTo(0f, p.s * k, -p.s, 0f)
                    paint.alpha = p.a
                    c.save()
                    c.translate(x, p.y)
                    c.rotate(p.rot)
                    c.drawPath(path, paint)
                    c.restore()
                }
                Fx.SUN -> {
                    paint.alpha = p.a / 2
                    c.drawCircle(x, p.y, p.s, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = resources.displayMetrics.density
                    paint.alpha = p.a
                    c.drawCircle(x, p.y, p.s, paint)
                }
                Fx.STARS -> {
                    val tw = 0.5f + 0.5f * sin(t * 1.5f + p.ph)
                    paint.alpha = (p.a * (0.25f + 0.75f * tw)).toInt().coerceIn(0, 255)
                    c.drawCircle(p.x, p.y, p.s, paint)
                }
                Fx.RAP -> {
                    paint.alpha = p.a
                    paint.textSize = p.s
                    paint.textAlign = Paint.Align.CENTER
                    paint.typeface = Typeface.DEFAULT_BOLD
                    c.save()
                    c.translate(x, p.y)
                    c.rotate(p.rot)
                    c.drawText(p.ch, 0f, p.s * 0.35f, paint)
                    c.restore()
                }
                Fx.NONE -> {}
            }
        }
        if (kind == Fx.STARS) drawShootingStar(c, dt)
        postInvalidateOnAnimation()
    }

    private fun drawShootingStar(c: Canvas, dt: Float) {
        val d = resources.displayMetrics.density
        if (shoot < 0f) {
            shootIn -= dt
            if (shootIn <= 0f) {
                shoot = 0f
                sx = rnd.nextFloat() * width * 0.6f
                sy = rnd.nextFloat() * height * 0.3f
                shootIn = 5f + rnd.nextFloat() * 6f
            }
            return
        }
        shoot += dt / 0.9f
        if (shoot >= 1f) { shoot = -1f; return }
        val dx = 0.85f; val dy = 0.52f
        val run = width * 0.55f * shoot
        val hx = sx + dx * run; val hy = sy + dy * run
        val tail = 90f * d
        streak.strokeWidth = 1.6f * d
        streak.color = if (Theme.light) Color.parseColor("#7C6CF5") else Color.WHITE
        streak.alpha = ((1f - shoot) * 220).toInt().coerceIn(0, 255)
        c.drawLine(hx - dx * tail, hy - dy * tail, hx, hy, streak)
    }

    companion object {
        /** Системная настройка «убрать анимации» отключает и фон. */
        fun allowed(c: Context): Boolean = try {
            Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (e: Exception) { true }
    }
}
