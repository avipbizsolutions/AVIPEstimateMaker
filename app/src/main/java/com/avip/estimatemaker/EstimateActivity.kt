package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

/** Review items and create the AVIP cost estimate PDF. */
class EstimateActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var totalsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)

        val clear = text(tr("Clear", "हटाएं"), 14f, Color.WHITE, bold = true) {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                if (Cart.count == 0) return@setOnClickListener
                AlertDialog.Builder(this@EstimateActivity)
                    .setMessage(tr("Remove all items from this cost estimate?", "इस अनुमान से सभी आइटम हटाएं?"))
                    .setPositiveButton(tr("Clear", "हटाएं")) { _, _ -> Cart.clear(this@EstimateActivity); render() }
                    .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
            }
        }
        val root = vertical()
        root.addView(topBar(tr("Your Cost Estimate", "आपका लागत अनुमान"), tr("AVIP B2B rates for ", "AVIP B2B रेट: ") + buyer().company, right = listOf(clear)))
        body = vertical(14)
        root.addView(pullToRefresh(ScrollView(this).apply { addView(body) }) { Catalog.refresh(this); render() }, lp(MATCH, 0, 1f))
        totalsBox = vertical { setPadding(dp(16), dp(12), dp(16), dp(14)); setBackgroundColor(Color.WHITE); elevation = dpf(10f) }
        root.addView(totalsBox)
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        if (team && TeamTarget.get(this) == null) {
            startActivity(Intent(this, SaathiListActivity::class.java).putExtra(SaathiListActivity.EXTRA_PICK, true))
            finish(); return
        }
        render()
    }

    private val team get() = Session.isTeam(this)

    /** Who the estimate is for: the Saathi, or the B2B customer the team member picked. */
    private fun buyer(): Buyer = if (team) TeamTarget.get(this) ?: Buyer("—", "", "", "", "", "") else Buyer.ofSaathi(this)

    private fun goShopping() {
        startActivity(Intent(this, CatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun render() {
        body.removeAllViews()

        if (team) {
            val b = buyer()
            body.addView(card(12) {
                background = rounded(0xFFE8F0FB.toInt(), dpf(14f))
                addView(horizontal {
                    addView(vertical {
                        addView(text(tr("FOR B2B CUSTOMER", "B2B ग्राहक के लिए"), 10.5f, Ui.MUTED, bold = true))
                        addView(text(b.company, 15.5f, Ui.TEXT, bold = true))
                        addView(text(listOf(b.contact, b.mobile, b.city).filter { it.isNotBlank() }.joinToString(" · "), 12f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(tr("Change", "बदलें"), 13.5f, Ui.BRAND, bold = true) {
                        setPadding(dp(10), dp(8), dp(4), dp(8))
                        setOnClickListener {
                            startActivity(Intent(this@EstimateActivity, SaathiListActivity::class.java).putExtra(SaathiListActivity.EXTRA_PICK, true))
                        }
                    })
                })
            }, lp().margins(this, b = 12))
        }

        body.addView(horizontal {
            addView(text(tr("Items", "आइटम") + " (${Cart.count})", 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            addView(text(tr("+ Add products", "+ प्रोडक्ट जोड़ें"), 14f, Ui.BRAND, bold = true) {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { goShopping() }
            })
        }, lp().margins(this, b = 6))

        if (Cart.count == 0) {
            body.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_cart, Ui.MUTED, 40))
                addView(text(tr("No items yet", "अभी कोई आइटम नहीं"), 16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 8))
                addView(text(tr("Add products from the categories to build your cost estimate.", "लागत अनुमान बनाने के लिए कैटेगरी से प्रोडक्ट जोड़ें।"), 13f, Ui.MUTED) { gravity = Gravity.CENTER })
                addView(pillButton(tr("Browse Products", "प्रोडक्ट देखें"), Ui.BRAND) { goShopping() }, lp(WRAP, WRAP).margins(context, t = 14))
            })
        }

        val totals = Cart.totals()
        totals.rows.forEach { r -> body.addView(lineCard(r), lp().margins(this, b = 8)) }

        if (Cart.count > 0) {
            body.addView(card(12) {
                background = rounded(0xFFE8F3EC.toInt(), dpf(14f))
                addView(text(if (team) tr("Tap a rate to give a special rate. The PDF goes to ${buyer().company} with your name as \"Prepared by\".",
                    "विशेष रेट देने के लिए रेट पर टैप करें। PDF ${buyer().company} को आपके नाम (\"Prepared by\") के साथ जाएगा।")
                else tr("This cost estimate is addressed to ${Vendor.company(context)} at AVIP B2B rates. To place the order, send the PDF to AVIP on WhatsApp.", "यह लागत अनुमान ${Vendor.company(context)} के लिए AVIP B2B रेट पर है। ऑर्डर देने के लिए PDF को WhatsApp पर AVIP को भेजें।"), 12.5f, Ui.BRAND_DARK))
            }, lp().margins(this, t = 8))
        }
        body.addView(View(this), lp(MATCH, dp(20)))
        renderTotals(totals)
    }

    private fun lineCard(r: Row): View {
        val l = r.line
        return card(12) {
            addView(horizontal {
                gravity = Gravity.TOP
                addView(productPhoto(Catalog.byId(l.productId), l.icon, 48))
                addView(vertical {
                    addView(text(l.name, 14.5f, Ui.TEXT, bold = true))
                    val sub = listOf(l.spec, "GST ${Fmt.num(l.gst)}%").filter { it.isNotBlank() }.joinToString(" · ")
                    addView(text(sub, 12f, Ui.MUTED))
                    addView(horizontal {
                        addView(text("${Fmt.inr(r.rate, r.rate < 100)} / ${l.unit.lowercase()}" +
                            (if (l.wp > 0) "  (₹${Fmt.num(round2(r.rate / l.wp))}/Wp)" else ""), 12.5f, if (l.special) 0xFFB26A00.toInt() else Ui.TEXT, bold = l.special))
                        if (l.special) addView(text(tr("Special rate", "विशेष रेट"), 10.5f, 0xFFB26A00.toInt(), bold = true) {
                            background = rounded(0xFFFFF4E0.toInt(), dpf(8f))
                            setPadding(dp(6), dp(2), dp(6), dp(2))
                        }, lp(WRAP, WRAP).margins(context, l = 6))
                        if (team) addView(text(tr("✎ Rate", "✎ रेट"), 12.5f, Ui.BRAND, bold = true) {
                            setPadding(dp(10), dp(2), dp(4), dp(2))
                        })
                        if (team) setOnClickListener { specialRate(l) }
                    }, lp().margins(context, t = 2))
                }, lp(0, WRAP, 1f).margins(context, l = 10))
                addView(icon(R.drawable.ic_delete, Ui.DANGER, 36).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { removeWithUndo(l) }
                })
            })
            addView(horizontal {
                addView(horizontal {
                    background = rounded(0xFFE8F3EC.toInt(), dpf(18f))
                    addView(icon(R.drawable.ic_minus, Ui.BRAND, 36).apply {
                        setPadding(dp(9), dp(9), dp(9), dp(9))
                        setOnClickListener {
                            // Pallets step a whole pallet at a time.
                            val next = l.qty - (if (l.minQty > 1) l.minQty else 1.0)
                            if (next < maxOf(l.minQty, 1.0)) return@setOnClickListener
                            l.qty = next; Cart.changed(this@EstimateActivity); render()
                        }
                    })
                    addView(text(Fmt.qty(l.qty), 15f, Ui.BRAND_DARK, bold = true) {
                        gravity = Gravity.CENTER; minWidth = dp(44)
                        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                        setOnClickListener {
                            askQty(this@EstimateActivity, l.name, l.unit, l.qty) { v ->
                                if (v <= 0) removeWithUndo(l)
                                else { l.qty = maxOf(v, l.minQty); Cart.changed(this@EstimateActivity); render() }
                            }
                        }
                    })
                    addView(icon(R.drawable.ic_plus, Ui.BRAND, 36).apply {
                        setPadding(dp(9), dp(9), dp(9), dp(9))
                        setOnClickListener { l.qty += (if (l.minQty > 1) l.minQty else 1.0); Cart.changed(this@EstimateActivity); render() }
                    })
                })
                addView(text(l.unit, 12f, Ui.MUTED), lp(0, WRAP, 1f).margins(context, l = 8))
                addView(text(Fmt.inr(r.amount, true), 16f, Ui.BRAND_DARK, bold = true))
            }, lp().margins(context, t = 8))
        }
    }

    /** Removes a line, with a few seconds to undo. */
    private fun removeWithUndo(l: Line) {
        val at = Cart.lines.indexOf(l)
        Cart.remove(this, l)
        render()
        com.google.android.material.snackbar.Snackbar
            .make(findViewById(android.R.id.content), tr("Removed ", "हटाया: ") + l.name, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(tr("UNDO", "वापस लाएं")) {
                if (Cart.lines.none { it.key == l.key }) {
                    Cart.lines.add(at.coerceIn(0, Cart.lines.size), l)
                    Cart.changed(this)
                    render()
                }
            }
            .setActionTextColor(Ui.SUN)
            .show()
    }

    private fun renderTotals(t: Totals) {
        totalsBox.removeAllViews()
        totalsBox.visibility = if (Cart.count == 0) View.GONE else View.VISIBLE
        fun row(label: String, value: String, strong: Boolean = false) = horizontal {
            addView(text(label, if (strong) 15f else 13f, if (strong) Ui.TEXT else Ui.MUTED, strong), lp(0, WRAP, 1f))
            addView(text(value, if (strong) 19f else 13f, if (strong) Ui.BRAND_DARK else Ui.TEXT, true))
        }
        if (t.kwp > 0) totalsBox.addView(row(tr("Panels total", "कुल पैनल"), "${Fmt.num(t.kwp)} kWp"))
        totalsBox.addView(row(tr("Sub total", "उप-योग"), Fmt.inr(t.subTotal, true)))
        totalsBox.addView(row("GST", Fmt.inr(t.gstTotal, true)))
        totalsBox.addView(row(tr("Grand total", "कुल राशि"), Fmt.inr(t.grandTotal), strong = true), lp().margins(this, t = 4))
        totalsBox.addView(pillButton(tr("Create Cost Estimate PDF", "लागत अनुमान PDF बनाएं"), Ui.BRAND, Color.WHITE, 52) { createPdf() }, lp().margins(this, t = 10))
    }

    /** AVIP Team: set a special rate on one line (or go back to the sheet rate). */
    private fun specialRate(l: Line) {
        // Solar panels are priced per Wp: type the ₹/Wp rate, the panel price follows.
        val perWp = l.wp > 0
        val product = Catalog.byId(l.productId)
        val sheet = if (perWp) product?.takeIf { it.perWp }?.rate else product?.unitPrice
        val current = if (perWp) round2(l.price / l.wp) else l.price
        val box = input(
            if (perWp) tr("Rate per Wp before GST", "प्रति Wp रेट (GST से पहले)")
            else tr("Rate per ${l.unit.lowercase()} before GST", "प्रति ${l.unit.lowercase()} रेट (GST से पहले)"),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.num(current))
        val preview = text("", 13f, Ui.BRAND_DARK, bold = true)
        fun showPreview() {
            val v = box.text.toString().toDoubleOrNull()
            preview.text = if (!perWp || v == null || v <= 0) ""
            else "= ${Fmt.inr(round2(v * l.wp), true)} " + tr("per panel", "प्रति पैनल") + " (${Fmt.num(l.wp)} Wp)" +
                (if (l.qty > 1) "  ·  ${Fmt.inr(round2(v * l.wp) * l.qty, true)} " + tr("for ${Fmt.qty(l.qty)} panels", "${Fmt.qty(l.qty)} पैनल के") else "")
        }
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = showPreview()
        })
        val form = vertical(18) {
            addView(text(l.name, 14f, Ui.TEXT, bold = true))
            if (sheet != null) addView(text(tr("Sheet rate: ", "शीट रेट: ") + Fmt.inr(sheet, true) + (if (perWp) " / Wp" else ""), 12.5f, Ui.MUTED), lp().margins(context, b = 8))
            addView(box)
            if (perWp) addView(preview, lp().margins(context, t = 8))
        }
        showPreview()
        val d = AlertDialog.Builder(this).setTitle(tr("Special rate", "विशेष रेट")).setView(form)
            .setPositiveButton(tr("Apply", "लागू करें"), null)
            .setNegativeButton(tr("Cancel", "रद्द करें"), null)
            .apply {
                if (l.special) setNeutralButton(tr("Use sheet rate", "शीट रेट रखें")) { _, _ ->
                    l.special = false; Cart.reprice(this@EstimateActivity); Cart.changed(this@EstimateActivity); render()
                }
            }.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = box.text.toString().toDoubleOrNull()
                if (v == null || v <= 0) { box.error = tr("Enter a rate", "रेट लिखें"); return@setOnClickListener }
                l.price = if (perWp) round2(v * l.wp) else v
                l.special = sheet == null || kotlin.math.abs(v - sheet) > 0.004
                Cart.changed(this); render(); d.dismiss()
            }
        }
        d.show()
    }

    private fun createPdf() {
        if (Cart.count == 0) return
        val t = Cart.totals()
        val b = buyer()
        val doc = EstimateDoc(
            number = EstimateStore.nextNumber(this),
            createdAt = System.currentTimeMillis(),
            lines = Cart.lines.map { it.copy() },
            grandTotal = t.grandTotal,
            buyer = b,
            madeBy = if (team) Session.teamName(this) else "",
        )
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { EstimateStore.save(this@EstimateActivity, doc) }
            } catch (e: Exception) {
                Toast.makeText(this@EstimateActivity, tr("Couldn't create PDF: ", "PDF नहीं बना: ") + e.message, Toast.LENGTH_LONG).show(); return@launch
            }
            Remote.queue(this@EstimateActivity, JSONObject()
                .put("kind", "estimate")
                .put("number", doc.number)
                .put("company", b.company)
                .put("contact", b.contact)
                .put("mobile", b.mobile)
                .put("gst", b.gst)
                .put("teamToken", if (team) Session.token(this@EstimateActivity) else "")
                .put("special", doc.lines.filter { it.special }.joinToString("; ") { "${it.name} @ ${Fmt.num(it.price)}" })
                .put("items", doc.lines.joinToString("; ") { "${it.name} ${it.spec} x ${Fmt.qty(it.qty)}".replace("  ", " ") })
                .put("subTotal", t.subTotal).put("gstAmount", t.gstTotal).put("grandTotal", t.grandTotal)
                .put("installId", Vendor.installId(this@EstimateActivity)))
            showDone(doc, file)
        }
    }

    private fun showDone(doc: EstimateDoc, file: File) {
        val b = doc.buyer ?: Buyer.ofSaathi(this)
        val msg = "Cost estimate ${doc.number} from AVIP Business Solutions LLP for ${b.company}"
        val sheet = vertical(20) {
            addView(iconBadge("kit", 54).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
            addView(text(tr("Cost estimate ready!", "लागत अनुमान तैयार!"), 20f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 10))
            addView(text("${doc.number} · ${Fmt.inr(doc.grandTotal)}", 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, b = 14))
        }
        val dialog = AlertDialog.Builder(this).setView(sheet).create()
        sheet.addView(pillButton(tr("Open PDF", "PDF खोलें"), Ui.BRAND) { EstimateStore.open(this, file) }, lp().margins(this, b = 8))
        if (team) sheet.addView(pillButton(tr("Send to customer on WhatsApp", "ग्राहक को WhatsApp पर भेजें"), 0xFF25D366.toInt()) {
            EstimateStore.whatsapp(this, file, b.mobile,
                "Namaste ${b.contact.substringBefore(' ')} ji, sharing AVIP cost estimate ${doc.number} for ${b.company}. — ${Session.teamName(this)}, AVIP Business Solutions LLP")
        }, lp().margins(this, b = 8))
        else sheet.addView(pillButton(tr("Send to AVIP on WhatsApp", "WhatsApp पर AVIP को भेजें"), 0xFF25D366.toInt()) {
            EstimateStore.whatsapp(this, file, Catalog.settings["whatsapp"],
                "Hello AVIP, I am ${Vendor.contact(this)} from ${Vendor.company(this)}. Sharing cost estimate ${doc.number}. I need help regarding order placement.")
        }, lp().margins(this, b = 8))
        sheet.addView(pillButton(tr("Share…", "भेजें…"), 0xFFEFF3F1.toInt(), Ui.TEXT) { EstimateStore.share(this, file, msg) }, lp().margins(this, b = 8))
        sheet.addView(pillButton(tr("New cost estimate", "नया लागत अनुमान"), Color.WHITE, Ui.BRAND) {
            Cart.clear(this); dialog.dismiss()
            if (team) {
                TeamTarget.clear(this)
                startActivity(Intent(this, SaathiListActivity::class.java).putExtra(SaathiListActivity.EXTRA_PICK, true))
                finish()
            } else render()
        }.apply { background = rounded(Color.WHITE, dpf(24f), Ui.BRAND, dp(1)) })
        dialog.show()
    }
}
