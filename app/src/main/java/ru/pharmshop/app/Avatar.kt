package ru.pharmshop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * Аватарка профиля. Лежит в закрытой папке приложения (другие приложения её не видят),
 * никуда не отправляется и не попадает в резервные копии. Хранится маленьким квадратным JPEG.
 */
object Avatar {
    private const val SIZE = 384
    private var dir: File? = null
    private var cache: Bitmap? = null

    fun init(ctx: Context) { dir = ctx.filesDir }

    private fun file(): File? = dir?.let { File(it, "avatar.jpg") }

    fun has(): Boolean = file()?.exists() == true

    /** Готовая картинка или null, если аватарки нет. */
    fun bitmap(): Bitmap? {
        cache?.let { return it }
        val f = file() ?: return null
        if (!f.exists()) return null
        val b = try { BitmapFactory.decodeFile(f.path) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
        cache = b
        return b
    }

    /** Берёт картинку по ссылке из галереи, обрезает до квадрата по центру, уменьшает и сохраняет. Не вызывать из главного потока. */
    fun save(ctx: Context, uri: Uri): Boolean {
        val f = file() ?: return false
        try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            val src = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false

            // поворот по данным камеры, чтобы фото не оказалось на боку
            val orient = try {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val m = Matrix()
            when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }

            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
            val out = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)

            val tmp = File(f.parentFile, "avatar.tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) return false
            }
            cache = out
            return true
        } catch (e: Exception) {
            AppLog.e("avatar", e)
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
    }

    /** Метка текущей аватарки (меняется при каждом новом фото): по ней понятно, загружена ли она уже на сервер чата. */
    fun stamp(): String = file()?.takeIf { it.exists() }?.lastModified()?.toString() ?: ""

    /** Уменьшенная копия 128×128 (JPEG, base64) для чата. Пустая строка — аватарки нет. Не вызывать из главного потока. */
    fun thumbBase64(): String {
        val b = bitmap() ?: return ""
        return try {
            val t = Bitmap.createScaledBitmap(b, 128, 128, true)
            val o = java.io.ByteArrayOutputStream()
            t.compress(Bitmap.CompressFormat.JPEG, 80, o)
            android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    /** Удаляет аватарку. */
    fun remove() {
        cache = null
        try { file()?.delete() } catch (e: Exception) { }
    }
}
