package com.avip.estimatemaker

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** First-launch Surya Saathi registration, and later "My Business Details". */
class ProfileActivity : AppCompatActivity() {

    private lateinit var company: EditText
    private lateinit var contact: EditText
    private lateinit var mobile: EditText
    private lateinit var gst: EditText
    private lateinit var gstStatus: TextView
    private lateinit var city: EditText
    private lateinit var address: EditText

    private val firstRun by lazy { !Vendor.isComplete(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()
        root.addView(
            topBar(
                if (firstRun) "Welcome, Surya Saathi!" else "My Business Details",
                if (firstRun) "Register once to see AVIP B2B prices" else "Printed on your quotations",
                showBack = !firstRun
            )
        )
        val form = vertical(16)
        val scroll = ScrollView(this).apply { addView(form); isFillViewport = true }
        root.addView(scroll, lp(MATCH, 0, 1f))

        if (firstRun) {
            form.addView(card {
                addView(horizontal {
                    addView(iconBadge("kit", 44))
                    addView(text("Become an AVIP Surya Saathi: get B2B prices for solar panels, inverters, batteries, structure, cables and more — and make PDF quotations in seconds.", 13.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 12))
                })
            }, lp().margins(this, b = 14))
        }

        company = input("e.g. Sun Power Solutions", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.company(this))
        contact = input("Your full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, Vendor.contact(this))
        mobile = input("10-digit mobile number", InputType.TYPE_CLASS_PHONE, Vendor.mobile(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10))
        }
        gst = input("15-character GSTIN, e.g. 10ABCDE1234F1Z5", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, Vendor.gst(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(15))
        }
        gstStatus = text("", 12f, Ui.MUTED)
        gst.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = showGstStatus()
        })
        city = input("e.g. Patna", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.city(this))
        address = input("Shop / office address (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, Vendor.address(this))

        form.addView(card {
            field(this, "Company Name *", company)
            field(this, "Contact Person *", contact)
            field(this, "Mobile Number *", mobile)
            field(this, "GST Number *", gst)
            addView(gstStatus, lp().margins(context, t = 6))
            field(this, "City / District", city)
            field(this, "Address", address)
        })

        form.addView(pillButton(if (firstRun) "Register as Surya Saathi  ›" else "Save", Ui.BRAND) { save() },
            lp().margins(this, t = 18, b = 8))
        form.addView(text("Your details are kept confidential with AVIP Business Solutions LLP.", 12f, Ui.MUTED) {
            gravity = Gravity.CENTER
        }, lp().margins(this, b = 16))
        form.addView(grievanceCard(), lp().margins(this, b = 20))

        setContentView(root)
        showGstStatus()
    }

    private fun field(parent: LinearLayout, label: String, view: EditText) {
        parent.addView(text(label, 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(this, t = if (parent.childCount == 0) 0 else 12, b = 6))
        parent.addView(view, lp())
    }

    /** Live feedback while typing the GSTIN. */
    private fun showGstStatus() {
        val g = gst.text.toString().trim()
        when {
            g.isEmpty() -> { gstStatus.text = "Required. We check the number as you type."; gstStatus.setTextColor(Ui.MUTED) }
            g.length < 15 -> { gstStatus.text = "${g.length}/15 characters"; gstStatus.setTextColor(Ui.MUTED) }
            else -> {
                val problem = Gstin.problem(g)
                if (problem == null) {
                    gstStatus.text = "✓ Valid GSTIN · ${Gstin.state(g)} · PAN ${Gstin.pan(g)}"
                    gstStatus.setTextColor(Ui.BRAND)
                } else {
                    gstStatus.text = "✗ $problem"
                    gstStatus.setTextColor(Ui.DANGER)
                }
            }
        }
    }

    private fun save() {
        val c = company.text.toString().trim()
        val n = contact.text.toString().trim()
        val m = mobile.text.toString().filter { it.isDigit() }
        val g = gst.text.toString().trim().uppercase()
        val gstProblem = Gstin.problem(g)
        when {
            c.length < 2 -> { company.error = "Enter company name"; company.requestFocus(); return }
            n.length < 2 -> { contact.error = "Enter contact person"; contact.requestFocus(); return }
            !Regex("[6-9][0-9]{9}").matches(m) -> { mobile.error = "Enter a valid 10-digit mobile"; mobile.requestFocus(); return }
            gstProblem != null -> { gst.error = gstProblem; gst.requestFocus(); return }
        }
        Vendor.save(this, c, n, m, g, city.text.toString().trim(), address.text.toString().trim())
        if (firstRun) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
