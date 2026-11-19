package com.avip.estimatemaker

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Review a count before it goes to the sheet: what changed, what is still uncounted. */
class StockReviewActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var submitBtn: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draft = Stock.Draft.get(this) ?: run { finish(); return }
        val root = vertical()
        root.addView(topBar(tr("Review count", "गिनती देखें"), Session.teamName(this)))
        list = vertical(12)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        submitBtn = pillButton(tr("Submit count", "गिनती सबमिट करें"), Ui.BRAND, Color.WHITE, 52) { submit() }
        root.addView(vertical(12) {
            addView(submitBtn)
            addView(pillButton(tr("Keep counting", "गिनती जारी रखें"), 0xFFEFF3F1.toInt(), Ui.TEXT, 44) { finish() }, lp().margins(context, t = 8))
        })
        setEdgeContent(root)
        render(draft)
    }

    private fun render(d: Stock.Draft) {
        list.removeAllViews()
        val items = Stock.items()
        val counted = items.filter { d.qty(it) != null }
        val changed = counted.filter { d.changed(it) }
        val newOnes = counted.filter { Stock.current[it.id] == null }
        val todo = items.filter { d.qty(it) == null }

        list.addView(card(14) {
            addView(kv(tr("Items counted", "गिने गए आइटम"), "${counted.size} of ${items.size}"))
            addView(kv(tr("Changed vs last count", "पिछली गिनती से बदले"), "${changed.size}"))
            if (newOnes.isNotEmpty()) addView(kv(tr("Counted for the first time", "पहली बार गिने"), "${newOnes.size}"))
            if (Session.founder(this@StockReviewActivity))
                addView(kv(tr("Stock value (today's rates, before GST)", "स्टॉक वैल्यू (आज के रेट, GST से पहले)"),
                    Fmt.lakh(counted.sumOf { Stock.value(it, d.qty(it) ?: 0.0) })))
        }, lp().margins(this, b = 12))

        if (changed.isNotEmpty()) {
            list.addView(section(tr("CHANGED SINCE LAST COUNT", "पिछली गिनती से बदले")))
            list.addView(card(12) {
                changed.forEach { p ->
                    val last = Stock.current[p.id]?.qty ?: 0.0
                    val q = d.qty(p) ?: 0.0
                    val diff = q - last
                    addView(horizontal {
                        addView(text(Stock.label(p), 12.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, r = 8))
                        addView(text("${Fmt.qty(last)} → ${Fmt.qty(q)}  ", 12.5f, Ui.TEXT))
                        addView(text((if (diff > 0) "+" else "") + Fmt.qty(diff), 12.5f, if (diff > 0) Ui.BRAND else Ui.DANGER, bold = true))
                    }, lp().margins(context, b = 6))
                }
            }, lp().margins(this, b = 12))
        }
        if (todo.isNotEmpty()) {
            list.addView(section(tr("NOT COUNTED (${todo.size})", "नहीं गिने (${todo.size})")))
            list.addView(card(12) {
                todo.take(40).forEach { p ->
                    addView(text(Stock.label(p), 12.5f, Ui.TEXT), lp().margins(context, b = 5))
                }
                if (todo.size > 40) addView(text(tr("+${todo.size - 40} more", "+${todo.size - 40} और"), 12f, Ui.MUTED))
            }, lp().margins(this, b = 10))
            list.addView(text(tr("Items not counted keep their last count in Current Stock.", "जो आइटम नहीं गिने, उनकी पिछली गिनती Current Stock में रहेगी।"),
                12f, Ui.TEXT) {
                background = rounded(0xFFFFF8EA.toInt(), dpf(12f)); setPadding(dp(12), dp(10), dp(12), dp(10))
            }, lp().margins(this, b = 10))
        }
        submitBtn.isEnabled = counted.isNotEmpty()
        submitBtn.alpha = if (counted.isNotEmpty()) 1f else 0.5f
    }

    private fun section(t: String) = text(t, 11.5f, Ui.MUTED, bold = true).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
    }

    private fun kv(k: String, v: String) = horizontal {
        setPadding(0, dp(4), 0, dp(4))
        addView(text(k, 13f, Ui.TEXT), lp(0, WRAP, 1f))
        addView(text(v, 13f, Ui.TEXT, bold = true) { gravity = Gravity.END })
    }

    private fun submit() {
        if (busy) return
        val d = Stock.Draft.get(this) ?: return
        AlertDialog.Builder(this)
            .setMessage(tr("Submit ${d.counts.size} counted items to the office sheet?", "${d.counts.size} गिने गए आइटम ऑफिस शीट में सबमिट करें?"))
            .setPositiveButton(tr("Submit", "सबमिट")) { _, _ ->
                busy = true
                submitBtn.text = tr("Submitting…", "सबमिट हो रहा है…")
                lifecycleScope.launch {
                    try {
                        Stock.submit(this@StockReviewActivity, d)
                        Toast.makeText(this@StockReviewActivity, tr("Stock count submitted ✓", "स्टॉक गिनती सबमिट हो गई ✓"), Toast.LENGTH_LONG).show()
                        startActivity(android.content.Intent(this@StockReviewActivity, StockActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    } catch (e: ApiException) {
                        AlertDialog.Builder(this@StockReviewActivity)
                            .setMessage(e.message + "\n\n" + tr("Your count is still saved on this phone.", "आपकी गिनती इस फ़ोन में सेव है।"))
                            .setPositiveButton("OK", null).show()
                    } finally {
                        busy = false
                        submitBtn.text = tr("Submit count", "गिनती सबमिट करें")
                    }
                }
            }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
    }
}
