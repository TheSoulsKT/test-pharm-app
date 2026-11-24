package ru.pharmshop.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Общий внешний вид чатов: компактные «пузыри» сообщений, поле ввода-«таблетка» и аватарки.
// Используется общим чатом, личными сообщениями и чатом поддержки.

/** Аватарки других пользователей, уже загруженные с сервера: uid → (версия, картинка). Живёт, пока открыто приложение. */
private object ChatAvatars {
    val cache = HashMap<Long, Pair<Long, Bitmap>>()
    val loading = HashSet<Long>()
}

private fun MainActivity.circle(v: View) {
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
    }
    v.clipToOutline = true
}

/**
 * Круглая аватарка пользователя чата. Своя берётся прямо из профиля, чужая — с сервера (там лежит копия 128×128,
 * которую человек сам поставил в профиле). Пока картинка грузится или её нет — кружок с первой буквой ника.
 * [av] — версия аватарки с сервера (0 — аватарки нет).
 */
internal fun MainActivity.chatAvatar(uid: Long, nick: String, av: Long, sizeDp: Int): View {
    val box = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }
    box.addView(TextView(this).apply {
        text = nick.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        textSize = sizeDp * 0.42f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(nickColor(nick)) }
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    })
    val iv = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        circle(this)
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
    box.addView(iv)

    fun show(b: Bitmap) { iv.setImageBitmap(b); iv.visibility = View.VISIBLE }

    if (uid == CommunityState.myUid && uid != 0L) {
        Avatar.bitmap()?.let { show(it) }          // своя — всегда та же, что в профиле
        return box
    }
    if (av <= 0L) { ChatAvatars.cache.remove(uid); return box }
    val cached = ChatAvatars.cache[uid]
    if (cached != null && cached.first == av) { show(cached.second); return box }
    if (ChatAvatars.loading.add(uid)) {
        io.execute {
            try {
                val r = ChatApi.avatarGet(uid)
                val bytes = android.util.Base64.decode(r.optString("image"), android.util.Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    ChatAvatars.cache[uid] = r.optLong("av") to bmp
                    ui { show(bmp) }
                }
            } catch (e: Exception) {
                // останется кружок с буквой; при следующем открытии чата попробуем снова
            } finally {
                ChatAvatars.loading.remove(uid)
            }
        }
    } else {
        // эту же аватарку уже грузит соседнее сообщение: подхватим, когда будет готова
        main.postDelayed({ ChatAvatars.cache[uid]?.let { show(it.second) } }, 1200L)
    }
    return box
}

/** Загружает на сервер аватарку из профиля, если она изменилась (или убрана) с прошлого раза. Нужен выбранный ник в чате. */
internal fun MainActivity.syncMyAvatar() {
    if (Store.chatNick.isEmpty()) return
    val stamp = Avatar.stamp()
    if (stamp == Store.avatarSynced) return
    io.execute {
        try {
            ChatApi.avatarSet(if (stamp.isEmpty()) "" else Avatar.thumbBase64())
            Store.avatarSynced = stamp
        } catch (e: Exception) {
            AppLog.e("chat", "avatar sync: ${e.message}")
        }
    }
}

// ---------- пузырь сообщения ----------

private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
private val dateFmt = SimpleDateFormat("d MMMM", Locale.getDefault())
internal val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

internal fun dayKey(ts: Long): String = dayFmt.format(Date(ts))

/** Небольшая плашка с датой между сообщениями разных дней. */
internal fun MainActivity.dateChip(ts: Long): View =
    txt(dateFmt.format(Date(ts)), 11f, MUTE).apply {
        gravity = Gravity.CENTER
        background = shape(mix(MUTE, 0.10f), 12)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6); bottomMargin = dp(10)
        }
    }

/** Ширина пузыря: не больше 72% экрана (минус место под аватарку). */
internal fun MainActivity.bubbleMax(withAvatar: Boolean): Int {
    val w = resources.displayMetrics.widthPixels - dp(36)   // поля экрана
    return ((w - if (withAvatar) dp(36) else 0) * 0.82f).toInt()
}

/**
 * Пузырь сообщения: небольшой «хвостик» со стороны автора, время внутри справа внизу.
 * [head] — необязательная строка сверху (ник или «Поддержка»). Возвращает пузырь и поле времени (под отметки «прочитано»).
 */
internal fun MainActivity.messageBubble(
    body: String, own: Boolean, timeText: String, head: View?, maxW: Int
): Pair<LinearLayout, TextView> {
    val r = corner(16)
    val t = dp(4).toFloat()
    val bg = GradientDrawable().apply {
        setColor(if (own) BLUE else CARD)
        if (!own) setStroke(dp(1), LINE)
        cornerRadii = if (own) floatArrayOf(r, r, r, r, t, t, r, r) else floatArrayOf(r, r, r, r, r, r, t, t)
    }
    val time = txt(timeText, 10f, if (own) ColorUtils.setAlphaComponent(Color.BLACK, 150) else MUTE).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; topMargin = dp(1)
        }
    }
    val bubble = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg
        setPadding(dp(11), dp(6), dp(11), dp(5))
        if (head != null) addView(head)
        if (body.isNotEmpty()) {
            val tv = TextView(this@messageBubble).apply {
                text = body
                textSize = 14.5f * Theme.textScale
                setTextColor(if (own) Color.BLACK else TEXT)
            }
            // Ширину считаем сами: раскладываем текст по максимальной ширине и берём самую длинную строку.
            // Так пузырь точно облегает сообщение и не зависит от того, как родитель его измеряет.
            val avail = (maxW - dp(22)).coerceAtLeast(dp(60))
            val widest = try {
                val lay = android.text.StaticLayout.Builder.obtain(body, 0, body.length, android.text.TextPaint(tv.paint), avail).build()
                var w = 0f
                for (i in 0 until lay.lineCount) w = maxOf(w, lay.getLineWidth(i))
                Math.ceil(w.toDouble()).toInt() + dp(2)
            } catch (e: Exception) { avail }
            tv.layoutParams = LinearLayout.LayoutParams(minOf(widest, avail), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(tv)
        }
        addView(time)
    }
    return bubble to time
}

/** Отметка под своим сообщением: «✓» доставлено, «✓✓» прочитано. */
internal fun MainActivity.paintReceipt(v: TextView, base: String, read: Boolean) {
    v.text = base + if (read) "  ✓✓" else "  ✓"
    v.setTextColor(ColorUtils.setAlphaComponent(Color.BLACK, if (read) 235 else 120))
}

/** Строка сообщения: у чужих слева аватарка (если нужна), у своих — прижата вправо. */
internal fun MainActivity.messageRow(own: Boolean, avatar: View?, content: View, gapTop: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = (if (own) Gravity.END else Gravity.START) or Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(gapTop) }
        if (avatar != null) {
            (avatar.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
            addView(avatar)
        }
        addView(content)
    }

// ---------- поле ввода ----------

/** Поле ввода-«таблетка» для сообщений. */
internal fun MainActivity.chatField(): EditText = EditText(this).apply {
    hint = "Сообщение"
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    setTextColor(TEXT)
    setHintTextColor(MUTE)
    textSize = 15f * Theme.textScale
    maxLines = 4
    minHeight = dp(44)
    gravity = Gravity.CENTER_VERTICAL
    filters = arrayOf(InputFilter.LengthFilter(1000))
    background = shape(CARD, 22, LINE)
    setPadding(dp(16), dp(10), dp(16), dp(10))
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) }
}

private fun MainActivity.roundIcon(fill: Int, ring: Int, onClick: () -> Unit, build: FrameLayout.() -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (ring != 0) setStroke(dp(1), ring)
        }
        isClickable = true
        setOnClickListener { onClick() }
        pressable(this)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        build()
    }

/** Круглая кнопка «отправить» со стрелкой. */
internal fun MainActivity.sendButton(onClick: () -> Unit): FrameLayout =
    roundIcon(BLUE, 0, onClick) {
        contentDescription = "Отправить"
        addView(IconView(this@sendButton, Ic.SEND, Color.BLACK).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
        // поверх стрелки — число секунд до следующего сообщения (показывается только во время паузы)
        addView(txt("", 16f, TEXT, true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
    }

/** Пауза между сообщениями: пока [sec] > 0, вместо стрелки на кнопке — оставшиеся секунды. 0 — обычный вид. */
internal fun MainActivity.setSendCooldown(btn: View, sec: Int) {
    val f = btn as? FrameLayout ?: return
    val icon = f.getChildAt(0) ?: return
    val label = f.getChildAt(1) as? TextView ?: return
    val on = sec > 0
    icon.visibility = if (on) View.GONE else View.VISIBLE
    label.visibility = if (on) View.VISIBLE else View.GONE
    if (on) label.text = sec.toString()
    (f.background as? GradientDrawable)?.apply {
        setColor(if (on) CARD else BLUE)
        setStroke(if (on) dp(1) else 0, LINE)
    }
    f.contentDescription = if (on) "Следующее сообщение через $sec с" else "Отправить"
}

/** Круглая кнопка-скрепка. */
internal fun MainActivity.attachButton(onClick: () -> Unit): View =
    roundIcon(CARD, LINE, onClick) {
        contentDescription = "Прикрепить фото"
        addView(txt("📎", 18f).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
    }

/** Нижняя строка чата: [скрепка] [поле] [отправить]. */
internal fun MainActivity.composerRow(attach: View?, field: View, send: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
        if (attach != null) addView(attach)
        addView(field)
        // у первого элемента без скрепки отступ слева не нужен
        if (attach == null) (field.layoutParams as LinearLayout.LayoutParams).leftMargin = 0
        addView(send)
    }
