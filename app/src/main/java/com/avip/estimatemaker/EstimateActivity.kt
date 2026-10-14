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

/** Review items and create the AVIP quotation PDF. */
class EstimateActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var totalsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)

        val clear = text("Clear", 14f, Color.WHITE, bold = true) {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                if (Cart.count == 0) return@setOnClickListener
                AlertDialog.Builder(this@EstimateActivity)
                    .setMessage("Remove all items from this quotation?")
                    .setPositiveButton("Clear") { _, _ -> Cart.clear(this@EstimateActivity); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        val root = vertical()
        root.addView(topBar("Your Quotation", "AVIP B2B rates for ${Vendor.company(this)}", right = listOf(clear)))
        body = vertical(14)
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        totalsBox = vertical { setPadding(dp(16), dp(12), dp(16), dp(14)); setBackgroundColor(Color.WHITE); elevation = dpf(10f) }
        root.addView(totalsBox)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun goShopping() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun render() {
        body.removeAllViews()

        body.addView(horizontal {
            addView(text("Items (${Cart.count})", 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            addView(text("+ Add products", 14f, Ui.BRAND, bold = true) {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { goShopping() }
            })
        }, lp().margins(this, b = 6))

        if (Cart.count == 0) {
            body.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_cart, Ui.MUTED, 40))
                addView(text("No items yet", 16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 8))
                addView(text("Add products from the categories to build your quotation.", 13f, Ui.MUTED) { gravity = Gravity.CENTER })
                addView(pillButton("Browse Products", Ui.BRAND) { goShopping() }, lp(WRAP, WRAP).margins(context, t = 14))
            })
        }

        val totals = Cart.totals()
        totals.rows.forEach { r -> body.addView(lineCard(r), lp().margins(this, b = 8)) }

        if (Cart.count > 0) {
            body.addView(card(12) {
                background = rounded(0xFFE8F3EC.toInt(), dpf(14f))
                addView(text("This quotation is addressed to ${Vendor.company(context)} at AVIP B2B rates. To place the order, send the PDF to AVIP on WhatsApp.", 12.5f, Ui.BRAND_DARK))
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
                    val sub = listOf(l.spec, "${Fmt.inr(r.rate, r.rate < 100)} / ${l.unit.lowercase()}", "GST ${Fmt.num(l.gst)}%")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    addView(text(sub, 12f, Ui.MUTED))
                }, lp(0, WRAP, 1f).margins(context, l = 10))
                addView(icon(R.drawable.ic_delete, Ui.DANGER, 36).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { Cart.remove(this@EstimateActivity, l); render() }
                })
            })
            addView(horizontal {
                addView(horizontal {
                    background = rounded(0xFFE8F3EC.toInt(), dpf(18f))
                    addView(icon(R.drawable.ic_minus, Ui.BRAND, 36).apply {
                        setPadding(dp(9), dp(9), dp(9), dp(9))
                        setOnClickListener {
                            val next = l.qty - 1
                            if (next < maxOf(l.minQty, 1.0)) return@setOnClickListener
                            l.qty = next; Cart.changed(this@EstimateActivity); render()
                        }
                    })
                    addView(text(Fmt.qty(l.qty), 15f, Ui.BRAND_DARK, bold = true) {
                        gravity = Gravity.CENTER; minWidth = dp(40)
                        setOnClickListener {
                            askQty(this@EstimateActivity, l.name, l.unit, l.qty) { v ->
                                if (v <= 0) Cart.remove(this@EstimateActivity, l)
                                else { l.qty = maxOf(v, l.minQty); Cart.changed(this@EstimateActivity) }
                                render()
                            }
                        }
                    })
                    addView(icon(R.drawable.ic_plus, Ui.BRAND, 36).apply {
                        setPadding(dp(9), dp(9), dp(9), dp(9))
                        setOnClickListener { l.qty += 1; Cart.changed(this@EstimateActivity); render() }
                    })
                })
                addView(text(l.unit, 12f, Ui.MUTED), lp(0, WRAP, 1f).margins(context, l = 8))
                addView(text(Fmt.inr(r.amount, true), 16f, Ui.BRAND_DARK, bold = true))
            }, lp().margins(context, t = 8))
        }
    }

    private fun renderTotals(t: Totals) {
        totalsBox.removeAllViews()
        totalsBox.visibility = if (Cart.count == 0) View.GONE else View.VISIBLE
        fun row(label: String, value: String, strong: Boolean = false) = horizontal {
            addView(text(label, if (strong) 15f else 13f, if (strong) Ui.TEXT else Ui.MUTED, strong), lp(0, WRAP, 1f))
            addView(text(value, if (strong) 19f else 13f, if (strong) Ui.BRAND_DARK else Ui.TEXT, true))
        }
        if (t.kwp > 0) totalsBox.addView(row("Panels total", "${Fmt.num(t.kwp)} kWp"))
        totalsBox.addView(row("Sub total", Fmt.inr(t.subTotal, true)))
        totalsBox.addView(row("GST", Fmt.inr(t.gstTotal, true)))
        totalsBox.addView(row("Grand total", Fmt.inr(t.grandTotal), strong = true), lp().margins(this, t = 4))
        totalsBox.addView(pillButton("Create Quotation PDF", Ui.BRAND, Color.WHITE, 52) { createPdf() }, lp().margins(this, t = 10))
    }

    private fun createPdf() {
        if (Cart.count == 0) return
        val t = Cart.totals()
        val doc = EstimateDoc(
            number = EstimateStore.nextNumber(this),
            createdAt = System.currentTimeMillis(),
            lines = Cart.lines.map { it.copy() },
            grandTotal = t.grandTotal,
        )
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { EstimateStore.save(this@EstimateActivity, doc) }
            } catch (e: Exception) {
                Toast.makeText(this@EstimateActivity, "Couldn't create PDF: ${e.message}", Toast.LENGTH_LONG).show(); return@launch
            }
            Remote.queue(this@EstimateActivity, JSONObject()
                .put("kind", "estimate")
                .put("number", doc.number)
                .put("company", Vendor.company(this@EstimateActivity))
                .put("contact", Vendor.contact(this@EstimateActivity))
                .put("mobile", Vendor.mobile(this@EstimateActivity))
                .put("gst", Vendor.gst(this@EstimateActivity))
                .put("items", doc.lines.joinToString("; ") { "${it.name} ${it.spec} x ${Fmt.qty(it.qty)}".replace("  ", " ") })
                .put("subTotal", t.subTotal).put("gstAmount", t.gstTotal).put("grandTotal", t.grandTotal)
                .put("installId", Vendor.installId(this@EstimateActivity)))
            showDone(doc, file)
        }
    }

    private fun showDone(doc: EstimateDoc, file: File) {
        val msg = "Quotation ${doc.number} from AVIP Business Solutions LLP for ${Vendor.company(this)}"
        val sheet = vertical(20) {
            addView(iconBadge("kit", 54).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
            addView(text("Quotation ready!", 20f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 10))
            addView(text("${doc.number} · ${Fmt.inr(doc.grandTotal)}", 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, b = 14))
        }
        val dialog = AlertDialog.Builder(this).setView(sheet).create()
        sheet.addView(pillButton("Open PDF", Ui.BRAND) { EstimateStore.open(this, file) }, lp().margins(this, b = 8))
        sheet.addView(pillButton("Send to AVIP on WhatsApp", 0xFF25D366.toInt()) {
            EstimateStore.whatsapp(this, file, Catalog.settings["whatsapp"],
                "Hello AVIP, I am ${Vendor.contact(this)} from ${Vendor.company(this)}. Sharing quotation ${doc.number}. I need help regarding order placement.")
        }, lp().margins(this, b = 8))
        sheet.addView(pillButton("Share…", 0xFFEFF3F1.toInt(), Ui.TEXT) { EstimateStore.share(this, file, msg) }, lp().margins(this, b = 8))
        sheet.addView(pillButton("New quotation", Color.WHITE, Ui.BRAND) {
            Cart.clear(this); dialog.dismiss(); render()
        }.apply { background = rounded(Color.WHITE, dpf(24f), Ui.BRAND, dp(1)) })
        dialog.show()
    }
}
