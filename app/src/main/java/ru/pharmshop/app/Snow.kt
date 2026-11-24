package ru.pharmshop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

/** Лёгкий снегопад поверх экрана (новогоднее оформление). Касания пропускает к экрану под собой. */
class SnowView(ctx: Context) : View(ctx) {
    private class Flake(var x: Float, var y: Float, val r: Float, val speed: Float, val drift: Float, val phase: Float, val alpha: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.snowColor }
    private val rnd = java.util.Random()
    private val flakes = ArrayList<Flake>()
    private var last = 0L
    private var t = 0f

    private fun make(w: Int, h: Int, anywhere: Boolean): Flake {
        val d = resources.displayMetrics.density
        val r = (1.5f + rnd.nextFloat() * 2.5f) * d
        return Flake(
            rnd.nextFloat() * w,
            if (anywhere) rnd.nextFloat() * h else -r * 2,
            r,
            (28f + r / d * 9f) * d,            // крупные снежинки падают быстрее
            (6f + rnd.nextFloat() * 14f) * d,  // раскачивание вбок
            rnd.nextFloat() * 6.28f,
            110 + rnd.nextInt(110)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        flakes.clear()
        if (w > 0 && h > 0) repeat(45) { flakes.add(make(w, h, true)) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        t += dt
        for (f in flakes) {
            f.y += f.speed * dt
            if (f.y > height + f.r) {
                val n = make(width, height, false)
                f.x = n.x
                f.y = n.y
            }
            paint.alpha = f.alpha
            canvas.drawCircle(f.x + sin(t * 0.8f + f.phase) * f.drift, f.y, f.r, paint)
        }
        postInvalidateOnAnimation()
    }
}
