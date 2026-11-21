package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Latest count of every item, by category. ₹ values for Founders only. */
class StockCurrentActivity : AppCompatActivity() {

    private enum class Show { ALL, IN, LOW, ZERO, NEVER }

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private var show = Show.ALL
    private var query = ""
    private val founder by lazy { Can.stockValue(this) }
    private val exact by lazy { Can.stockQty(this) }
    private val df = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stock.loadCached(this)
        val last = Stock.history.firstOrNull()
        val share = ImageView(this).apply {
            setImageResource(R.drawable.ic_share)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { shareReport() }
        }
        val root = vertical()
        if (!exact) share.visibility = android.view.View.GONE
        root.addView(topBar(if (exact) tr("Current stock", "मौजूदा स्टॉक") else tr("Stock availability", "स्टॉक उपलब्धता"),
            last?.let { tr("Last count ", "पिछली गिनती ") + df.format(Date(it.date)) + " · " + it.by } ?: Stock.godown, right = listOf(share)))
        root.addView(input(tr("Search product, brand…", "प्रोडक्ट, ब्रांड खोजें…")).apply {
            background = rounded(Color.WHITE, dpf(24f), Ui.LINE, dp(1))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString(); render() }
            })
        }, lp().margins(this, 12, 10, 12, 0))
        chips = horizontal { setPadding(dp(12), dp(8), dp(12), dp(2)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        list = vertical(12)
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) {
            try { Stock.refresh(this) } catch (_: ApiException) {}
            render()
        }, lp(MATCH, 0, 1f))
        setEdgeContent(root)
        render()
        lifecycleScope.launch {
            try { Stock.refresh(this@StockCurrentActivity) } catch (e: ApiException) {
                if (e.code == "SESSION" || e.code == "NOTEAM") { goLogin(this@StockCurrentActivity, e.message); return@launch }
            }
            render()
        }
    }

    private fun qtyOf(p: Product) = Stock.current[p.id]?.qty

    private fun matches(p: Product): Boolean {
        val q = qtyOf(p)
        return when (show) {
            Show.ALL -> true
            Show.IN -> q != null && q > 0
            Show.LOW -> Stock.isLow(p, q)
            Show.ZERO -> q != null && q <= 0
            Show.NEVER -> q == null
        }
    }

    private fun render() {
        val items = Stock.items()
        chips.removeAllViews()
        listOf(Show.ALL to tr("All", "सभी"), Show.IN to tr("In stock", "स्टॉक में"),
            Show.LOW to if (!exact) tr("Low", "कम") else tr("Below min (${items.count { Stock.isLow(it, qtyOf(it)) }})", "न्यूनतम से कम (${items.count { Stock.isLow(it, qtyOf(it)) }})"),
            Show.ZERO to if (!exact) tr("Out", "खत्म") else tr("Zero (${items.count { (qtyOf(it) ?: 1.0) <= 0 }})", "शून्य (${items.count { (qtyOf(it) ?: 1.0) <= 0 }})"),
            Show.NEVER to tr("Never counted", "कभी नहीं गिने")).forEach { (k, label) ->
            val on = show == k
            chips.addView(text(label, 14.5f, if (on) Color.WHITE else Ui.TEXT, bold = on) {
                background = rounded(if (on) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (on) 0 else dp(1))
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setOnClickListener { show = k; render() }
            }, lp(WRAP, WRAP).margins(this, r = 8))
        }

        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val shown = items.filter { p ->
            val hay = "${p.category} ${p.brand} ${p.name} ${p.spec}".lowercase()
            words.all { hay.contains(it) } && matches(p)
        }
        list.removeAllViews()
        if (!Stock.loaded) { list.addView(text(tr("Loading…", "लोड हो रहा है…"), 16f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40)); return }
        if (shown.isEmpty()) { list.addView(text(tr("Nothing here", "यहां कुछ नहीं"), 16f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40)); return }
        shown.groupBy { it.category }.forEach { (cat, ps) ->
            val v = ps.sumOf { Stock.value(it, qtyOf(it) ?: 0.0) }
            list.addView(text(cat.uppercase() + if (founder && v > 0) "  ·  " + Fmt.lakh(v) else "", 13.5f, Ui.MUTED, bold = true),
                lp().margins(this, t = 6, b = 6))
            list.addView(card(12) {
                ps.sortedWith(compareBy<Product> { it.brand.lowercase() }.thenBy { it.name.lowercase() }).forEachIndexed { i, p ->
                    val q = qtyOf(p)
                    val cur = Stock.current[p.id]
                    if (i > 0) addView(android.view.View(context).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, 1).margins(context, t = 10, b = 10))
                    addView(horizontal {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(vertical {
                            addView(text(Stock.label(p), 15f, Ui.TEXT, bold = true))
                            val status = when {
                                q == null -> tr("Never counted", "कभी नहीं गिना") to Ui.MUTED
                                q <= 0 -> tr("Zero stock", "शून्य स्टॉक") to Ui.DANGER
                                Stock.isLow(p, q) -> (if (exact) tr("Below min ${Fmt.qty(cur?.min ?: 0.0)}", "न्यूनतम ${Fmt.qty(cur?.min ?: 0.0)} से कम")
                                    else tr("Low stock", "स्टॉक कम")) to 0xFFB7791F.toInt()
                                founder && p.unitPrice != null -> "${Fmt.inr(p.unitPrice!!)} / ${p.unit.lowercase()}" to Ui.MUTED
                                else -> (cur?.lastCounted?.takeIf { it > 0 }?.let { tr("Counted ", "गिना ") + df.format(Date(it)) } ?: "") to Ui.MUTED
                            }
                            if (status.first.isNotBlank()) addView(text(status.first, 13.5f, status.second))
                            if (!cur?.note.isNullOrBlank()) addView(text("📝 " + cur!!.note, 13.5f, Ui.MUTED))
                        }, lp(0, WRAP, 1f).margins(context, r = 8))
                        addView(vertical {
                            gravity = Gravity.END
                            addView(text(if (!exact) (when { q == null -> "—"; q <= 0 -> tr("Out", "खत्म"); Stock.isLow(p, q) -> tr("Low", "कम"); else -> tr("In stock", "उपलब्ध") })
                                else (q?.let { Fmt.qty(it) } ?: "—") + " " + p.unit, 18f, Ui.TEXT, bold = true) { gravity = Gravity.END })
                            if (founder && q != null && q > 0 && p.unitPrice != null)
                                addView(text(Fmt.lakh(Stock.value(p, q)), 13.5f, Ui.MUTED) { gravity = Gravity.END })
                        })
                    })
                }
            }, lp().margins(this, b = 10))
        }
    }

    /** CSV that opens in Excel / Google Sheets. ₹ columns only for Founders. */
    private fun shareReport() {
        val sb = StringBuilder()
        fun csv(vararg v: String) = sb.append(v.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append("\r\n")
        if (founder) csv("Category", "Product", "Unit", "Qty", "Min Level", "Last Counted", "Rate (before GST)", "Value (before GST)")
        else csv("Category", "Product", "Unit", "Qty", "Min Level", "Last Counted")
        Stock.items().filter { matches(it) }.forEach { p ->
            val c = Stock.current[p.id]
            val cols = mutableListOf(p.category, Stock.label(p), p.unit, c?.qty?.let { Fmt.qty(it) } ?: "",
                c?.min?.let { Fmt.qty(it) } ?: "", c?.lastCounted?.takeIf { it > 0 }?.let { df.format(Date(it)) } ?: "")
            if (founder) { cols += p.unitPrice?.let { Fmt.num(it) } ?: ""; cols += c?.let { Fmt.num(Stock.value(p, it.qty)) } ?: "" }
            csv(*cols.toTypedArray())
        }
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "AVIP-Stock-" + SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date()) + ".csv")
            f.writeText("﻿" + sb)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "AVIP stock — " + Stock.godown)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Share stock report", "स्टॉक रिपोर्ट भेजें")))
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }
}
