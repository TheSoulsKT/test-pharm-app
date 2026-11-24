package ru.pharmshop.app

import org.json.JSONObject

// Разбор статуса заказа. Не зависит от экрана: им пользуются и профиль, и фоновые уведомления.

// 0 — ждёт оплаты, 1 — оплачен/в работе, 2 — выполнен, 3 — отменён, 4 — неизвестный статус
/**
 * Итоговый статус заказа. Сайт может отдавать статус в разных полях
 * (status / payment_status / delivery_status …) или флагами (paid, paid_at …),
 * поэтому берём самый «продвинутый» из всех найденных.
 */
internal fun effStatus(o: JSONObject): String {
    val fields = listOf(
        "status", "order_status", "state", "payment_status",
        "delivery_status", "fulfillment_status", "payout_status"
    )
    var best = ""
    var bestRank = -1
    fun rank(k: Int) = when (k) { 2 -> 4; 3 -> 3; 1 -> 2; 0 -> 1; else -> 0 }
    for (f in fields) {
        val v = o.optString(f)
        if (v.isEmpty() || v == "null") continue
        val r = rank(statusKind(v))
        if (r > bestRank) { bestRank = r; best = v }
    }
    // признаки выполнения/оплаты, если строкового статуса мало
    fun has(key: String) = o.has(key) && !o.isNull(key) && o.optString(key).isNotEmpty() && o.optString(key) != "false"
    if (bestRank < 4 && (has("completed_at") || has("delivered_at") || has("fulfilled_at"))) return "completed"
    if (bestRank < 2 && (has("paid") && o.optString("paid") != "0" || has("is_paid") && o.optString("is_paid") != "0" || has("paid_at"))) return "paid"
    return best
}

internal fun statusKind(raw: String): Int {
    val s = raw.lowercase().trim()
    return when {
        s.isEmpty() -> 4
        s in setOf("completed", "complete", "done", "delivered", "success", "succeeded", "fulfilled", "finished", "sent") -> 2
        listOf("cancel", "fail", "expire", "refund", "reject", "error").any { s.contains(it) } -> 3
        listOf("complet", "success", "fulfil", "finish").any { s.contains(it) } -> 2
        listOf("paid", "process", "progress", "deliver", "confirm", "send", "queue", "work").any { s.contains(it) } -> 1
        listOf("pend", "wait", "new", "creat", "unpaid", "await").any { s.contains(it) } -> 0
        else -> 4
    }
}
