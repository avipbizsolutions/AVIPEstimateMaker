package com.avip.estimatemaker

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** The Surya Saathi's business details printed on their customer quotations and invoices. */
class BusinessActivity : AppCompatActivity() {

    companion object {
        /** Set when opened from "New quotation / invoice" so we return there after saving. */
        const val EXTRA_THEN = "then_type"
    }

    private lateinit var address: EditText
    private lateinit var pincode: EditText
    private lateinit var phone: EditText
    private lateinit var email: EditText
    private lateinit var composition: SwitchCompat
    private lateinit var bankName: EditText
    private lateinit var bankAc: EditText
    private lateinit var ifsc: EditText
    private lateinit var upi: EditText
    private lateinit var invPrefix: EditText
    private lateinit var qtPrefix: EditText
    private lateinit var qtTerms: EditText
    private lateinit var invTerms: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()
        root.addView(topBar(tr("My Business", "मेरा व्यवसाय"), tr("Printed on your quotations & invoices", "आपके कोटेशन व इनवॉइस पर छपता है")))
        val form = vertical(16)
        root.addView(ScrollView(this).apply { addView(form); isFillViewport = true }, lp(MATCH, 0, 1f))

        val multi = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        address = input(tr("Shop / office address with landmark", "दुकान / ऑफिस का पता, लैंडमार्क सहित"), multi, Business.address(this)).apply { minLines = 2 }
        pincode = input("e.g. 800001", InputType.TYPE_CLASS_NUMBER, Business.pincode(this)).apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6)) }
        phone = input(tr("Phone on invoices", "इनवॉइस पर फ़ोन"), InputType.TYPE_CLASS_PHONE, Business.phone(this))
        email = input(tr("Email (optional)", "ईमेल (वैकल्पिक)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, Business.email(this))
        composition = SwitchCompat(this).apply {
            text = tr("I'm under the GST Composition scheme", "मैं GST Composition योजना में हूं")
            isChecked = Business.composition(this@BusinessActivity)
            textSize = 14f
        }
        bankName = input(tr("Bank & branch", "बैंक व शाखा"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Business.bankName(this))
        bankAc = input(tr("Account number", "खाता संख्या"), InputType.TYPE_CLASS_NUMBER, Business.bankAccount(this))
        ifsc = input("IFSC", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, Business.bankIfsc(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(11))
        }
        upi = input(tr("UPI ID (optional), e.g. name@okaxis", "UPI ID (वैकल्पिक), जैसे name@okaxis"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, Business.upi(this))
        invPrefix = input("INV", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, Business.invoicePrefix(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(5))
        }
        qtPrefix = input("QT", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, Business.quotePrefix(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(5))
        }
        qtTerms = input("", multi, Business.quoteTerms(this)).apply { minLines = 3 }
        invTerms = input("", multi, Business.invoiceTerms(this)).apply { minLines = 3 }

        form.addView(card {
            addView(text(tr("From your registration", "आपके रजिस्ट्रेशन से"), 12.5f, Ui.BRAND_DARK, bold = true))
            addView(text(Business.legalName(context), 16f, Ui.TEXT, bold = true), lp().margins(context, t = 4))
            addView(text("GSTIN ${Business.gstin(context)} · ${stateName(Business.stateCode(context))}", 13f, Ui.MUTED))
            addView(text(tr("Change ›", "बदलें ›"), 13f, Ui.BRAND, bold = true) {
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { startActivity(Intent(this@BusinessActivity, ProfileActivity::class.java)) }
            })
        })
        form.addView(card {
            field(this, tr("Business address *", "व्यवसाय का पता *"), address)
            field(this, tr("PIN code *", "पिन कोड *"), pincode)
            field(this, tr("Phone", "फ़ोन"), phone)
            field(this, tr("Email", "ईमेल"), email)
            addView(composition, lp().margins(context, t = 14))
            addView(text(tr("Composition dealers issue a \"Bill of Supply\" without GST. Leave off if you charge GST.",
                "Composition डीलर GST के बिना \"Bill of Supply\" देते हैं। अगर आप GST लेते हैं तो इसे बंद रखें।"), 11.5f, Ui.MUTED))
        }, lp().margins(this, t = 12))
        form.addView(card {
            addView(text(tr("Payment details (printed for your customer)", "भुगतान विवरण (ग्राहक के लिए छपेगा)"), 14f, Ui.TEXT, bold = true))
            field(this, tr("Bank", "बैंक"), bankName)
            field(this, tr("Account no.", "खाता संख्या"), bankAc)
            field(this, "IFSC", ifsc)
            field(this, "UPI", upi)
        }, lp().margins(this, t = 12))
        form.addView(card {
            addView(text(tr("Numbering & terms", "नंबरिंग व शर्तें"), 14f, Ui.TEXT, bold = true))
            field(this, tr("Invoice prefix (e.g. INV → INV/26-27/001)", "इनवॉइस प्रीफ़िक्स (जैसे INV → INV/26-27/001)"), invPrefix)
            field(this, tr("Quotation prefix", "कोटेशन प्रीफ़िक्स"), qtPrefix)
            field(this, tr("Quotation terms (one per line)", "कोटेशन की शर्तें (हर लाइन में एक)"), qtTerms)
            field(this, tr("Invoice terms (one per line)", "इनवॉइस की शर्तें (हर लाइन में एक)"), invTerms)
        }, lp().margins(this, t = 12))
        form.addView(pillButton(tr("Save", "सेव करें"), Ui.BRAND) { save() }, lp().margins(this, t = 18, b = 24))
        setEdgeContent(root)
    }

    private fun field(parent: LinearLayout, label: String, view: android.view.View) {
        parent.addView(text(label, 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(this, t = if (parent.childCount == 0) 0 else 12, b = 6))
        parent.addView(view, lp())
    }

    private fun save() {
        val a = address.text.toString().trim()
        val pin = pincode.text.toString().trim()
        if (a.length < 5) { address.error = tr("Enter your business address", "व्यवसाय का पता लिखें"); address.requestFocus(); return }
        if (!Regex("[1-9][0-9]{5}").matches(pin)) { pincode.error = tr("Enter a 6-digit PIN code", "6 अंकों का पिन कोड लिखें"); pincode.requestFocus(); return }
        val ic = ifsc.text.toString().trim()
        if (ic.isNotEmpty() && !Regex("[A-Z]{4}0[A-Z0-9]{6}").matches(ic)) { ifsc.error = tr("IFSC looks wrong (e.g. SBIN0001234)", "IFSC गलत लगता है (जैसे SBIN0001234)"); ifsc.requestFocus(); return }
        Business.save(this, mapOf(
            "address" to a, "pincode" to pin,
            "phone" to phone.text.toString().trim(), "email" to email.text.toString().trim(),
            "composition" to composition.isChecked,
            "bank_name" to bankName.text.toString().trim(), "bank_account" to bankAc.text.toString().trim(),
            "bank_ifsc" to ic, "upi" to upi.text.toString().trim(),
            "invoice_prefix" to invPrefix.text.toString().trim().filter { it.isLetterOrDigit() }.ifBlank { "INV" },
            "quote_prefix" to qtPrefix.text.toString().trim().filter { it.isLetterOrDigit() }.ifBlank { "QT" },
            "quote_terms" to qtTerms.text.toString().trim(), "invoice_terms" to invTerms.text.toString().trim(),
        ))
        Toast.makeText(this, tr("Saved", "सेव हो गया"), Toast.LENGTH_SHORT).show()
        intent.getStringExtra(EXTRA_THEN)?.let {
            startActivity(Intent(this, SaleEditorActivity::class.java).putExtra(SaleEditorActivity.EXTRA_TYPE, it))
        }
        finish()
    }
}
