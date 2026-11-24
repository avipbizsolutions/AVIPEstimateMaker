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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A submitted stock count, item by item (read-only). */
class StockCountDetailActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "count_id" }

    private enum class Show { ALL, CHANGED, UP, DOWN, NOTES }
    private class Row(val id: String, val category: String, val name: String, val unit: String,
                      val qty: Double, val last: Double?, val diff: Double?, val note: String) {
        val changed get() = diff != null && kotlin.math.abs(diff) > 0.0001
    }

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var subtitle: TextView
    private var rows: List<Row>? = null
    private var by = ""
    private var date = 0L
    private var error: String? = null
    private var show = Show.ALL
    private var query = ""
    private val df = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
    private val countId by lazy { intent.getStringExtra(EXTRA_ID) ?: "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = ImageView(this).apply {
            setImageResource(R.drawable.ic_share)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { shareCsv() }
        }
        val root = vertical()
        val bar = topBar(tr("Stock count", "स्टॉक गिनती"), "…", right = listOf(share))
        root.addView(bar)
        subtitle = findSubtitle(bar)
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
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) { load() }, lp(MATCH, 0, 1f))
        setEdgeContent(root)
        render()
        lifecycleScope.launch { load() }
    }

    private fun findSubtitle(v: android.view.View): TextView {
        val texts = mutableListOf<TextView>()
        fun walk(x: android.view.View) { if (x is TextView) texts += x; if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(v)
        return texts.firstOrNull { it.text.toString() == "…" } ?: TextView(this)
    }

    private suspend fun load() {
        try {
            val o = Api.call(this, "stockCountDetail", JSONObject().put("token", Session.token(this)).put("countId", countId))
            by = o.optString("by"); date = o.optLong("date")
            val arr = o.optJSONArray("items")
            rows = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.map {
                Row(it.optString("id"), it.optString("category"), it.optString("name"), it.optString("unit"), it.optDouble("qty", 0.0),
                    if (it.isNull("last")) null else it.optDouble("last"), if (it.isNull("diff")) null else it.optDouble("diff"), it.optString("note"))
            }
            error = null
        } catch (e: ApiException) {
            error = e.message
            if (e.code == "SESSION" || e.code == "NOTEAM") { goLogin(this, e.message); return }
        }
        render()
    }

    private fun matches(r: Row) = when (show) {
        Show.ALL -> true
        Show.CHANGED -> r.changed
        Show.UP -> (r.diff ?: 0.0) > 0.0001
        Show.DOWN -> (r.diff ?: 0.0) < -0.0001
        Show.NOTES -> r.note.isNotBlank()
    }

    private fun render() {
        val all = rows
        subtitle.text = if (date > 0) df.format(Date(date)) + " · " + by else countId
        chips.removeAllViews()
        listOf(Show.ALL to tr("All (${all?.size ?: 0})", "सभी (${all?.size ?: 0})"),
            Show.CHANGED to tr("Changed (${all?.count { it.changed } ?: 0})", "बदले (${all?.count { it.changed } ?: 0})"),
            Show.UP to tr("Increased", "बढ़े"), Show.DOWN to tr("Decreased", "घटे"),
            Show.NOTES to tr("With notes", "नोट वाले")).forEach { (k, label) ->
            val on = show == k
            chips.addView(text(label, 14.5f, if (on) Color.WHITE else Ui.TEXT, bold = on) {
                background = rounded(if (on) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (on) 0 else dp(1))
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setOnClickListener { show = k; render() }
            }, lp(WRAP, WRAP).margins(this, r = 8))
        }
        list.removeAllViews()
        error?.let { list.addView(text(it, 14f, Ui.DANGER), lp().margins(this, b = 8)) }
        if (all == null) {
            if (error == null) list.addView(text(tr("Loading…", "लोड हो रहा है…"), 16f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40))
            return
        }
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val shown = all.filter { r -> matches(r) && words.all { w -> "${r.category} ${r.name}".lowercase().contains(w) } }
        if (shown.isEmpty()) { list.addView(text(tr("Nothing here", "यहां कुछ नहीं"), 16f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40)); return }

        // Summary
        val up = all.count { (it.diff ?: 0.0) > 0.0001 }
        val down = all.count { (it.diff ?: 0.0) < -0.0001 }
        val first = all.count { it.last == null }
        list.addView(text(tr("${all.size} items counted · $up increased · $down decreased", "${all.size} आइटम गिने · $up बढ़े · $down घटे") +
            if (first > 0) tr(" · $first first time", " · $first पहली बार") else "", 14f, Ui.MUTED), lp().margins(this, b = 8))

        shown.groupBy { it.category }.forEach { (cat, rs) ->
            list.addView(text(cat.uppercase(), 13.5f, Ui.MUTED, bold = true), lp().margins(this, t = 6, b = 6))
            list.addView(card(12) {
                rs.forEachIndexed { i, r ->
                    if (i > 0) addView(android.view.View(context).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, 1).margins(context, t = 10, b = 10))
                    addView(horizontal {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(vertical {
                            addView(text(r.name, 15f, Ui.TEXT, bold = true))
                            addView(text(if (r.last == null) tr("First count", "पहली गिनती") else tr("Before: ", "पहले: ") + Fmt.qty(r.last) + " " + r.unit,
                                13.5f, Ui.MUTED))
                            if (r.note.isNotBlank()) addView(text("📝 " + r.note, 13.5f, Ui.MUTED))
                        }, lp(0, WRAP, 1f).margins(context, r = 8))
                        addView(vertical {
                            gravity = Gravity.END
                            addView(text(Fmt.qty(r.qty) + " " + r.unit, 18f, Ui.TEXT, bold = true) { gravity = Gravity.END })
                            if (r.changed) addView(text((if (r.diff!! > 0) "+" else "") + Fmt.qty(r.diff), 14f,
                                if (r.diff > 0) Ui.BRAND else Ui.DANGER, bold = true) { gravity = Gravity.END })
                            else if (r.last != null) addView(text(tr("same", "वही"), 13.5f, Ui.MUTED) { gravity = Gravity.END })
                        })
                    })
                }
            }, lp().margins(this, b = 10))
        }
    }

    private fun shareCsv() {
        val all = rows ?: return
        val sb = StringBuilder()
        fun csv(vararg v: String) = sb.append(v.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append("\r\n")
        csv("Category", "Product", "Unit", "Counted", "Before", "Difference", "Note")
        all.filter { matches(it) }.forEach { r ->
            csv(r.category, r.name, r.unit, Fmt.qty(r.qty), r.last?.let { Fmt.qty(it) } ?: "", r.diff?.let { Fmt.qty(it) } ?: "", r.note)
        }
        try {
            val f = File(File(cacheDir, "shared").apply { mkdirs() }, "AVIP-Stock-Count-" +
                SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(if (date > 0) date else System.currentTimeMillis())) + ".csv")
            f.writeText("﻿" + sb)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "AVIP stock count " + (if (date > 0) df.format(Date(date)) else "") + " · " + by)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Share stock count", "स्टॉक गिनती भेजें")))
        } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
}
