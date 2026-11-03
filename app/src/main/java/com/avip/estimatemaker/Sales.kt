package com.avip.estimatemaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlin.math.round

/**
 * The Surya Saathi's own business, as printed on the quotations and tax invoices they
 * give their customers. Legal name, GSTIN and mobile come from registration.
 */
object Business {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("business", Context.MODE_PRIVATE)

    fun legalName(c: Context) = Vendor.company(c)
    fun gstin(c: Context) = Vendor.gst(c)
    fun stateCode(c: Context) = Vendor.gst(c).take(2)
    fun address(c: Context) = p(c).getString("address", null) ?: Vendor.address(c)
    fun pincode(c: Context) = p(c).getString("pincode", "") ?: ""
    fun phone(c: Context) = p(c).getString("phone", null) ?: Vendor.mobile(c)
    fun email(c: Context) = p(c).getString("email", "") ?: ""
    fun composition(c: Context) = p(c).getBoolean("composition", false)
    fun bankName(c: Context) = p(c).getString("bank_name", "") ?: ""
    fun bankAccount(c: Context) = p(c).getString("bank_account", "") ?: ""
    fun bankIfsc(c: Context) = p(c).getString("bank_ifsc", "") ?: ""
    fun upi(c: Context) = p(c).getString("upi", "") ?: ""
    fun invoicePrefix(c: Context) = (p(c).getString("invoice_prefix", "INV") ?: "INV").ifBlank { "INV" }
    fun quotePrefix(c: Context) = (p(c).getString("quote_prefix", "QT") ?: "QT").ifBlank { "QT" }
    fun quoteTerms(c: Context) = p(c).getString("quote_terms", null)
        ?: "Prices valid for 15 days.\n50% advance with order, balance before installation.\nWarranty as per manufacturer terms."
    fun invoiceTerms(c: Context) = p(c).getString("invoice_terms", null)
        ?: "Goods once sold will not be taken back.\nWarranty as per manufacturer terms.\nSubject to local jurisdiction."

    /** Enough to print a valid invoice: address and pincode. */
    fun isReady(c: Context) = address(c).length >= 5 && pincode(c).length == 6

    fun save(c: Context, values: Map<String, Any>) {
        val e = p(c).edit()
        values.forEach { (k, v) -> if (v is Boolean) e.putBoolean(k, v) else e.putString(k, v.toString()) }
        e.apply()
    }

    fun clear(c: Context) = p(c).edit().clear().apply()
}

enum class SaleType { QUOTE, INVOICE }

/** One line on a customer quotation / invoice. [rate] is per unit, as typed (see SaleDoc.inclusive). */
data class SaleLine(
    var name: String,
    var hsn: String,
    var unit: String,
    var qty: Double,
    var rate: Double,
    var gst: Double,
    val productId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("hsn", hsn).put("unit", unit)
        .put("qty", qty).put("rate", rate).put("gst", gst).put("productId", productId ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = SaleLine(
            o.optString("name"), o.optString("hsn"), o.optString("unit", "Nos"),
            o.optDouble("qty", 1.0), o.optDouble("rate", 0.0), o.optDouble("gst", 0.0),
            if (o.isNull("productId")) null else o.optString("productId"),
        )
    }
}

/** The customer (recipient). [stateCode] = place of supply, e.g. "10" for Bihar. */
data class Party(
    var name: String = "",
    var mobile: String = "",
    var address: String = "",
    var stateCode: String = "10",
    var gstin: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("mobile", mobile)
        .put("address", address).put("stateCode", stateCode).put("gstin", gstin)

    companion object {
        fun fromJson(o: JSONObject?) = Party(
            o?.optString("name") ?: "", o?.optString("mobile") ?: "", o?.optString("address") ?: "",
            o?.optString("stateCode", "10") ?: "10", o?.optString("gstin") ?: "",
        )
    }
}

class SaleDoc(
    val id: String = UUID.randomUUID().toString(),
    val type: SaleType,
    var number: String,
    var date: Long = System.currentTimeMillis(),
    var customer: Party = Party(),
    var shipTo: String = "",
    var inclusive: Boolean = false,
    val lines: MutableList<SaleLine> = mutableListOf(),
    var notes: String = "",
    var validDays: Int = 15,
    var fromQuote: String = "",
    var driveId: String = "",
    var driveSynced: Boolean = false,
    var total: Double = 0.0,
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("number", number).put("date", date)
        .put("customer", customer.toJson()).put("shipTo", shipTo).put("inclusive", inclusive)
        .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
        .put("notes", notes).put("validDays", validDays).put("fromQuote", fromQuote)
        .put("driveId", driveId).put("driveSynced", driveSynced).put("total", total).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): SaleDoc {
            val arr = o.optJSONArray("lines") ?: JSONArray()
            return SaleDoc(
                id = o.optString("id"),
                type = runCatching { SaleType.valueOf(o.optString("type")) }.getOrDefault(SaleType.QUOTE),
                number = o.optString("number"),
                date = o.optLong("date"),
                customer = Party.fromJson(o.optJSONObject("customer")),
                shipTo = o.optString("shipTo"),
                inclusive = o.optBoolean("inclusive"),
                lines = (0 until arr.length()).map { SaleLine.fromJson(arr.getJSONObject(it)) }.toMutableList(),
                notes = o.optString("notes"),
                validDays = o.optInt("validDays", 15),
                fromQuote = o.optString("fromQuote"),
                driveId = o.optString("driveId"),
                driveSynced = o.optBoolean("driveSynced"),
                total = o.optDouble("total", 0.0),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}

/** One computed row: taxable value and tax split. */
class SaleRow(val line: SaleLine, val unitTaxable: Double, val taxable: Double, val gstRate: Double,
              val cgst: Double, val sgst: Double, val igst: Double) {
    val tax get() = cgst + sgst + igst
    val amount get() = round2(taxable + tax)
}

/** Rate-wise tax summary row. */
class TaxSlab(val rate: Double, val taxable: Double, val cgst: Double, val sgst: Double, val igst: Double)

class SaleTotals(
    val rows: List<SaleRow>,
    val intraState: Boolean,
    val composition: Boolean,
    val taxable: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double,
    val slabs: List<TaxSlab>,
    val roundOff: Double,
    val grand: Double,
)

/**
 * GST working for a customer document.
 * Intra-state (supplier state = place of supply): CGST + SGST, half each.
 * Inter-state: IGST. Composition dealers charge no GST (Bill of Supply).
 * With "rates include GST" the taxable value is backed out of the typed rate.
 */
object SaleCalc {
    fun compute(doc: SaleDoc, supplierState: String, composition: Boolean): SaleTotals {
        val intra = supplierState.isBlank() || supplierState == doc.customer.stateCode
        val rows = doc.lines.map { l ->
            val g = if (composition) 0.0 else l.gst
            val unitTaxable = if (doc.inclusive && g > 0) l.rate / (1 + g / 100.0) else l.rate
            val taxable = round2(unitTaxable * l.qty)
            val tax = round2(taxable * g / 100.0)
            val cgst = if (intra) round2(tax / 2) else 0.0
            val sgst = if (intra) round2(tax - cgst) else 0.0
            val igst = if (intra) 0.0 else tax
            SaleRow(l, unitTaxable, taxable, g, cgst, sgst, igst)
        }
        val slabs = rows.groupBy { it.gstRate }.toSortedMap().map { (r, list) ->
            TaxSlab(r, round2(list.sumOf { it.taxable }), round2(list.sumOf { it.cgst }),
                round2(list.sumOf { it.sgst }), round2(list.sumOf { it.igst }))
        }
        val taxable = round2(rows.sumOf { it.taxable })
        val cgst = round2(rows.sumOf { it.cgst })
        val sgst = round2(rows.sumOf { it.sgst })
        val igst = round2(rows.sumOf { it.igst })
        val exact = taxable + cgst + sgst + igst
        val grand = round(exact)
        return SaleTotals(rows, intra, composition, taxable, cgst, sgst, igst, slabs, round2(grand - exact), grand)
    }
}

/** Default HSN / SAC by AVIP product category (editable on every line). */
object Hsn {
    fun forIcon(icon: String): String = when (icon) {
        "panel", "kit" -> "8541"
        "inverter", "hybrid", "offgrid" -> "8504"
        "battery" -> "8507"
        "structure" -> "7308"
        "box" -> "8537"
        "pipe" -> "3917"
        "hardware" -> "7318"
        "earthing" -> "8535"
        "cable" -> "8544"
        "service" -> "9954"
        else -> ""
    }
    const val INSTALLATION_SAC = "9954"
}

/** Customer quotations and invoices saved on the phone (JSON + PDF). */
object SalesStore {
    private fun dir(c: Context) = File(c.filesDir, "sales").apply { mkdirs() }
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun jsonFile(c: Context, doc: SaleDoc) = File(dir(c), doc.id + ".json")
    fun pdfFile(c: Context, doc: SaleDoc) = File(dir(c), "${doc.type.name.lowercase()}_${safe(doc.number)}.pdf")

    fun all(c: Context, type: SaleType? = null): List<SaleDoc> =
        dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull {
            runCatching { SaleDoc.fromJson(JSONObject(it.readText())) }.getOrNull()
        }.filter { type == null || it.type == type }.sortedWith(compareByDescending<SaleDoc> { it.date }.thenByDescending { it.updatedAt })

    fun byId(c: Context, id: String?): SaleDoc? =
        id?.let { File(dir(c), "$it.json") }?.takeIf { it.exists() }
            ?.let { runCatching { SaleDoc.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun count(c: Context, type: SaleType) = dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty().count {
        it.readText().contains("\"type\":\"${type.name}\"")
    }

    fun pendingDrive(c: Context) = all(c).count { !it.driveSynced }

    /** Writes the JSON (and the PDF when given) and marks it for Drive upload. */
    fun save(c: Context, doc: SaleDoc) {
        doc.updatedAt = System.currentTimeMillis()
        jsonFile(c, doc).writeText(doc.toJson().toString())
        bumpCounter(c, doc)
    }

    fun delete(c: Context, doc: SaleDoc) {
        jsonFile(c, doc).delete()
        pdfFile(c, doc).delete()
    }

    fun deleteAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }

    /** Indian financial year label for a date, e.g. "26-27" for Apr 2026 – Mar 2027. */
    fun fy(time: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = time }
        val y = cal.get(Calendar.YEAR)
        val start = if (cal.get(Calendar.MONTH) >= Calendar.APRIL) y else y - 1
        return "%02d-%02d".format(start % 100, (start + 1) % 100)
    }

    private fun prefix(c: Context, type: SaleType) =
        if (type == SaleType.INVOICE) Business.invoicePrefix(c) else Business.quotePrefix(c)

    /** Next number in the series, e.g. INV/26-27/007 (max 16 characters as GST rules require). */
    fun nextNumber(c: Context, type: SaleType, date: Long = System.currentTimeMillis()): String {
        val pre = prefix(c, type)
        val fy = fy(date)
        val head = "$pre/$fy/"
        val used = all(c, type).mapNotNull { d ->
            if (d.number.startsWith(head)) d.number.removePrefix(head).toIntOrNull() else null
        }.maxOrNull() ?: 0
        val counter = c.getSharedPreferences("sale_numbers", Context.MODE_PRIVATE).getInt("${type.name}_$head", 0)
        return head + (maxOf(used, counter) + 1).toString().padStart(3, '0')
    }

    private fun bumpCounter(c: Context, doc: SaleDoc) {
        val head = doc.number.substringBeforeLast('/', "") + "/"
        val n = doc.number.substringAfterLast('/').toIntOrNull() ?: return
        val p = c.getSharedPreferences("sale_numbers", Context.MODE_PRIVATE)
        val key = "${doc.type.name}_$head"
        if (n > p.getInt(key, 0)) p.edit().putInt(key, n).apply()
    }

    /** Problem with a typed document number, or null if fine. */
    fun numberProblem(c: Context, doc: SaleDoc): String? {
        val n = doc.number.trim()
        if (n.isEmpty()) return tr("Enter a number", "नंबर लिखें")
        if (n.length > 16) return tr("Max 16 characters (GST rule)", "अधिकतम 16 अक्षर (GST नियम)")
        if (!Regex("[A-Za-z0-9/-]+").matches(n)) return tr("Use only letters, numbers, / and -", "केवल अक्षर, अंक, / और - का उपयोग करें")
        if (all(c, doc.type).any { it.id != doc.id && it.number.equals(n, true) && fy(it.date) == fy(doc.date) })
            return tr("This number is already used this financial year", "यह नंबर इस वित्तीय वर्ष में पहले से इस्तेमाल हुआ है")
        return null
    }
}

fun stateName(code: String): String = Gstin.STATES[code] ?: code

