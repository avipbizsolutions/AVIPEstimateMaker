package com.avip.estimatemaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small photo loader: memory + disk cache, downscaled decoding, no extra libraries.
 * Photos are downloaded once and then work offline.
 */
object ImageLoader {
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = mutableSetOf<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Google Drive "share" links become direct image links. */
    fun direct(url: String): String {
        val u = url.trim()
        val id = Regex("drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?(?:.*&)?id=)([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
        return if (id != null) "https://drive.google.com/uc?export=download&id=$id" else u
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    /** Loads [url] and calls [onLoaded] on the main thread (not called if it fails). */
    fun load(context: Context, url: String, maxPx: Int, onLoaded: (Bitmap) -> Unit) {
        if (url.isBlank()) return
        memory.get(url)?.let { onLoaded(it); return }
        if (url in failed) return
        val app = context.applicationContext
        pool.execute {
            val bmp = try { fetch(app, url, maxPx) } catch (_: Throwable) { null }
            if (bmp != null) {
                memory.put(url, bmp)
                main.post { onLoaded(bmp) }
            } else synchronized(failed) { failed += url }
        }
    }

    private fun fetch(c: Context, url: String, maxPx: Int): Bitmap? {
        val dir = File(c.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, md5(url))
        if (!file.exists() || file.length() == 0L) {
            val part = File(dir, file.name + ".part")
            var target = URL(direct(url))
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = target.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AVIP-Surya-Saathi")
                conn.setRequestProperty("Accept", "image/*")
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: return null
                    conn.disconnect(); target = URL(target, next); hops++; continue
                }
                if (code != 200) { conn.disconnect(); return null }
                break
            }
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (!part.renameTo(file)) return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) { file.delete(); return null }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
