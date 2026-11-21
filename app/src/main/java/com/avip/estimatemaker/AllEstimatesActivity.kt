package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Founders / Sales Managers: every cost estimate and tax invoice made by the AVIP Team.
 * Estimates can be opened, sent, edited or converted into a tax invoice.
 */
class AllEstimatesActivity : AppCompatActivity() {

    private class Est(val o: JSONObject) {
        val number = o.optString("number")
        val createdAt = o.optLong("createdAt")
        val company = o.optString("company")
        val mobile = o.optString("mobile")
        val total = o.optDouble("grandTotal", 0.0)
        val madeBy = o.optString("madeBy")
        val mine = o.optBoolean("mine")
        val device = o.optString("device")
        val items = o.optString("items")
        val doc: EstimateDoc? = o.optString("doc").takeIf { it.startsWith("{") }
            ?.let { runCatching { EstimateDoc.fromJson(JSONObject(it)) }.getOrNull() }
        fun buyer() = doc?.buyer ?: Buyer(company, o.optString("contact"), mobile, o.optString("gst"), "", "")
    }

    private lateinit var list: LinearLayout
    private lateinit var tabs: LinearLayout
    private var showInvoices = false
    private var query = ""
    private var estimates: List<Est>? = null
    private var invoices: List<Invoices.Inv>? = null
    private var error: String? = null
    private val df = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Can.editTeamEstimates(this)) { startActivity(Intent(this, HistoryActivity::class.java)); finish(); return }
        val root = vertical()
        root.addView(topBar(tr("All Cost Estimates", "सभी लागत अनुमान"), tr("Made by the AVIP Team", "AVIP टीम के बनाए")))
        tabs = horizontal { background = rounded(0xFFEFF3F1.toInt(), dpf(22f)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(tabs, lp().margins(this, 12, 10, 12, 0))
        root.addView(input(tr("Search customer, number, product…", "ग्राहक, नंबर, प्रोडक्ट खोजें…")).apply {
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
        list = vertical(12)
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) { load() }, lp(MATCH, 0, 1f))
        setEdgeContent(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val body = JSONObject().put("token", Session.token(this))
            val e = Api.call(this, "allEstimates", body).optJSONArray("estimates")
            estimates = (0 until (e?.length() ?: 0)).mapNotNull { e!!.optJSONObject(it)?.let(::Est) }
            val i = Api.call(this, "invoiceList", JSONObject().put("token", Session.token(this))).optJSONArray("invoices")
            invoices = (0 until (i?.length() ?: 0)).mapNotNull { i!!.optJSONObject(it)?.let { o -> Invoices.Inv(o) } }
            error = null
        } catch (x: ApiException) {
            error = x.message
            if (x.code == "SESSION" || x.code == "NOTEAM") { goLogin(this, x.message); return }
        }
        render()
    }

    private fun tab(label: String, on: Boolean, click: () -> Unit) = text(label, 14f, if (on) Color.WHITE else Ui.TEXT, bold = true) {
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        background = if (on) rounded(Ui.BRAND, dpf(20f)) else null
        setOnClickListener { click() }
    }

    private fun render() {
        tabs.removeAllViews()
        tabs.addView(tab(tr("Cost Estimates", "लागत अनुमान") + (estimates?.let { " (${it.size})" } ?: ""), !showInvoices) { showInvoices = false; render() }, lp(0, WRAP, 1f))
        tabs.addView(tab(tr("Tax Invoices", "टैक्स इनवॉइस") + (invoices?.let { " (${it.size})" } ?: ""), showInvoices) { showInvoices = true; render() }, lp(0, WRAP, 1f))
        list.removeAllViews()
        error?.let { list.addView(text(it, 13f, Ui.DANGER), lp().margins(this, b = 8)) }
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        fun match(vararg s: String) = words.all { w -> s.any { it.lowercase().contains(w) } }
        if (showInvoices) {
            val all = invoices ?: return loading()
            val shown = all.filter { match(it.number, it.company, it.mobile, it.gst, it.estimateNo, it.madeBy) }
            val live = shown.filter { !it.cancelled }
            list.addView(text(tr("${shown.size} invoices · ", "${shown.size} इनवॉइस · ") + Fmt.inr(live.sumOf { it.total }) +
                tr(" · due ", " · बाकी ") + Fmt.inr(live.sumOf { it.balance }), 13.5f, Ui.MUTED), lp().margins(this, b = 8))
            if (shown.isEmpty()) return empty(tr("No tax invoices yet. Open a cost estimate and tap \"Convert to Tax Invoice\".",
                "अभी कोई टैक्स इनवॉइस नहीं। किसी लागत अनुमान को खोलें और \"टैक्स इनवॉइस बनाएं\" दबाएं।"))
            shown.forEach { inv -> list.addView(invoiceCard(inv), lp().margins(this, b = 10)) }
        } else {
            val all = estimates ?: return loading()
            val shown = all.filter { match(it.number, it.company, it.mobile, it.madeBy, it.items) }
            list.addView(text(tr("${shown.size} cost estimates · ", "${shown.size} लागत अनुमान · ") + Fmt.inr(shown.sumOf { it.total }),
                13.5f, Ui.MUTED), lp().margins(this, b = 8))
            if (shown.isEmpty()) return empty(tr("No cost estimates found", "कोई लागत अनुमान नहीं मिला"))
            shown.forEach { e -> list.addView(estimateCard(e), lp().margins(this, b = 10)) }
        }
    }

    private fun loading() { list.addView(text(tr("Loading…", "लोड हो रहा है…"), 15f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40)) }
    private fun empty(t: String) { list.addView(text(t, 14f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40)) }

    private fun invoiceOf(e: Est) = invoices?.firstOrNull { !it.cancelled && it.estimateNo == e.number && it.mobile.takeLast(10) == e.mobile.takeLast(10) }

    private fun estimateCard(e: Est) = card(12) {
        foreground = ripple()
        addView(horizontal {
            addView(vertical {
                addView(text(e.company.ifBlank { "—" }, 15.5f, Ui.TEXT, bold = true))
                addView(text(e.number + (if (e.createdAt > 0) "  ·  " + df.format(Date(e.createdAt)) else ""), 13f, Ui.MUTED))
                addView(text(tr("By ", "द्वारा: ") + e.madeBy.ifBlank { "—" }, 13f, Ui.BRAND_DARK))
                invoiceOf(e)?.let { addView(tag("🧾 " + it.number, 0xFFE8F3EC.toInt(), Ui.BRAND), lp(WRAP, WRAP).margins(context, t = 4)) }
            }, lp(0, WRAP, 1f).margins(context, r = 8))
            addView(text(Fmt.inr(e.total), 16f, Ui.BRAND_DARK, bold = true))
        })
        setOnClickListener { estimateActions(e) }
    }

    private fun invoiceCard(inv: Invoices.Inv) = card(12) {
        foreground = ripple()
        if (inv.cancelled) alpha = 0.6f
        addView(horizontal {
            addView(vertical {
                addView(text(inv.company.ifBlank { "—" }, 15.5f, Ui.TEXT, bold = true))
                addView(text(inv.number + (if (inv.date > 0) "  ·  " + df.format(Date(inv.date)) else ""), 13f, Ui.MUTED))
                addView(horizontal {
                    if (inv.cancelled) addView(tag(tr("CANCELLED", "रद्द"), 0xFFFDECEA.toInt(), Ui.DANGER))
                    else {
                        val (bg, fg) = when (inv.payment) {
                            "Paid" -> 0xFFE8F3EC.toInt() to Ui.BRAND
                            "Partly Paid" -> 0xFFFFF4DF.toInt() to 0xFFB7791F.toInt()
                            else -> 0xFFFDECEA.toInt() to Ui.DANGER
                        }
                        addView(tag(inv.payment.uppercase().ifBlank { "UNPAID" }, bg, fg))
                        if (inv.gst.isNotBlank()) addView(
                            if (inv.irn.isNotBlank()) tag("IRN ✓", 0xFFE8F3EC.toInt(), Ui.BRAND) else tag(tr("IRN PENDING", "IRN बाकी"), 0xFFFFF4DF.toInt(), 0xFFB7791F.toInt()),
                            lp(WRAP, WRAP).margins(context, l = 6))
                    }
                }, lp().margins(context, t = 4))
            }, lp(0, WRAP, 1f).margins(context, r = 8))
            addView(vertical {
                gravity = Gravity.END
                addView(text(Fmt.inr(inv.total), 16f, Ui.BRAND_DARK, bold = true) { gravity = Gravity.END })
                if (!inv.cancelled && inv.balance > 0.5 && inv.received > 0)
                    addView(text(tr("Due ", "बाकी ") + Fmt.inr(inv.balance), 12.5f, Ui.DANGER) { gravity = Gravity.END })
            })
        })
        setOnClickListener { Invoices.actions(this@AllEstimatesActivity, inv) { changed -> replace(changed) } }
    }

    private fun replace(inv: Invoices.Inv) {
        invoices = invoices?.map { if (it.number == inv.number) inv else it }
        render()
    }

    private fun estimateActions(e: Est) {
        val doc = e.doc ?: run {
            Toast.makeText(this, tr("Only the summary of this older estimate was saved, so it can't be opened.",
                "इस पुराने अनुमान का केवल सारांश सेव है, इसलिए इसे खोला नहीं जा सकता।"), Toast.LENGTH_LONG).show(); return
        }
        val buyer = e.buyer()
        val full = doc.copy(buyer = buyer)
        val made = invoiceOf(e)
        val opts = mutableListOf<Pair<String, () -> Unit>>(
            tr("Open PDF", "PDF खोलें") to { Invoices.pdf(this, full) { EstimateStore.open(this, it) } },
            tr("Send to customer on WhatsApp", "ग्राहक को WhatsApp पर भेजें") to {
                Invoices.pdf(this, full) {
                    EstimateStore.whatsapp(this, it, buyer.mobile,
                        "Namaste ${buyer.contact.substringBefore(' ')} ji, sharing AVIP cost estimate ${doc.number} for ${buyer.company}. — ${Session.teamName(this)}, AVIP Business Solutions LLP")
                }
            },
            (if (made != null) tr("Open its tax invoice (${made.number})", "इसका टैक्स इनवॉइस खोलें (${made.number})") else tr("Convert to Tax Invoice", "टैक्स इनवॉइस बनाएं")) to {
                if (made != null) Invoices.actions(this, made) { replace(it) }
                else Invoices.convert(this, doc, buyer) { inv -> invoices = listOf(inv) + (invoices ?: emptyList()); render() }
            },
            tr("Edit this cost estimate", "यह लागत अनुमान बदलें") to { edit(doc, buyer, existing = true, device = if (e.mine) "" else e.device) },
            tr("Edit as new cost estimate", "नए अनुमान के रूप में बदलें") to { edit(doc, buyer, existing = false) },
        )
        AlertDialog.Builder(this).setTitle(e.number).setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    private fun edit(doc: EstimateDoc, buyer: Buyer, existing: Boolean, device: String = "") {
        val go = {
            TeamTarget.set(this, buyer)
            Cart.loadFrom(this, doc, edit = existing, device = device)
            startActivity(Intent(this, EstimateActivity::class.java))
        }
        Cart.load(this)
        if (Cart.count > 0) AlertDialog.Builder(this)
            .setMessage(tr("Replace the items in your current cost estimate?", "मौजूदा अनुमान के आइटम बदल दें?"))
            .setPositiveButton(tr("Replace", "बदलें")) { _, _ -> go() }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
        else go()
    }
}
