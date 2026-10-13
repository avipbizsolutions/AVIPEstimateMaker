package com.avip.estimatemaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The estimate being built. Kept on the phone so nothing is lost if the app closes. */
object Cart {
    private const val PREFS = "cart"

    val lines = mutableListOf<Line>()

    // Estimate details that belong with the cart
    var type = EstimateType.AVIP_QUOTE
    var customerName = ""
    var customerPhone = ""
    var customerAddress = ""
    var marginPct = 0.0
    var showRates = true
    var showSubsidy = false
    var subsidyOverride: Double? = null
    var notes = ""

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
            for (i in 0 until arr.length()) lines += Line.fromJson(arr.getJSONObject(i))
            type = runCatching { EstimateType.valueOf(o.optString("type")) }.getOrDefault(EstimateType.AVIP_QUOTE)
            customerName = o.optString("customerName")
            customerPhone = o.optString("customerPhone")
            customerAddress = o.optString("customerAddress")
            marginPct = o.optDouble("marginPct", 0.0)
            showRates = o.optBoolean("showRates", true)
            showSubsidy = o.optBoolean("showSubsidy", false)
            subsidyOverride = if (o.has("subsidyOverride")) o.optDouble("subsidyOverride") else null
            notes = o.optString("notes")
        } catch (_: Exception) {
        }
    }

    fun save(context: Context) {
        val o = JSONObject()
            .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
            .put("type", type.name)
            .put("customerName", customerName)
            .put("customerPhone", customerPhone)
            .put("customerAddress", customerAddress)
            .put("marginPct", marginPct)
            .put("showRates", showRates)
            .put("showSubsidy", showSubsidy)
            .put("notes", notes)
        subsidyOverride?.let { o.put("subsidyOverride", it) }
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

    fun addCustom(context: Context, name: String, unit: String, price: Double, gst: Double, qty: Double) {
        lines += Line(
            key = "C-" + UUID.randomUUID().toString().take(8),
            productId = null, name = name, spec = "", unit = unit, price = price, gst = gst,
            qty = qty, wp = 0.0, minQty = 0.0, icon = "service",
        )
        changed(context)
    }

    fun remove(context: Context, line: Line) {
        lines.remove(line)
        changed(context)
    }

    fun clear(context: Context) {
        lines.clear()
        customerName = ""; customerPhone = ""; customerAddress = ""
        subsidyOverride = null; notes = ""
        changed(context)
    }

    /** Fresh prices from the sheet are applied to items already in the estimate. */
    fun reprice(context: Context) {
        load(context)
        var any = false
        lines.forEach { l ->
            val p = Catalog.byId(l.productId) ?: return@forEach
            val price = p.unitPrice ?: return@forEach
            if (price != l.price || p.gst != l.gst) {
                l.price = price; l.gst = p.gst; any = true
            }
        }
        if (any) save(context)
    }

    /** Puts a saved estimate back into the cart for editing. */
    fun loadFrom(context: Context, doc: EstimateDoc) {
        lines.clear()
        lines += doc.lines.map { it.copy() }
        type = doc.type
        customerName = doc.customerName
        customerPhone = doc.customerPhone
        customerAddress = doc.customerAddress
        marginPct = doc.marginPct
        showRates = doc.showRates
        showSubsidy = doc.showSubsidy
        subsidyOverride = doc.subsidy.takeIf { doc.showSubsidy }
        notes = doc.notes
        reprice(context)
        changed(context)
    }

    fun totals(): Totals = Calc.compute(lines, if (type == EstimateType.CUSTOMER) marginPct else 0.0)

    /** Quick total for the bottom bar (AVIP prices, incl. GST). */
    fun quickTotal(): Double = Calc.compute(lines, 0.0).grandTotal
}
