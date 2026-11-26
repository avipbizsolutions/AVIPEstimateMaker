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
 * Stock statement PDF (for the accountant and the bank): AVIP letterhead, summary, item table by
 * category with HSN, quantity, change since the previous count, rate and value, category and grand
 * totals, notes, certification and signature. Values are left out when [withValues] is false.
 */
class StockPdf(private val context: Context) {

    class Item(
        val category: String, val name: String, val unit: String, val hsn: String,
        val qty: Double, val before: Double?, val rate: Double?, val note: String,
    ) {
        val value get() = (rate ?: 0.0) * qty
        val diff get() = before?.let { qty - it }
    }

    class Meta(
        val title: String,           // "STOCK STATEMENT"
        val asOn: Long,              // count date
        val countedBy: String,
        val reference: String,       // count ID or "Current stock"
        val godown: String,
        val showChange: Boolean,
        val withValues: Boolean,
        val preparedBy: String,
    )

    private val pageW = 595
    private val pageH = 842
    private val margin = 32f
    private val right get() = pageW - margin
    private val brand = 0xFF0E6B3A.toInt()
    private val brandLight = 0xFFE8F3EC.toInt()
    private val sun = 0xFFF7A21B.toInt()
    private val ink = 0xFF17211B.toInt()
    private val muted = 0xFF5F6B64.toInt()
    private val rule = 0xFFD5DDD8.toInt()
    private val red = 0xFFC0392B.toInt()

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0
    private lateinit var meta: Meta
    private val dateF = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private val timeF = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.ENGLISH)

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    private class Col(val title: String, val w: Float, val align: Paint.Align)

    fun make(items: List<Item>, m: Meta, out: File) {
        meta = m
        val s = Catalog.settings
        doc = PdfDocument()
        newPage()

        // ---------- Letterhead ----------
        val logo = ContextCompat.getDrawable(context, R.drawable.ic_logo)?.let { d ->
            Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 160, 160); d.draw(Canvas(b)) }
        }
        val top = y
        var textLeft = margin
        if (logo != null) {
            canvas.drawBitmap(logo, null, RectF(margin, top, margin + 54f, top + 54f), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft = margin + 66f
        }
        val titleW = 180f
        val headW = right - titleW - textLeft - 8f
        var hy = top + 15f
        hy = wrapDraw(s["company_name"].ifBlank { "AVIP Business Solutions LLP" }, textLeft, hy, headW, paint(15f, brand, true), 18f)
        hy = wrapDraw(s["address"], textLeft, hy, headW, paint(8.3f), 10.5f)
        hy = wrapDraw(listOf(s["phone"], s["email"], s["website"]).filter { it.isNotBlank() }.joinToString("  ·  "), textLeft, hy, headW, paint(8.3f), 10.5f)
        if (s["gstin"].isNotBlank()) hy = wrapDraw("GSTIN: ${s["gstin"]}", textLeft, hy, headW, paint(8.3f, ink, true), 10.5f)

        val tb = RectF(right - titleW, top, right, top + 56f)
        canvas.drawRoundRect(tb, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand })
        canvas.drawText(m.title, tb.centerX(), tb.top + 22f, paint(14f, Color.WHITE, true, Paint.Align.CENTER))
        canvas.drawText("As on ${dateF.format(Date(m.asOn))}", tb.centerX(), tb.top + 39f, paint(9f, Color.WHITE, align = Paint.Align.CENTER))
        y = maxOf(hy, tb.bottom) + 8f
        canvas.drawRect(margin, y, right, y + 3f, Paint().apply { color = sun })
        y += 14f

        // ---------- Summary ----------
        val totalValue = items.sumOf { it.value }
        val inStock = items.count { it.qty > 0 }
        val summary = mutableListOf(
            "Godown" to m.godown,
            "Counted on" to timeF.format(Date(m.asOn)),
            "Counted by" to m.countedBy.ifBlank { "—" },
            "Reference" to m.reference,
            "Items" to "${items.size} listed  ·  $inStock in stock  ·  ${items.size - inStock} at zero",
        )
        if (m.showChange) {
            val up = items.count { (it.diff ?: 0.0) > 0.0001 }; val down = items.count { (it.diff ?: 0.0) < -0.0001 }
            summary += "Change since previous count" to "$up increased  ·  $down decreased  ·  ${items.count { it.before == null }} first time"
        }
        if (m.withValues) summary += "Total stock value" to Fmt.inr(totalValue, true) + "  (before GST)"
        val boxTop = y
        var sy = y + 15f
        summary.forEach { (k, v) ->
            canvas.drawText(k, margin + 10f, sy, paint(8.5f, muted))
            sy = wrapDraw(v, margin + 170f, sy, right - margin - 180f, paint(8.8f, ink, k == "Total stock value"), 11.5f) + 1.5f
        }
        canvas.drawRoundRect(RectF(margin, boxTop, right, sy + 2f), 6f, 6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; style = Paint.Style.STROKE; strokeWidth = 0.8f })
        y = sy + 16f

        // ---------- Table ----------
        val cols = mutableListOf(Col("#", 20f, Paint.Align.CENTER), Col("Item", 0f, Paint.Align.LEFT),
            Col("HSN", 50f, Paint.Align.LEFT), Col("Unit", 36f, Paint.Align.CENTER), Col("Qty", 50f, Paint.Align.RIGHT))
        if (m.showChange) cols += Col("Change", 44f, Paint.Align.RIGHT)
        if (m.withValues) { cols += Col("Rate", 58f, Paint.Align.RIGHT); cols += Col("Value", 72f, Paint.Align.RIGHT) }
        val itemW = right - margin - cols.sumOf { it.w.toDouble() }.toFloat()
        val widths = cols.map { if (it.w == 0f) itemW else it.w }
        fun tx(i: Int, x: Float) = when (cols[i].align) { Paint.Align.LEFT -> x + 4f; Paint.Align.RIGHT -> x + widths[i] - 4f; else -> x + widths[i] / 2 }
        fun header() {
            canvas.drawRect(margin, y, right, y + 19f, Paint().apply { color = brand })
            var x = margin
            cols.forEachIndexed { i, c -> canvas.drawText(c.title, tx(i, x), y + 13f, paint(8f, Color.WHITE, true, c.align)); x += widths[i] }
            y += 19f
        }
        header()
        var n = 0
        val namePaint = paint(8.4f, ink)
        items.groupBy { it.category }.forEach { (cat, rows) ->
            if (y + 40f > pageH - 70f) { newPage(); header() }
            // category heading with subtotal
            canvas.drawRect(margin, y, right, y + 17f, Paint().apply { color = brandLight })
            canvas.drawText(cat.uppercase(), margin + 6f, y + 12f, paint(8f, brand, true))
            val catText = "${rows.size} items" + if (m.withValues) "  ·  " + Fmt.inr(rows.sumOf { it.value }, true) else ""
            canvas.drawText(catText, right - 4f, y + 12f, paint(8f, brand, true, Paint.Align.RIGHT))
            y += 17f
            rows.forEach { r ->
                n++
                val lines = wrap(r.name, itemW - 8f, namePaint)
                val rowH = 6f + lines.size * 10.5f + 3f
                if (y + rowH > pageH - 70f) { newPage(); header() }
                var x = margin
                val base = y + 12f
                cols.forEachIndexed { i, c ->
                    val t = tx(i, x)
                    val p = paint(8.4f, ink, align = c.align)
                    when (c.title) {
                        "#" -> canvas.drawText("$n", t, base, paint(8f, muted, align = c.align))
                        "Item" -> { var iy = base; lines.forEach { canvas.drawText(it, t, iy, namePaint); iy += 10.5f } }
                        "HSN" -> canvas.drawText(r.hsn, t, base, paint(8f, muted))
                        "Unit" -> canvas.drawText(r.unit, t, base, p)
                        "Qty" -> canvas.drawText(Fmt.qty(r.qty), t, base, paint(8.6f, if (r.qty <= 0) red else ink, true, c.align))
                        "Change" -> r.diff?.let { d ->
                            val same = kotlin.math.abs(d) < 0.0001
                            canvas.drawText(if (same) "—" else (if (d > 0) "+" else "") + Fmt.qty(d), t, base,
                                paint(8.2f, if (same) muted else if (d > 0) brand else red, !same, c.align))
                        } ?: canvas.drawText("new", t, base, paint(7.8f, muted, align = c.align))
                        "Rate" -> canvas.drawText(r.rate?.let { Fmt.inr(it, true) } ?: "—", t, base, p)
                        "Value" -> canvas.drawText(if (r.rate != null) Fmt.inr(r.value, true) else "—", t, base, p)
                    }
                    x += widths[i]
                }
                y += rowH
                canvas.drawLine(margin, y, right, y, Paint().apply { color = rule; strokeWidth = 0.5f })
            }
        }
        // grand total
        if (y + 24f > pageH - 70f) { newPage(); header() }
        canvas.drawRect(margin, y, right, y + 22f, Paint().apply { color = brand })
        canvas.drawText("TOTAL  ·  ${items.size} items", margin + 8f, y + 15f, paint(9f, Color.WHITE, true))
        if (m.withValues) canvas.drawText(Fmt.inr(totalValue, true), right - 4f, y + 15f, paint(10f, Color.WHITE, true, Paint.Align.RIGHT))
        y += 30f
        if (m.withValues) {
            y = wrapDraw("Total stock value in words: " + Fmt.words(totalValue), margin, y + 4f, right - margin, paint(8.6f, ink, true), 11f) + 4f
        }

        // ---------- Notes ----------
        val notes = items.filter { it.note.isNotBlank() }
        if (notes.isNotEmpty()) {
            section("Notes from the count")
            notes.forEach { y = wrapDraw("•  ${it.name}: ${it.note}", margin, y, right - margin, paint(8.3f), 10.5f) }
            y += 6f
        }
        section("Basis")
        val basis = mutableListOf("Quantities are as physically counted at the godown on ${dateF.format(Date(m.asOn))}" +
            (if (m.countedBy.isNotBlank()) " by ${m.countedBy}" else "") + ".")
        if (m.withValues) basis += s["stock_valuation_note"].ifBlank { "Values are at AVIP's selling rates before GST as on the date of this statement (from the AVIP price sheet)." }
        basis.forEach { y = wrapDraw(it, margin, y, right - margin, paint(8.3f), 10.5f) }

        // ---------- Certification & signature ----------
        if (y + 90f > pageH - 60f) newPage()
        y += 14f
        y = wrapDraw("Certified that the above is a true and correct statement of the stock held at our godown, " + m.godown + ", as on " +
            dateF.format(Date(m.asOn)) + ".", margin, y, right - margin, paint(8.6f, ink), 11f)
        y += 34f
        canvas.drawText("Prepared by: ${m.preparedBy}", margin, y, paint(8.5f, muted))
        canvas.drawText("For ${s["company_name"].ifBlank { "AVIP Business Solutions LLP" }}", right, y - 22f, paint(9f, ink, true, Paint.Align.RIGHT))
        canvas.drawText("Authorised Signatory", right, y + 12f, paint(8f, muted, align = Paint.Align.RIGHT))
        canvas.drawText("Generated ${timeF.format(Date())}", margin, y + 12f, paint(7.5f, muted))

        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun section(t: String) {
        if (y + 40f > pageH - 60f) newPage()
        canvas.drawText(t.uppercase(), margin, y + 10f, paint(7.8f, brand, true))
        y += 22f
    }

    private fun newPage() {
        finishPage()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        canvas = page!!.canvas
        canvas.drawRect(0f, 0f, pageW.toFloat(), 6f, Paint().apply { color = brand })
        y = margin
        if (pageNo > 1) {   // running header on later pages
            canvas.drawText("${meta.title}  ·  as on ${dateF.format(Date(meta.asOn))}  ·  ${meta.godown}", margin, y + 6f, paint(8f, muted, true))
            y += 18f
        }
    }

    private fun finishPage() {
        val p = page ?: return
        val c = p.canvas
        c.drawLine(margin, pageH - 34f, right, pageH - 34f, Paint().apply { color = rule; strokeWidth = 0.6f })
        c.drawText("${Catalog.settings["company_name"].ifBlank { "AVIP Business Solutions LLP" }}  ·  ${meta.title}  ·  ${meta.reference}",
            margin, pageH - 22f, paint(7.5f, muted))
        c.drawText("Page $pageNo", right, pageH - 22f, paint(7.5f, muted, align = Paint.Align.RIGHT))
        doc.finishPage(p)
        page = null
    }

    private fun wrapDraw(text: String, x: Float, startY: Float, width: Float, p: Paint, lineH: Float): Float {
        if (text.isBlank()) return startY
        var yy = startY
        wrap(text, width, p).forEach { line ->
            if (yy > pageH - 50f) { newPage(); yy = y + lineH }
            canvas.drawText(line, x, yy, p); yy += lineH
        }
        return yy
    }

    private fun wrap(text: String, width: Float, p: Paint): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var line = ""
            para.split(' ').forEach { word ->
                val cand = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(cand) <= width) line = cand
                else { if (line.isNotEmpty()) out += line; line = word }
            }
            out += line
        }
        return out
    }

    companion object {
        /** Item for a product of the catalog (rate and HSN from today's sheet). */
        fun item(category: String, name: String, unit: String, productId: String, qty: Double, before: Double?, note: String): Item {
            val p = Catalog.byId(productId)
            return Item(category, name, unit, p?.let { Catalog.hsnFor(it.category, it.title) } ?: Catalog.hsnFor(category, name),
                qty, before, p?.unitPrice, note)
        }

        /** Shares a PDF (or opens the chooser) from any screen. */
        fun share(a: android.app.Activity, f: File, subject: String) {
            val uri = androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.files", f)
            a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("application/pdf").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Share stock statement", "स्टॉक स्टेटमेंट भेजें")))
        }
    }
}
