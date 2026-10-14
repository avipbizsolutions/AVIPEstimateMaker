package com.avip.estimatemaker

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/** One row of the "App Prices" tab. */
data class Product(
    val id: String,
    val category: String,
    val icon: String,
    val name: String,
    val brand: String,
    val spec: String,
    val unit: String,
    val rate: Double?,
    val perWp: Boolean,
    val wp: Double?,
    val gst: Double,
    val minQty: Int,
    val inStock: Boolean,
    val notes: String,
) {
    /** Price of one unit (one panel, one kit, one kg …), before GST. */
    val unitPrice: Double?
        get() = rate?.let { r -> if (perWp && wp != null && wp > 0) round2(r * wp) else r }

    val available: Boolean get() = inStock && unitPrice != null

    val title: String get() = listOf(brand, name).filter { it.isNotBlank() }.joinToString(" ")

    /** Inverter / kit capacity read from the name, e.g. "3.3 kW On-grid Inverter" → 3.3. */
    val kw: Double?
        get() = Regex("""(\d+(?:\.\d+)?)\s*kw""", RegexOption.IGNORE_CASE).find(name)
            ?.groupValues?.get(1)?.toDoubleOrNull()

    /** Panel wattage, for system size (kWp) — only for per-Wp products. */
    val panelWp: Double get() = if (perWp) (wp ?: 0.0) else 0.0
}

data class Category(val name: String, val icon: String, val products: List<Product>) {
    val availableCount get() = products.count { it.available }
    val fromPrice get() = products.filter { it.available }.mapNotNull { it.unitPrice }.minOrNull()
}

/** Key/value pairs from the "App Settings" tab. */
class AppSettings(private val map: Map<String, String>) {
    operator fun get(key: String): String = map[key]?.trim() ?: ""
    fun num(key: String, default: Double): Double =
        get(key).replace(",", "").toDoubleOrNull() ?: default

    fun terms(): List<String> = (1..10).map { get("terms_$it") }.filter { it.isNotBlank() }

    fun toJson(): JSONObject = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val m = mutableMapOf<String, String>()
            o.keys().forEach { m[it] = o.optString(it) }
            return AppSettings(m)
        }
    }
}

/** A line in the quotation: one catalogue product. */
data class Line(
    val key: String,
    val productId: String?,
    var name: String,
    var spec: String,
    var unit: String,
    var price: Double,
    var gst: Double,
    var qty: Double,
    var wp: Double,
    var minQty: Double,
    val icon: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("productId", productId ?: JSONObject.NULL)
        .put("name", name).put("spec", spec).put("unit", unit)
        .put("price", price).put("gst", gst).put("qty", qty)
        .put("wp", wp).put("minQty", minQty).put("icon", icon)

    companion object {
        fun fromJson(o: JSONObject) = Line(
            key = o.optString("key"),
            productId = if (o.isNull("productId")) null else o.optString("productId"),
            name = o.optString("name"),
            spec = o.optString("spec"),
            unit = o.optString("unit"),
            price = o.optDouble("price", 0.0),
            gst = o.optDouble("gst", 0.0),
            qty = o.optDouble("qty", 1.0),
            wp = o.optDouble("wp", 0.0),
            minQty = o.optDouble("minQty", 1.0),
            icon = o.optString("icon", "other"),
        )

        fun fromProduct(p: Product, qty: Double) = Line(
            key = p.id,
            productId = p.id,
            name = p.title,
            spec = listOf(p.category, p.spec).filter { it.isNotBlank() }.joinToString(" · "),
            unit = p.unit,
            price = p.unitPrice ?: 0.0,
            gst = p.gst,
            qty = qty,
            wp = p.panelWp,
            minQty = p.minQty.toDouble().coerceAtLeast(1.0),
            icon = p.icon,
        )
    }
}

/** Everything needed to draw (and later re-open) one AVIP quotation. */
data class EstimateDoc(
    val number: String,
    val createdAt: Long,
    val lines: List<Line>,
    val grandTotal: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("number", number).put("createdAt", createdAt).put("grandTotal", grandTotal)
        .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): EstimateDoc {
            val arr = o.optJSONArray("lines") ?: JSONArray()
            return EstimateDoc(
                number = o.optString("number"),
                createdAt = o.optLong("createdAt"),
                lines = (0 until arr.length()).map { Line.fromJson(arr.getJSONObject(it)) },
                grandTotal = o.optDouble("grandTotal", 0.0),
            )
        }
    }
}

fun round2(v: Double) = round(v * 100.0) / 100.0
