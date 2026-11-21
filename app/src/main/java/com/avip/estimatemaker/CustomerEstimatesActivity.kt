package com.avip.estimatemaker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AVIP Team: every cost estimate made for one B2B customer, by anyone in the team (from the office log). */
class CustomerEstimatesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BUYER = "buyer"
        fun open(a: android.app.Activity, b: Buyer) =
            a.startActivity(Intent(a, CustomerEstimatesActivity::class.java).putExtra(EXTRA_BUYER, b.toJson().toString()))
    }

    private class Item(val number: String, val createdAt: Long, val grandTotal: Double, val items: String,
                       val madeBy: String, val mine: Boolean, val doc: EstimateDoc?)

    private lateinit var buyer: Buyer
    private lateinit var list: LinearLayout
    private var items: List<Item>? = null
    private var error: String? = null
    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buyer = Buyer.fromJson(runCatching { JSONObject(intent.getStringExtra(EXTRA_BUYER) ?: "") }.getOrNull())
            ?: run { finish(); return }
        val root = vertical()
        root.addView(topBar(buyer.company, if (Can.allEstimates(this)) tr("Cost estimates by the AVIP Team", "AVIP टीम के बनाए लागत अनुमान") else tr("Cost estimates I made", "मेरे बनाए लागत अनुमान")))
        list = vertical(14)
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) { load() }, lp(MATCH, 0, 1f))
        if (Can.estimate(this)) root.addView(vertical(12) {
            addView(pillButton(tr("+ New cost estimate", "+ नया लागत अनुमान"), Ui.BRAND, android.graphics.Color.WHITE, 52) { newEstimate() })
        })
        setEdgeContent(root)
        render()
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val o = Api.call(this, "customerEstimates", JSONObject().put("token", Session.token(this)).put("mobile", buyer.mobile))
            val arr = o.optJSONArray("estimates")
            items = (0 until (arr?.length() ?: 0)).mapNotNull { k ->
                val e = arr!!.optJSONObject(k) ?: return@mapNotNull null
                val doc = e.optString("doc").takeIf { it.startsWith("{") }
                    ?.let { runCatching { EstimateDoc.fromJson(JSONObject(it)) }.getOrNull() }
                Item(e.optString("number"), e.optLong("createdAt"), e.optDouble("grandTotal", 0.0), e.optString("items"),
                    e.optString("madeBy"), e.optBoolean("mine"), doc)
            }
            error = null
        } catch (e: ApiException) {
            error = e.message
            if (e.code == "SESSION") { goLogin(this, e.message ?: ""); return }
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val all = items
        if (all == null) {
            list.addView(text(error ?: tr("Loading…", "लोड हो रहा है…"), 15f, if (error != null) Ui.DANGER else Ui.MUTED) { gravity = Gravity.CENTER },
                lp().margins(this, t = 40))
            return
        }
        error?.let { list.addView(text(it, 12.5f, Ui.DANGER), lp().margins(this, b = 8)) }
        if (all.isEmpty()) {
            list.addView(vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_pdf, Ui.MUTED, 48))
                addView(text(tr("No cost estimates yet", "अभी कोई लागत अनुमान नहीं"), 16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 10))
                addView(text(tr("Estimates made for this customer by anyone in the team will appear here.",
                    "इस ग्राहक के लिए टीम में किसी के भी बनाए अनुमान यहां दिखेंगे।"), 13f, Ui.MUTED) { gravity = Gravity.CENTER })
            }, lp().margins(this, t = 60))
            return
        }
        list.addView(text(tr("${all.size} cost estimates · ", "${all.size} लागत अनुमान · ") + Fmt.inr(all.sumOf { it.grandTotal }),
            12.5f, Ui.MUTED), lp().margins(this, b = 8))
        all.forEach { e ->
            list.addView(card(12) {
                foreground = ripple()
                addView(horizontal {
                    addView(iconBadge("kit", 42, square = true))
                    addView(vertical {
                        addView(text(e.number, 15f, Ui.TEXT, bold = true))
                        addView(text(if (e.createdAt > 0) fmt.format(Date(e.createdAt)) else "", 11.5f, Ui.MUTED))
                        addView(text(tr("By ", "द्वारा: ") + e.madeBy.ifBlank { "—" } + if (e.mine) tr(" · this phone", " · यह फ़ोन") else "", 11.5f, Ui.BRAND_DARK))
                        if (e.items.isNotBlank()) addView(text(e.items, 11.5f, Ui.MUTED) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    }, lp(0, WRAP, 1f).margins(context, l = 12))
                    addView(text(Fmt.inr(e.grandTotal), 15f, Ui.BRAND_DARK, bold = true))
                })
                setOnClickListener { actions(e) }
            }, lp().margins(this, b = 10))
        }
    }

    private fun actions(e: Item) {
        val doc = e.doc
        if (doc == null) {
            Toast.makeText(this, tr("Only the summary of this older estimate was saved, so it can't be opened.",
                "इस पुराने अनुमान का केवल सारांश सेव है, इसलिए इसे खोला नहीं जा सकता।"), Toast.LENGTH_LONG).show()
            return
        }
        val opts = mutableListOf(tr("Open PDF", "PDF खोलें"), tr("Send to customer on WhatsApp", "ग्राहक को WhatsApp पर भेजें"))
        if (Can.estimate(this)) {
            if (e.mine) opts += tr("Edit this cost estimate", "यह लागत अनुमान बदलें")
            opts += tr("Edit as new cost estimate", "नए अनुमान के रूप में बदलें")
        }
        AlertDialog.Builder(this).setTitle(e.number).setItems(opts.toTypedArray()) { _, which ->
            when (opts[which]) {
                opts[0] -> withPdf(doc) { EstimateStore.open(this, it) }
                opts[1] -> withPdf(doc) {
                    EstimateStore.whatsapp(this, it, buyer.mobile,
                        "Namaste ${buyer.contact.substringBefore(' ')} ji, sharing AVIP cost estimate ${doc.number} for ${buyer.company}. — ${Session.teamName(this)}, AVIP Business Solutions LLP")
                }
                else -> edit(doc, existing = e.mine && which == 2)
            }
        }.show()
    }

    /** Makes the PDF on this phone from the saved estimate data. */
    private fun withPdf(doc: EstimateDoc, then: (File) -> Unit) {
        lifecycleScope.launch {
            val f = try {
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    File(dir, doc.number.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".pdf")
                        .also { PdfMaker(this@CustomerEstimatesActivity).make(doc.copy(buyer = doc.buyer ?: buyer), it) }
                }
            } catch (x: Exception) {
                Toast.makeText(this@CustomerEstimatesActivity, tr("Couldn't create PDF: ", "PDF नहीं बना: ") + x.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            then(f)
        }
    }

    private fun edit(doc: EstimateDoc, existing: Boolean) {
        val go = {
            TeamTarget.set(this, buyer)
            Cart.loadFrom(this, doc, edit = existing)
            startActivity(Intent(this, EstimateActivity::class.java))
        }
        if (Cart.count > 0) AlertDialog.Builder(this)
            .setMessage(tr("Replace the items in your current cost estimate?", "मौजूदा अनुमान के आइटम बदल दें?"))
            .setPositiveButton(tr("Replace", "बदलें")) { _, _ -> go() }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
        else go()
    }

    private fun newEstimate() {
        Cart.load(this)
        val before = TeamTarget.get(this)
        if (Cart.count > 0 && before?.mobile != buyer.mobile) Cart.clear(this)
        TeamTarget.set(this, buyer)
        startActivity(Intent(this, if (Cart.count > 0) EstimateActivity::class.java else CatalogActivity::class.java))
    }
}
