package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Review items, choose AVIP quotation or customer estimate, and create the PDF. */
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
                    .setMessage("Remove all items from this estimate?")
                    .setPositiveButton("Clear") { _, _ -> Cart.clear(this@EstimateActivity); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        val root = vertical()
        root.addView(topBar("Your Estimate", "Review and create PDF", right = listOf(clear)))
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

    override fun onPause() {
        super.onPause()
        Cart.save(this)
    }

    private val isCustomer get() = Cart.type == EstimateType.CUSTOMER

    private fun render() {
        body.removeAllViews()

        // Estimate type
        body.addView(text("Who is this estimate for?", 15f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
        body.addView(horizontal {
            addView(typeCard("AVIP Quotation", "Your purchase price from AVIP, with bank details", "kit", !isCustomer) {
                Cart.type = EstimateType.AVIP_QUOTE; Cart.changed(this@EstimateActivity); render()
            }, lp(0, MATCH, 1f).margins(context, r = 6))
            addView(typeCard("My Customer", "Your own estimate with your margin & logo", "service", isCustomer) {
                Cart.type = EstimateType.CUSTOMER; Cart.changed(this@EstimateActivity); render()
            }, lp(0, MATCH, 1f).margins(context, l = 6))
        }, lp().margins(this, b = 16))

        // Items
        body.addView(horizontal {
            addView(text("Items (${Cart.count})", 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            addView(text("+ Add products", 14f, Ui.BRAND, bold = true) {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { startActivity(Intent(this@EstimateActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            })
        }, lp().margins(this, b = 6))

        if (Cart.count == 0) {
            body.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_cart, Ui.MUTED, 40))
                addView(text("No items yet", 16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 8))
                addView(text("Add products from the categories or use the Solar System Builder.", 13f, Ui.MUTED) { gravity = Gravity.CENTER })
                addView(pillButton("Browse Products", Ui.BRAND) {
                    startActivity(Intent(this@EstimateActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }, lp(WRAP, WRAP).margins(context, t = 14))
            })
        }

        val totals = Cart.totals()
        totals.rows.forEach { r -> body.addView(lineCard(r), lp().margins(this, b = 8)) }

        if (isCustomer) {
            body.addView(text("+ Add your own item / charge (installation, transport…)", 14f, Ui.BRAND, bold = true) {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { customDialog() }
            })
            customerSection(totals)
        } else {
            body.addView(card(12) {
                background = rounded(0xFFE8F3EC.toInt(), dpf(14f))
                addView(text("This quotation is addressed to ${Vendor.company(context)} at AVIP B2B rates. To place an order, share the PDF with AVIP on WhatsApp.", 12.5f, Ui.BRAND_DARK))
            }, lp().margins(this, t = 8))
        }
        body.addView(View(this), lp(MATCH, dp(20)))
        renderTotals(totals)
    }

    private fun typeCard(title: String, sub: String, icon: String, selected: Boolean, onClick: () -> Unit): View =
        vertical(12) {
            background = rounded(if (selected) 0xFFE8F3EC.toInt() else Color.WHITE, dpf(14f),
                if (selected) Ui.BRAND else Ui.LINE, dp(if (selected) 2 else 1))
            addView(horizontal {
                addView(iconBadge(icon, 30))
                addView(text(if (selected) "✓" else "", 18f, Ui.BRAND, bold = true) { gravity = Gravity.END }, lp(0, WRAP, 1f))
            })
            addView(text(title, 14.5f, Ui.TEXT, bold = true), lp().margins(context, t = 8))
            addView(text(sub, 11.5f, Ui.MUTED), lp().margins(context, t = 2))
            setOnClickListener { onClick() }
        }

    private fun lineCard(r: Row): View {
        val l = r.line
        return card(12) {
            addView(horizontal {
                gravity = Gravity.TOP
                addView(iconBadge(l.icon, 38, square = true))
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
            if (l.custom) setOnClickListener { customDialog(l) }
        }
    }

    private fun customerSection(totals: Totals) {
        body.addView(text("Customer details", 15f, Ui.TEXT, bold = true), lp().margins(this, t = 14, b = 8))
        body.addView(card {
            addView(bound("Customer name", Cart.customerName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { Cart.customerName = it })
            addView(bound("Customer mobile", Cart.customerPhone, InputType.TYPE_CLASS_PHONE) { Cart.customerPhone = it }, lp().margins(context, t = 10))
            addView(bound("Site address", Cart.customerAddress, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { Cart.customerAddress = it }, lp().margins(context, t = 10))
        })

        // Margin
        body.addView(text("Your margin on AVIP rates", 15f, Ui.TEXT, bold = true), lp().margins(this, t = 16, b = 8))
        body.addView(card {
            addView(text("Added to every product rate. Your own items are not changed.", 12f, Ui.MUTED), lp().margins(context, b = 10))
            addView(horizontal {
                listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0).forEach { m ->
                    val sel = Cart.marginPct == m
                    addView(text("${Fmt.num(m)}%", 13.5f, if (sel) Color.WHITE else Ui.TEXT, bold = sel) {
                        gravity = Gravity.CENTER
                        background = rounded(if (sel) Ui.BRAND else Color.WHITE, dpf(16f), Ui.LINE, if (sel) 0 else dp(1))
                        setPadding(0, dp(8), 0, dp(8))
                        setOnClickListener { Cart.marginPct = m; Cart.changed(this@EstimateActivity); render() }
                    }, lp(0, WRAP, 1f).margins(context, r = 6))
                }
            })
            addView(horizontal {
                addView(text("Custom %", 13f, Ui.MUTED), lp(0, WRAP, 1f))
                val box = input("0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.num(Cart.marginPct))
                box.setOnFocusChangeListener { _, has ->
                    if (!has) {
                        val v = box.text.toString().toDoubleOrNull() ?: 0.0
                        if (v != Cart.marginPct) { Cart.marginPct = v.coerceIn(0.0, 500.0); Cart.changed(this@EstimateActivity); render() }
                    }
                }
                box.setOnEditorActionListener { v, _, _ -> v.clearFocus(); false }
                addView(box, lp(dp(100), WRAP))
            }, lp().margins(context, t = 10))
        })

        // Options
        body.addView(text("PDF options", 15f, Ui.TEXT, bold = true), lp().margins(this, t = 16, b = 8))
        body.addView(card {
            addView(switchRow("Show item-wise rates", "Off = show only quantities and the total", Cart.showRates) {
                Cart.showRates = it; Cart.changed(this@EstimateActivity)
            })
            if (totals.kwp > 0) {
                val auto = Calc.subsidy(totals.kwp, Catalog.settings)
                addView(switchRow("Show PM Surya Ghar subsidy", "Auto for ${Fmt.num(totals.kwp)} kWp: ${Fmt.inr(auto)} (editable)", Cart.showSubsidy) {
                    Cart.showSubsidy = it; Cart.changed(this@EstimateActivity); render()
                }, lp().margins(context, t = 10))
                if (Cart.showSubsidy) {
                    val box = input("Subsidy amount", InputType.TYPE_CLASS_NUMBER, Fmt.num(Cart.subsidyOverride ?: auto))
                    box.addTextChangedListener(watcher { s -> Cart.subsidyOverride = s.toDoubleOrNull(); Cart.save(this@EstimateActivity); renderTotals(Cart.totals()) })
                    addView(box, lp().margins(context, t = 8))
                }
            }
            addView(text("Terms / notes on PDF", 13f, Ui.TEXT, bold = true), lp().margins(context, t = 14, b = 6))
            val notes = input("e.g. Installation within 7 days of advance. Net-metering by customer.", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, Cart.notes).apply {
                minLines = 3; gravity = Gravity.TOP
            }
            notes.addTextChangedListener(watcher { Cart.notes = it; Cart.save(this@EstimateActivity) })
            addView(notes)
        })
        if (Vendor.logo(this) == null) {
            body.addView(text("Tip: add your logo in My Business Details to show it on the PDF.", 12f, Ui.MUTED) {
                setOnClickListener { startActivity(Intent(this@EstimateActivity, ProfileActivity::class.java)) }
            }, lp().margins(this, t = 10))
        }
    }

    private fun bound(hint: String, value: String, type: Int, set: (String) -> Unit): EditText =
        input(hint, type, value).apply {
            addTextChangedListener(watcher { set(it.trim()); Cart.save(this@EstimateActivity) })
        }

    private fun watcher(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = after(s?.toString() ?: "")
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View = horizontal {
        addView(vertical {
            addView(text(title, 14f, Ui.TEXT, bold = true))
            addView(text(sub, 11.5f, Ui.MUTED))
        }, lp(0, WRAP, 1f))
        addView(SwitchCompat(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    private fun renderTotals(t: Totals) {
        totalsBox.removeAllViews()
        totalsBox.visibility = if (Cart.count == 0) View.GONE else View.VISIBLE
        fun row(label: String, value: String, strong: Boolean = false) = horizontal {
            addView(text(label, if (strong) 15f else 13f, if (strong) Ui.TEXT else Ui.MUTED, strong), lp(0, WRAP, 1f))
            addView(text(value, if (strong) 19f else 13f, if (strong) Ui.BRAND_DARK else Ui.TEXT, true))
        }
        if (t.kwp > 0) totalsBox.addView(row("System size", "${Fmt.num(t.kwp)} kWp"))
        totalsBox.addView(row("Sub total" + if (isCustomer && Cart.marginPct > 0) " (incl. ${Fmt.num(Cart.marginPct)}% margin)" else "", Fmt.inr(t.subTotal, true)))
        totalsBox.addView(row("GST", Fmt.inr(t.gstTotal, true)))
        totalsBox.addView(row("Grand total", Fmt.inr(t.grandTotal), strong = true), lp().margins(this, t = 4))
        if (isCustomer && Cart.showSubsidy && t.kwp > 0) {
            val sub = Cart.subsidyOverride ?: Calc.subsidy(t.kwp, Catalog.settings)
            totalsBox.addView(row("After subsidy (${Fmt.inr(sub)})", Fmt.inr(t.grandTotal - sub)))
        }
        totalsBox.addView(pillButton("Create PDF Estimate", Ui.BRAND, Color.WHITE, 52) { createPdf() }, lp().margins(this, t = 10))
    }

    private fun customDialog(existing: Line? = null) {
        val name = input("Item or charge, e.g. Installation & commissioning", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.name ?: "")
        val price = input("Rate (₹, before GST)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, existing?.let { Fmt.num(it.price) } ?: "")
        val qty = input("Quantity", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, existing?.let { Fmt.qty(it.qty) } ?: "1")
        val unit = input("Unit, e.g. Job, kW, Nos", InputType.TYPE_CLASS_TEXT, existing?.unit ?: "Job")
        val gst = input("GST %", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, existing?.let { Fmt.num(it.gst) } ?: "18")
        val form = vertical(20) {
            listOf(name, price, qty, unit, gst).forEachIndexed { i, v -> addView(v, lp().margins(context, t = if (i == 0) 0 else 8)) }
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add your own item" else "Edit item")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val p = price.text.toString().toDoubleOrNull()
                if (n.isBlank() || p == null) { Toast.makeText(this, "Enter a name and rate", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val q = qty.text.toString().toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                val g = gst.text.toString().toDoubleOrNull() ?: 0.0
                val u = unit.text.toString().trim().ifBlank { "Job" }
                if (existing == null) Cart.addCustom(this, n, u, p, g, q)
                else { existing.name = n; existing.price = p; existing.qty = q; existing.gst = g; existing.unit = u; Cart.changed(this) }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Delete") { _, _ -> Cart.remove(this, existing); render() }
        b.show()
    }

    private fun createPdf() {
        currentFocus?.clearFocus()
        if (Cart.count == 0) return
        if (isCustomer && Cart.customerName.isBlank()) {
            Toast.makeText(this, "Enter the customer's name", Toast.LENGTH_SHORT).show(); return
        }
        val t = Cart.totals()
        val subsidy = if (isCustomer && Cart.showSubsidy && t.kwp > 0) (Cart.subsidyOverride ?: Calc.subsidy(t.kwp, Catalog.settings)) else 0.0
        val doc = EstimateDoc(
            number = EstimateStore.nextNumber(this, Cart.type),
            createdAt = System.currentTimeMillis(),
            type = Cart.type,
            customerName = Cart.customerName,
            customerPhone = Cart.customerPhone,
            customerAddress = Cart.customerAddress,
            marginPct = if (isCustomer) Cart.marginPct else 0.0,
            showRates = Cart.showRates,
            showSubsidy = subsidy > 0,
            subsidy = subsidy,
            notes = Cart.notes,
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
                .put("type", if (isCustomer) "Customer Estimate" else "AVIP Quotation")
                .put("company", Vendor.company(this@EstimateActivity))
                .put("mobile", Vendor.mobile(this@EstimateActivity))
                .put("customer", doc.customerName)
                .put("items", doc.lines.joinToString("; ") { "${it.name} x ${Fmt.qty(it.qty)}" })
                .put("subTotal", t.subTotal).put("gst", t.gstTotal).put("grandTotal", t.grandTotal)
                .put("installId", Vendor.installId(this@EstimateActivity)))
            showDone(doc, file)
        }
    }

    private fun showDone(doc: EstimateDoc, file: File) {
        val isAvip = doc.type == EstimateType.AVIP_QUOTE
        val msg = if (isAvip) "Quotation ${doc.number} from AVIP Business Solutions LLP for ${Vendor.company(this)}"
        else "Solar estimate ${doc.number} from ${Vendor.company(this)}"
        val sheet = vertical(20) {
            addView(iconBadge("kit", 54).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
            addView(text("PDF ready!", 20f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 10))
            addView(text("${doc.number} · ${Fmt.inr(doc.grandTotal)}", 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, b = 14))
        }
        val dialog = AlertDialog.Builder(this).setView(sheet).create()
        sheet.addView(pillButton("Open PDF", Ui.BRAND) { EstimateStore.open(this, file) }, lp().margins(this, b = 8))
        if (isAvip) {
            sheet.addView(pillButton("Send to AVIP on WhatsApp", 0xFF25D366.toInt()) {
                EstimateStore.whatsapp(this, file, Catalog.settings["whatsapp"], "$msg. Please confirm availability.")
            }, lp().margins(this, b = 8))
        } else if (doc.customerPhone.filter { it.isDigit() }.length >= 10) {
            val phone = doc.customerPhone.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
            sheet.addView(pillButton("WhatsApp to Customer", 0xFF25D366.toInt()) {
                EstimateStore.whatsapp(this, file, phone, msg)
            }, lp().margins(this, b = 8))
        }
        sheet.addView(pillButton("Share…", 0xFFEFF3F1.toInt(), Ui.TEXT) { EstimateStore.share(this, file, msg) }, lp().margins(this, b = 8))
        sheet.addView(pillButton("New estimate", Color.WHITE, Ui.BRAND) {
            Cart.clear(this); dialog.dismiss(); render()
        }.apply { background = rounded(Color.WHITE, dpf(24f), Ui.BRAND, dp(1)) })
        dialog.show()
    }
}
