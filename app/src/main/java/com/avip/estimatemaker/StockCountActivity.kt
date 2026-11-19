package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Counting screen: every product from App Prices (pallet rates left out), saved on the phone as you go. */
class StockCountActivity : AppCompatActivity() {

    private enum class Show { ALL, TODO, DONE, CHANGED }

    private lateinit var draft: Stock.Draft
    private lateinit var list: LinearLayout
    private lateinit var catChips: LinearLayout
    private lateinit var showChips: LinearLayout
    private lateinit var reviewBtn: TextView
    private lateinit var subtitle: TextView
    private var category: String? = null
    private var show = Show.ALL
    private var query = ""
    private val all by lazy { Stock.items() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = Stock.Draft.get(this) ?: run { finish(); return }
        val root = vertical()
        val bar = topBar(tr("Count · ", "गिनती · ") + java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(java.util.Date(draft.started)), "…")
        root.addView(bar)
        subtitle = findSubtitle(bar)
        val box = input(tr("Search product, brand…", "प्रोडक्ट, ब्रांड खोजें…")).apply {
            background = rounded(Color.WHITE, dpf(24f), Ui.LINE, dp(1))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString(); render() }
            })
        }
        root.addView(box, lp().margins(this, 12, 10, 12, 0))
        catChips = horizontal { setPadding(dp(12), dp(8), dp(12), 0) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(catChips) })
        showChips = horizontal { setPadding(dp(12), dp(6), dp(12), dp(2)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(showChips) })
        list = vertical(12)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        reviewBtn = pillButton("", Ui.BRAND, Color.WHITE, 52) {
            startActivity(Intent(this, StockReviewActivity::class.java))
        }
        root.addView(vertical(12) { addView(reviewBtn) })
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        draft = Stock.Draft.get(this) ?: run { finish(); return }
        render()
    }

    /** The subtitle TextView of a topBar (second text in its title column), or a dummy. */
    private fun findSubtitle(v: View): TextView {
        val texts = mutableListOf<TextView>()
        fun walk(x: View) { if (x is TextView) texts += x; if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(v)
        return texts.getOrNull(texts.indexOfFirst { it.text.toString() == "…" }.takeIf { it >= 0 } ?: -1) ?: TextView(this)
    }

    private fun chip(label: String, on: Boolean, row: LinearLayout, click: () -> Unit) {
        row.addView(text(label, 12.5f, if (on) Color.WHITE else Ui.TEXT, bold = on) {
            background = rounded(if (on) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (on) 0 else dp(1))
            setPadding(dp(13), dp(7), dp(13), dp(7))
            setOnClickListener { click() }
        }, lp(WRAP, WRAP).margins(this, r = 8))
    }

    private val rows = mutableMapOf<String, View>()

    /** After one item changes: redraw just its row and the counters (keeps the scroll position). */
    private fun changed(p: Product) {
        val old = rows[p.id]
        val i = old?.let { list.indexOfChild(it) } ?: -1
        if (i < 0) { render(); return }
        val lpOld = old!!.layoutParams
        list.removeViewAt(i)
        val v = row(p)
        rows[p.id] = v
        list.addView(v, i, lpOld)
        header()
    }

    private fun render() {
        header()
        renderList()
    }

    private fun header() {
        val done = all.count { draft.qty(it) != null }
        subtitle.text = tr("$done of ${all.size} counted", "${all.size} में से $done गिने")
        reviewBtn.text = tr("Review & submit ($done)", "देखें व सबमिट करें ($done)")

        catChips.removeAllViews()
        chip(tr("All", "सभी"), category == null, catChips) { category = null; render() }
        all.map { it.category }.distinct().forEach { c -> chip(c, category == c, catChips) { category = c; render() } }

        val inCat = all.filter { category == null || it.category == category }
        showChips.removeAllViews()
        chip(tr("Not counted (${inCat.count { draft.qty(it) == null }})", "नहीं गिने (${inCat.count { draft.qty(it) == null }})"), show == Show.TODO, showChips) {
            show = if (show == Show.TODO) Show.ALL else Show.TODO; render() }
        chip(tr("Counted (${inCat.count { draft.qty(it) != null }})", "गिने (${inCat.count { draft.qty(it) != null }})"), show == Show.DONE, showChips) {
            show = if (show == Show.DONE) Show.ALL else Show.DONE; render() }
        chip(tr("Changed (${inCat.count { draft.changed(it) }})", "बदले (${inCat.count { draft.changed(it) }})"), show == Show.CHANGED, showChips) {
            show = if (show == Show.CHANGED) Show.ALL else Show.CHANGED; render() }
    }

    private fun renderList() {
        val inCat = all.filter { category == null || it.category == category }

        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val shown = inCat.filter { p ->
            val hay = "${p.category} ${p.brand} ${p.name} ${p.spec}".lowercase()
            words.all { hay.contains(it) } && when (show) {
                Show.ALL -> true
                Show.TODO -> draft.qty(p) == null
                Show.DONE -> draft.qty(p) != null
                Show.CHANGED -> draft.changed(p)
            }
        }.sortedWith(compareBy<Product> { all.indexOfFirst { a -> a.category == it.category } }.thenBy { it.brand.lowercase() }.thenBy { it.name.lowercase() })

        list.removeAllViews()
        rows.clear()
        if (shown.isEmpty()) {
            list.addView(text(if (show == Show.TODO && words.isEmpty()) tr("All items here are counted ✓", "यहां सभी आइटम गिन लिए ✓") else tr("No products found", "कोई प्रोडक्ट नहीं मिला"),
                14f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40))
            return
        }
        var lastCat = ""
        shown.forEach { p ->
            if (category == null && p.category != lastCat) {
                lastCat = p.category
                list.addView(text(p.category.uppercase(), 11.5f, Ui.MUTED, bold = true), lp().margins(this, t = 6, b = 6))
            }
            val v = row(p)
            rows[p.id] = v
            list.addView(v, lp().margins(this, b = 8))
        }
    }

    private fun row(p: Product): View = card(10) {
        val q = draft.qty(p)
        val last = Stock.current[p.id]?.qty
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(productPhoto(p, p.icon, 40))
            addView(vertical {
                addView(text(p.title, 13.5f, Ui.TEXT, bold = true))
                addView(text(listOf(p.spec, p.unit).filter { it.isNotBlank() }.joinToString(" · "), 11.5f, Ui.MUTED))
                val info = mutableListOf<String>()
                info += if (last != null) tr("Last count ", "पिछली गिनती ") + Fmt.qty(last) else tr("Not counted before", "पहले नहीं गिना")
                val d = if (q != null && last != null) q - last else null
                addView(text(info.joinToString(), 11.5f, Ui.MUTED) {
                    if (d != null) append(if (kotlin.math.abs(d) < 0.0001) "  ·  " + tr("same", "वही") else "  ·  " + (if (d > 0) "+" else "") + Fmt.qty(d))
                    if (d != null && kotlin.math.abs(d) >= 0.0001) setTextColor(if (d > 0) Ui.BRAND else Ui.DANGER)
                })
                if (draft.note(p).isNotBlank()) addView(text("📝 " + draft.note(p), 11.5f, Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 10, r = 6))
            addView(round("−") { step(p, -1.0) })
            addView(text(q?.let { Fmt.qty(it) } ?: "—", 15f, if (q != null) Ui.TEXT else Ui.MUTED, bold = q != null) {
                gravity = Gravity.CENTER
                minWidth = dp(54)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                background = rounded(Color.WHITE, dpf(9f), if (q != null) Ui.BRAND else Ui.LINE, dp(if (q != null) 2 else 1))
                setOnClickListener { typeCount(p) }
            }, lp(WRAP, WRAP).margins(context, l = 4, r = 4))
            addView(round("+") { step(p, 1.0) })
        })
    }

    private fun round(t: String, click: () -> Unit) = text(t, 18f, Ui.TEXT, bold = true) {
        gravity = Gravity.CENTER
        background = rounded(0xFFEFF3F1.toInt(), dpf(16f))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        setOnClickListener { click() }
    }

    /** − / +: the first tap starts from the last count (or 0). */
    private fun step(p: Product, by: Double) {
        val base = draft.qty(p) ?: Stock.current[p.id]?.qty ?: 0.0
        draft.set(this, p, (if (draft.qty(p) == null) base else base + by).coerceAtLeast(0.0))
        changed(p)
    }

    private fun typeCount(p: Product) {
        val last = Stock.current[p.id]?.qty
        val qty = input(tr("Count in ${p.unit}", "${p.unit} में गिनती"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            draft.qty(p)?.let { Fmt.qty(it) } ?: "")
        val note = input(tr("Note (optional), e.g. 2 cut coils", "नोट (वैकल्पिक), जैसे 2 कटे कॉइल"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, draft.note(p))
        val body = vertical(18) {
            addView(text(Stock.label(p), 13f, Ui.MUTED))
            if (last != null) addView(text(tr("Last count: ", "पिछली गिनती: ") + Fmt.qty(last) + " " + p.unit, 12.5f, Ui.MUTED), lp().margins(context, t = 4))
            addView(qty, lp().margins(context, t = 10))
            addView(note, lp().margins(context, t = 8))
        }
        val d = AlertDialog.Builder(this).setTitle(p.title).setView(body)
            .setPositiveButton(tr("Save", "सेव")) { _, _ ->
                val v = qty.text.toString().replace(",", "").toDoubleOrNull()
                if (v != null) draft.set(this, p, v, note.text.toString().trim())
                changed(p)
            }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null)
            .apply {
                if (last != null) setNeutralButton(tr("Same as last", "पिछली जैसी")) { _, _ -> draft.set(this@StockCountActivity, p, last, note.text.toString().trim()); changed(p) }
                if (draft.qty(p) != null) setNeutralButton(tr("Clear", "हटाएं")) { _, _ -> draft.set(this@StockCountActivity, p, null); changed(p) }
            }.create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        qty.requestFocus(); qty.selectAll()
    }
}
