package com.avip.estimatemaker

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Make or edit a customer quotation / tax invoice. */
class SaleEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_FROM_QUOTE = "from_quote"
    }

    private lateinit var doc: SaleDoc
    private var autoNumber = true
    private val isInvoice get() = doc.type == SaleType.INVOICE
    private val composition by lazy { Business.composition(this) }

    private lateinit var number: EditText
    private lateinit var dateView: TextView
    private lateinit var validDays: EditText
    private lateinit var cName: EditText
    private lateinit var cMobile: EditText
    private lateinit var cAddress: EditText
    private lateinit var cGstin: EditText
    private lateinit var gstStatus: TextView
    private lateinit var stateView: TextView
    private lateinit var shipTo: EditText
    private lateinit var notes: EditText
    private lateinit var linesBox: LinearLayout
    private lateinit var totalsBox: LinearLayout
    private lateinit var bottomTotal: TextView

    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        val type = runCatching { SaleType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrDefault(SaleType.QUOTE)
        val existing = SalesStore.byId(this, intent.getStringExtra(EXTRA_ID))
        val quote = SalesStore.byId(this, intent.getStringExtra(EXTRA_FROM_QUOTE))
        doc = when {
            existing != null -> { autoNumber = false; existing }
            quote != null -> SaleDoc(
                type = SaleType.INVOICE, number = SalesStore.nextNumber(this, SaleType.INVOICE),
                customer = quote.customer.copy(), shipTo = quote.shipTo, inclusive = quote.inclusive,
                lines = quote.lines.map { it.copy() }.toMutableList(), notes = Business.invoiceTerms(this),
                fromQuote = quote.number,
            )
            else -> SaleDoc(
                type = type, number = SalesStore.nextNumber(this, type),
                customer = Party(stateCode = Business.stateCode(this).ifBlank { "10" }),
                notes = if (type == SaleType.INVOICE) Business.invoiceTerms(this) else Business.quoteTerms(this),
            )
        }

        val root = vertical()
        val title = when {
            !isInvoice -> tr("Customer Quotation", "ग्राहक कोटेशन")
            composition -> tr("Bill of Supply", "बिल ऑफ़ सप्लाई")
            else -> tr("Tax Invoice", "टैक्स इनवॉइस")
        }
        root.addView(topBar(title, if (existing != null) doc.number else tr("New", "नया") + (if (quote != null) tr(" · from ${quote.number}", " · ${quote.number} से") else "")))
        val form = vertical(14)
        root.addView(ScrollView(this).apply { addView(form) }, lp(MATCH, 0, 1f))

        // ---- document details ----
        number = input("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, doc.number).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(16))
            addTextChangedListener(watch { if (it != doc.number) autoNumber = false })
        }
        dateView = text(dateFmt.format(Date(doc.date)), 16f, Ui.TEXT) {
            background = rounded(0xFFF7FAF8.toInt(), dpf(12f), Ui.LINE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { pickDate() }
        }
        validDays = input("15", InputType.TYPE_CLASS_NUMBER, doc.validDays.toString())
        form.addView(card {
            addView(horizontal {
                addView(vertical {
                    addView(label(if (isInvoice) tr("Invoice no.", "इनवॉइस नं.") else tr("Quotation no.", "कोटेशन नं.")))
                    addView(number)
                }, lp(0, WRAP, 1.3f))
                addView(vertical {
                    addView(label(tr("Date", "तारीख")))
                    addView(dateView)
                }, lp(0, WRAP, 1f).margins(context, l = 10))
            })
            if (!isInvoice) {
                addView(label(tr("Valid for (days)", "कितने दिन मान्य")), lp().margins(context, t = 10))
                addView(validDays)
            }
        })

        // ---- customer ----
        cName = input(tr("Customer / company name", "ग्राहक / कंपनी का नाम"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, doc.customer.name)
        cMobile = input(tr("10-digit mobile", "10 अंकों का मोबाइल"), InputType.TYPE_CLASS_PHONE, doc.customer.mobile).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10))
        }
        cAddress = input(tr("Full address with PIN", "पिन सहित पूरा पता"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, doc.customer.address).apply { minLines = 2 }
        cGstin = input(tr("Customer GSTIN (if registered)", "ग्राहक का GSTIN (अगर है)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, doc.customer.gstin).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(15))
        }
        gstStatus = text("", 11.5f, Ui.MUTED)
        cGstin.addTextChangedListener(watch { g ->
            when {
                g.isEmpty() -> { gstStatus.text = tr("Leave blank for a home owner / unregistered customer", "घर मालिक / बिना GST वाले ग्राहक के लिए खाली छोड़ें"); gstStatus.setTextColor(Ui.MUTED) }
                g.length < 15 -> { gstStatus.text = "${g.length}/15"; gstStatus.setTextColor(Ui.MUTED) }
                else -> Gstin.problem(g).let { p ->
                    if (p == null) {
                        gstStatus.text = tr("✓ Valid GSTIN · ", "✓ सही GSTIN · ") + (Gstin.state(g) ?: "")
                        gstStatus.setTextColor(Ui.BRAND)
                        doc.customer.stateCode = g.take(2); showState(); renderTotals()
                    } else { gstStatus.text = "✗ $p"; gstStatus.setTextColor(Ui.DANGER) }
                }
            }
        })
        stateView = text("", 16f, Ui.TEXT) {
            background = rounded(0xFFF7FAF8.toInt(), dpf(12f), Ui.LINE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { pickState() }
        }
        shipTo = input(tr("Site / delivery address (if different)", "साइट / डिलीवरी पता (अगर अलग हो)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, doc.shipTo)
        form.addView(card {
            addView(text(tr("Customer", "ग्राहक"), 15f, Ui.TEXT, bold = true))
            addView(label(tr("Name *", "नाम *")), lp().margins(context, t = 10)); addView(cName)
            addView(label(tr("Mobile", "मोबाइल")), lp().margins(context, t = 10)); addView(cMobile)
            addView(label(if (isInvoice) tr("Billing address *", "बिलिंग पता *") else tr("Address", "पता")), lp().margins(context, t = 10)); addView(cAddress)
            addView(label("GSTIN"), lp().margins(context, t = 10)); addView(cGstin); addView(gstStatus, lp().margins(context, t = 4))
            addView(label(tr("State (place of supply) *", "राज्य (सप्लाई का स्थान) *")), lp().margins(context, t = 10)); addView(stateView)
            addView(label(tr("Site / delivery address", "साइट / डिलीवरी पता")), lp().margins(context, t = 10)); addView(shipTo)
        }, lp().margins(this, t = 12))
        showState()
        cGstin.text = cGstin.text // trigger status

        // ---- items ----
        linesBox = vertical()
        form.addView(card {
            addView(horizontal {
                addView(text(tr("Items", "आइटम"), 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                if (!composition) addView(SwitchCompat(context).apply {
                    text = tr("Rates incl. GST", "रेट GST सहित")
                    textSize = 12.5f
                    isChecked = doc.inclusive
                    setOnCheckedChangeListener { _, on -> doc.inclusive = on; renderLines() }
                })
            })
            addView(linesBox, lp().margins(context, t = 6))
            addView(horizontal {
                addView(pillButton(tr("+ AVIP product", "+ AVIP प्रोडक्ट"), Ui.BRAND, Color.WHITE, 42) { pickProduct() }, lp(0, WRAP, 1f))
                addView(pillButton(tr("+ Other item", "+ अन्य आइटम"), 0xFFEFF3F1.toInt(), Ui.TEXT, 42) {
                    lineDialog(SaleLine("", "", "Nos", 1.0, 0.0, 18.0), isNew = true)
                }, lp(0, WRAP, 1f).margins(context, l = 8))
            }, lp().margins(context, t = 10))
            addView(text(tr("+ Installation charges", "+ इंस्टॉलेशन चार्ज"), 13.5f, Ui.BRAND, bold = true) {
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener {
                    lineDialog(SaleLine(tr("Installation & commissioning of solar system", "Installation & commissioning of solar system"),
                        Hsn.INSTALLATION_SAC, "Job", 1.0, 0.0, 18.0), isNew = true)
                }
            })
        }, lp().margins(this, t = 12))

        totalsBox = vertical()
        form.addView(card { addView(totalsBox) }, lp().margins(this, t = 12))

        notes = input("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, doc.notes).apply { minLines = 3 }
        form.addView(card {
            addView(text(tr("Terms (one per line)", "शर्तें (हर लाइन में एक)"), 15f, Ui.TEXT, bold = true))
            addView(notes, lp().margins(context, t = 8))
        }, lp().margins(this, t = 12, b = 20))

        // ---- bottom bar ----
        bottomTotal = text("", 18f, Ui.BRAND_DARK, bold = true)
        root.addView(horizontal {
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dpf(10f)
            addView(vertical {
                addView(text(tr("Total", "कुल"), 11.5f, Ui.MUTED))
                addView(bottomTotal)
            }, lp(0, WRAP, 1f))
            addView(pillButton(tr("Save & PDF", "सेव करें व PDF"), if (isInvoice) 0xFF6A1B9A.toInt() else 0xFF1565C0.toInt(), Color.WHITE, 50) { save() })
        })
        setEdgeContent(root)
        renderLines()
    }

    // ---------- helpers ----------

    private fun label(t: String) = text(t, 12.5f, Ui.BRAND_DARK, bold = true).apply { setPadding(0, 0, 0, dp(5)) }

    private fun watch(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = after(s.toString().trim())
    }

    private fun showState() {
        stateView.text = "${stateName(doc.customer.stateCode)} (${doc.customer.stateCode})  ▾"
    }

    private fun pickState() {
        val states = Gstin.STATES.entries.sortedBy { it.value }
        AlertDialog.Builder(this)
            .setTitle(tr("Place of supply", "सप्लाई का स्थान"))
            .setItems(states.map { "${it.value} (${it.key})" }.toTypedArray()) { _, i ->
                doc.customer.stateCode = states[i].key; showState(); renderTotals()
            }.show()
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = doc.date }
        DatePickerDialog(this, { _, y, m, d ->
            val fyBefore = SalesStore.fy(doc.date)
            doc.date = Calendar.getInstance().apply { set(y, m, d) }.timeInMillis
            dateView.text = dateFmt.format(Date(doc.date))
            if (autoNumber && SalesStore.fy(doc.date) != fyBefore) {
                doc.number = SalesStore.nextNumber(this, doc.type, doc.date); number.setText(doc.number); autoNumber = true
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis() + 365L * 86_400_000L
        }.show()
    }

    private fun renderLines() {
        linesBox.removeAllViews()
        val t = SaleCalc.compute(doc, Business.stateCode(this), composition)
        if (t.rows.isEmpty()) {
            linesBox.addView(text(tr("No items yet. Add AVIP products with your own selling rate, or any other item.",
                "अभी कोई आइटम नहीं। अपने बिक्री रेट के साथ AVIP प्रोडक्ट या कोई अन्य आइटम जोड़ें।"), 13f, Ui.MUTED), lp().margins(this, t = 6, b = 4))
        }
        t.rows.forEachIndexed { i, r ->
            val l = r.line
            linesBox.addView(horizontal {
                background = rounded(0xFFF7FAF8.toInt(), dpf(12f), Ui.LINE, dp(1))
                setPadding(dp(12), dp(10), dp(6), dp(10))
                foreground = ripple()
                addView(vertical {
                    addView(text(l.name, 14f, Ui.TEXT, bold = true))
                    val gstTxt = if (composition) "" else " · GST ${Fmt.num(l.gst)}%"
                    addView(text("HSN ${l.hsn.ifBlank { "—" }} · ${Fmt.qty(l.qty)} ${l.unit} × ${Fmt.inr(l.rate, true)}$gstTxt", 11.5f, Ui.MUTED))
                }, lp(0, WRAP, 1f))
                addView(text(Fmt.inr(if (composition) r.taxable else r.amount, true), 14f, Ui.BRAND_DARK, bold = true))
                addView(icon(R.drawable.ic_delete, Ui.DANGER, 34).apply {
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    setOnClickListener { doc.lines.removeAt(i); renderLines() }
                })
                setOnClickListener { lineDialog(l, isNew = false) }
            }, lp().margins(this, t = 8))
        }
        renderTotals()
    }

    private fun renderTotals() {
        if (!::totalsBox.isInitialized) return
        val t = SaleCalc.compute(doc, Business.stateCode(this), composition)
        totalsBox.removeAllViews()
        fun row(k: String, v: String, strong: Boolean = false) = totalsBox.addView(horizontal {
            addView(text(k, if (strong) 15f else 13f, if (strong) Ui.TEXT else Ui.MUTED, strong), lp(0, WRAP, 1f))
            addView(text(v, if (strong) 17f else 13f, if (strong) Ui.BRAND_DARK else Ui.TEXT, true))
        }, lp().margins(this, t = 3))
        row(tr("Taxable value", "कर योग्य मूल्य"), Fmt.inr(t.taxable, true))
        if (!composition) {
            if (t.intraState) {
                row("CGST", Fmt.inr(t.cgst, true)); row("SGST", Fmt.inr(t.sgst, true))
            } else row("IGST", Fmt.inr(t.igst, true))
        }
        if (t.roundOff != 0.0) row(tr("Round off", "राउंड ऑफ़"), Fmt.inr(t.roundOff, true))
        row(tr("Total", "कुल"), Fmt.inr(t.grand, true), strong = true)
        totalsBox.addView(text(
            when {
                composition -> tr("Composition scheme: no GST charged (Bill of Supply).", "Composition योजना: GST नहीं लगेगा (Bill of Supply)।")
                t.intraState -> tr("Same state: CGST + SGST", "एक ही राज्य: CGST + SGST")
                else -> tr("Other state: IGST", "दूसरा राज्य: IGST")
            }, 11.5f, Ui.MUTED), lp().margins(this, t = 6))
        if (::bottomTotal.isInitialized) bottomTotal.text = Fmt.inr(t.grand)
    }

    /** AVIP catalog search → pick a product → set your selling rate. */
    private fun pickProduct() {
        val box = input(tr("Search product, brand or size…", "प्रोडक्ट, ब्रांड या साइज़ खोजें…"), InputType.TYPE_CLASS_TEXT)
        val results = vertical()
        val wrap = vertical(16) {
            addView(box)
            addView(ScrollView(context).apply { addView(results) }, lp(MATCH, dp(380)).margins(context, t = 10))
        }
        val dialog = AlertDialog.Builder(this).setTitle(tr("AVIP products", "AVIP प्रोडक्ट")).setView(wrap)
            .setNegativeButton(tr("Close", "बंद करें"), null).create()
        fun show(q: String) {
            results.removeAllViews()
            val list = if (q.isBlank()) Catalog.products.filter { it.available } else Catalog.search(q)
            list.take(80).forEach { p ->
                results.addView(horizontal {
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    foreground = ripple()
                    addView(iconBadge(p.icon, 34, square = true))
                    addView(vertical {
                        addView(text(p.title, 13.5f, Ui.TEXT, bold = true))
                        addView(text(listOf(p.category, p.spec, p.unit).filter { it.isNotBlank() }.joinToString(" · "), 11f, Ui.MUTED))
                    }, lp(0, WRAP, 1f).margins(context, l = 10))
                    setOnClickListener {
                        dialog.dismiss()
                        lineDialog(SaleLine(
                            name = listOf(p.title, p.spec).filter { it.isNotBlank() }.joinToString(" "),
                            hsn = Hsn.forIcon(p.icon), unit = p.unit, qty = p.minQty.toDouble().coerceAtLeast(1.0),
                            rate = 0.0, gst = p.gst, productId = p.id,
                        ), isNew = true, buyRate = p.unitPrice)
                    }
                })
            }
            if (list.isEmpty()) results.addView(text(tr("No products found", "कोई प्रोडक्ट नहीं मिला"), 13f, Ui.MUTED))
        }
        box.addTextChangedListener(watch { show(it) })
        show("")
        dialog.show()
    }

    /** Add or edit one line. [buyRate] = the Surya Saathi's AVIP purchase rate, shown as a hint. */
    private fun lineDialog(line: SaleLine, isNew: Boolean, buyRate: Double? = null) {
        val name = input(tr("Item / service description", "आइटम / सेवा का विवरण"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, line.name)
        val hsn = input("HSN / SAC", InputType.TYPE_CLASS_NUMBER, line.hsn).apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8)) }
        val qty = input(tr("Qty", "मात्रा"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.qty(line.qty))
        val unit = input(tr("Unit", "इकाई"), InputType.TYPE_CLASS_TEXT, line.unit)
        val rate = input(if (doc.inclusive) tr("Rate per unit incl. GST", "प्रति इकाई रेट (GST सहित)") else tr("Rate per unit before GST", "प्रति इकाई रेट (GST से पहले)"),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, if (line.rate > 0) Fmt.num(line.rate) else "")
        val gst = input("GST %", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.num(line.gst))
        val box = vertical(18) {
            addView(label(tr("Description", "विवरण"))); addView(name)
            addView(horizontal {
                addView(vertical { addView(label("HSN / SAC")); addView(hsn) }, lp(0, WRAP, 1f))
                if (!composition) addView(vertical { addView(label("GST %")); addView(gst) }, lp(0, WRAP, 0.7f).margins(context, l = 8))
            }, lp().margins(context, t = 10))
            addView(horizontal {
                addView(vertical { addView(label(tr("Qty", "मात्रा"))); addView(qty) }, lp(0, WRAP, 1f))
                addView(vertical { addView(label(tr("Unit", "इकाई"))); addView(unit) }, lp(0, WRAP, 0.8f).margins(context, l = 8))
            }, lp().margins(context, t = 10))
            addView(label(if (doc.inclusive) tr("Your selling rate (incl. GST) *", "आपका बिक्री रेट (GST सहित) *") else tr("Your selling rate (before GST) *", "आपका बिक्री रेट (GST से पहले) *")),
                lp().margins(context, t = 10))
            addView(rate)
            if (buyRate != null) addView(text(tr("Your AVIP buying rate: ", "आपका AVIP खरीद रेट: ") + Fmt.inr(buyRate, true) + tr(" + GST", " + GST"), 11.5f, 0xFFB26A00.toInt()),
                lp().margins(context, t = 4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) tr("Add item", "आइटम जोड़ें") else tr("Edit item", "आइटम बदलें"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(tr("OK", "ठीक है"), null)
            .setNegativeButton(tr("Cancel", "रद्द करें"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = qty.text.toString().toDoubleOrNull()
                val r = rate.text.toString().toDoubleOrNull()
                val g = gst.text.toString().toDoubleOrNull() ?: 0.0
                when {
                    name.text.isBlank() -> { name.error = tr("Required", "ज़रूरी"); return@setOnClickListener }
                    q == null || q <= 0 -> { qty.error = tr("Enter quantity", "मात्रा लिखें"); return@setOnClickListener }
                    r == null || r <= 0 -> { rate.error = tr("Enter your rate", "अपना रेट लिखें"); return@setOnClickListener }
                    g !in listOf(0.0, 0.1, 0.25, 1.5, 3.0, 5.0, 6.0, 12.0, 18.0, 28.0, 40.0) -> { gst.error = tr("Not a GST rate", "यह GST दर नहीं है"); return@setOnClickListener }
                }
                line.name = name.text.toString().trim()
                line.hsn = hsn.text.toString().trim()
                line.qty = q!!
                line.unit = unit.text.toString().trim().ifBlank { "Nos" }
                line.rate = r!!
                line.gst = g
                if (isNew) doc.lines += line
                renderLines()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ---------- save ----------

    private fun save() {
        doc.number = number.text.toString().trim().uppercase()
        doc.customer.name = cName.text.toString().trim()
        doc.customer.mobile = cMobile.text.toString().filter { it.isDigit() }
        doc.customer.address = cAddress.text.toString().trim()
        doc.customer.gstin = cGstin.text.toString().trim().uppercase()
        doc.shipTo = shipTo.text.toString().trim()
        doc.notes = notes.text.toString().trim()
        doc.validDays = validDays.text.toString().toIntOrNull()?.coerceIn(1, 365) ?: 15

        SalesStore.numberProblem(this, doc)?.let { number.error = it; number.requestFocus(); return }
        if (doc.customer.name.length < 2) { cName.error = tr("Enter customer name", "ग्राहक का नाम लिखें"); cName.requestFocus(); return }
        if (doc.customer.mobile.isNotEmpty() && !Regex("[6-9][0-9]{9}").matches(doc.customer.mobile)) {
            cMobile.error = tr("Enter a valid 10-digit mobile", "सही 10 अंकों का मोबाइल लिखें"); cMobile.requestFocus(); return
        }
        if (isInvoice && doc.customer.address.length < 5) { cAddress.error = tr("Address is required on a tax invoice", "टैक्स इनवॉइस पर पता ज़रूरी है"); cAddress.requestFocus(); return }
        if (doc.customer.gstin.isNotEmpty()) Gstin.problem(doc.customer.gstin)?.let { cGstin.error = it; cGstin.requestFocus(); return }
        if (doc.lines.isEmpty()) { Toast.makeText(this, tr("Add at least one item", "कम से कम एक आइटम जोड़ें"), Toast.LENGTH_SHORT).show(); return }
        if (isInvoice && !composition && doc.lines.any { it.hsn.length < 4 }) {
            Toast.makeText(this, tr("Enter HSN / SAC (at least 4 digits) for every item", "हर आइटम का HSN / SAC लिखें (कम से कम 4 अंक)"), Toast.LENGTH_LONG).show(); return
        }

        val t = SaleCalc.compute(doc, Business.stateCode(this), composition)
        doc.total = t.grand
        doc.driveSynced = false
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val f = SalesStore.pdfFile(this@SaleEditorActivity, doc)
                    SalePdf(this@SaleEditorActivity).make(doc, f)
                    SalesStore.save(this@SaleEditorActivity, doc)
                    f
                }
            } catch (e: Exception) {
                Toast.makeText(this@SaleEditorActivity, tr("Couldn't create PDF: ", "PDF नहीं बना: ") + e.message, Toast.LENGTH_LONG).show(); return@launch
            }
            DriveSync.syncPendingAsync(this@SaleEditorActivity)
            done(file)
        }
    }

    private fun done(file: java.io.File) {
        val linked = DriveSync.isLinked(this)
        val sheet = vertical(20) {
            addView(iconBadgeRes(if (isInvoice) R.drawable.ic_receipt else R.drawable.ic_quote,
                if (isInvoice) 0xFF6A1B9A.toInt() else 0xFF1565C0.toInt(), 54).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
            addView(text(if (isInvoice) tr("Invoice ready!", "इनवॉइस तैयार!") else tr("Quotation ready!", "कोटेशन तैयार!"), 20f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 10))
            addView(text("${doc.number} · ${Fmt.inr(doc.total)}", 13f, Ui.MUTED) { gravity = Gravity.CENTER })
            addView(text(if (linked) tr("☁ Saving a copy to your Google Drive", "☁ आपकी Google Drive में कॉपी सेव हो रही है") else tr("Saved on this phone", "इस फ़ोन में सेव"),
                12f, if (linked) 0xFF0F9D58.toInt() else Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, b = 14))
        }
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(sheet) }).setCancelable(false).create()
        sheet.addView(pillButton(tr("Open PDF", "PDF खोलें"), Ui.BRAND) { EstimateStore.open(this, file) }, lp().margins(this, b = 8))
        sheet.addView(pillButton(tr("Send to customer on WhatsApp", "ग्राहक को WhatsApp पर भेजें"), 0xFF25D366.toInt()) {
            EstimateStore.whatsapp(this, file, doc.customer.mobile, shareText(doc))
        }, lp().margins(this, b = 8))
        sheet.addView(pillButton(tr("Share…", "भेजें…"), 0xFFEFF3F1.toInt(), Ui.TEXT) { EstimateStore.share(this, file, shareText(doc)) }, lp().margins(this, b = 8))
        if (!isInvoice) sheet.addView(pillButton(tr("Convert to Tax Invoice", "टैक्स इनवॉइस बनाएं"), 0xFF6A1B9A.toInt()) {
            dialog.dismiss()
            startActivity(Intent(this, SaleEditorActivity::class.java)
                .putExtra(EXTRA_TYPE, SaleType.INVOICE.name).putExtra(EXTRA_FROM_QUOTE, doc.id))
            finish()
        }, lp().margins(this, b = 8))
        if (!linked) sheet.addView(pillButton(tr("Link Google Drive", "Google Drive लिंक करें"), Color.WHITE, 0xFF0F9D58.toInt()) {
            startActivity(Intent(this, DriveActivity::class.java))
        }.apply { background = rounded(Color.WHITE, dpf(24f), 0xFF0F9D58.toInt(), dp(1)) }, lp().margins(this, b = 8))
        sheet.addView(text(tr("Done", "हो गया"), 15f, Ui.BRAND, bold = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { dialog.dismiss(); finish() }
        })
        dialog.show()
    }
}
