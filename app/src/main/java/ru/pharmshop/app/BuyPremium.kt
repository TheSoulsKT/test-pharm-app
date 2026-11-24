package ru.pharmshop.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import com.hcaptcha.sdk.HCaptchaException
import com.hcaptcha.sdk.HCaptchaTokenResponse
import org.json.JSONObject

// Покупка Premium и оплата.

// ---------- покупка Premium ----------
internal fun MainActivity.showPremium() {
    onHome = false
    Diag.feature("premium")
    val col = screen(bottomBar = true)
    val sid = screenId
    buyHeader(col, "Купить Premium")
    val status = tv("Загружаем данные…", 14f, MUTE)
    col.addView(status)
    riseIn(col)
    io.execute {
        try {
            val c = cfg ?: Api.config().also { cfg = it }
            ui { if (sid == screenId) { status.visibility = View.GONE; buildPremiumForm(col, c) } }
        } catch (e: Exception) {
            ui { if (sid == screenId) status.text = "Не удалось загрузить данные: ${e.message}" }
        }
    }
}

internal fun MainActivity.buildPremiumForm(col: LinearLayout, c: JSONObject) {
    val firstNew = col.childCount
    // доступные сроки: (месяцев, цена от)
    val plans = ArrayList<Pair<Int, String>>()
    val arr = c.optJSONArray("premium")
    if (arr != null) for (i in 0 until arr.length()) {
        val p = arr.optJSONObject(i) ?: continue
        if (p.optBoolean("available", true)) plans.add(Pair(p.optInt("months"), p.optString("total")))
    }
    val methods = shownMethods(c)
    if (plans.isEmpty() || methods.isEmpty()) {
        col.addView(tv("Premium сейчас недоступен", 14f, MUTE))
        return
    }
    c.optString("hcaptcha_sitekey").takeIf { it.isNotEmpty() }?.let { captcha(it) }

    // получатель
    col.addView(sectionLabel("Получатель", true))
    val (userBox, user) = userField()
    user.setText(Store.lastUser)
    col.addView(userBox)

    var months = plans[0].first
    var method = methods[0].first
    var methodName = methods[0].second
    var token = 0

    // закреплённая панель: сумма и кнопка
    val bar = payBar { info ->
        pay(user.text.toString(), months, method, methodName, c, info, "premium")
    }

    fun requestQuote() {
        token++
        val my = token
        bar.cap.text = "К оплате · $months мес."
        popText(bar.total, "…")
        val m = months
        val mt = method
        io.execute {
            try {
                val r = Api.quote(m, mt, "premium")
                ui { if (my == token) popText(bar.total, fmtMoney(r)) }
            } catch (e: Exception) {
                ui { if (my == token) popText(bar.total, "Не удалось рассчитать цену") }
            }
        }
    }

    // срок подписки: широкие карточки со скидкой за длинный срок
    col.addView(sectionLabel("Срок подписки"))
    val planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(planBox)
    planRows(planBox, plans, months) { m -> months = m; requestQuote() }

    // способ оплаты
    col.addView(sectionLabel("Способ оплаты"))
    val methodBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
    col.addView(methodBox)
    methodPicker(methodBox, methods, method) { id, name -> method = id; methodName = name; requestQuote() }

    attachBar(col, bar.root)
    riseIn(col, firstNew)
    requestQuote()
}

/** Готовим hCaptcha заранее, чтобы по кнопке «Оплатить» проверка открывалась сразу. */
internal fun MainActivity.captcha(siteKey: String): HCaptcha {
    hc?.let { return it }
    val h = HCaptcha.getClient(this)
    h.addOnSuccessListener { r: HCaptchaTokenResponse -> hcOk?.invoke(r.tokenResult) }
    h.addOnFailureListener { e: HCaptchaException -> hcFail?.invoke(e.message ?: "") }
    // Сервер сайта проверяет домен токена, поэтому подписываемся его доменом, а не hcaptcha.com
    h.setup(HCaptchaConfig.builder().siteKey(siteKey).host("pharmshop.tgstars.tg").build())
    hc = h
    return h
}

internal fun MainActivity.pay(rawUser: String, q: Int, method: String, methodName: String, c: JSONObject, info: TextView, product: String = "") {
    if (busy) return
    val username = rawUser.trim().removePrefix("@")
    val min = c.optInt("min", 50)
    val max = c.optInt("max", 10000)
    if (username.length < 4) { toast("Введите имя пользователя"); return }
    if (product.isEmpty() && (q < min || q > max)) { toast("Количество: от $min до $max"); return }
    val siteKey = c.optString("hcaptcha_sitekey")
    if (siteKey.isEmpty()) { toast("Нет ключа проверки"); return }

    busy = true
    info.text = "Пройдите проверку…"
    val h = captcha(siteKey)
    hcOk = { token ->
        info.text = "Создаём заказ…"
        io.execute {
            try {
                val o = Api.createOrder(username, q, method, token, product)
                o.put("method_name", methodName)
                Store.add(o)
                Store.lastUser = username
                OrderSync.syncAll()
                val url = o.optString("payment_url")
                val num = o.optString("order_number")
                ui {
                    busy = false
                    info.text = "Заказ $num создан. Переходим к оплате…"
                    openPay(url, info)
                }
            } catch (e: Exception) {
                ui { busy = false; info.text = "Ошибка: ${e.message}" }
            }
        }
    }
    hcFail = { msg ->
        busy = false
        hc = null   // в следующий раз подготовим проверку заново
        info.text = "Проверка не пройдена: $msg"
    }
    h.verifyWithHCaptcha()
}

internal fun MainActivity.openPay(url: String, info: TextView) {
    if (!url.startsWith("https://")) {
        info.text = "Сайт не вернул ссылку на оплату"
        return
    }
    try {
        returnFromPay = true
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        returnFromPay = false
        info.text = "Откройте ссылку вручную: $url"
    }
}
