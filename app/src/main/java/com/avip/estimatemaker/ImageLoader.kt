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
        while (bounds.outWidth / (sample * 2) >= maxPx * 2 && bounds.outHeight / (sample * 2) >= maxPx * 2) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return try { squareAroundProduct(raw, maxPx) } catch (_: Throwable) { if (raw.isRecycled) null else raw }
    }

    private class Comp(
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
        val n: Int, val cx: Float, val cy: Float, val strip: Boolean, val thinStrip: Boolean,
    )

    private class Found(val l: Int, val t: Int, val r: Int, val b: Int, val n: Int) {
        val area get() = (r - l + 1) * (b - t + 1)
    }

    /**
     * Turns any photo (phone portrait, WhatsApp landscape, …) into a square picture with
     * the product centred and filling the frame. The product is found against the paper /
     * floor background two ways (whole-photo background colour, and local background for
     * photos with shadows); thin dark table edges along the border are painted over.
     */
    fun squareAroundProduct(src: Bitmap, maxPx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val sc = 160f / maxOf(w, h)
        val sw = maxOf(1, Math.round(w * sc))
        val sh = maxOf(1, Math.round(h * sc))
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val n = sw * sh
        val px = IntArray(n)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val rr = IntArray(n) { (px[it] shr 16) and 0xFF }
        val gg = IntArray(n) { (px[it] shr 8) and 0xFF }
        val bb = IntArray(n) { px[it] and 0xFF }
        fun median(a: IntArray) = a.copyOf().also { it.sort() }[a.size / 2]

        // Background colour from the border band.
        val band = maxOf(2, minOf(sw, sh) / 12)
        val bi = (0 until n).filter { val x = it % sw; val y = it / sw; x < band || y < band || x >= sw - band || y >= sh - band }
        val gbR = median(IntArray(bi.size) { rr[bi[it]] })
        val gbG = median(IntArray(bi.size) { gg[bi[it]] })
        val gbB = median(IntArray(bi.size) { bb[bi[it]] })

        // Local background: box blur (integral image) with a large radius.
        val rad = maxOf(4, Math.round(maxOf(sw, sh) / 5f))
        fun blur(c: IntArray): FloatArray {
            val ii = DoubleArray((sw + 1) * (sh + 1))
            for (y in 0 until sh) {
                var row = 0.0
                for (x in 0 until sw) {
                    row += c[y * sw + x]
                    ii[(y + 1) * (sw + 1) + x + 1] = ii[y * (sw + 1) + x + 1] + row
                }
            }
            val o = FloatArray(n)
            for (y in 0 until sh) for (x in 0 until sw) {
                val x0 = maxOf(0, x - rad); val x1 = minOf(sw, x + rad + 1)
                val y0 = maxOf(0, y - rad); val y1 = minOf(sh, y + rad + 1)
                val sum = ii[y1 * (sw + 1) + x1] - ii[y0 * (sw + 1) + x1] - ii[y1 * (sw + 1) + x0] + ii[y0 * (sw + 1) + x0]
                o[y * sw + x] = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
            }
            return o
        }
        val lR = blur(rr); val lG = blur(gg); val lB = blur(bb)

        fun components(fg: BooleanArray, dilate: Boolean): List<Comp> {
            val fd = if (!dilate) fg else BooleanArray(n) { i ->
                val x = i % sw; val y = i / sw
                var v = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until sw && yy in 0 until sh && fg[yy * sw + xx]) v = true
                }
                v
            }
            val label = IntArray(n) { -1 }
            val comps = ArrayList<Comp>()
            val stack = IntArray(n)
            for (s in 0 until n) {
                if (!fd[s] || label[s] >= 0) continue
                var top = 0; stack[top++] = s; label[s] = comps.size
                var minX = sw; var minY = sh; var maxX = 0; var maxY = 0; var cnt = 0; var sx = 0L; var sy = 0L
                while (top > 0) {
                    val p = stack[--top]; val x = p % sw; val y = p / sw
                    cnt++; sx += x; sy += y
                    if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (x > 0 && fd[p - 1] && label[p - 1] < 0) { label[p - 1] = comps.size; stack[top++] = p - 1 }
                    if (x < sw - 1 && fd[p + 1] && label[p + 1] < 0) { label[p + 1] = comps.size; stack[top++] = p + 1 }
                    if (y > 0 && fd[p - sw] && label[p - sw] < 0) { label[p - sw] = comps.size; stack[top++] = p - sw }
                    if (y < sh - 1 && fd[p + sw] && label[p + sw] < 0) { label[p + sw] = comps.size; stack[top++] = p + sw }
                }
                val bw = maxX - minX + 1; val bh = maxY - minY + 1
                val touchLR = minX <= 1 || maxX >= sw - 2
                val touchTB = minY <= 1 || maxY >= sh - 2
                val thin = (touchLR && bw < sw * 0.12f) || (touchTB && bh < sh * 0.12f)
                comps += Comp(minX, minY, maxX, maxY, cnt, sx.toFloat() / cnt, sy.toFloat() / cnt,
                    strip = thin || bw * bh > n * 0.7f, thinStrip = thin)
            }
            return comps
        }

        fun pick(comps: List<Comp>): Found? {
            val cand = comps.filter { !it.strip && it.n >= 5 }
            if (cand.isEmpty()) return null
            fun score(c: Comp): Float {
                val dx = (c.cx - sw / 2f) / (sw / 2f); val dy = (c.cy - sh / 2f) / (sh / 2f)
                val dist = minOf(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                val f = 1f - 0.8f * dist
                return c.n * f * f
            }
            val sorted = cand.sortedByDescending { score(it) }
            val m = sorted[0]
            var l = m.minX; var t = m.minY; var r = m.maxX; var b = m.maxY
            val padX = (r - l + 1) * 0.25f + 3; val padY = (b - t + 1) * 0.25f + 3
            for (c in sorted.drop(1)) {
                if (c.n >= m.n * 0.08f && c.maxX >= l - padX && c.minX <= r + padX && c.maxY >= t - padY && c.minY <= b + padY) {
                    l = minOf(l, c.minX); t = minOf(t, c.minY); r = maxOf(r, c.maxX); b = maxOf(b, c.maxY)
                }
            }
            return Found(l, t, r, b, m.n)
        }

        val globalComps = components(BooleanArray(n) {
            Math.abs(rr[it] - gbR) + Math.abs(gg[it] - gbG) + Math.abs(bb[it] - gbB) > 60
        }, dilate = false)
        val global = pick(globalComps)
        val local = pick(components(BooleanArray(n) {
            Math.abs(rr[it] - lR[it]) + Math.abs(gg[it] - lG[it]) + Math.abs(bb[it] - lB[it]) > 45
        }, dilate = true))
        val res = when {
            global == null -> local
            local == null -> global
            global.n > local.n * 3 && global.area < n * 0.6f -> global
            else -> local
        }

        var left = 0f; var top = 0f; var right = w.toFloat(); var bottom = h.toFloat()
        if (res != null) {
            left = res.l / sc; top = res.t / sc; right = (res.r + 1) / sc; bottom = (res.b + 1) / sc
        }
        var side = maxOf(right - left, bottom - top) * 1.35f
        side = maxOf(side, minOf(w, h) * 0.3f)
        var cx = (left + right) / 2f
        var cy = (top + bottom) / 2f
        // Keep the square inside the photo when it fits, so no padding is needed.
        if (side <= w) cx = cx.coerceIn(side / 2, w - side / 2)
        if (side <= h) cy = cy.coerceIn(side / 2, h - side / 2)

        val out = maxPx.coerceAtMost(600)
        val result = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val fill = android.graphics.Paint().apply { color = android.graphics.Color.rgb(median(rr), median(gg), median(bb)) }
        canvas.drawRect(0f, 0f, out.toFloat(), out.toFloat(), fill)
        val sL = cx - side / 2; val sT = cy - side / 2
        val cl = maxOf(0f, sL); val ct = maxOf(0f, sT)
        val cr = minOf(w.toFloat(), sL + side); val cb = minOf(h.toFloat(), sT + side)
        val k = out / side
        canvas.drawBitmap(
            src, android.graphics.Rect(cl.toInt(), ct.toInt(), cr.toInt(), cb.toInt()),
            android.graphics.RectF((cl - sL) * k, (ct - sT) * k, (cr - sL) * k, (cb - sT) * k),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        // Paint over thin dark table edges along the photo border.
        for (q in globalComps) {
            if (!q.thinStrip || q.n < 5) continue
            canvas.drawRect(
                ((q.minX - 1) / sc - sL) * k, ((q.minY - 1) / sc - sT) * k,
                ((q.maxX + 2) / sc - sL) * k, ((q.maxY + 2) / sc - sT) * k, fill
            )
        }
        src.recycle()
        return result
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
