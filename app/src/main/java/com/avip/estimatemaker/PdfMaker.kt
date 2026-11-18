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
 * Draws an AVIP cost estimate as an A4 PDF: AVIP letterhead, the Surya Saathi as
 * "Cost estimate to", item table with GST, bank details, godown and terms.
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
        val totals = Calc.compute(est.lines)
        val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(est.createdAt))
        footerText = "${s["company_name"].ifBlank { "AVIP Business Solutions LLP" }}  ·  ${s["phone"]}  ·  ${s["website"]}"

        doc = PdfDocument()
        newPage()

        // ---------- Letterhead ----------
        val logo: Bitmap? = drawableBitmap(R.drawable.ic_logo, 160)
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
        hy = drawWrapped(s["company_name"].ifBlank { "AVIP Business Solutions LLP" }, textLeft, hy, headWidth, paint(16f, brand, true), 19f)
        if (s["tagline"].isNotBlank()) hy = drawWrapped(s["tagline"], textLeft, hy, headWidth, paint(8.5f, muted), 11f)
        hy = drawWrapped(s["address"], textLeft, hy, headWidth, paint(8.5f, ink), 11f)
        hy = drawWrapped(listOf(s["phone"], s["email"], s["website"]).filter { it.isNotBlank() }.joinToString("  ·  "), textLeft, hy, headWidth, paint(8.5f, ink), 11f)
        if (s["gstin"].isNotBlank()) hy = drawWrapped("GSTIN: ${s["gstin"]}", textLeft, hy, headWidth, paint(8.5f, ink, true), 11f)

        // Title block on the right
        val title = "COST ESTIMATE"
        val tb = RectF(right - titleW, headTop, right, headTop + 62f)
        canvas.drawRoundRect(tb, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand })
        canvas.drawText(title, tb.centerX(), tb.top + 21f, paint(15f, Color.WHITE, true, Paint.Align.CENTER))
        canvas.drawText("No: ${est.number}", tb.centerX(), tb.top + 37f, paint(8.5f, Color.WHITE, align = Paint.Align.CENTER))
        canvas.drawText("Date: $date", tb.centerX(), tb.top + 50f, paint(8.5f, Color.WHITE, align = Paint.Align.CENTER))
        val days = s.num("quote_validity_days", 7.0).toInt()
        val till = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(est.createdAt + days * 86_400_000L))
        canvas.drawText("Valid till: $till", tb.centerX(), tb.bottom + 12f, paint(8f, muted, align = Paint.Align.CENTER))

        y = maxOf(hy, headTop + 76f) + 6f
        canvas.drawRect(margin, y, right, y + 3f, Paint().apply { color = sun })
        y += 14f

        // ---------- To / For ----------
        val buyer = est.buyer ?: Buyer.ofSaathi(context)
        val toTitle = if (est.madeBy.isNotBlank()) "Cost estimate for  ·  B2B customer" else "Cost estimate for  ·  Surya Saathi"
        val toLines = mutableListOf<String>()
        toLines += buyer.company
        toLines += listOf(buyer.contact, if (buyer.mobile.isNotBlank()) "Mob: " + buyer.mobile else "").filter { it.isNotBlank() }.joinToString("  ·  ")
        val place = listOf(buyer.address, buyer.city).filter { it.isNotBlank() }.joinToString(", ")
        if (place.isNotBlank()) toLines += place
        if (buyer.gst.isNotBlank()) toLines += "GSTIN: ${buyer.gst}" + (buyer.gst.take(2).takeIf { it.all(Char::isDigit) && it.length == 2 }?.let { "  ·  State: " + stateName(it) } ?: "")
        if (est.madeBy.isNotBlank()) toLines += "Prepared by: ${est.madeBy}, AVIP Business Solutions LLP"
        val boxTop = y
        val half = (right - margin - 12f) / 2f
        var ly = boxTop + 14f
        canvas.drawText(toTitle.uppercase(), margin + 10f, ly, paint(7.5f, muted, true))
        ly += 13f
        toLines.forEachIndexed { i, t ->
            ly = drawWrapped(t, margin + 10f, ly, half - 20f, paint(if (i == 0) 10.5f else 8.5f, ink, i == 0), if (i == 0) 13f else 11f)
        }
        // Estimate details on the right
        val sellerState = s["gstin"].take(2).ifBlank { "10" }
        val buyerState = buyer.gst.take(2).takeIf { it.length == 2 && it.all(Char::isDigit) } ?: sellerState
        val interState = buyerState != sellerState
        var ry = boxTop + 14f
        val rx = margin + half + 22f
        canvas.drawText("ESTIMATE DETAILS", rx, ry, paint(7.5f, muted, true)); ry += 14f
        canvas.drawText("Place of supply: ${stateName(buyerState)}", rx, ry, paint(8.5f, ink)); ry += 11f
        canvas.drawText("Items: ${est.lines.size}", rx, ry, paint(8.5f, ink)); ry += 11f
        if (totals.kwp > 0) {
            val panels = est.lines.filter { it.wp > 0 }.sumOf { it.qty }
            canvas.drawText("Solar: ${Fmt.num(totals.kwp)} kWp (${Fmt.qty(panels)} panels)", rx, ry, paint(8.5f, brand, true)); ry += 11f
        }
        val boxBottom = maxOf(ly, ry) + 4f
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; style = Paint.Style.STROKE; strokeWidth = 0.8f }
        canvas.drawRoundRect(RectF(margin, boxTop, margin + half, boxBottom), 6f, 6f, boxPaint)
        canvas.drawRoundRect(RectF(margin + half + 12f, boxTop, right, boxBottom), 6f, 6f, boxPaint)
        y = boxBottom + 14f

        // ---------- Items table ----------
        val cols = listOf(
            Col("#", 18f, Paint.Align.CENTER), Col("Item name", 0f, Paint.Align.LEFT),
            Col("HSN/ SAC", 50f, Paint.Align.LEFT), Col("Quantity", 44f, Paint.Align.RIGHT),
            Col("Unit", 34f, Paint.Align.CENTER), Col("Price/ Unit", 62f, Paint.Align.RIGHT),
            Col("GST", 62f, Paint.Align.RIGHT), Col("Amount", 70f, Paint.Align.RIGHT),
        )
        val fixed = cols.sumOf { it.w.toDouble() }.toFloat()
        val itemW = right - margin - fixed
        val widths = cols.map { if (it.w == 0f) itemW else it.w }
        fun tx(i: Int, x: Float) = when (cols[i].align) { Paint.Align.LEFT -> x + 5f; Paint.Align.RIGHT -> x + widths[i] - 5f; else -> x + widths[i] / 2 }

        fun header() {
            canvas.drawRect(margin, y, right, y + 20f, Paint().apply { color = brand })
            var x = margin
            cols.forEachIndexed { i, c ->
                canvas.drawText(c.title, tx(i, x), y + 13.5f, paint(7.8f, Color.WHITE, true, c.align))
                x += widths[i]
            }
            y += 20f
        }
        header()

        val namePaint = paint(8.5f, ink, true)
        val subPaint = paint(7.2f, muted)
        val cell = paint(8.3f, ink)
        totals.rows.forEachIndexed { idx, r ->
            val l = r.line
            val hsn = l.hsn.ifBlank { Catalog.byId(l.productId)?.let { Catalog.hsnFor(it.category, it.title) } ?: Catalog.hsnFor(l.spec.substringBefore(" · "), l.name) }
            val sub = l.spec.substringAfter(" · ", "")
            val nameLines = wrap(l.name, itemW - 10f, namePaint)
            val subLines = if (sub.isBlank()) emptyList() else wrap(sub, itemW - 10f, subPaint)
            val rowH = maxOf(8f + nameLines.size * 10.5f + subLines.size * 9f, 8f + 2 * 10f) + 4f
            if (y + rowH > pageH - 70f) { newPage(); header() }
            if (idx % 2 == 1) canvas.drawRect(margin, y, right, y + rowH, Paint().apply { color = 0xFFF6F9F7.toInt() })
            var x = margin
            val base = y + 14f
            cols.forEachIndexed { i, c ->
                val t = tx(i, x)
                val al = paint(8.3f, ink, align = c.align)
                when (c.title) {
                    "#" -> canvas.drawText("${idx + 1}", t, base, paint(8.3f, muted, align = c.align))
                    "Item name" -> {
                        var iy = base
                        nameLines.forEach { canvas.drawText(it, t, iy, namePaint); iy += 10.5f }
                        subLines.forEach { canvas.drawText(it, t, iy - 1f, subPaint); iy += 9f }
                    }
                    "HSN/ SAC" -> canvas.drawText(hsn, t, base, cell)
                    "Quantity" -> canvas.drawText(Fmt.qty(l.qty), t, base, al)
                    "Unit" -> canvas.drawText(l.unit, t, base, al)
                    "Price/ Unit" -> canvas.drawText(Fmt.inr(r.rate, true), t, base, al)
                    "GST" -> {
                        canvas.drawText(Fmt.inr(r.gstAmount, true), t, base, al)
                        canvas.drawText("(${Fmt.num(l.gst)}%)", t, base + 10f, paint(7.5f, muted, align = c.align))
                    }
                    "Amount" -> canvas.drawText(Fmt.inr(r.amount + r.gstAmount, true), t, base, paint(8.5f, ink, true, c.align))
                }
                x += widths[i]
            }
            y += rowH
            canvas.drawLine(margin, y, right, y, Paint().apply { color = rule; strokeWidth = 0.5f })
        }
        // Total row
        if (y + 20f > pageH - 70f) { newPage(); header() }
        run {
            canvas.drawRect(margin, y, right, y + 20f, Paint().apply { color = brandLight })
            var x = margin
            cols.forEachIndexed { i, c ->
                val t = tx(i, x)
                val bold = paint(8.5f, ink, true, c.align)
                when (c.title) {
                    "Item name" -> canvas.drawText("Total", t, y + 13.5f, bold)
                    "Quantity" -> canvas.drawText(Fmt.qty(est.lines.sumOf { it.qty }), t, y + 13.5f, bold)
                    "GST" -> canvas.drawText(Fmt.inr(totals.gstTotal, true), t, y + 13.5f, bold)
                    "Amount" -> canvas.drawText(Fmt.inr(totals.subTotal + totals.gstTotal, true), t, y + 13.5f, bold)
                }
                x += widths[i]
            }
            y += 20f
            canvas.drawLine(margin, y, right, y, Paint().apply { color = brand; strokeWidth = 0.8f })
        }

        // ---------- Totals ----------
        val totalRows = mutableListOf<Pair<String, String>>()
        totalRows += "Sub Total" to Fmt.inr(totals.subTotal, true)
        totals.gstByRate.forEach { (rate, amt) ->
            if (interState) totalRows += "IGST@${Fmt.num(rate)}%" to Fmt.inr(amt, true)
            else {
                val half1 = round2(amt / 2)
                totalRows += "SGST@${Fmt.num(rate / 2)}%" to Fmt.inr(half1, true)
                totalRows += "CGST@${Fmt.num(rate / 2)}%" to Fmt.inr(round2(amt - half1), true)
            }
        }
        if (totals.roundOff != 0.0) totalRows += "Round off" to Fmt.inr(totals.roundOff, true)
        val needed = 30f + totalRows.size * 15f + 26f + 20f
        if (y + needed > pageH - 70f) newPage()
        y += 10f
        val tx1 = right - 230f
        val sumTop = y
        totalRows.forEach { (label, value) ->
            canvas.drawText(label, tx1, y + 10f, paint(9f, muted))
            canvas.drawText(value, right - 6f, y + 10f, paint(9f, ink, align = Paint.Align.RIGHT))
            y += 15f
        }
        val gt = RectF(tx1 - 8f, y + 2f, right, y + 26f)
        canvas.drawRoundRect(gt, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand })
        canvas.drawText("Total", tx1, gt.top + 16f, paint(10f, Color.WHITE, true))
        canvas.drawText(Fmt.inr(totals.grandTotal, true), right - 6f, gt.top + 16.5f, paint(12f, Color.WHITE, true, Paint.Align.RIGHT))
        y = gt.bottom + 4f
        // amount in words on the left side
        canvas.drawText("ESTIMATE AMOUNT IN WORDS", margin, sumTop + 10f, paint(7.8f, brand, true))
        drawWrapped(Fmt.words(totals.grandTotal), margin, sumTop + 24f, tx1 - margin - 20f, paint(8.5f, ink), 11f)
        y += 6f

        // ---------- Footer blocks ----------
        y += 10f
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

        // signature
        if (y + 50f > pageH - 60f) newPage()
        y += 16f
        val signer = "For ${s["company_name"].ifBlank { "AVIP Business Solutions LLP" }}"
        canvas.drawText(signer, right, y, paint(9f, ink, true, Paint.Align.RIGHT))
        canvas.drawText("Authorised Signatory", right, y + 34f, paint(8f, muted, align = Paint.Align.RIGHT))

        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun stateName(code: String): String = code + "-" + (mapOf(
        "01" to "Jammu & Kashmir", "02" to "Himachal Pradesh", "03" to "Punjab", "04" to "Chandigarh", "05" to "Uttarakhand",
        "06" to "Haryana", "07" to "Delhi", "08" to "Rajasthan", "09" to "Uttar Pradesh", "10" to "Bihar", "11" to "Sikkim",
        "12" to "Arunachal Pradesh", "13" to "Nagaland", "14" to "Manipur", "15" to "Mizoram", "16" to "Tripura",
        "17" to "Meghalaya", "18" to "Assam", "19" to "West Bengal", "20" to "Jharkhand", "21" to "Odisha",
        "22" to "Chhattisgarh", "23" to "Madhya Pradesh", "24" to "Gujarat", "26" to "Dadra & Nagar Haveli and Daman & Diu",
        "27" to "Maharashtra", "29" to "Karnataka", "30" to "Goa", "32" to "Kerala", "33" to "Tamil Nadu",
        "34" to "Puducherry", "35" to "Andaman & Nicobar", "36" to "Telangana", "37" to "Andhra Pradesh", "38" to "Ladakh",
    )[code] ?: "")

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
