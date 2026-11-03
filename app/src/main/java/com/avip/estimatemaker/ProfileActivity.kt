package com.avip.estimatemaker

import android.content.Intent
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
                if (firstRun) tr("Welcome, Surya Saathi!", "स्वागत है, सूर्य साथी!") else tr("My Registration", "मेरा रजिस्ट्रेशन"),
                if (firstRun) tr("Register once to see AVIP B2B prices", "AVIP B2B रेट देखने के लिए एक बार रजिस्टर करें") else tr("Your details with AVIP", "AVIP के पास आपकी जानकारी"),
                showBack = !firstRun,
                right = listOf(langToggle()),
            )
        )
        val form = vertical(16)
        val scroll = ScrollView(this).apply { addView(form); isFillViewport = true }
        root.addView(scroll, lp(MATCH, 0, 1f))

        if (firstRun) {
            form.addView(card {
                addView(horizontal {
                    addView(iconBadge("kit", 44))
                    addView(text(tr("Join Bihar Solar Circle as a Surya Saathi: get AVIP B2B prices for solar panels, inverters, batteries, structure, cables and more, make customer quotations and GST invoices, and find installers and transporters.", "सूर्य साथी के रूप में Bihar Solar Circle से जुड़ें: Solar Panel, Inverter, Battery, Structure, Cable आदि के AVIP B2B रेट पाएं, ग्राहक कोटेशन व GST इनवॉइस बनाएं, और इंस्टॉलर व ट्रांसपोर्टर खोजें।"), 13.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 12))
                })
            }, lp().margins(this, b = 14))
        }

        company = input(tr("e.g. Sun Power Solutions", "जैसे Sun Power Solutions"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.company(this))
        contact = input(tr("Your full name", "आपका पूरा नाम"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, Vendor.contact(this))
        mobile = input(tr("10-digit mobile number", "10 अंकों का मोबाइल नंबर"), InputType.TYPE_CLASS_PHONE, Vendor.mobile(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10))
        }
        gst = input(tr("15-character GSTIN, e.g. 10ABCDE1234F1Z5", "15 अक्षर का GSTIN, जैसे 10ABCDE1234F1Z5"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, Vendor.gst(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(15))
        }
        gstStatus = text("", 12f, Ui.MUTED)
        gst.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = showGstStatus()
        })
        city = input(tr("e.g. Patna", "जैसे Patna"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.city(this))
        address = input(tr("Shop / office address (optional)", "दुकान / ऑफिस का पता (वैकल्पिक)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, Vendor.address(this))

        form.addView(card {
            field(this, tr("Company Name *", "कंपनी का नाम *"), company)
            field(this, tr("Contact Person *", "संपर्क व्यक्ति *"), contact)
            field(this, tr("Mobile Number *", "मोबाइल नंबर *"), mobile)
            field(this, tr("GST Number *", "GST नंबर *"), gst)
            addView(gstStatus, lp().margins(context, t = 6))
            field(this, tr("City / District", "शहर / ज़िला"), city)
            field(this, tr("Address", "पता"), address)
        })

        form.addView(pillButton(if (firstRun) tr("Register as Surya Saathi  ›", "सूर्य साथी के रूप में रजिस्टर करें  ›") else tr("Save", "सेव करें"), Ui.BRAND) { save() },
            lp().margins(this, t = 18, b = 8))
        form.addView(text(tr("Your details are kept confidential with AVIP Business Solutions LLP.", "आपकी जानकारी AVIP Business Solutions LLP के पास गोपनीय रखी जाती है।"), 12f, Ui.MUTED) {
            gravity = Gravity.CENTER
        }, lp().margins(this, b = 16))
        form.addView(grievanceCard(), lp().margins(this, b = 4))
        form.addView(privacyLink(), lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (!firstRun) {
            form.addView(text(tr("Delete my account", "मेरा अकाउंट मिटाएं"), 13.5f, Ui.DANGER, bold = true) {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { confirmDelete() }
            }, lp(WRAP, WRAP).margins(this, b = 20).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            form.addView(View(this), lp(MATCH, dp(20)))
        }

        setEdgeContent(root)
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
            g.isEmpty() -> { gstStatus.text = tr("Required. We check the number as you type.", "ज़रूरी है। लिखते समय नंबर जांचा जाता है।"); gstStatus.setTextColor(Ui.MUTED) }
            g.length < 15 -> { gstStatus.text = tr("${g.length}/15 characters", "${g.length}/15 अक्षर"); gstStatus.setTextColor(Ui.MUTED) }
            else -> {
                val problem = Gstin.problem(g)
                if (problem == null) {
                    gstStatus.text = tr("✓ Valid GSTIN", "✓ सही GSTIN") + " · ${Gstin.state(g)} · PAN ${Gstin.pan(g)}"
                    gstStatus.setTextColor(Ui.BRAND)
                } else {
                    gstStatus.text = "✗ $problem"
                    gstStatus.setTextColor(Ui.DANGER)
                }
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete your account?", "अकाउंट मिटाएं?"))
            .setMessage(tr("This removes your registration and records from AVIP Business Solutions LLP, and deletes your business details, cost estimates, quotations and invoices from this phone. Copies already saved in your Google Drive stay there.\n\nYou can register again at any time.", "इससे AVIP Business Solutions LLP के पास आपका रजिस्ट्रेशन और रिकॉर्ड हट जाएगा, और इस फ़ोन से आपकी व्यवसाय जानकारी, लागत अनुमान, कोटेशन व इनवॉइस मिट जाएंगे। आपकी Google Drive में सेव कॉपी वहीं रहेंगी।\n\nआप कभी भी दोबारा रजिस्टर कर सकते हैं।"))
            .setPositiveButton(tr("Delete", "मिटाएं")) { _, _ ->
                Vendor.deleteAccount(this)
                Toast.makeText(this, tr("Your account has been deleted", "आपका अकाउंट मिटा दिया गया"), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, ProfileActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null)
            .show()
    }

    private fun save() {
        val c = company.text.toString().trim()
        val n = contact.text.toString().trim()
        val m = mobile.text.toString().filter { it.isDigit() }
        val g = gst.text.toString().trim().uppercase()
        val gstProblem = Gstin.problem(g)
        when {
            c.length < 2 -> { company.error = tr("Enter company name", "कंपनी का नाम लिखें"); company.requestFocus(); return }
            n.length < 2 -> { contact.error = tr("Enter contact person", "संपर्क व्यक्ति का नाम लिखें"); contact.requestFocus(); return }
            !Regex("[6-9][0-9]{9}").matches(m) -> { mobile.error = tr("Enter a valid 10-digit mobile", "सही 10 अंकों का मोबाइल नंबर लिखें"); mobile.requestFocus(); return }
            gstProblem != null -> { gst.error = gstProblem; gst.requestFocus(); return }
        }
        Vendor.save(this, c, n, m, g, city.text.toString().trim(), address.text.toString().trim())
        if (firstRun) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            Toast.makeText(this, tr("Saved", "सेव हो गया"), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
