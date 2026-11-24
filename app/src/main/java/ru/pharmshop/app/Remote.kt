package ru.pharmshop.app

import org.json.JSONObject

/**
 * Удалённые настройки с сервера чата (server/worker.js): объявление на главном экране,
 * включение/выключение разделов и обязательное обновление. Последний ответ хранится на устройстве.
 */
object Remote {
    private fun cached(): JSONObject = try { JSONObject(Store.remoteConfig) } catch (e: Exception) { JSONObject() }

    /** Запрашивает настройки. true — они изменились с прошлого раза. */
    fun refresh(): Boolean {
        val r = ChatApi.appConfig(BuildConfig.VERSION_CODE).toString()
        val changed = r != Store.remoteConfig
        Store.remoteConfig = r
        return changed
    }

    /** Активное объявление или null. */
    fun announcement(): JSONObject? {
        val a = cached().optJSONObject("announcement") ?: return null
        return if (a.optBoolean("active") && a.optString("text").isNotEmpty()) a else null
    }

    /** Технические работы чата: объект {on, reason} или null, если чат работает. */
    fun chatMaint(): JSONObject? {
        val m = cached().optJSONObject("chat_maint") ?: return null
        return if (m.optBoolean("on")) m else null
    }

    /** Настройки раздела: enabled, title, sub, url, message (любое поле может отсутствовать). */
    fun section(key: String): JSONObject? = cached().optJSONObject("sections")?.optJSONObject(key)

    /** Минимальная сборка, с которой приложение ещё разрешено (0 — без ограничений). */
    fun minVersion(): Int = cached().optInt("min_version_code", 0)

    fun updateMessage(): String = cached().optString("update_message")
}
