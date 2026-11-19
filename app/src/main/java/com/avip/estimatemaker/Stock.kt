package com.avip.estimatemaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stock Take (AVIP Team only): periodic physical counts at the Chirora godown.
 * The office keeps the history ("Stock Counts" tab) and the latest qty per item ("Current Stock",
 * with the Min Level the office types in). The count in progress is kept on this phone until submitted.
 */
object Stock {
    class Current(val id: String, val qty: Double, val min: Double?, val lastCounted: Long, val by: String, val note: String)
    class Past(val id: String, val date: Long, val by: String, val items: Int, val changed: Int)
    class ServerDraft(val id: String, val by: String, val mobile: String, val started: Long)

    @Volatile var current: Map<String, Current> = emptyMap(); private set
    @Volatile var history: List<Past> = emptyList(); private set
    @Volatile var serverDraft: ServerDraft? = null; private set
    @Volatile var loaded = false; private set
    var godown = "Chirora, Patna"; private set

    /** Products that are counted: one line per physical item (pallet rates are only a price, not stock). */
    fun items(): List<Product> = Catalog.products.filter { !it.isPallet }

    fun label(p: Product) = listOf(p.title, p.spec).filter { it.isNotBlank() }.joinToString(" · ")

    /** Value at today's rate before GST (shown to Founders only). */
    fun value(p: Product, qty: Double) = (p.unitPrice ?: 0.0) * qty

    fun isLow(p: Product, qty: Double?): Boolean {
        val min = current[p.id]?.min ?: return false
        return qty != null && qty > 0 && qty < min
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("stock", Context.MODE_PRIVATE)

    fun loadCached(c: Context) {
        if (loaded) return
        prefs(c).getString("status", null)?.let { runCatching { apply(JSONObject(it)) } }
    }

    suspend fun refresh(c: Context) {
        val o = Api.call(c, "stockStatus", JSONObject().put("token", Session.token(c)))
        apply(o)
        prefs(c).edit().putString("status", o.toString()).apply()
    }

    private fun apply(o: JSONObject) {
        val cur = o.optJSONArray("current") ?: JSONArray()
        current = (0 until cur.length()).mapNotNull { cur.optJSONObject(it) }.associate {
            it.optString("id") to Current(it.optString("id"), it.optDouble("qty", 0.0),
                if (it.isNull("min") || !it.has("min")) null else it.optDouble("min").takeIf { m -> !m.isNaN() },
                it.optLong("lastCounted"), it.optString("by"), it.optString("note"))
        }
        val h = o.optJSONArray("history") ?: JSONArray()
        history = (0 until h.length()).mapNotNull { h.optJSONObject(it) }.map {
            Past(it.optString("id"), it.optLong("date"), it.optString("by"), it.optInt("items"), it.optInt("changed"))
        }
        serverDraft = o.optJSONObject("draft")?.let {
            ServerDraft(it.optString("id"), it.optString("by"), it.optString("mobile"), it.optLong("started"))
        }
        godown = o.optString("godown").ifBlank { godown }
        loaded = true
    }

    fun draftIsOthers(c: Context): Boolean {
        val d = serverDraft ?: return false
        return d.mobile != Session.teamMobile(c) && Draft.get(c)?.id != d.id
    }

    /** The count in progress on this phone. */
    class Draft(val id: String, val started: Long, val counts: MutableMap<String, Pair<Double, String>>) {
        companion object {
            fun get(c: Context): Draft? = runCatching {
                val o = JSONObject(prefs(c).getString("draft", "") ?: "")
                val m = mutableMapOf<String, Pair<Double, String>>()
                val cs = o.getJSONObject("counts")
                cs.keys().forEach { k -> val v = cs.getJSONObject(k); m[k] = v.optDouble("qty") to v.optString("note") }
                Draft(o.getString("id"), o.optLong("started"), m)
            }.getOrNull()

            fun start(c: Context, id: String, started: Long) = Draft(id, started, mutableMapOf()).also { it.save(c) }
            fun clear(c: Context) = prefs(c).edit().remove("draft").apply()
        }

        fun save(c: Context) {
            val cs = JSONObject()
            counts.forEach { (k, v) -> cs.put(k, JSONObject().put("qty", v.first).put("note", v.second)) }
            prefs(c).edit().putString("draft", JSONObject().put("id", id).put("started", started).put("counts", cs).toString()).apply()
        }

        fun set(c: Context, p: Product, qty: Double?, note: String? = null) {
            if (qty == null) counts.remove(p.id)
            else counts[p.id] = qty.coerceAtLeast(0.0) to (note ?: counts[p.id]?.second ?: "")
            save(c)
        }

        fun qty(p: Product) = counts[p.id]?.first
        fun note(p: Product) = counts[p.id]?.second ?: ""
        fun changed(p: Product): Boolean {
            val q = qty(p) ?: return false
            val last = current[p.id]?.qty ?: return false
            return kotlin.math.abs(q - last) > 0.0001
        }

        fun body(): JSONArray = JSONArray().apply {
            val byId = items().associateBy { it.id }
            counts.forEach { (id, v) ->
                val p = byId[id] ?: return@forEach
                put(JSONObject().put("id", id).put("category", p.category).put("name", label(p))
                    .put("unit", p.unit).put("qty", v.first).put("note", v.second))
            }
        }
    }

    /** Starts (or continues) a count. Throws ApiException("BUSY") when someone else is counting. */
    suspend fun start(c: Context, force: Boolean = false): Draft {
        Draft.get(c)?.let { return it }
        return try {
            val o = Api.call(c, "stockStart", JSONObject().put("token", Session.token(c)).put("force", force))
            val d = o.getJSONObject("draft")
            Draft.start(c, d.optString("id"), d.optLong("started"))
        } catch (e: ApiException) {
            if (e.code != "NET") throw e
            // No signal in the godown: count on the phone now, the office lock is taken at submit.
            val id = "SC-" + java.text.SimpleDateFormat("yyMMdd-HHmm", java.util.Locale.ENGLISH).format(java.util.Date())
            Draft.start(c, id, System.currentTimeMillis())
        }
    }

    suspend fun submit(c: Context, d: Draft) {
        Api.call(c, "stockSubmit", JSONObject().put("token", Session.token(c)).put("countId", d.id).put("items", d.body()))
        Draft.clear(c)
        runCatching { refresh(c) }
    }

    suspend fun cancel(c: Context) {
        Draft.clear(c)
        runCatching { Api.call(c, "stockCancel", JSONObject().put("token", Session.token(c))) }
        runCatching { refresh(c) }
    }
}
