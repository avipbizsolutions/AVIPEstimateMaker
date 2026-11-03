package com.avip.estimatemaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A4 PDF of the Surya Saathi's own Quotation, Tax Invoice or Bill of Supply.
 * The tax invoice carries the particulars of GST Rule 46: supplier name, address and
 * GSTIN; serial number (≤16 chars, unique per financial year) and date; recipient name,
 * address, GSTIN (if registered) and state with code; place of supply; HSN/SAC,
 * description, quantity, unit, taxable value, GST rate and CGST/SGST or IGST amounts;
 * total in words; reverse-charge statement; and the authorised signatory.
 */
class SalePdf(private val c: Context) {

    private val pageW = 595
    private val pageH = 842
    private val margin = 30f
    private val right get() = pageW - margin

    private val ink = 0xFF17211B.toInt()
    private val muted = 0xFF5F6B64.toInt()
    private val rule = 0xFFB9C4BE.toInt()
    private val accent = 0xFF1F3B57.toInt()
    private val shade = 0xFFF1F4F6.toInt()

    private lateinit var pdf: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    private val linePaint = Paint().apply { color = rule; strokeWidth = 0.7f; style = Paint.Style.STROKE }

    fun make(doc: SaleDoc, out: File) {
        val composition = Business.composition(c)
        val t = SaleCalc.compute(doc, Business.stateCode(c), composition)
        val invoice = doc.type == SaleType.INVOICE
        val title = when {
            !invoice -> "QUOTATION"
            composition -> "BILL OF SUPPLY"
            else -> "TAX INVOICE"
        }
        val df = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

        pdf = PdfDocument()
        newPage()

        // ---------- Title ----------
        canvas.drawText(title, pageW / 2f, y + 14f, paint(15f, accent, true, Paint.Align.CENTER))
        if (invoice) canvas.drawText("ORIGINAL FOR RECIPIENT", right, y + 12f, paint(7f, muted, true, Paint.Align.RIGHT))
        y += 24f

        // ---------- Supplier (left) + document details (right) ----------
        val top = y
        val splitX = margin + (right - margin) * 0.58f
        var ly = top + 15f
        ly = wrapText(Business.legalName(c), margin + 8f, ly, splitX - margin - 16f, paint(12.5f, ink, true), 15f)
        val addr = listOf(Business.address(c), Business.pincode(c).takeIf { it.isNotBlank() }?.let { "PIN $it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
        ly = wrapText(addr, margin + 8f, ly, splitX - margin - 16f, paint(8f), 10.5f)
        ly = wrapText(listOf("Ph: " + Business.phone(c), Business.email(c)).filter { it.isNotBlank() && it != "Ph: " }.joinToString("  ·  "),
            margin + 8f, ly, splitX - margin - 16f, paint(8f), 10.5f)
        ly = wrapText("GSTIN: ${Business.gstin(c)}", margin + 8f, ly, splitX - margin - 16f, paint(8.5f, ink, true), 11f)
        ly = wrapText("State: ${stateName(Business.stateCode(c))} (Code ${Business.stateCode(c)})", margin + 8f, ly, splitX - margin - 16f, paint(8f), 10.5f)

        val meta = mutableListOf<Pair<String, String>>()
        meta += (if (invoice) "Invoice No." else "Quotation No.") to doc.number
        meta += (if (invoice) "Invoice Date" else "Date") to df.format(Date(doc.date))
        if (!invoice) meta += "Valid till" to df.format(Date(doc.date + doc.validDays * 86_400_000L))
        meta += "Place of Supply" to "${stateName(doc.customer.stateCode)} (${doc.customer.stateCode})"
        if (invoice) meta += "Reverse Charge" to "No"
        if (invoice && doc.fromQuote.isNotBlank()) meta += "Against Quotation" to doc.fromQuote
        var ry = top + 15f
        meta.forEach { (k, v) ->
            canvas.drawText(k, splitX + 8f, ry, paint(8f, muted))
            canvas.drawText(v, right - 8f, ry, paint(8.5f, ink, true, Paint.Align.RIGHT))
            ry += 13f
        }
        val headBottom = maxOf(ly, ry) + 2f
        canvas.drawRect(margin, top, right, headBottom, linePaint)
        canvas.drawLine(splitX, top, splitX, headBottom, linePaint)
        y = headBottom

        // ---------- Bill to / Ship to ----------
        val cust = doc.customer
        val half = margin + (right - margin) / 2f
        val boxTop = y
        var by = boxTop + 13f
        canvas.drawText("BILL TO", margin + 8f, by, paint(7.2f, muted, true)); by += 12f
        by = wrapText(cust.name, margin + 8f, by, half - margin - 16f, paint(10f, ink, true), 12.5f)
        by = wrapText(cust.address, margin + 8f, by, half - margin - 16f, paint(8f), 10.5f)
        if (cust.mobile.isNotBlank()) by = wrapText("Mob: ${cust.mobile}", margin + 8f, by, half - margin - 16f, paint(8f), 10.5f)
        by = wrapText("GSTIN: " + cust.gstin.ifBlank { "Unregistered" }, margin + 8f, by, half - margin - 16f, paint(8f, ink, cust.gstin.isNotBlank()), 10.5f)
        by = wrapText("State: ${stateName(cust.stateCode)} (Code ${cust.stateCode})", margin + 8f, by, half - margin - 16f, paint(8f), 10.5f)

        var sy = boxTop + 13f
        canvas.drawText(if (invoice) "SHIP TO / DELIVERY ADDRESS" else "SITE / DELIVERY ADDRESS", half + 8f, sy, paint(7.2f, muted, true)); sy += 12f
        sy = wrapText(doc.shipTo.ifBlank { "Same as billing address" }, half + 8f, sy, right - half - 16f,
            paint(8f, if (doc.shipTo.isBlank()) muted else ink), 10.5f)
        val boxBottom = maxOf(by, sy) + 2f
        canvas.drawRect(margin, boxTop, right, boxBottom, linePaint)
        canvas.drawLine(half, boxTop, half, boxBottom, linePaint)
        y = boxBottom + 10f

        // ---------- Items ----------
        val showTax = !composition
        val cols = mutableListOf(
            Col("#", 18f, Paint.Align.CENTER), Col("Description", 0f, Paint.Align.LEFT),
            Col("HSN/SAC", 46f, Paint.Align.CENTER), Col("Qty", 36f, Paint.Align.RIGHT),
            Col("Unit", 30f, Paint.Align.CENTER), Col("Rate", 56f, Paint.Align.RIGHT),
        )
        if (showTax) {
            cols += Col("Taxable", 62f, Paint.Align.RIGHT)
            cols += Col("GST", 30f, Paint.Align.RIGHT)
            cols += Col("Tax", 52f, Paint.Align.RIGHT)
        }
        cols += Col("Amount", 64f, Paint.Align.RIGHT)
        val descW = right - margin - cols.sumOf { it.w.toDouble() }.toFloat()
        val widths = cols.map { if (it.w == 0f) descW else it.w }

        fun tx(i: Int, x: Float, a: Paint.Align) = when (a) {
            Paint.Align.LEFT -> x + 4f; Paint.Align.RIGHT -> x + widths[i] - 4f; else -> x + widths[i] / 2
        }
        fun header() {
            canvas.drawRect(margin, y, right, y + 18f, Paint().apply { color = accent })
            var x = margin
            cols.forEachIndexed { i, col ->
                canvas.drawText(col.title, tx(i, x, col.align), y + 12f, paint(7.3f, Color.WHITE, true, col.align))
                x += widths[i]
            }
            y += 18f
        }
        header()
        val namePaint = paint(8f, ink, true)
        t.rows.forEachIndexed { idx, r ->
            val l = r.line
            val nameLines = wrap(l.name, descW - 8f, namePaint)
            val h = 6f + nameLines.size * 10f + 4f
            if (y + h > pageH - 60f) { newPage(); header() }
            var x = margin
            val base = y + 12f
            cols.forEachIndexed { i, col ->
                val p = paint(7.8f, ink, align = col.align)
                val tX = tx(i, x, col.align)
                when (col.title) {
                    "#" -> canvas.drawText("${idx + 1}", tX, base, p)
                    "Description" -> { var iy = base; nameLines.forEach { canvas.drawText(it, tX, iy, namePaint); iy += 10f } }
                    "HSN/SAC" -> canvas.drawText(l.hsn, tX, base, p)
                    "Qty" -> canvas.drawText(Fmt.qty(l.qty), tX, base, p)
                    "Unit" -> canvas.drawText(l.unit.take(6), tX, base, p)
                    "Rate" -> canvas.drawText(money(r.unitTaxable), tX, base, p)
                    "Taxable" -> canvas.drawText(money(r.taxable), tX, base, p)
                    "GST" -> canvas.drawText("${Fmt.num(r.gstRate)}%", tX, base, p)
                    "Tax" -> canvas.drawText(money(r.tax), tX, base, p)
                    "Amount" -> canvas.drawText(money(if (showTax) r.amount else r.taxable), tX, base, paint(7.8f, ink, true, col.align))
                }
                x += widths[i]
            }
            y += h
            canvas.drawLine(margin, y, right, y, Paint().apply { color = 0xFFDDE3E0.toInt(); strokeWidth = 0.5f })
        }
        // column totals
        if (y + 16f > pageH - 60f) { newPage(); header() }
        canvas.drawRect(margin, y, right, y + 15f, Paint().apply { color = shade })
        run {
            var x = margin
            cols.forEachIndexed { i, col ->
                val v = when (col.title) {
                    "Description" -> "Total"
                    "Qty" -> Fmt.qty(t.rows.sumOf { it.line.qty })
                    "Taxable" -> money(t.taxable)
                    "Tax" -> money(t.cgst + t.sgst + t.igst)
                    "Amount" -> money(t.taxable + t.cgst + t.sgst + t.igst)
                    else -> ""
                }
                if (v.isNotBlank()) canvas.drawText(v, tx(i, x, col.align), y + 10.5f, paint(7.8f, ink, true, col.align))
                x += widths[i]
            }
        }
        y += 15f
        y += 10f

        // ---------- Tax summary (left) + totals (right) ----------
        val needed = 30f + (t.slabs.size + 4) * 13f + 40f
        if (y + needed > pageH - 60f) newPage()
        val sumTop = y
        val sumRight = margin + (right - margin) * 0.56f
        if (showTax) {
            val sCols = if (t.intraState) listOf("GST Rate", "Taxable", "CGST", "SGST", "Total Tax") else listOf("GST Rate", "Taxable", "IGST", "Total Tax")
            val sw = (sumRight - margin) / sCols.size
            canvas.drawRect(margin, y, sumRight, y + 14f, Paint().apply { color = shade })
            sCols.forEachIndexed { i, h -> canvas.drawText(h, margin + sw * (i + 1) - 4f, y + 10f, paint(7f, muted, true, Paint.Align.RIGHT)) }
            y += 14f
            t.slabs.forEach { s ->
                val vals = if (t.intraState) listOf("${Fmt.num(s.rate)}%", money(s.taxable),
                    "${money(s.cgst)} @${Fmt.num(s.rate / 2)}%", "${money(s.sgst)} @${Fmt.num(s.rate / 2)}%", money(s.cgst + s.sgst))
                else listOf("${Fmt.num(s.rate)}%", money(s.taxable), money(s.igst), money(s.igst))
                vals.forEachIndexed { i, v -> canvas.drawText(v, margin + sw * (i + 1) - 4f, y + 10f, paint(7f, ink, align = Paint.Align.RIGHT)) }
                y += 13f
            }
            canvas.drawRect(margin, sumTop, sumRight, y + 2f, linePaint)
        } else {
            y = wrapText("Composition taxable person, not eligible to collect tax on supplies.", margin, y + 10f, sumRight - margin, paint(8f, ink, true), 10.5f)
        }
        val leftBottom = y + 6f

        y = sumTop
        val totals = mutableListOf<Pair<String, String>>()
        if (showTax) {
            totals += "Taxable Value" to money(t.taxable)
            if (t.intraState) { totals += "CGST" to money(t.cgst); totals += "SGST" to money(t.sgst) }
            else totals += "IGST" to money(t.igst)
        } else totals += "Total Value" to money(t.taxable)
        if (t.roundOff != 0.0) totals += "Round Off" to money(t.roundOff)
        totals.forEach { (k, v) ->
            canvas.drawText(k, sumRight + 14f, y + 10f, paint(8.5f, muted))
            canvas.drawText(v, right - 6f, y + 10f, paint(8.5f, ink, align = Paint.Align.RIGHT))
            y += 14f
        }
        val gt = RectF(sumRight + 8f, y + 2f, right, y + 24f)
        canvas.drawRoundRect(gt, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        canvas.drawText(if (invoice) "Invoice Total" else "Quotation Total", gt.left + 6f, gt.top + 15f, paint(9.5f, Color.WHITE, true))
        canvas.drawText(Fmt.inr(t.grand, true), gt.right - 6f, gt.top + 15.5f, paint(11f, Color.WHITE, true, Paint.Align.RIGHT))
        y = maxOf(gt.bottom, leftBottom) + 8f
        y = wrapText("Amount in words: " + Fmt.words(t.grand), margin, y + 4f, right - margin, paint(8.5f, ink, true), 11f)
        if (showTax && invoice) y = wrapText("Tax amount in words: " + Fmt.words(t.cgst + t.sgst + t.igst), margin, y, right - margin, paint(8f, muted), 10.5f)
        y += 6f

        // ---------- Bank, terms, declaration, signature ----------
        val bank = mutableListOf<String>()
        if (Business.bankAccount(c).isNotBlank()) {
            bank += "A/c Name: ${Business.legalName(c)}"
            bank += "A/c No: ${Business.bankAccount(c)}   ·   IFSC: ${Business.bankIfsc(c)}"
            if (Business.bankName(c).isNotBlank()) bank += "Bank: ${Business.bankName(c)}"
        }
        if (Business.upi(c).isNotBlank()) bank += "UPI: ${Business.upi(c)}"
        if (bank.isNotEmpty()) section("Bank details", bank)
        val terms = doc.notes.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (terms.isNotEmpty()) section("Terms & conditions", terms.mapIndexed { i, s -> "${i + 1}. $s" })
        if (invoice) section("Declaration", listOf("We declare that this invoice shows the actual price of the goods / services described and that all particulars are true and correct."))

        if (y + 60f > pageH - 50f) newPage()
        y += 8f
        val sig = RectF(right - 200f, y, right, y + 56f)
        canvas.drawRect(sig, linePaint)
        canvas.drawText("For ${Business.legalName(c)}".take(48), sig.right - 6f, sig.top + 12f, paint(8f, ink, true, Paint.Align.RIGHT))
        canvas.drawText("Authorised Signatory", sig.right - 6f, sig.bottom - 6f, paint(7.5f, muted, align = Paint.Align.RIGHT))
        if (!invoice) canvas.drawText("Customer acceptance: ____________________", margin, sig.bottom - 6f, paint(8f, muted))
        y = sig.bottom + 6f

        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    private fun money(v: Double) = Fmt.inr(v, true).removePrefix("₹")

    private data class Col(val title: String, val w: Float, val align: Paint.Align)

    private fun section(title: String, lines: List<String>) {
        val p = paint(7.8f, ink)
        val needed = 16f + lines.sumOf { wrap(it, right - margin, p).size.toDouble() * 10.0 }.toFloat()
        if (y + needed > pageH - 50f) newPage()
        canvas.drawText(title.uppercase(), margin, y + 9f, paint(7.3f, accent, true))
        y += 20f
        lines.forEach { y = wrapText(it, margin, y, right - margin, p, 10f) }
        y += 4f
    }

    private fun newPage() {
        finishPage()
        pageNo++
        page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        canvas = page!!.canvas
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val cv = p.canvas
        cv.drawText("This is a computer-generated document.  ·  Made with Bihar Solar Circle", margin, pageH - 18f, paint(6.8f, muted))
        cv.drawText("Page $pageNo", right, pageH - 18f, paint(6.8f, muted, align = Paint.Align.RIGHT))
        pdf.finishPage(p)
        page = null
    }

    private fun wrapText(text: String, x: Float, startY: Float, width: Float, p: Paint, lineH: Float): Float {
        if (text.isBlank()) return startY
        var yy = startY
        wrap(text, width, p).forEach { line ->
            if (yy > pageH - 45f) { newPage(); yy = y + lineH }
            canvas.drawText(line, x, yy, p)
            yy += lineH
        }
        return yy
    }

    private fun wrap(text: String, width: Float, p: Paint): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var line = ""
            para.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= width) line = candidate
                else {
                    if (line.isNotEmpty()) out += line
                    var w = word
                    while (p.measureText(w) > width && w.length > 1) {
                        val n = p.breakText(w, true, width, null).coerceAtLeast(1)
                        out += w.substring(0, n); w = w.substring(n)
                    }
                    line = w
                }
            }
            out += line
        }
        return out
    }
}
