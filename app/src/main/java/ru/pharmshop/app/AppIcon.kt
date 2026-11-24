package ru.pharmshop.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Реальная активность приложения: весь код в MainActivity, а LauncherActivity лишь зарегистрирована в манифесте. */
class LauncherActivity : MainActivity()

/**
 * Иконка приложения на выбор. Каждая иконка — отдельный activity-alias в манифесте; включён всегда ровно один.
 * Первая («Классика») называется .MainActivity, как раньше: тогда ярлыки на рабочем столе переживают обновление.
 */
object AppIcon {
    class Entry(val component: String, val title: String, val bgColor: Int, val fg: Int)

    val all: List<Entry> = listOf(
        Entry("ru.pharmshop.app.MainActivity", "Классика", R.color.ic_launcher_background, R.drawable.ic_launcher_foreground),
        Entry("ru.pharmshop.app.IconCat2", "Рыжик", R.color.ic_cat_2_bg, R.drawable.ic_cat_2_fg),
        Entry("ru.pharmshop.app.IconCat3", "Снежок", R.color.ic_cat_3_bg, R.drawable.ic_cat_3_fg),
        Entry("ru.pharmshop.app.IconCat4", "Дымка", R.color.ic_cat_4_bg, R.drawable.ic_cat_4_fg),
        Entry("ru.pharmshop.app.IconCat5", "Сиам", R.color.ic_cat_5_bg, R.drawable.ic_cat_5_fg),
        Entry("ru.pharmshop.app.IconCat6", "Пятнашка", R.color.ic_cat_6_bg, R.drawable.ic_cat_6_fg)
    )

    private fun enabled(c: Context, i: Int): Boolean {
        val st = c.packageManager.getComponentEnabledSetting(ComponentName(c.packageName, all[i].component))
        return st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && i == 0)   // в манифесте включена только «Классика»
    }

    /** Номер включённой сейчас иконки. */
    fun current(c: Context): Int = all.indices.firstOrNull { enabled(c, it) } ?: 0

    /** Включает выбранную иконку и выключает остальные. Новую включаем первой, чтобы значок приложения не пропадал. */
    fun set(c: Context, index: Int) {
        val pm = c.packageManager
        pm.setComponentEnabledSetting(
            ComponentName(c.packageName, all[index].component),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        for (i in all.indices) if (i != index) {
            pm.setComponentEnabledSetting(
                ComponentName(c.packageName, all[i].component),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
    }
}
