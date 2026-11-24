package ru.pharmshop.app

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

/** Анимированный фон темы. */
enum class Fx { NONE, SNOW, LEAVES, PETALS, SUN, STARS, RAP }

/** Палитра темы для одного режима (тёмного или светлого). Цвет «accent» — заливка кнопок, текст на нём чёрный. */
class Pal(bgHex: String, cardHex: String, lineHex: String, accentHex: String) {
    val bg: Int = Color.parseColor(bgHex)
    val card: Int = Color.parseColor(cardHex)
    val line: Int = Color.parseColor(lineHex)
    val accent: Int = Color.parseColor(accentHex)
}

/** Оформления приложения. Выбор хранится в Store.skin (id). Тёмный/светлый режим выбирается отдельно (Store.theme). */
enum class Skin(val id: Int, val emoji: String, val title: String, val fx: Fx, val dark: Pal, val light: Pal) {
    CLASSIC(0, "💊", "Стандарт", Fx.NONE,
        Pal("#121214", "#1D1C21", "#2B2A31", "#3B8EF3"), Pal("#F4F4F7", "#FFFFFF", "#E1E1E8", "#3B8EF3")),
    AUTUMN(1, "🍂", "Осень", Fx.LEAVES,
        Pal("#17120E", "#231A13", "#3A2B1F", "#FF9A3D"), Pal("#FBF3EA", "#FFFFFF", "#EBD9C6", "#F28A2E")),
    NEWYEAR(2, "🎄", "Новый год", Fx.SNOW,
        Pal("#0E1613", "#16231D", "#27392F", "#FF5A5F"), Pal("#F1F8F3", "#FFFFFF", "#D3E5D9", "#EF4B52")),
    SUMMER(3, "☀️", "Лето", Fx.SUN,
        Pal("#0C1A1E", "#132A30", "#1F4048", "#FFC233"), Pal("#FFF8E6", "#FFFFFF", "#F5E3B3", "#FFB300")),
    SPRING(4, "🌸", "Весна", Fx.PETALS,
        Pal("#1A1218", "#271A24", "#3E2A39", "#F472B6"), Pal("#FFF4F8", "#FFFFFF", "#F4D3E2", "#F06AA8")),
    SPACE(5, "🚀", "Космос", Fx.STARS,
        Pal("#06071A", "#0F1130", "#222550", "#8B7CFF"), Pal("#EEF0FF", "#FFFFFF", "#D6D9F5", "#7C6CF5")),
    RAP(6, "🎤", "Rap", Fx.RAP,
        Pal("#0A0A0A", "#161616", "#2C2C2C", "#D4FF00"), Pal("#F0F0F0", "#FFFFFF", "#DADADA", "#FF2E93"));

    companion object {
        fun byId(id: Int): Skin = values().firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** Свой акцентный цвет (Настройки → Оформление). Для светлого режима цвет чуть темнее, чтобы текст читался. */
class AccentOpt(val id: Int, val title: String, val dark: Int, val light: Int)

/** Плашка-поздравление на главном экране. */
class Banner(val title: String, val sub: String, val c1: Int, val c2: Int, val on: Int, val onSub: Int)

/**
 * Тема оформления. Режим (Store.theme): 1 — тёмная (по умолчанию), 2 — светлая, 0 — как в системе.
 * Оформление (Store.skin): Стандарт, Осень, Новый год, Лето, Весна, Космос, Rap.
 */
object Theme {
    @JvmField var light = false
    @JvmField var skin: Skin = Skin.CLASSIC
    /** Множитель размера текста (Настройки → Оформление → Размер текста). */
    @JvmField var textScale = 1f
    /** Множитель скругления углов карточек (Настройки → Оформление → Скругление карточек). */
    @JvmField var corner = 1f

    val ACCENTS = listOf(
        AccentOpt(1, "Синий", Color.parseColor("#3B8EF3"), Color.parseColor("#2F7DE0")),
        AccentOpt(2, "Бирюзовый", Color.parseColor("#22C1DC"), Color.parseColor("#0C93AB")),
        AccentOpt(3, "Зелёный", Color.parseColor("#34C759"), Color.parseColor("#1E9E45")),
        AccentOpt(4, "Жёлтый", Color.parseColor("#FFC233"), Color.parseColor("#E0A000")),
        AccentOpt(5, "Оранжевый", Color.parseColor("#FF9A3D"), Color.parseColor("#E0700C")),
        AccentOpt(6, "Красный", Color.parseColor("#FF5A5F"), Color.parseColor("#E0352C")),
        AccentOpt(7, "Розовый", Color.parseColor("#F472B6"), Color.parseColor("#E0489A")),
        AccentOpt(8, "Фиолетовый", Color.parseColor("#8B7CFF"), Color.parseColor("#7C6CF5"))
    )

    // текущие цвета (пересчитываются в update)
    @JvmField var bg: Int = Color.parseColor("#121214")
    @JvmField var card: Int = Color.parseColor("#1D1C21")
    @JvmField var line: Int = Color.parseColor("#2B2A31")
    @JvmField var text: Int = Color.parseColor("#F2F2F4")
    @JvmField var mute: Int = Color.parseColor("#9A9DA3")
    @JvmField var accent: Int = Color.parseColor("#3B8EF3")

    // базовая тёмная палитра (текст)
    val D_TEXT = Color.parseColor("#F2F2F4"); val D_MUTE = Color.parseColor("#9A9DA3")
    // базовая светлая палитра (текст)
    val L_TEXT = Color.parseColor("#17171A"); val L_MUTE = Color.parseColor("#6B6F76")
    // акценты: на светлом фоне темнее, чтобы текст читался
    val L_GOLD = Color.parseColor("#C98200"); val L_ORANGE = Color.parseColor("#E0560C"); val L_CYAN = Color.parseColor("#0C93AB")
    val L_GREEN = Color.parseColor("#1E9E45"); val L_AMBER = Color.parseColor("#B7791F"); val L_RED = Color.parseColor("#E0352C")
    val L_VIOLET = Color.parseColor("#7C4DEB"); val L_VIOLET_L = Color.parseColor("#6D3FE0")

    val rippleColor get() = if (light) 0x22000000 else 0x33FFFFFF
    val snowColor get() = if (light) Color.parseColor("#8FA6C4") else Color.WHITE

    /** Вызывать в onCreate после Store.init и при смене настроек: определяет режим и палитру. */
    fun update(a: android.content.Context) {
        light = when (Store.theme) {
            2 -> true
            1 -> false
            else -> (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        }
        skin = Skin.byId(Store.skin)
        textScale = when (Store.textSize) { 0 -> 0.9f; 2 -> 1.15f; else -> 1f }
        val p = if (light) skin.light else skin.dark
        bg = p.bg; card = p.card; line = p.line; accent = p.accent
        // свой акцентный цвет перекрывает цвет темы
        ACCENTS.firstOrNull { it.id == Store.accentId }?.let { accent = if (light) it.light else it.dark }
        corner = when (Store.cornerStyle) { 0 -> 0.5f; 2 -> 1.5f; else -> 1f }
        text = if (light) L_TEXT else D_TEXT
        mute = if (light) L_MUTE else D_MUTE
    }

    fun nightMode(): Int = when (Store.theme) {
        2 -> AppCompatDelegate.MODE_NIGHT_NO
        1 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Новогоднее оформление «по умолчанию» включено с 15 ноября по 20 января. */
    fun newYearWindow(): Boolean {
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.MONTH)
        val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        return m == java.util.Calendar.DECEMBER ||
            (m == java.util.Calendar.JANUARY && d <= 20) ||
            (m == java.util.Calendar.NOVEMBER && d >= 15)
    }

    private fun c(s: String) = Color.parseColor(s)

    /** Плашка на главном экране для текущего оформления (null — без плашки). */
    fun banner(): Banner? = when (skin) {
        Skin.CLASSIC -> if (newYearWindow()) newYearBanner() else null
        Skin.NEWYEAR -> newYearBanner()
        Skin.AUTUMN -> Banner("🍂 Золотая осень", "Тёплые заказы без хлопот", c("#B5541C"), c("#7A2E12"), Color.WHITE, c("#FFE8D2"))
        Skin.SUMMER -> Banner("☀️ Лето в PharmShop", "Звёзды — как солнечные лучи", c("#FF9A1F"), c("#E5533D"), Color.WHITE, c("#FFF1D6"))
        Skin.SPRING -> Banner("🌸 Весна пришла", "Пусть всё расцветает — и звёзды тоже", c("#E0579B"), c("#8E4FD6"), Color.WHITE, c("#FDE7F2"))
        Skin.SPACE -> Banner("🚀 Полёт нормальный", "Звёзды ближе, чем кажется", c("#3A2C9E"), c("#12154A"), Color.WHITE, c("#D9DBFF"))
        Skin.RAP -> Banner("🎤 Stars on the beat", "Свежие звёзды — без лишних слов", c("#D4FF00"), c("#7CFF3A"), Color.BLACK, c("#1E2A00"))
    }

    private fun newYearBanner() = Banner(
        "🎄 С наступающим Новым годом!", "Пусть в новом году будет больше звёзд ⭐",
        c("#C62F3E"), c("#1F7A4D"), Color.WHITE, c("#E8F5EC")
    )
}
