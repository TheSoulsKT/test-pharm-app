package ru.pharmshop.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** История заказов и последний ник — хранятся только на этом устройстве. */
object Store {
    private lateinit var sp: SharedPreferences

    /** «Повторить заказ»: количество звёзд, которое нужно подставить при открытии формы (используется один раз). */
    @Volatile var repeatQty: Int = 0

    /** Тема оформления: 1 — тёмная (по умолчанию), 2 — светлая, 0 — как в системе. */
    var theme: Int
        get() = sp.getInt("theme", 1)
        set(v) { sp.edit().putInt("theme", v).apply() }

    /** Оформление: 0 — Стандарт, 1 — Осень, 2 — Новый год, 3 — Лето, 4 — Весна, 5 — Космос, 6 — Rap (см. Skin). */
    var skin: Int
        get() = sp.getInt("skin", 0)
        set(v) { sp.edit().putInt("skin", v).apply() }

    /** Стартовая заставка уже показана (при самом первом запуске). */
    var introSeen: Boolean
        get() = sp.getBoolean("intro_seen", false)
        set(v) { sp.edit().putBoolean("intro_seen", v).apply() }

    /** Шаги заказа: 0 — значок со статусом (как раньше), 1 — полоса «Создан → Оплачен → Выполнен». */
    var stepsStyle: Int
        get() = sp.getInt("steps_style", 0)
        set(v) { sp.edit().putInt("steps_style", v).apply() }

    // ----- переключатели в настройках (Оформление / Безопасность) -----
    /** Анимированный фон темы (снег, листья, звёзды…). */
    var fxEnabled: Boolean
        get() = sp.getBoolean("fx_enabled", true)
        set(v) { sp.edit().putBoolean("fx_enabled", v).apply() }

    /** Плавное появление экранов и элементов. */
    var uiAnim: Boolean
        get() = sp.getBoolean("ui_anim", true)
        set(v) { sp.edit().putBoolean("ui_anim", v).apply() }

    /** Лёгкая вибрация при нажатии на кнопки. */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", false)
        set(v) { sp.edit().putBoolean("haptics", v).apply() }

    /** Запретить скриншоты и скрыть содержимое в списке «Недавние». */
    var secureScreen: Boolean
        get() = sp.getBoolean("secure_screen", false)
        set(v) { sp.edit().putBoolean("secure_screen", v).apply() }

    /** Прятать ссылку подписки VPN, пока на неё не нажали. */
    var vpnMaskLink: Boolean
        get() = sp.getBoolean("vpn_mask_link", false)
        set(v) { sp.edit().putBoolean("vpn_mask_link", v).apply() }

    /** Очищать буфер обмена через минуту после копирования ссылки подписки. */
    var clipAutoClear: Boolean
        get() = sp.getBoolean("clip_auto_clear", false)
        set(v) { sp.edit().putBoolean("clip_auto_clear", v).apply() }

    /** Размер текста: 0 — мелкий, 1 — обычный, 2 — крупный. */
    var textSize: Int
        get() = sp.getInt("text_size", 1)
        set(v) { sp.edit().putInt("text_size", v).apply() }

    /** Сколько частиц у анимированного фона: 0 — меньше, 1 — как раньше, 2 — больше. */
    var fxDensity: Int
        get() = sp.getInt("fx_density", 1)
        set(v) { sp.edit().putInt("fx_density", v).apply() }

    /** Какая вкладка открывается при запуске: 0 — ТГ, 4 — VPN, 5 — Чат, 2 — Профиль (1 — старое значение «Чат», теперь тоже общий чат). */
    var startTab: Int
        get() = sp.getInt("start_tab", 0)
        set(v) { sp.edit().putInt("start_tab", v).apply() }

    /** Не показывать плашку-поздравление на главной. */
    var hideBanner: Boolean
        get() = sp.getBoolean("hide_banner", false)
        set(v) { sp.edit().putBoolean("hide_banner", v).apply() }

    // ----- профиль и оформление разделов -----
    /** Описание в профиле (до 120 символов). Хранится только на этом устройстве. */
    var bio: String
        get() = sp.getString("bio", "") ?: ""
        set(v) { sp.edit().putString("bio", v.take(120)).apply() }

    /** Свой акцентный цвет (номер из Theme.ACCENTS). 0 — цвет берётся из выбранной темы. */
    var accentId: Int
        get() = sp.getInt("accent_id", 0)
        set(v) { sp.edit().putInt("accent_id", v).apply() }

    /** Скругление углов карточек: 0 — меньше, 1 — как раньше, 2 — больше. */
    var cornerStyle: Int
        get() = sp.getInt("corner_style", 1)
        set(v) { sp.edit().putInt("corner_style", v).apply() }

    /** Размер карточек разделов на главной: 0 — обычные, 1 — компактные. */
    var cardSize: Int
        get() = sp.getInt("card_size", 0)
        set(v) { sp.edit().putInt("card_size", v).apply() }

    /** Не показывать на главной блок «Скоро». */
    var hideSoon: Boolean
        get() = sp.getBoolean("hide_soon", false)
        set(v) { sp.edit().putBoolean("hide_soon", v).apply() }

    /** Разделы главной, которые пользователь скрыл у себя: ключи через запятую. */
    var hiddenSections: String
        get() = sp.getString("hidden_sections", "") ?: ""
        set(v) { sp.edit().putString("hidden_sections", v).apply() }

    /** Обложка карточки профиля (номер из ProfileCovers). 0 — по цвету темы. */
    var profileCover: Int
        get() = sp.getInt("profile_cover", 0)
        set(v) { sp.edit().putInt("profile_cover", v).apply() }

    /** Скрывать ники и суммы в профиле, пока не нажмёшь на карточку. */
    var hideSensitive: Boolean
        get() = sp.getBoolean("hide_sensitive", false)
        set(v) { sp.edit().putBoolean("hide_sensitive", v).apply() }

    /** Спрашивать подтверждение с суммой перед переходом к оплате. */
    var confirmPay: Boolean
        get() = sp.getBoolean("confirm_pay", false)
        set(v) { sp.edit().putBoolean("confirm_pay", v).apply() }

    /** Просить PIN / отпечаток перед выходом из VPN-аккаунта и сменой ссылки подписки. */
    var vpnPinGuard: Boolean
        get() = sp.getBoolean("vpn_pin_guard", false)
        set(v) { sp.edit().putBoolean("vpn_pin_guard", v).apply() }

    /** Блокировать приложение сразу, как только его свернули (без задержки). */
    var lockOnLeave: Boolean
        get() = sp.getBoolean("lock_on_leave", false)
        set(v) { sp.edit().putBoolean("lock_on_leave", v).apply() }

    /** Блокировать приложение, когда выключили экран телефона. */
    var lockOnScreenOff: Boolean
        get() = sp.getBoolean("lock_on_screen_off", false)
        set(v) { sp.edit().putBoolean("lock_on_screen_off", v).apply() }

    /** После входа сообщать, сколько раз PIN вводили неверно. */
    var showFailedPins: Boolean
        get() = sp.getBoolean("show_failed_pins", false)
        set(v) { sp.edit().putBoolean("show_failed_pins", v).apply() }

    // ----- VPN: уведомления, кэш для виджета -----
    /** Предупреждать, что подписка VPN скоро закончится. */
    var vpnNotifyExpiry: Boolean
        get() = sp.getBoolean("vpn_notify_expiry", true)
        set(v) { sp.edit().putBoolean("vpn_notify_expiry", v).apply() }

    /** Предупреждать, что трафик VPN заканчивается. */
    var vpnNotifyTraffic: Boolean
        get() = sp.getBoolean("vpn_notify_traffic", true)
        set(v) { sp.edit().putBoolean("vpn_notify_traffic", v).apply() }

    /** Краткие данные подписок для виджета (без ссылок подписки): JSON-массив. */
    var vpnCache: String
        get() = sp.getString("vpn_cache", "") ?: ""
        set(v) { sp.edit().putString("vpn_cache", v).apply() }

    var vpnCacheAt: Long
        get() = sp.getLong("vpn_cache_at", 0L)
        set(v) { sp.edit().putLong("vpn_cache_at", v).apply() }

    /** О чём уже предупредили (ключи «подписка|событие|дата окончания»), чтобы не повторять. */
    var vpnNotified: String
        get() = sp.getString("vpn_notified", "") ?: ""
        set(v) { sp.edit().putString("vpn_notified", v).apply() }

    var vpnCheckedAt: Long
        get() = sp.getLong("vpn_checked_at", 0L)
        set(v) { sp.edit().putLong("vpn_checked_at", v).apply() }

    /** Номер сборки, для которой уже показали «Что нового» (-1 — ещё не показывали). */
    var seenCode: Int
        get() = sp.getInt("seen_code", -1)
        set(v) { sp.edit().putInt("seen_code", v).apply() }

    // ----- вклад в развитие приложения (для достижений) -----
    /** Сколько отчётов о реальных ошибках отправлено. Засчитывается не больше одного в сутки, чтобы нельзя было накрутить. */
    var bugReports: Int
        get() = sp.getInt("bug_reports", 0)
        set(v) { sp.edit().putInt("bug_reports", v).apply() }

    private var bugReportDay: Long
        get() = sp.getLong("bug_report_day", 0L)
        set(v) { sp.edit().putLong("bug_report_day", v).apply() }

    /** Отчёт об ошибке отправлен. [real] — в нём была настоящая ошибка (сбой, запись в журнале) или описание проблемы. */
    fun noteBugReport(real: Boolean) {
        if (!real) return
        val day = System.currentTimeMillis() / 86_400_000L
        if (day == bugReportDay) return
        sp.edit().putInt("bug_reports", bugReports + 1).putLong("bug_report_day", day).apply()
    }

    /** Сколько раз администратор отметил обращение в поддержку решённым. Число приходит с сервера и только растёт. */
    var supportSolved: Int
        get() = sp.getInt("support_solved", 0)
        set(v) { sp.edit().putInt("support_solved", v).apply() }

    fun noteSupportSolved(n: Int) { if (n > supportSolved) supportSolved = n }

    // ----- чат -----
    /** Ник в чате (кэш: его показывает профиль). Пусто — ник ещё не выбран. */
    var chatNick: String
        get() = sp.getString("chat_nick", "") ?: ""
        set(v) { sp.edit().putString("chat_nick", v).apply() }

    /** Сколько сообщений написано в чате (число приходит с сервера и только растёт): для достижений «за общение». */
    var chatMsgs: Int
        get() = sp.getInt("chat_msgs", 0)
        set(v) { sp.edit().putInt("chat_msgs", v).apply() }

    fun noteChatMsgs(n: Int) { if (n > chatMsgs) chatMsgs = n }

    /** Какая версия аватарки (см. Avatar.stamp) уже загружена на сервер для чата. */
    var avatarSynced: String
        get() = sp.getString("avatar_synced", "") ?: ""
        set(v) { sp.edit().putString("avatar_synced", v).apply() }

    // ----- защита входа -----
    /** 0 — выключена, 1 — PIN, 2 — отпечаток (PIN остаётся запасным входом). */
    var lockMode: Int
        get() = sp.getInt("lock_mode", 0)
        set(v) { sp.edit().putInt("lock_mode", v).apply() }

    /** Через сколько секунд после ухода из приложения снова просить вход. */
    var lockDelaySec: Int
        get() = sp.getInt("lock_delay", 60)
        set(v) { sp.edit().putInt("lock_delay", v).apply() }

    val hasPin: Boolean get() = !sp.getString("pin_hash", "").isNullOrEmpty()

    /** Хэш PIN с солью, дополнительно зашифрован ключом Android Keystore. Сам PIN нигде не хранится. */
    fun setPin(pin: String) {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        val salt = b.joinToString("") { "%02x".format(it) }
        val h = pinDigest(pin, salt)
        val stored = try { "s:" + seal(h) } catch (e: Exception) { "p:$h" }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", stored)
            .putInt("pin_fails", 0).putLong("pin_lock_until", 0L).apply()
    }

    fun checkPin(pin: String): Boolean {
        val stored = sp.getString("pin_hash", "") ?: ""
        val salt = sp.getString("pin_salt", "") ?: ""
        if (stored.length < 3 || salt.isEmpty()) return false
        val want = if (stored.startsWith("s:")) unseal(stored.substring(2)) else stored.substring(2)
        if (want.isEmpty()) return false
        return java.security.MessageDigest.isEqual(want.toByteArray(), pinDigest(pin, salt).toByteArray())
    }

    fun clearPin() {
        sp.edit().remove("pin_hash").remove("pin_salt").remove("pin_fails").remove("pin_lock_until").apply()
    }

    private fun pinDigest(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val saltBytes = salt.toByteArray()
        var d = saltBytes + pin.toByteArray()
        repeat(20000) { d = md.digest(d + saltBytes) }
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Неверный PIN: после каждых 5 ошибок подряд вход блокируется на 30 с, 60 с, 2 мин … до 10 минут. */
    fun pinFailed() {
        val n = sp.getInt("pin_fails", 0) + 1
        val e = sp.edit().putInt("pin_fails", n)
        if (n % 5 == 0) {
            val step = n / 5 - 1
            val sec = minOf(30L shl minOf(step, 5), 600L)
            e.putLong("pin_lock_until", System.currentTimeMillis() + sec * 1000L)
        }
        e.apply()
    }

    /** Сколько раз PIN вводили неверно с последнего успешного входа. */
    fun pinFails(): Int = sp.getInt("pin_fails", 0)

    fun pinFailsReset() { sp.edit().putInt("pin_fails", 0).putLong("pin_lock_until", 0L).apply() }

    /** Сколько миллисекунд ещё действует блокировка после неверных попыток (0 — можно вводить). */
    fun pinLockLeftMs(): Long = (sp.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Сброс внешнего вида и переключателей. PIN, заказы, вход в VPN, уведомления и обновления не трогаем. */
    fun resetSettings() {
        val e = sp.edit()
        for (k in listOf(
            "theme", "skin", "steps_style", "fx_enabled", "fx_density", "ui_anim", "haptics", "text_size",
            "start_tab", "hide_banner", "secure_screen", "hide_sensitive", "confirm_pay", "vpn_mask_link",
            "clip_auto_clear", "vpn_pin_guard", "lock_on_leave", "lock_on_screen_off", "show_failed_pins", "lock_delay",
            "accent_id", "corner_style", "card_size", "hide_soon", "hidden_sections", "profile_cover"
        )) e.remove(k)
        e.apply()
    }

    /** Полный сброс данных приложения (если забыли PIN). */
    fun wipeAll() { sp.edit().clear().commit(); Avatar.remove(); Diag.reset() }

    // ----- обновления в фоне -----
    /** Скачивать новые версии в фоне, когда телефон на Wi-Fi. */
    var wifiUpdate: Boolean
        get() = sp.getBoolean("wifi_update", false)
        set(v) { sp.edit().putBoolean("wifi_update", v).apply() }

    /** Сборка, уже скачанная в фоне и ожидающая установки (0 — нет). */
    var pendingUpdateCode: Int
        get() = sp.getInt("pending_code", 0)
        set(v) { sp.edit().putInt("pending_code", v).apply() }

    var pendingUpdateName: String
        get() = sp.getString("pending_name", "") ?: ""
        set(v) { sp.edit().putString("pending_name", v).apply() }

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("pharmshop", Context.MODE_PRIVATE)
        Avatar.init(ctx.applicationContext)
        // старые версии хранили сам код админа открытым текстом — стираем; админ войдёт заново и получит токен
        if (sp.contains("admin_code")) sp.edit().remove("admin_code").apply()
    }

    // ----- шифрование секретов ключом из Android Keystore (ключ нельзя вынуть из устройства) -----
    private const val KS_ALIAS = "pharmshop_secrets"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Пустая строка, если данных нет или их не удалось расшифровать (например, после сброса хранилища ключей). */
    private fun unseal(blob: String): String = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    var lastUser: String
        get() = sp.getString("last_user", "") ?: ""
        set(v) { sp.edit().putString("last_user", v).apply() }

    /** Токен сессии администратора (код сервер выдаёт один раз при входе). Хранится зашифрованным. */
    var adminToken: String
        get() = sp.getString("admin_token", "")?.takeIf { it.isNotEmpty() }?.let { unseal(it) } ?: ""
        set(v) {
            sp.edit().putString("admin_token", if (v.isEmpty()) "" else try { seal(v) } catch (e: Exception) { "" }).apply()
        }

    // ----- VPN (личный кабинет pvpn.vitrinka.net) -----
    /** Долгоживущий ключ входа в кабинет VPN (cookie client_refresh). Хранится зашифрованным. */
    // Расшифровка через Android Keystore небольшая, но не бесплатная (на части телефонов — десятки миллисекунд), а ключ
    // спрашивают на главном потоке при каждом открытии вкладки VPN. Поэтому расшифрованное значение держим в памяти процесса.
    @Volatile private var vpnRefreshMem = ""
    var vpnRefresh: String
        get() {
            val m = vpnRefreshMem
            if (m.isNotEmpty()) return m
            val v = sp.getString("vpn_refresh", "")?.takeIf { it.isNotEmpty() }?.let { unseal(it) } ?: ""
            if (v.isNotEmpty()) vpnRefreshMem = v   // пустой результат не запоминаем: вдруг это разовый сбой хранилища ключей
            return v
        }
        set(v) {
            vpnRefreshMem = v
            sp.edit().putString("vpn_refresh", if (v.isEmpty()) "" else try { seal(v) } catch (e: Exception) { "" }).apply()
        }

    // ----- встроенный VPN-клиент (PharmVpnService) -----
    /** Ссылка подписки, по которой сервис качает серверы. Это личный ключ, поэтому хранится зашифрованной. */
    var vpnSubUrl: String
        get() = sp.getString("vpn_sub_url", "")?.takeIf { it.isNotEmpty() }?.let { unseal(it) } ?: ""
        set(v) { sp.edit().putString("vpn_sub_url", if (v.isEmpty()) "" else try { seal(v) } catch (e: Exception) { "" }).apply() }

    /** Последний скачанный ответ подписки: чтобы подключаться без сети до панели. Тоже зашифрован. */
    var vpnSubRaw: String
        get() = sp.getString("vpn_sub_raw", "")?.takeIf { it.isNotEmpty() }?.let { unseal(it) } ?: ""
        set(v) { sp.edit().putString("vpn_sub_raw", if (v.isEmpty()) "" else try { seal(v) } catch (e: Exception) { "" }).apply() }

    /** Выбранный сервер (название); пусто — первый в списке. */
    var vpnServer: String
        get() = sp.getString("vpn_server", "") ?: ""
        set(v) { sp.edit().putString("vpn_server", v).apply() }

    /** Постоянный идентификатор устройства для панели (по нему считается лимит устройств). */
    val vpnHwid: String
        get() {
            val cur = sp.getString("vpn_hwid", "") ?: ""
            if (cur.isNotEmpty()) return cur
            val v = java.util.UUID.randomUUID().toString()
            sp.edit().putString("vpn_hwid", v).apply()
            return v
        }

    /** Платёж VPN, который ждёт оплаты (id): после возврата в приложение проверяем его статус. */
    var vpnPay: String
        get() = sp.getString("vpn_pay", "") ?: ""
        set(v) { sp.edit().putString("vpn_pay", v).apply() }

    /** Анонимный идентификатор устройства для чата поддержки. */
    val deviceId: String
        get() {
            val cur = sp.getString("dev_id", "") ?: ""
            if (cur.isNotEmpty()) return cur
            val n = java.util.UUID.randomUUID().toString()
            sp.edit().putString("dev_id", n).apply()
            return n
        }

    /** Секрет устройства: по нему сервер понимает, что это ваш чат, а не чужой. */
    val deviceSecret: String
        get() {
            val cur = sp.getString("dev_secret", "") ?: ""
            if (cur.isNotEmpty()) return cur
            val b = ByteArray(24)
            java.security.SecureRandom().nextBytes(b)
            val n = b.joinToString("") { "%02x".format(it) }
            sp.edit().putString("dev_secret", n).apply()
            return n
        }

    /** Последние удалённые настройки (объявление, разделы, минимальная версия) — работают и без сети. */
    var remoteConfig: String
        get() = sp.getString("remote_config", "") ?: ""
        set(v) { sp.edit().putString("remote_config", v).apply() }

    // ----- фоновые уведомления -----
    /** Пользователь включает/выключает push в профиле. */
    var pushEnabled: Boolean
        get() = sp.getBoolean("push_enabled", true)
        set(v) { sp.edit().putBoolean("push_enabled", v).apply() }

    /** Пользователь хоть раз писал в поддержку — только тогда есть смысл спрашивать сервер об ответах. */
    var chatStarted: Boolean
        get() = sp.getBoolean("chat_started", false)
        set(v) { sp.edit().putBoolean("chat_started", v).apply() }

    /** Последний ответ поддержки, о котором уже уведомили (id сообщения). */
    var notifiedSupportId: Long
        get() = sp.getLong("n_support", 0L)
        set(v) { sp.edit().putLong("n_support", v).apply() }

    /** Последнее сообщение пользователя, о котором уже уведомили админа (время). */
    var notifiedAdminTs: Long
        get() = sp.getLong("n_admin", 0L)
        set(v) { sp.edit().putLong("n_admin", v).apply() }

    /** Токен FCM этого устройства (пусто, если Firebase не настроен). */
    var fcmToken: String
        get() = sp.getString("fcm_token", "") ?: ""
        set(v) { sp.edit().putString("fcm_token", v).apply() }

    /** Токен, который уже передан серверу как токен пользователя (чтобы не слать повторно). */
    var fcmUserSent: String
        get() = sp.getString("fcm_user_sent", "") ?: ""
        set(v) { sp.edit().putString("fcm_user_sent", v).apply() }

    /** Имена способов оплаты {id: имя} — как они показаны в форме (для подписи заказов). */
    var methodNames: String
        get() = sp.getString("method_names", "{}") ?: "{}"
        set(v) { sp.edit().putString("method_names", v).apply() }

    @Synchronized
    fun orders(): List<JSONObject> {
        val arr = try { JSONArray(sp.getString("orders", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) out.add(o)
        }
        return out
    }

    private fun save(list: List<JSONObject>) {
        val arr = JSONArray()
        for (o in list) arr.put(o)
        sp.edit().putString("orders", arr.toString()).apply()
    }

    @Synchronized
    fun add(o: JSONObject) {
        o.put("seen_kind", statusKind(effStatus(o)))   // о первом статусе уведомлять не нужно
        val id = o.optString("id")
        val list = orders().filter { it.optString("id") != id }.toMutableList()
        list.add(0, o)
        save(list.take(100))
    }

    /** Обновляет сохранённый заказ свежими данными с сервера (остальные поля не трогает). */
    @Synchronized
    fun update(id: String, fresh: JSONObject) {
        val list = orders()
        for (o in list) {
            if (o.optString("id") == id) {
                val keys = fresh.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    o.put(k, fresh.get(k))
                }
            }
        }
        save(list)
    }

    /** Пользователь видел заказы на экране: до этих статусов фоновые уведомления не нужны. */
    @Synchronized
    fun markOrdersSeen(onlyMissing: Boolean = false) {
        val list = orders()
        var changed = false
        for (o in list) {
            val k = statusKind(effStatus(o))
            if (onlyMissing && o.has("seen_kind")) continue
            if (o.optInt("seen_kind", -1) != k) { o.put("seen_kind", k); changed = true }
        }
        if (changed) save(list)
    }

    @Synchronized
    fun clear() {
        sp.edit().remove("orders").apply()
    }
}
