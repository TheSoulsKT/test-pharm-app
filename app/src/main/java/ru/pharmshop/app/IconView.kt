package ru.pharmshop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Ic { STAR, GIFT, CROWN, BOX, KEY, DB, AT, SEND, USER, BACK, GEAR, BAG, CHAT, GROUP }

/** Линейные иконки в стиле сайта. Рисуются в сетке 24x24. */
class IconView(ctx: Context, private val kind: Ic, private val tint: Int) : View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = tint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
        textAlign = Paint.Align.CENTER
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Иконка никогда не бывает больше 28dp, что бы ни пришло от родителя. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (28 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(clamp(widthMeasureSpec, cap), clamp(heightMeasureSpec, cap))
    }

    private fun clamp(spec: Int, cap: Int): Int {
        val size = MeasureSpec.getSize(spec)
        return when (MeasureSpec.getMode(spec)) {
            MeasureSpec.UNSPECIFIED -> cap
            else -> minOf(size, cap)
        }
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        c.scale(s / 24f, s / 24f)
        // внутренние поля: рисунок занимает ~84% рамки, как у иконок референса
        c.translate(12f, 12f)
        c.scale(0.84f, 0.84f)
        c.translate(-12f, -12f)
        when (kind) {
            Ic.STAR -> {
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = PI / 5 * i - PI / 2
                    val x = 12f + (r * cos(a)).toFloat()
                    val y = 12.6f + (r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, stroke)
            }
            Ic.GIFT -> {
                c.drawRoundRect(RectF(3.5f, 11f, 20.5f, 21f), 1.5f, 1.5f, stroke)
                c.drawRoundRect(RectF(2.5f, 7.5f, 21.5f, 11f), 1.5f, 1.5f, stroke)
                c.drawLine(12f, 7.5f, 12f, 21f, stroke)
                val p = Path()
                p.moveTo(12f, 7.5f)
                p.cubicTo(9.5f, 2.5f, 5.5f, 4f, 8.5f, 7.5f)
                p.moveTo(12f, 7.5f)
                p.cubicTo(14.5f, 2.5f, 18.5f, 4f, 15.5f, 7.5f)
                c.drawPath(p, stroke)
            }
            Ic.CROWN -> {
                val p = Path()
                p.moveTo(3.5f, 18f)
                p.lineTo(3f, 7f)
                p.lineTo(8.5f, 12f)
                p.lineTo(12f, 5f)
                p.lineTo(15.5f, 12f)
                p.lineTo(21f, 7f)
                p.lineTo(20.5f, 18f)
                p.close()
                c.drawPath(p, stroke)
                c.drawLine(4f, 21f, 20f, 21f, stroke)
            }
            Ic.BOX -> {
                c.drawRoundRect(RectF(4f, 8f, 20f, 12f), 1.2f, 1.2f, stroke)
                c.drawRoundRect(RectF(5.5f, 12f, 18.5f, 20.5f), 1.2f, 1.2f, stroke)
                c.drawLine(12f, 8f, 12f, 20.5f, stroke)
            }
            Ic.KEY -> {
                c.drawCircle(8f, 12f, 3.5f, stroke)
                c.drawLine(11.5f, 12f, 20f, 12f, stroke)
                c.drawLine(17f, 12f, 17f, 15f, stroke)
                c.drawLine(20f, 12f, 20f, 14.5f, stroke)
            }
            Ic.DB -> {
                c.drawOval(RectF(5f, 3.5f, 19f, 9.5f), stroke)
                c.drawLine(5f, 6.5f, 5f, 17.5f, stroke)
                c.drawLine(19f, 6.5f, 19f, 17.5f, stroke)
                c.drawArc(RectF(5f, 9.5f, 19f, 15f), 0f, 180f, false, stroke)
                c.drawArc(RectF(5f, 14.5f, 19f, 20.5f), 0f, 180f, false, stroke)
            }
            Ic.AT -> {
                c.drawText("@", 12f, 19.5f, fill)
            }
            Ic.SEND -> {
                val p = Path()
                p.moveTo(21f, 3f)
                p.lineTo(14.5f, 21f)
                p.lineTo(10f, 14f)
                p.lineTo(3f, 9.5f)
                p.close()
                c.drawPath(p, stroke)
                c.drawLine(21f, 3f, 10f, 14f, stroke)
            }
            Ic.USER -> {
                c.drawCircle(12f, 8f, 4f, stroke)
                c.drawArc(RectF(4f, 15f, 20f, 31f), 180f, 180f, false, stroke)
            }
            Ic.GEAR -> {
                val p = Path()
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    fun pt(da: Double, r: Float, first: Boolean) {
                        val x = 12f + (r * cos(a + Math.toRadians(da))).toFloat()
                        val y = 12f + (r * sin(a + Math.toRadians(da))).toFloat()
                        if (first) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    pt(-17.0, 7.3f, i == 0)
                    pt(-10.0, 9.6f, false)
                    pt(10.0, 9.6f, false)
                    pt(17.0, 7.3f, false)
                }
                p.close()
                c.drawPath(p, stroke)
                c.drawCircle(12f, 12f, 3.1f, stroke)
            }
            Ic.BAG -> {
                val p = Path()
                p.moveTo(5f, 8f)
                p.lineTo(19f, 8f)
                p.lineTo(20f, 20f)
                p.lineTo(4f, 20f)
                p.close()
                c.drawPath(p, stroke)
                c.drawArc(RectF(8.5f, 3.5f, 15.5f, 12.5f), 180f, 180f, false, stroke)
            }
            Ic.CHAT -> {
                val p = Path()
                p.moveTo(5f, 4.5f)
                p.lineTo(19f, 4.5f)
                p.quadTo(21f, 4.5f, 21f, 6.5f)
                p.lineTo(21f, 14.5f)
                p.quadTo(21f, 16.5f, 19f, 16.5f)
                p.lineTo(11f, 16.5f)
                p.lineTo(6.5f, 20.5f)
                p.lineTo(6.5f, 16.5f)
                p.lineTo(5f, 16.5f)
                p.quadTo(3f, 16.5f, 3f, 14.5f)
                p.lineTo(3f, 6.5f)
                p.quadTo(3f, 4.5f, 5f, 4.5f)
                p.close()
                c.drawPath(p, stroke)
            }
            Ic.GROUP -> {
                c.drawCircle(8f, 8f, 3.4f, stroke)
                c.drawArc(RectF(1f, 14.5f, 15f, 28.5f), 180f, 180f, false, stroke)
                c.drawCircle(17f, 8.5f, 2.7f, stroke)
                c.drawArc(RectF(13f, 14.5f, 21f, 22.5f), 180f, 180f, false, stroke)
            }
            Ic.BACK -> {
                val p = Path()
                p.moveTo(15f, 5f)
                p.lineTo(8f, 12f)
                p.lineTo(15f, 19f)
                c.drawPath(p, stroke)
            }
        }
        c.restore()
    }
}

/** Логотип: синий квадрат с чёрным котом (нарисован по референсу). */
class LogoView(ctx: Context) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.accent }
    private val cat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0B0D") }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) return
        c.drawRoundRect(RectF(0f, 0f, w, w), w * 0.24f, w * 0.24f, bg)

        val p = Path()
        p.moveTo(0.20f * w, 0.55f * w)
        p.lineTo(0.20f * w, 0.20f * w)
        p.lineTo(0.38f * w, 0.34f * w)
        p.quadTo(0.50f * w, 0.30f * w, 0.62f * w, 0.34f * w)
        p.lineTo(0.80f * w, 0.20f * w)
        p.lineTo(0.80f * w, 0.55f * w)
        p.quadTo(0.80f * w, 0.82f * w, 0.50f * w, 0.82f * w)
        p.quadTo(0.20f * w, 0.82f * w, 0.20f * w, 0.55f * w)
        p.close()
        c.drawPath(p, cat)

        eye.strokeWidth = w * 0.035f
        val e = Path()
        e.moveTo(0.30f * w, 0.58f * w)
        e.lineTo(0.36f * w, 0.50f * w)
        e.lineTo(0.42f * w, 0.58f * w)
        e.moveTo(0.58f * w, 0.58f * w)
        e.lineTo(0.64f * w, 0.50f * w)
        e.lineTo(0.70f * w, 0.58f * w)
        c.drawPath(e, eye)
    }
}
