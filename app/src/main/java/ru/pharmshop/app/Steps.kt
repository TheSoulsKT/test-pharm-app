package ru.pharmshop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.sin

/**
 * Шаги заказа полосой: Создан → Оплачен → Выполнен.
 * kind — результат statusKind(): 0 ждёт оплаты, 1 оплачен/в работе, 2 выполнен, 3 отменён.
 */
class StepsView(
    ctx: Context,
    private val kind: Int,
    private val accent: Int,
    private val done: Int,
    private val bad: Int,
    private val track: Int,
    private val textColor: Int,
    private val muteColor: Int
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val labels = listOf("Создан", "Оплачен", "Выполнен")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    /** Текущий шаг: 1 — ждём оплату, 2 — выдаём, 3 — всё готово. */
    private val current: Int get() = when (kind) { 0 -> 1; 1 -> 2; else -> 3 }
    private val cancelled: Boolean get() = kind == 3
    private val animated: Boolean get() = kind == 0 || kind == 1

    override fun onMeasure(w: Int, h: Int) {
        val width = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) (260 * d).toInt() else MeasureSpec.getSize(w)
        setMeasuredDimension(width, (54 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val r = 9f * d
        val pad = 34f * d
        val cy = 14f * d
        val w = width.toFloat()
        val xs = FloatArray(3) { pad + it * (w - 2 * pad) / 2f }
        val t = System.nanoTime() / 1e9f

        // линии между шагами: пройденный участок закрашен, остальное — серый трек
        line.strokeWidth = 3f * d
        for (i in 0 until 2) {
            line.alpha = 255
            line.color = if (cancelled) bad else if (i + 1 < current) done else track
            c.drawLine(xs[i] + r, cy, xs[i + 1] - r, cy, line)
        }

        // участок к текущему шагу мягко «бежит»
        if (animated) {
            val i = current - 1
            val from = xs[i] + r
            val to = xs[i + 1] - r
            val k = 0.5f + 0.5f * sin(t * 2.2f)
            line.color = accent
            c.drawLine(from, cy, from + (to - from) * k, cy, line)
        }

        for (i in 0 until 3) {
            val x = xs[i]
            val state = when {
                cancelled -> if (i == 2) 3 else 1           // 3 — крестик, 1 — пройден (красный)
                i < current -> 1                            // пройден
                i == current -> 2                           // текущий
                else -> 0                                   // впереди
            }
            when (state) {
                1 -> {
                    fill.color = if (cancelled) bad else done
                    c.drawCircle(x, cy, r, fill)
                    drawCheck(c, x, cy, r)
                }
                2 -> {
                    val pulse = 0.5f + 0.5f * sin(t * 3f)
                    fill.color = accent
                    fill.alpha = (60 + 70 * pulse).toInt()
                    c.drawCircle(x, cy, r + 3f * d * pulse, fill)
                    fill.alpha = 255
                    fill.color = accent
                    c.drawCircle(x, cy, r * 0.62f, fill)
                }
                3 -> {
                    fill.color = bad
                    c.drawCircle(x, cy, r, fill)
                    drawCross(c, x, cy, r)
                }
                else -> {
                    line.color = track
                    line.strokeWidth = 2f * d
                    c.drawCircle(x, cy, r - d, line)
                }
            }

            val text = if (cancelled && i == 2) "Отменён" else labels[i]
            label.textSize = 11f * d
            label.isFakeBoldText = (state == 2 || state == 3)
            label.color = when (state) { 3 -> bad; 2 -> textColor; 1 -> if (cancelled) muteColor else textColor; else -> muteColor }
            c.drawText(text, x, cy + r + 17f * d, label)
        }
        if (animated) postInvalidateOnAnimation()
    }

    private fun drawCheck(c: Canvas, x: Float, y: Float, r: Float) {
        mark.color = 0xFF000000.toInt()
        mark.strokeWidth = 2f * d
        path.reset()
        path.moveTo(x - r * 0.45f, y + r * 0.02f)
        path.lineTo(x - r * 0.1f, y + r * 0.38f)
        path.lineTo(x + r * 0.5f, y - r * 0.32f)
        c.drawPath(path, mark)
    }

    private fun drawCross(c: Canvas, x: Float, y: Float, r: Float) {
        mark.color = 0xFF000000.toInt()
        mark.strokeWidth = 2f * d
        val k = r * 0.38f
        c.drawLine(x - k, y - k, x + k, y + k, mark)
        c.drawLine(x - k, y + k, x + k, y - k, mark)
    }
}

/** Полоса шагов в цветах текущей темы. */
internal fun MainActivity.stepsView(kind: Int): StepsView =
    StepsView(this, kind, BLUE, GREEN, RED, LINE, TEXT, MUTE)
