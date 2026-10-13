package com.avip.estimatemaker

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** One printed row of an estimate, after the vendor's margin. */
data class Row(
    val line: Line,
    val rate: Double,
    val amount: Double,
    val gstAmount: Double,
)

class Totals(
    val rows: List<Row>,
    val subTotal: Double,
    val gstByRate: Map<Double, Double>,
    val gstTotal: Double,
    val roundOff: Double,
    val grandTotal: Double,
    val kwp: Double,
)

object Calc {

    /** Margin is added to catalogue items only; the vendor's own lines are taken as entered. */
    fun compute(lines: List<Line>, marginPct: Double): Totals {
        val rows = lines.map { l ->
            val rate = if (l.custom) l.price else round2(l.price * (1 + marginPct / 100.0))
            val amount = round2(rate * l.qty)
            Row(l, rate, amount, round2(amount * l.gst / 100.0))
        }
        val sub = round2(rows.sumOf { it.amount })
        val byRate = rows.filter { it.gstAmount > 0 }
            .groupBy { it.line.gst }
            .mapValues { (_, r) -> round2(r.sumOf { it.gstAmount }) }
            .toSortedMap()
        val gst = round2(byRate.values.sum())
        val exact = sub + gst
        val grand = round(exact)
        return Totals(rows, sub, byRate, gst, round2(grand - exact), grand, kwp(lines))
    }

    fun kwp(lines: List<Line>): Double = round2(lines.sumOf { it.wp * it.qty } / 1000.0)

    /** PM Surya Ghar central subsidy, using the slabs set in the sheet. */
    fun subsidy(kw: Double, s: AppSettings): Double {
        if (kw <= 0) return 0.0
        val first = s.num("subsidy_per_kw_first_2", 30000.0)
        val third = s.num("subsidy_per_kw_3rd", 18000.0)
        val max = s.num("subsidy_max", 78000.0)
        val v = if (kw <= 2) kw * first else 2 * first + minOf(kw - 2, 1.0) * third
        return round(minOf(v, max))
    }
}

object Fmt {
    private val sym = DecimalFormatSymbols(Locale.ENGLISH)

    /** Indian grouping: 1,23,45,678 */
    fun inr(v: Double, decimals: Boolean = false): String {
        val neg = v < 0
        val a = abs(v)
        val whole = floor(a).toLong()
        val paise = round((a - whole) * 100).toLong()
        val (w, p) = if (paise == 100L) whole + 1 to 0L else whole to paise
        val s = w.toString()
        val grouped = if (s.length <= 3) s else {
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            "$rest,$last3"
        }
        val dec = if (decimals || p != 0L) "." + p.toString().padStart(2, '0') else ""
        return (if (neg) "-" else "") + "₹" + grouped + dec
    }

    fun qty(v: Double): String =
        if (v == floor(v)) v.toLong().toString() else DecimalFormat("0.##", sym).format(v)

    fun num(v: Double): String = DecimalFormat("0.##", sym).format(v)

    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
        "Eighteen", "Nineteen"
    )
    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    private fun below100(n: Int): String =
        if (n < 20) ones[n] else (tens[n / 10] + if (n % 10 != 0) " " + ones[n % 10] else "")

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return listOfNotNull(
            if (h > 0) ones[h] + " Hundred" else null,
            if (r > 0) below100(r) else null
        ).joinToString(" ")
    }

    /** "Rupees One Lakh Twenty Three Thousand Only" */
    fun words(amount: Double): String {
        var n = round(abs(amount)).toLong()
        if (n == 0L) return "Rupees Zero Only"
        val parts = mutableListOf<String>()
        val crore = n / 10_000_000; n %= 10_000_000
        val lakh = n / 100_000; n %= 100_000
        val thousand = n / 1000; n %= 1000
        if (crore > 0) parts += (if (crore >= 1000) crore.toString() else below1000(crore.toInt())) + " Crore"
        if (lakh > 0) parts += below100(lakh.toInt()) + " Lakh"
        if (thousand > 0) parts += below100(thousand.toInt()) + " Thousand"
        if (n > 0) parts += below1000(n.toInt())
        return "Rupees " + parts.joinToString(" ") + " Only"
    }
}
