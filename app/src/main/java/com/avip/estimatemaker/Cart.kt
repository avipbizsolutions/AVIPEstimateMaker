package com.avip.estimatemaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The quotation being built. Kept on the phone so nothing is lost if the app closes. */
object Cart {
    private const val PREFS = "cart"

    val lines = mutableListOf<Line>()

    private val listeners = mutableSetOf<() -> Unit>()
    private var loaded = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val o = JSONObject(p.getString("cart", "{}") ?: "{}")
            val arr = o.optJSONArray("lines") ?: JSONArray()
            lines.clear()
            for (i in 0 until arr.length()) {
                val l = Line.fromJson(arr.getJSONObject(i))
                if (l.productId != null) lines += l // drop items from the old "own item" feature
            }
        } catch (_: Exception) {
        }
    }

    fun save(context: Context) {
        val o = JSONObject().put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cart", o.toString()).apply()
    }

    fun changed(context: Context) {
        save(context)
        listeners.toList().forEach { it() }
    }

    val count get() = lines.size

    fun qtyOf(productId: String): Double = lines.firstOrNull { it.productId == productId }?.qty ?: 0.0

    fun setQty(context: Context, product: Product, qty: Double) {
        val existing = lines.firstOrNull { it.productId == product.id }
        when {
            qty <= 0 -> existing?.let { lines.remove(it) }
            existing != null -> existing.qty = qty
            else -> lines += Line.fromProduct(product, qty)
        }
        changed(context)
    }

    fun remove(context: Context, line: Line) {
        lines.remove(line)
        changed(context)
    }

    fun clear(context: Context) {
        lines.clear()
        changed(context)
    }

    /** Fresh prices from the sheet are applied to items already in the quotation. */
    fun reprice(context: Context) {
        load(context)
        var any = false
        // Items no longer priced in the Worksheet (renamed, removed or rate cleared) are dropped.
        if (Catalog.isLive && Catalog.isLoaded) {
            any = lines.removeAll { l -> Catalog.byId(l.productId)?.available != true }
        }
        lines.forEach { l ->
            val p = Catalog.byId(l.productId) ?: return@forEach
            val price = p.unitPrice ?: return@forEach
            if (price != l.price || p.gst != l.gst) {
                l.price = price; l.gst = p.gst; any = true
            }
        }
        if (any) save(context)
    }

    /** Puts a saved quotation back into the cart for editing. */
    fun loadFrom(context: Context, doc: EstimateDoc) {
        lines.clear()
        lines += doc.lines.filter { it.productId != null }.map { it.copy() }
        reprice(context)
        changed(context)
    }

    fun totals(): Totals = Calc.compute(lines)
}
