package com.avip.estimatemaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws an estimate as an A4 PDF.
 * AVIP quotation: AVIP letterhead, vendor as "Quotation to", bank details and terms.
 * Customer estimate: vendor's own letterhead/logo, customer as "Estimate for",
 * vendor margin built into rates, optional PM Surya Ghar subsidy.
 */
class PdfMaker(private val context: Context) {

    private val pageW = 595
    private val pageH = 842
    private val margin = 36f
    private val right get() = pageW - margin

    private val brand = 0xFF0E6B3A.toInt()
    private val brandLight = 0xFFE8F3EC.toInt()
    private val sun = 0xFFF7A21B.toInt()
    private val ink = 0xFF17211B.toInt()
    private val muted = 0xFF5F6B64.toInt()
    private val rule = 0xFFD5DDD8.toInt()

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            textAlign = align
        }

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0
    private lateinit var footerText: String

    fun make(est: EstimateDoc, out: File) {
        val s = Catalog.settings
        val isAvip = est.type == EstimateType.AVIP_QUOTE
        val totals = Calc.compute(est.lines, if (isAvip) 0.0 else est.marginPct)
        val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(est.createdAt))
        footerText = if (isAvip) "${s["company_name"].ifBlank { "AVIP Business Solutions LLP" }}  ·  ${s["phone"]}  ·  ${s["website"]}"
        else Vendor.company(context)

        doc = PdfDocument()
        newPage()

        // ---------- Letterhead ----------
        val logo: Bitmap? = if (isAvip) drawableBitmap(R.drawable.ic_logo, 160) else Vendor.logo(context)
        var textLeft = margin
        val headTop = y
        if (logo != null) {
            val h = 58f
            val w = (logo.width.toFloat() / logo.height * h).coerceAtMost(130f)
            canvas.drawBitmap(logo, null, RectF(margin, headTop, margin + w, headTop + h), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft = margin + w + 12f
        }
        val titleW = 170f
        val headWidth = right - titleW - textLeft
        var hy = headTop + 16f
        if (isAvip) {
            hy = drawWrapped(s["company_name"].ifBlank { "AVIP Business Solutions LLP" }, textLeft, hy, headWidth, paint(16f, brand, true), 19f)
            if (s["tagline"].isNotBlank()) hy = drawWrapped(s["tagline"], textLeft, hy, headWidth, paint(8.5f, muted), 11f)
            hy = drawWrapped(s["address"], textLeft, hy, headWidth, paint(8.5f, ink), 11f)
            hy = drawWrapped(listOf(s["phone"], s["email"], s["website"]).filter { it.isNotBlank() }.joinToString("  ·  "), textLeft, hy, headWidth, paint(8.5f, ink), 11f)
            if (s["gstin"].isNotBlank()) hy = drawWrapped("GSTIN: ${s["gstin"]}", textLeft, hy, headWidth, paint(8.5f, ink, true), 11f)
        } else {
            hy = drawWrapped(Vendor.company(context), textLeft, hy, headWidth, paint(16f, brand, true), 19f)
            if (Vendor.address(context).isNotBlank()) hy = drawWrapped(Vendor.address(context), textLeft, hy, headWidth, paint(8.5f, ink), 11f)
            val contact = listOf(Vendor.contact(context), "Mob: " + Vendor.mobile(context), Vendor.city(context)).filter { it.isNotBlank() }
            hy = drawWrapped(contact.joinToString("  ·  "), textLeft, hy, headWidth, paint(8.5f, ink), 11f)
            if (Vendor.gst(context).isNotBlank()) hy = drawWrapped("GSTIN: ${Vendor.gst(context)}", textLeft, hy, headWidth, paint(8.5f, ink, true), 11f)
        }

        // Title block on the right
        val title = if (isAvip) "QUOTATION" else "ESTIMATE"
        val tb = RectF(right - titleW, headTop, right, headTop + 62f)
        canvas.drawRoundRect(tb, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand })
        canvas.drawText(title, tb.centerX(), tb.top + 21f, paint(15f, Color.WHITE, true, Paint.Align.CENTER))
        canvas.drawText("No: ${est.number}", tb.centerX(), tb.top + 37f, paint(8.5f, Color.WHITE, align = Paint.Align.CENTER))
        canvas.drawText("Date: $date", tb.centerX(), tb.top + 50f, paint(8.5f, Color.WHITE, align = Paint.Align.CENTER))
        if (isAvip) {
            val days = s.num("quote_validity_days", 7.0).toInt()
            val till = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(est.createdAt + days * 86_400_000L))
            canvas.drawText("Valid till: $till", tb.centerX(), tb.bottom + 12f, paint(8f, muted, align = Paint.Align.CENTER))
        }

        y = maxOf(hy, headTop + 76f) + 6f
        canvas.drawRect(margin, y, right, y + 3f, Paint().apply { color = sun })
        y += 14f

        // ---------- To / For ----------
        val toLines = mutableListOf<String>()
        val toTitle: String
        if (isAvip) {
            toTitle = "Quotation to"
            toLines += Vendor.company(context)
            toLines += listOf(Vendor.contact(context), "Mob: " + Vendor.mobile(context)).filter { it.isNotBlank() }.joinToString("  ·  ")
            val place = listOf(Vendor.address(context), Vendor.city(context)).filter { it.isNotBlank() }.joinToString(", ")
            if (place.isNotBlank()) toLines += place
            if (Vendor.gst(context).isNotBlank()) toLines += "GSTIN: ${Vendor.gst(context)}"
        } else {
            toTitle = "Estimate for"
            toLines += est.customerName.ifBlank { "Customer" }
            if (est.customerPhone.isNotBlank()) toLines += "Mob: ${est.customerPhone}"
            if (est.customerAddress.isNotBlank()) toLines += est.customerAddress
        }
        val boxTop = y
        val half = (right - margin - 12f) / 2f
        var ly = boxTop + 14f
        canvas.drawText(toTitle.uppercase(), margin + 10f, ly, paint(7.5f, muted, true))
        ly += 13f
        toLines.forEachIndexed { i, t ->
            ly = drawWrapped(t, margin + 10f, ly, half - 20f, paint(if (i == 0) 10.5f else 8.5f, ink, i == 0), if (i == 0) 13f else 11f)
        }
        // System summary on the right
        var ry = boxTop + 14f
        val rx = margin + half + 22f
        if (totals.kwp > 0) {
            canvas.drawText("SOLAR SYSTEM", rx, ry, paint(7.5f, muted, true)); ry += 16f
            canvas.drawText("${Fmt.num(totals.kwp)} kWp", rx, ry, paint(15f, brand, true)); ry += 14f
            val panels = est.lines.filter { it.wp > 0 }.sumOf { it.qty }
            canvas.drawText("${Fmt.qty(panels)} solar panels", rx, ry, paint(8.5f, ink)); ry += 11f
        } else {
            canvas.drawText("ITEMS", rx, ry, paint(7.5f, muted, true)); ry += 16f
            canvas.drawText("${est.lines.size}", rx, ry, paint(15f, brand, true)); ry += 14f
        }
        val boxBottom = maxOf(ly, ry) + 4f
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; style = Paint.Style.STROKE; strokeWidth = 0.8f }
        canvas.drawRoundRect(RectF(margin, boxTop, margin + half, boxBottom), 6f, 6f, boxPaint)
        canvas.drawRoundRect(RectF(margin + half + 12f, boxTop, right, boxBottom), 6f, 6f, boxPaint)
        y = boxBottom + 14f

        // ---------- Items table ----------
        val cols = if (est.showRates || isAvip) listOf(
            Col("#", 22f, Paint.Align.CENTER), Col("Item", 0f, Paint.Align.LEFT),
            Col("Qty", 46f, Paint.Align.RIGHT), Col("Rate", 70f, Paint.Align.RIGHT),
            Col("GST", 34f, Paint.Align.RIGHT), Col("Amount", 80f, Paint.Align.RIGHT),
        ) else listOf(
            Col("#", 22f, Paint.Align.CENTER), Col("Item", 0f, Paint.Align.LEFT),
            Col("Qty", 70f, Paint.Align.RIGHT), Col("Unit", 70f, Paint.Align.RIGHT),
        )
        val fixed = cols.sumOf { it.w.toDouble() }.toFloat()
        val itemW = right - margin - fixed
        val widths = cols.map { if (it.w == 0f) itemW else it.w }
        val showRates = cols.size == 6

        fun header() {
            canvas.drawRect(margin, y, right, y + 20f, Paint().apply { color = brand })
            var x = margin
            cols.forEachIndexed { i, c ->
                val tx = when (c.align) { Paint.Align.LEFT -> x + 6f; Paint.Align.RIGHT -> x + widths[i] - 6f; else -> x + widths[i] / 2 }
                canvas.drawText(c.title, tx, y + 13.5f, paint(8f, Color.WHITE, true, c.align))
                x += widths[i]
            }
            y += 20f
        }
        header()

        val namePaint = paint(8.8f, ink, true)
        val subPaint = paint(7.5f, muted)
        totals.rows.forEachIndexed { idx, r ->
            val l = r.line
            val sub = listOf(l.spec, if (showRates) "per ${l.unit.lowercase()}" else "").filter { it.isNotBlank() }.joinToString(" · ")
            val nameLines = wrap(l.name, itemW - 12f, namePaint)
            val subLines = if (sub.isBlank()) emptyList() else wrap(sub, itemW - 12f, subPaint)
            val rowH = 8f + nameLines.size * 11f + subLines.size * 9.5f + 4f
            if (y + rowH > pageH - 70f) { newPage(); header() }
            if (idx % 2 == 1) canvas.drawRect(margin, y, right, y + rowH, Paint().apply { color = 0xFFF6F9F7.toInt() })
            var x = margin
            val base = y + 15f
            cols.forEachIndexed { i, c ->
                val w = widths[i]
                val tx = when (c.align) { Paint.Align.LEFT -> x + 6f; Paint.Align.RIGHT -> x + w - 6f; else -> x + w / 2 }
                when (c.title) {
                    "#" -> canvas.drawText("${idx + 1}", tx, base, paint(8.5f, muted, align = c.align))
                    "Item" -> {
                        var iy = base
                        nameLines.forEach { canvas.drawText(it, tx, iy, namePaint); iy += 11f }
                        subLines.forEach { canvas.drawText(it, tx, iy - 1f, subPaint); iy += 9.5f }
                    }
                    "Qty" -> canvas.drawText(Fmt.qty(l.qty), tx, base, paint(8.5f, ink, align = c.align))
                    "Unit" -> canvas.drawText(l.unit, tx, base, paint(8.5f, ink, align = c.align))
                    "Rate" -> canvas.drawText(Fmt.inr(r.rate, true), tx, base, paint(8.5f, ink, align = c.align))
                    "GST" -> canvas.drawText("${Fmt.num(l.gst)}%", tx, base, paint(8.5f, ink, align = c.align))
                    "Amount" -> canvas.drawText(Fmt.inr(r.amount, true), tx, base, paint(8.8f, ink, true, c.align))
                }
                x += w
            }
            y += rowH
            canvas.drawLine(margin, y, right, y, Paint().apply { color = rule; strokeWidth = 0.5f })
        }

        // ---------- Totals ----------
        val totalRows = mutableListOf<Triple<String, String, Boolean>>()
        totalRows += Triple("Sub Total (before GST)", Fmt.inr(totals.subTotal, true), false)
        totals.gstByRate.forEach { (rate, amt) -> totalRows += Triple("GST @ ${Fmt.num(rate)}%", Fmt.inr(amt, true), false) }
        if (totals.roundOff != 0.0) totalRows += Triple("Round off", Fmt.inr(totals.roundOff, true), false)
        val subsidy = if (!isAvip && est.showSubsidy) est.subsidy else 0.0
        val needed = 30f + totalRows.size * 15f + 26f + (if (subsidy > 0) 50f else 0f) + 20f
        if (y + needed > pageH - 70f) newPage()
        y += 10f
        val tx1 = right - 230f
        totalRows.forEach { (label, value, _) ->
            canvas.drawText(label, tx1, y + 10f, paint(9f, muted))
            canvas.drawText(value, right - 6f, y + 10f, paint(9f, ink, align = Paint.Align.RIGHT))
            y += 15f
        }
        val gt = RectF(tx1 - 8f, y + 2f, right, y + 26f)
        canvas.drawRoundRect(gt, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand })
        canvas.drawText("Grand Total (incl. GST)", tx1, gt.top + 16f, paint(10f, Color.WHITE, true))
        canvas.drawText(Fmt.inr(totals.grandTotal), right - 6f, gt.top + 16.5f, paint(12f, Color.WHITE, true, Paint.Align.RIGHT))
        y = gt.bottom + 4f
        // amount in words on the left side
        drawWrapped(Fmt.words(totals.grandTotal), margin, gt.top + 12f, tx1 - margin - 20f, paint(8f, muted), 10f)
        y += 6f

        if (subsidy > 0) {
            val sb = RectF(margin, y, right, y + 44f)
            canvas.drawRoundRect(sb, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF4E0.toInt() })
            canvas.drawText("Less: Expected PM Surya Ghar central subsidy", margin + 10f, y + 16f, paint(9f, ink, true))
            canvas.drawText("- ${Fmt.inr(subsidy)}", right - 10f, y + 16f, paint(9.5f, ink, true, Paint.Align.RIGHT))
            canvas.drawText("Net effective cost to customer", margin + 10f, y + 34f, paint(9.5f, brand, true))
            canvas.drawText(Fmt.inr(totals.grandTotal - subsidy), right - 10f, y + 34f, paint(11f, brand, true, Paint.Align.RIGHT))
            y += 50f
            y = drawWrapped("Subsidy is credited by the government to the customer's bank account after installation and inspection; the full amount above is payable to the vendor.", margin, y + 4f, right - margin, paint(7.5f, muted), 9.5f)
        }

        // ---------- Footer blocks ----------
        y += 10f
        if (isAvip) {
            val bank = listOf(
                "A/c Name: ${s["bank_account_name"]}",
                "A/c No: ${s["bank_account_no"]}   ·   IFSC: ${s["bank_ifsc"]}",
                "Bank: ${s["bank_branch"]}",
            )
            section("Bank details", bank)
            if (s["address"].isNotBlank()) section("Pickup / Godown", listOfNotNull(s["address"], s["map_link"].takeIf { it.isNotBlank() }))
            val terms = s.terms()
            if (terms.isNotEmpty()) section("Terms & conditions", terms.mapIndexed { i, t -> "${i + 1}. $t" })
            if (s["price_note"].isNotBlank()) section("Note", listOf(s["price_note"] + (if (s["price_last_updated"].isNotBlank()) " Price list dated ${s["price_last_updated"]}." else "")))
        } else {
            val notes = est.notes.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (notes.isNotEmpty()) section("Terms & notes", notes)
        }

        // signature
        if (y + 50f > pageH - 60f) newPage()
        y += 16f
        val signer = if (isAvip) "For ${s["company_name"].ifBlank { "AVIP Business Solutions LLP" }}" else "For ${Vendor.company(context)}"
        canvas.drawText(signer, right, y, paint(9f, ink, true, Paint.Align.RIGHT))
        canvas.drawText("Authorised Signatory", right, y + 34f, paint(8f, muted, align = Paint.Align.RIGHT))

        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private data class Col(val title: String, val w: Float, val align: Paint.Align)

    private fun section(title: String, lines: List<String>) {
        val p = paint(8.2f, ink)
        val needed = 18f + lines.sumOf { wrap(it, right - margin, p).size.toDouble() * 10.5 }.toFloat()
        if (y + needed > pageH - 60f) newPage()
        canvas.drawText(title.uppercase(), margin, y + 10f, paint(7.8f, brand, true))
        y += 22f
        lines.forEach { y = drawWrapped(it, margin, y, right - margin, p, 10.5f) }
        y += 6f
    }

    private fun newPage() {
        finishPage()
        pageNo++
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
        page = doc.startPage(info)
        canvas = page!!.canvas
        canvas.drawRect(0f, 0f, pageW.toFloat(), 6f, Paint().apply { color = brand })
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val c = p.canvas
        c.drawLine(margin, pageH - 34f, right, pageH - 34f, Paint().apply { color = rule; strokeWidth = 0.6f })
        c.drawText(footerText, margin, pageH - 22f, paint(7.5f, muted))
        c.drawText("Page $pageNo", right, pageH - 22f, paint(7.5f, muted, align = Paint.Align.RIGHT))
        doc.finishPage(p)
        page = null
    }

    /** Draws text wrapped to [width]; returns the y below the last line. */
    private fun drawWrapped(text: String, x: Float, startY: Float, width: Float, p: Paint, lineH: Float): Float {
        if (text.isBlank()) return startY
        var yy = startY
        wrap(text, width, p).forEach { line ->
            if (yy > pageH - 50f) { newPage(); yy = y + lineH }
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

    private fun drawableBitmap(res: Int, size: Int): Bitmap? {
        val d = ContextCompat.getDrawable(context, res) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }
}
