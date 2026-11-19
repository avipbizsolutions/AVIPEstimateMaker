package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stock Take home (AVIP Team): stock at a glance, the count in progress, past counts. */
class StockActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var startBtn: android.widget.TextView
    private var error: String? = null
    private var busy = false
    private val df = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
    private val tf = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.isTeam(this)) { finish(); return }
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        Stock.loadCached(this)
        val root = vertical()
        root.addView(topBar(tr("Stock Take", "स्टॉक गिनती"), tr("Godown · ", "गोदाम · ") + Stock.godown))
        list = vertical(12)
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) { load() }, lp(MATCH, 0, 1f))
        startBtn = pillButton(tr("+ Start new count", "+ नई गिनती शुरू करें"), Ui.BRAND, Color.WHITE, 52) { start() }
        root.addView(vertical(12) { addView(startBtn) })
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        try { Stock.refresh(this); error = null } catch (e: ApiException) {
            error = e.message
            if (e.code == "SESSION") { goLogin(this, e.message); return }
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val draft = Stock.Draft.get(this)
        startBtn.text = if (draft != null) tr("Continue counting", "गिनती जारी रखें") else tr("+ Start new count", "+ नई गिनती शुरू करें")
        error?.let { list.addView(text(it, 12.5f, Ui.DANGER), lp().margins(this, b = 8)) }

        // At a glance
        val items = Stock.items()
        val counted = items.filter { Stock.current.containsKey(it.id) }
        val zero = counted.count { (Stock.current[it.id]?.qty ?: 0.0) <= 0.0 }
        val low = counted.count { Stock.isLow(it, Stock.current[it.id]?.qty) }
        list.addView(horizontal {
            if (Session.founder(this@StockActivity)) {
                val v = counted.sumOf { Stock.value(it, Stock.current[it.id]?.qty ?: 0.0) }
                addView(stat(Fmt.lakh(v), tr("Stock value", "स्टॉक वैल्यू"), Ui.BRAND), lp(0, WRAP, 1f).margins(context, r = 8))
            }
            addView(stat("${counted.size - zero}", tr("Items in stock", "स्टॉक में आइटम"), Ui.BRAND), lp(0, WRAP, 1f).margins(context, r = 8))
            addView(stat("$zero", tr("Zero stock", "शून्य स्टॉक"), Ui.DANGER), lp(0, WRAP, 1f).margins(context, r = if (low > 0) 8 else 0))
            if (low > 0) addView(stat("$low", tr("Below min", "न्यूनतम से कम"), 0xFFB7791F.toInt()), lp(0, WRAP, 1f))
        }, lp().margins(this, b = 10))

        // Count in progress
        val other = Stock.serverDraft?.takeIf { Stock.draftIsOthers(this) }
        if (draft != null) list.addView(card(14) {
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(tr("Count · ", "गिनती · ") + df.format(Date(draft.started)), 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(tag(tr("DRAFT", "ड्राफ्ट"), 0xFFFFF4DF.toInt(), 0xFFB7791F.toInt()))
            })
            addView(text(tr("Started ", "शुरू: ") + tf.format(Date(draft.started)) + " · " + tr("on this phone", "इस फ़ोन पर"), 11.5f, Ui.MUTED))
            addView(progress(draft.counts.size, items.size), lp(MATCH, dp(7)).margins(context, t = 8, b = 4))
            addView(text(tr("${draft.counts.size} of ${items.size} items counted", "${items.size} में से ${draft.counts.size} आइटम गिने"), 11.5f, Ui.MUTED))
            addView(pillButton(tr("Continue counting", "गिनती जारी रखें"), Ui.BRAND, Color.WHITE, 42) { openCount() }, lp().margins(context, t = 10))
            addView(text(tr("Discard this count", "यह गिनती हटाएं"), 12.5f, Ui.DANGER, bold = true) {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
                setOnClickListener { discard() }
            })
        }, lp().margins(this, b = 10))
        else if (other != null) list.addView(card(14) {
            background = rounded(0xFFFFF8EA.toInt(), dpf(16f))
            addView(text("🧮 " + tr("${other.by} is counting now", "${other.by} अभी गिनती कर रहे हैं"), 14f, Ui.TEXT, bold = true))
            addView(text(tr("Started ", "शुरू: ") + tf.format(Date(other.started)) + ". " +
                tr("Wait for them to submit, so counts don't clash.", "उनके सबमिट करने तक रुकें, ताकि गिनती आपस में न टकराए।"), 12f, Ui.MUTED))
        }, lp().margins(this, b = 10))

        list.addView(pillButton(tr("View current stock", "मौजूदा स्टॉक देखें"), Color.WHITE, Ui.BRAND, 46) {
            startActivity(Intent(this, StockCurrentActivity::class.java))
        }.apply { background = rounded(Color.WHITE, dpf(23f), Ui.BRAND, dp(1)) }, lp().margins(this, b = 14))

        list.addView(text(tr("PREVIOUS COUNTS", "पिछली गिनती"), 11.5f, Ui.MUTED, bold = true), lp().margins(this, b = 6))
        if (Stock.history.isEmpty()) list.addView(text(
            if (Stock.loaded) tr("No counts yet. Start the first one.", "अभी कोई गिनती नहीं। पहली गिनती शुरू करें।") else tr("Loading…", "लोड हो रहा है…"),
            13f, Ui.MUTED), lp().margins(this, t = 4))
        Stock.history.forEach { h ->
            list.addView(card(12) {
                addView(horizontal {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(tr("Count · ", "गिनती · ") + df.format(Date(h.date)), 14.5f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                    addView(tag(tr("SUBMITTED", "सबमिट"), 0xFFE8F3EC.toInt(), Ui.BRAND))
                })
                addView(text("${h.by} · " + tr("${h.items} items · ${h.changed} changed", "${h.items} आइटम · ${h.changed} बदले"), 11.5f, Ui.MUTED))
            }, lp().margins(this, b = 8))
        }
        list.addView(text(tr("Set minimum levels in the \"Current Stock\" tab (Min Level column) of the price sheet.",
            "न्यूनतम स्तर प्राइस शीट के \"Current Stock\" टैब (Min Level कॉलम) में डालें।"), 11.5f, Ui.MUTED), lp().margins(this, t = 8, b = 8))
    }

    private fun stat(n: String, label: String, color: Int) = vertical(10) {
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(Color.WHITE, dpf(14f))
        addView(text(n, 18f, color, bold = true) { gravity = Gravity.CENTER })
        addView(text(label, 11f, Ui.MUTED) { gravity = Gravity.CENTER })
    }

    private fun start() {
        if (busy) return
        if (Stock.Draft.get(this) != null) { openCount(); return }
        busy = true
        lifecycleScope.launch {
            try {
                Stock.start(this@StockActivity)
                openCount()
            } catch (e: ApiException) {
                if (e.code == "BUSY" && Session.founder(this@StockActivity)) AlertDialog.Builder(this@StockActivity)
                    .setMessage(e.message + "\n\n" + tr("As a Founder you can start a new count anyway. Their count can then not be submitted.",
                        "फ़ाउंडर के रूप में आप फिर भी नई गिनती शुरू कर सकते हैं। तब उनकी गिनती सबमिट नहीं होगी।"))
                    .setPositiveButton(tr("Start anyway", "फिर भी शुरू करें")) { _, _ ->
                        lifecycleScope.launch {
                            try { Stock.start(this@StockActivity, force = true); openCount() }
                            catch (x: ApiException) { Toast.makeText(this@StockActivity, x.message, Toast.LENGTH_LONG).show() }
                        }
                    }
                    .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
                else AlertDialog.Builder(this@StockActivity).setMessage(e.message).setPositiveButton("OK", null).show()
            } finally { busy = false }
        }
    }

    private fun openCount() = startActivity(Intent(this, StockCountActivity::class.java))

    private fun discard() {
        AlertDialog.Builder(this)
            .setMessage(tr("Discard this count? The numbers typed so far will be removed from this phone.",
                "यह गिनती हटाएं? अब तक लिखी गिनती इस फ़ोन से हट जाएगी।"))
            .setPositiveButton(tr("Discard", "हटाएं")) { _, _ -> lifecycleScope.launch { Stock.cancel(this@StockActivity); render() } }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
    }
}

fun android.content.Context.tag(t: String, bg: Int, fg: Int) = text(t, 10.5f, fg, bold = true) {
    background = rounded(bg, dpf(8f))
    setPadding(dp(7), dp(2), dp(7), dp(2))
}

fun android.content.Context.progress(done: Int, total: Int) = android.widget.FrameLayout(this).apply {
    background = rounded(0xFFDDE6E1.toInt(), dpf(4f))
    addView(android.view.View(context).apply { background = rounded(Ui.BRAND, dpf(4f)) },
        android.widget.FrameLayout.LayoutParams(0, MATCH).also { lpp ->
            post { lpp.width = if (total > 0) width * done.coerceAtMost(total) / total else 0; requestLayout() }
        })
}
