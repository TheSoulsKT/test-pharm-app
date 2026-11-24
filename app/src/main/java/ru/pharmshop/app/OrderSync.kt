package ru.pharmshop.app

import org.json.JSONArray
import org.json.JSONObject

/** Отправляет на сервер чата заказы приложения (новые и со сменившимся статусом), чтобы админ видел их в разделе «Заказы». */
object OrderSync {
    fun syncAll() {
        try {
            val todo = Store.orders()
                .filter { it.optString("id").isNotEmpty() && it.optString("rep") != effStatus(it) }
                .take(20)
            if (todo.isEmpty()) return
            val arr = JSONArray()
            for (o in todo) {
                val status = effStatus(o)
                val premium = o.optString("product") == "premium"
                arr.put(
                    JSONObject()
                        .put("id", o.optString("id"))
                        .put("order_number", o.optString("order_number"))
                        .put("username", o.optString("username"))
                        .put("product", if (premium) "premium" else "stars")
                        .put("quantity", if (premium) o.optInt("months", o.optInt("quantity")) else o.optInt("quantity"))
                        .put("total", o.optString("total"))
                        .put("currency", o.optString("currency"))
                        .put("method", o.optString("method_name").ifEmpty { o.optString("method_id") })
                        .put("status", status)
                        .put("kind", statusKind(status))
                        .put("created_at", o.optString("created_at"))
                )
            }
            ChatApi.reportOrders(Store.deviceId, Store.deviceSecret, arr)
            for (o in todo) Store.update(o.optString("id"), JSONObject().put("rep", effStatus(o)))
        } catch (e: Exception) {
            // нет сети или сервер недоступен — повторим при следующем обновлении
        }
    }
}
