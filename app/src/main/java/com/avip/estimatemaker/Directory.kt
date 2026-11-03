package com.avip.estimatemaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installers and transporters, read from AVIP's two Google Sheets ("anyone with the link
 * can view"). Fetched on app launch and every time the list is opened; the last good
 * copy is kept on the phone for offline use.
 * Sheet IDs can be changed in App Settings → installers_sheet / transporters_sheet.
 */
object Directory {

    enum class Kind(val defaultSheet: String, val settingKey: String) {
        INSTALLERS("1zvf2JgnTFn1r2lC1DLDl8e8w7lhmqr0y_R01JueQD9k", "installers_sheet"),
        TRANSPORTERS("1FCOUi-6FNZaxEYLiDk4fVUfYY2AqWRM2Ck_iIIMlrOw", "transporters_sheet"),
    }

    /** One person in a list. [extra] = "Area interested" (installers) or vehicle number (transporters). */
    data class Entry(
        val name: String,
        val phone: String,
        val location: String,
        val area: String,
        val vehicleType: String,
        val vehicleNo: String,
    )

    @Volatile var installers: List<Entry> = emptyList(); private set
    @Volatile var transporters: List<Entry> = emptyList(); private set
    @Volatile var updatedAt = mutableMapOf<Kind, Long>()

    fun list(kind: Kind) = if (kind == Kind.INSTALLERS) installers else transporters

    private fun file(c: Context, kind: Kind) = File(c.filesDir, "dir_${kind.name.lowercase()}.csv")

    private fun sheetId(kind: Kind): String {
        val v = Catalog.settings[kind.settingKey]
        // Accept either a bare ID or a full sheet link in App Settings.
        val fromLink = Regex("/d/([A-Za-z0-9_-]{20,})").find(v)?.groupValues?.get(1)
        return fromLink ?: v.takeIf { Regex("[A-Za-z0-9_-]{20,}").matches(it) } ?: kind.defaultSheet
    }

    fun loadCached(c: Context) {
        Kind.values().forEach { k ->
            val f = file(c, k)
            if (f.exists() && list(k).isEmpty()) runCatching {
                set(k, parse(k, f.readText()))
                updatedAt[k] = f.lastModified()
            }
        }
    }

    /** Fetches both lists. Returns true if both came through. */
    suspend fun refreshAll(c: Context): Boolean {
        var ok = true
        Kind.values().forEach { if (!refresh(c, it)) ok = false }
        return ok
    }

    suspend fun refresh(c: Context, kind: Kind): Boolean = withContext(Dispatchers.IO) {
        try {
            val csv = download("https://docs.google.com/spreadsheets/d/${sheetId(kind)}/gviz/tq?tqx=out:csv&headers=1")
            val rows = parse(kind, csv)
            if (rows.isEmpty() && list(kind).isNotEmpty()) error("empty")
            set(kind, rows)
            file(c, kind).writeText(csv)
            updatedAt[kind] = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            if (list(kind).isEmpty()) loadCached(c)
            false
        }
    }

    private fun set(kind: Kind, rows: List<Entry>) {
        if (kind == Kind.INSTALLERS) installers = rows else transporters = rows
    }

    private fun download(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Reads columns by their headings, so the team can add or reorder columns. */
    private fun parse(kind: Kind, csv: String): List<Entry> {
        val rows = Csv.parse(csv)
        if (rows.size < 2) return emptyList()
        val h = rows[0].map { it.trim().lowercase() }
        fun col(vararg keys: String) = h.indexOfFirst { head -> keys.any { head.contains(it) } }
        val cName = col("name")
        val cPhone = col("phone", "mobile", "contact")
        val cLoc = col("base location", "location", "city")
        val cArea = col("area")
        val cType = col("vehicle type", "type")
        val cNo = col("vehicle number", "vehicle no", "number plate")
        return rows.drop(1).mapNotNull { r ->
            fun v(c: Int) = if (c in r.indices) r[c].trim().replace(Regex("\\s+"), " ") else ""
            val name = titleCase(v(cName))
            val phone = v(cPhone).filter { it.isDigit() }.takeLast(10)
            if (name.isBlank() || phone.length < 10) return@mapNotNull null
            Entry(
                name = name,
                phone = phone,
                location = titleCase(v(cLoc)),
                area = if (kind == Kind.INSTALLERS) titleCase(v(cArea)) else "",
                vehicleType = if (kind == Kind.TRANSPORTERS) titleCase(v(cType)) else "",
                vehicleNo = if (kind == Kind.TRANSPORTERS) v(cNo).uppercase().replace(" ", "") else "",
            )
        }
    }

    private fun titleCase(s: String) = s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
        if (w.length > 1 && w.all { it.isUpperCase() || !it.isLetter() }) w  // keep codes like AIIMS
        else w.lowercase().replaceFirstChar { it.uppercase() }
    }
}
