package com.avip.estimatemaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Prices and settings come straight from the AVIP Google Sheet ("anyone with the
 * link can view"), read as CSV. The last good copy is kept on the phone, so the
 * app still works offline.
 */
object Catalog {

    private const val SHEET_ID = "1TEhXDY6fUY93h63IZzhWFwxA0pXwFkNflybA9hO9DVc"
    private const val PRICES_TAB = "App Prices"
    private const val SETTINGS_TAB = "App Settings"
    private const val PHOTOS_TAB = "Product Photos"

    /** One row of the "Product Photos" tab: all [words] must appear in a product. */
    private class PhotoRule(val words: List<String>, val url: String)
    @Volatile private var photoRules: List<PhotoRule> = emptyList()

    @Volatile var products: List<Product> = emptyList()
        private set
    @Volatile var settings: AppSettings = AppSettings(emptyMap())
        private set
    @Volatile var updatedAt: Long = 0L
        private set
    @Volatile var isLive: Boolean = false
        private set

    val isLoaded get() = products.isNotEmpty()

    private fun url(tab: String) =
        "https://docs.google.com/spreadsheets/d/$SHEET_ID/gviz/tq?tqx=out:csv&headers=1&sheet=" +
            URLEncoder.encode(tab, "UTF-8").replace("+", "%20")

    private fun pricesFile(c: Context) = File(c.filesDir, "prices.csv")
    private fun settingsFile(c: Context) = File(c.filesDir, "settings.csv")
    private fun photosFile(c: Context) = File(c.filesDir, "photos.csv")

    /** Loads the copy saved on the phone. Returns false if there is none. */
    fun loadCached(context: Context): Boolean {
        val p = pricesFile(context)
        val s = settingsFile(context)
        if (!p.exists()) return false
        return try {
            apply(p.readText(), if (s.exists()) s.readText() else "")
            updatedAt = p.lastModified()
            isLive = false
            photosFile(context).takeIf { it.exists() }?.let { photoRules = parsePhotos(it.readText()) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Downloads fresh prices from the sheet. Returns true on success. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val prices = download(url(PRICES_TAB))
            val sets = download(url(SETTINGS_TAB))
            apply(prices, sets) // throws if the data is unusable, keeping the old copy
            pricesFile(context).writeText(prices)
            settingsFile(context).writeText(sets)
            try {
                val photos = download(url(PHOTOS_TAB))
                photoRules = parsePhotos(photos)
                photosFile(context).writeText(photos)
            } catch (_: Exception) {
            }
            updatedAt = System.currentTimeMillis()
            isLive = true
            Cart.reprice(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun download(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun apply(pricesCsv: String, settingsCsv: String) {
        val list = parseProducts(pricesCsv)
        if (list.isEmpty()) error("no products")
        products = list
        settings = parseSettings(settingsCsv)
    }

    fun byId(id: String?): Product? = products.firstOrNull { it.id == id }

    /**
     * Versions of the same item shown on one card:
     * packet + loose (e.g. "20 mm PVC Elbows" per packet and per piece), or
     * single panel + pallet (same brand). Returns just [p] when it has no partner.
     */
    fun variantsOf(p: Product): List<Product> {
        if (p.group.isBlank()) return listOf(p)
        val same = products.filter { it.category == p.category && it.group == p.group }
        if (!p.isPallet) {
            val loose = same.filter { it.isLoose }
            val packs = same.filter { !it.isLoose && !it.isPallet }
            if (loose.size == 1 && packs.size == 1 && (p.isLoose || p == packs[0])) return packs + loose
        }
        if (!p.isLoose) {
            val brand = same.filter { it.brand == p.brand && !it.isLoose }
            val singles = brand.filter { !it.isPallet }
            val pallets = brand.filter { it.isPallet }
            if (singles.size == 1 && pallets.size == 1) return singles + pallets
        }
        return listOf(p)
    }

    private fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9.]+")).map { it.trim('.') }.filter { it.isNotBlank() }.toSet()

    /** Photo link for a product: the matching rule with the most keywords ("" = none). */
    fun photoFor(p: Product): String {
        val have = tokens("${p.category} ${p.brand} ${p.name}")
        var best: PhotoRule? = null
        for (r in photoRules) {
            if (r.words.isNotEmpty() && have.containsAll(r.words) &&
                (best == null || r.words.size > best.words.size)) best = r
        }
        return best?.url ?: ""
    }

    private fun parsePhotos(csv: String): List<PhotoRule> =
        Csv.parse(csv).drop(1).mapNotNull { r ->
            val words = tokens(r.getOrNull(0) ?: "").toList()
            if (words.isEmpty()) null else PhotoRule(words, (r.getOrNull(1) ?: "").trim())
        }

    /** Categories in the order they first appear in the sheet. */
    fun categories(): List<Category> =
        products.groupBy { it.category }.map { (name, items) ->
            Category(name, items.first().icon, items)
        }

    fun search(query: String): List<Product> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return products.filter { p ->
            val hay = "${p.category} ${p.brand} ${p.name} ${p.spec} ${p.unit}".lowercase()
            words.all { hay.contains(it) }
        }
    }

    // ---- parsing ----

    private fun parseProducts(csv: String): List<Product> {
        val rows = Csv.parse(csv)
        if (rows.size < 2) return emptyList()
        val h = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n ->
            h.indexOf(n).takeIf { it >= 0 }
        } ?: -1
        val cId = col("id"); val cCat = col("category"); val cIcon = col("icon")
        val cName = col("product"); val cBrand = col("brand"); val cSpec = col("spec")
        val cUnit = col("unit"); val cRate = col("rate"); val cBasis = col("rate basis")
        val cWp = col("wp"); val cGst = col("gst %", "gst"); val cMin = col("min qty")
        val cStock = col("in stock"); val cNotes = col("notes")
        val cGroup = col("group"); val cPack = col("pack")
        if (cName < 0 || cRate < 0) return emptyList()

        return rows.drop(1).mapIndexedNotNull { i, r ->
            fun v(c: Int) = if (c in r.indices) r[c].trim() else ""
            val name = v(cName)
            if (name.isBlank()) return@mapIndexedNotNull null
            val stock = v(cStock).lowercase()
            Product(
                id = v(cId).ifBlank { "ROW${i + 2}" },
                category = v(cCat).ifBlank { "Other Products" },
                icon = v(cIcon).lowercase().ifBlank { "other" },
                name = name,
                brand = v(cBrand),
                spec = v(cSpec),
                unit = v(cUnit).ifBlank { "Pc" },
                rate = number(v(cRate))?.takeIf { it > 0 },
                perWp = v(cBasis).lowercase().contains("wp"),
                wp = number(v(cWp)),
                gst = number(v(cGst)) ?: 18.0,
                minQty = number(v(cMin))?.toInt()?.coerceAtLeast(1) ?: 1,
                inStock = !(stock == "no" || stock == "n" || stock == "false"),
                notes = v(cNotes),
                group = v(cGroup),
                pack = v(cPack),
            )
        }
    }

    private fun parseSettings(csv: String): AppSettings {
        val map = mutableMapOf<String, String>()
        Csv.parse(csv).drop(1).forEach { r ->
            if (r.size >= 2 && r[0].isNotBlank()) map[r[0].trim()] = r[1].trim()
        }
        return AppSettings(map)
    }

    private fun number(s: String): Double? =
        s.replace(",", "").replace("₹", "").replace("%", "").trim().toDoubleOrNull()
}

/** Minimal RFC-4180 CSV reader (quotes, escaped quotes, newlines inside cells). */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString()); rows.add(row)
        }
        return rows
    }
}
