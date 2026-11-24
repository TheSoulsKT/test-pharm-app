package ru.pharmshop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление приложения через GitHub Releases.
 * На каждый push в main GitHub Actions собирает APK и публикует релиз с тегом build-<versionCode>.
 * Приложение смотрит последний релиз и, если versionCode больше текущего, скачивает APK и запускает установку.
 */
object Updater {
    class Info(
        val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val size: Long,
        /** SHA-256 файла из релиза (hex). Без него обновление не ставится. */
        val sha256: String
    )

    /** Репозиторий подставляется сборкой на GitHub (GITHUB_REPOSITORY), например "user/pharmshop-app2". */
    private val repo: String get() = BuildConfig.GITHUB_REPO

    val configured: Boolean get() = repo.isNotEmpty()

    /** Пояснение к последней проверке (для диагностики, когда обновлений не нашлось). */
    @Volatile var lastNote = ""

    /** null — установлена последняя версия. */
    fun check(): Info? {
        if (!configured) throw IllegalStateException("Обновления доступны только в сборке с GitHub")
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "PharmShopApp")
            val code = c.responseCode
            if (code == 404) {   // релизов ещё нет (или репозиторий закрыт)
                lastNote = "В репозитории $repo не найдено ни одного релиза (или репозиторий закрыт)."
                return null
            }
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 403 || code == 429) return checkViaWeb()   // лимит API без токена — идём через обычный сайт
            if (code !in 200..299) throw Exception("GitHub ответил кодом $code")
            val r = JSONObject(text)
            val tag = r.optString("tag_name")
            val remote = tag.removePrefix("build-").toIntOrNull()
            if (remote == null) {
                lastNote = "Последний релиз называется «$tag», а нужен тег вида build-107."
                return null
            }
            if (remote <= BuildConfig.VERSION_CODE) {
                lastNote = "Последний релиз на GitHub: сборка $remote, у вас: сборка ${BuildConfig.VERSION_CODE}."
                return null
            }
            val assets = r.optJSONArray("assets")
            if (assets == null) { lastNote = "В релизе $tag нет файлов."; return null }
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    val apkUrl = a.optString("browser_download_url")
                    if (!apkUrl.startsWith("https://github.com/$repo/releases/download/")) {
                        lastNote = "Адрес файла в релизе $tag выглядит подозрительно, обновление пропущено."
                        return null
                    }
                    val body = r.optString("body").trim()
                    val sum = checksum(assets, a.optString("name"), body)
                    if (sum.isEmpty()) {
                        lastNote = "В релизе $tag нет контрольной суммы SHA-256, обновление пропущено."
                        return null
                    }
                    return Info(remote, r.optString("name").ifEmpty { "сборка $remote" }, body, apkUrl, a.optLong("size"), sum)
                }
            }
            lastNote = "В релизе $tag нет файла .apk."
            return null
        } finally {
            c.disconnect()
        }
    }

    /**
     * Запасной путь без api.github.com (у него лимит 60 запросов/час на IP, из-за него и бывает 403):
     * https://github.com/<repo>/releases/latest перенаправляет на тег последнего релиза,
     * а список файлов берём из страницы releases/expanded_assets/<тег>.
     */
    private fun checkViaWeb(): Info? {
        val head = URL("https://github.com/$repo/releases/latest").openConnection() as HttpURLConnection
        val tag: String
        try {
            head.instanceFollowRedirects = false
            head.connectTimeout = 15000; head.readTimeout = 20000
            head.setRequestProperty("User-Agent", "PharmShopApp")
            val loc = head.getHeaderField("Location") ?: ""
            tag = loc.substringAfter("/releases/tag/", "").substringBefore('?').trim()
            if (head.responseCode == 404 || tag.isEmpty()) {
                lastNote = "В репозитории $repo не найдено ни одного релиза (или репозиторий закрыт)."
                return null
            }
        } finally { head.disconnect() }
        val remote = tag.removePrefix("build-").toIntOrNull()
        if (remote == null) { lastNote = "Последний релиз называется «$tag», а нужен тег вида build-107."; return null }
        if (remote <= BuildConfig.VERSION_CODE) {
            lastNote = "Последний релиз на GitHub: сборка $remote, у вас: сборка ${BuildConfig.VERSION_CODE}."
            return null
        }
        val page = httpText("https://github.com/$repo/releases/expanded_assets/$tag")
        val prefix = "/$repo/releases/download/$tag/"
        val names = Regex("href=\"" + Regex.escape(prefix) + "([^\"]+)\"").findAll(page).map { it.groupValues[1] }.toList().distinct()
        val apk = names.firstOrNull { it.endsWith(".apk") }
        if (apk == null) { lastNote = "В релизе $tag нет файла .apk."; return null }
        val base = "https://github.com$prefix"
        val sum = if (names.contains("$apk.sha256"))
            HEX64.find(httpText("$base$apk.sha256"))?.value?.lowercase() ?: "" else ""
        if (sum.isEmpty()) { lastNote = "В релизе $tag нет контрольной суммы SHA-256, обновление пропущено."; return null }
        return Info(remote, "сборка $remote", "", base + apk, 0L, sum)
    }

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "PharmShopApp")
            if (c.responseCode !in 200..299) throw Exception("GitHub ответил кодом ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private val HEX64 = Regex("[0-9a-fA-F]{64}")

    /** Контрольная сумма APK: файл «<имя>.apk.sha256» из релиза, а если его нет — строка «SHA-256: …» в описании. */
    private fun checksum(assets: org.json.JSONArray, apkName: String, body: String): String {
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "$apkName.sha256" && a.optLong("size") in 1L..1024L) {
                try {
                    val u = a.optString("browser_download_url")
                    if (!u.startsWith("https://github.com/$repo/releases/download/")) continue
                    val c = URL(u).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15000; c.readTimeout = 20000
                        c.setRequestProperty("User-Agent", "PharmShopApp")
                        if (c.responseCode in 200..299) {
                            HEX64.find(c.inputStream.bufferedReader().use { it.readText() })?.let { return it.value.lowercase() }
                        }
                    } finally { c.disconnect() }
                } catch (e: Exception) { /* пробуем описание релиза */ }
            }
        }
        return Regex("SHA-256:\\s*(" + HEX64.pattern + ")").find(body)?.groupValues?.get(1)?.lowercase() ?: ""
    }

    /** Скачивает APK в кэш приложения. onProgress получает проценты 0..100. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection   // GitHub перенаправляет на CDN — HttpURLConnection идёт сам
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "PharmShopApp")
            if (c.responseCode !in 200..299) throw Exception("Не удалось скачать (код ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            var done = 0L
            var lastPct = -1
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && done < total) { out.delete(); throw Exception("Файл скачался не полностью, попробуйте ещё раз") }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != info.sha256) {
                out.delete()
                throw Exception("Контрольная сумма файла не совпала — обновление отменено. Попробуйте позже.")
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Можно ли ставить APK из этого приложения (Android 8+ требует отдельного разрешения). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        ctx.startActivity(i)
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
