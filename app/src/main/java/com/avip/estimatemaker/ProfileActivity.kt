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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Not logged in: Surya Saathi registration (gets the default PIN 1234).
 * Surya Saathi: My Profile — business details, Change PIN, Logout, Delete account.
 * AVIP Team: who is logged in, Change PIN, Logout.
 */
class ProfileActivity : AppCompatActivity() {

    companion object { const val EXTRA_MOBILE = "mobile" }

    private lateinit var company: EditText
    private lateinit var contact: EditText
    private lateinit var mobile: EditText
    private lateinit var gst: EditText
    private lateinit var gstStatus: TextView
    private lateinit var city: EditText
    private lateinit var address: EditText
    private lateinit var saveBtn: TextView
    private var busy = false

    private val registering by lazy { Session.kind(this) == Session.Kind.NONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        if (Session.isTeam(this)) { buildTeam(); return }

        val root = vertical()
        root.addView(topBar(
            if (registering) tr("Register as Surya Saathi", "सूर्य साथी रजिस्ट्रेशन") else tr("My Profile", "मेरी प्रोफ़ाइल"),
            if (registering) tr("Get AVIP B2B prices and tools", "AVIP B2B रेट और टूल्स पाएं") else tr("Your details with AVIP", "AVIP के पास आपकी जानकारी"),
            right = listOf(langToggle()),
        ))
        val form = vertical(16)
        root.addView(ScrollView(this).apply { addView(form); isFillViewport = true }, lp(MATCH, 0, 1f))

        if (registering) {
            form.addView(card {
                addView(horizontal {
                    addView(iconBadge("kit", 44))
                    addView(text(tr("Join Bihar Solar Circle as a Surya Saathi: get AVIP B2B prices for solar panels, inverters, batteries, structure, cables and more, make cost estimates in seconds, and find installers and transporters.", "सूर्य साथी के रूप में Bihar Solar Circle से जुड़ें: Solar Panel, Inverter, Battery, Structure, Cable आदि के AVIP B2B रेट पाएं, तुरंत लागत अनुमान बनाएं, और इंस्टॉलर व ट्रांसपोर्टर खोजें।"), 13.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 12))
                })
            }, lp().margins(this, b = 14))
        } else {
            form.addView(vertical(16) {
                background = gradient(dpf(18f), 0xFF0B5A33.toInt(), 0xFF14935A.toInt())
                addView(text(Vendor.company(context), 19f, android.graphics.Color.WHITE, bold = true))
                addView(text("${Vendor.contact(context)} · ${Vendor.mobile(context)}", 13.5f, 0xEEFFFFFF.toInt()), lp().margins(context, t = 2))
                if (Vendor.gst(context).isNotBlank()) addView(text("GSTIN ${Vendor.gst(context)} ✓", 12.5f, 0xDDFFFFFF.toInt()))
                if (Vendor.since(context) > 0) addView(text(tr("Surya Saathi since ", "सूर्य साथी: ") +
                    SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(Vendor.since(context))), 12f, 0xCCFFFFFF.toInt()), lp().margins(context, t = 6))
            }, lp().margins(this, b = 14))
            form.addView(text(tr("Business details", "व्यवसाय की जानकारी"), 15f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
        }

        company = input(tr("e.g. Sun Power Solutions", "जैसे Sun Power Solutions"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.company(this))
        contact = input(tr("Your full name", "आपका पूरा नाम"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, Vendor.contact(this))
        mobile = input(tr("10-digit mobile number", "10 अंकों का मोबाइल नंबर"), InputType.TYPE_CLASS_PHONE,
            intent.getStringExtra(EXTRA_MOBILE)?.takeIf { it.isNotBlank() } ?: Vendor.mobile(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10))
            if (!registering) { isEnabled = false; alpha = 0.6f }
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
            field(this, if (registering) tr("Mobile Number * (your login)", "मोबाइल नंबर * (आपका लॉगिन)") else tr("Mobile Number (login, can't be changed)", "मोबाइल नंबर (लॉगिन, बदला नहीं जा सकता)"), mobile)
            field(this, tr("GST Number *", "GST नंबर *"), gst)
            addView(gstStatus, lp().margins(context, t = 6))
            field(this, tr("City / District", "शहर / ज़िला"), city)
            field(this, tr("Address", "पता"), address)
        })

        saveBtn = pillButton(if (registering) tr("Register  ›", "रजिस्टर करें  ›") else tr("Save changes", "बदलाव सेव करें"), Ui.BRAND) { save() }
        form.addView(saveBtn, lp().margins(this, t = 18, b = 8))
        form.addView(text(tr("Your details are kept confidential with AVIP Business Solutions LLP.", "आपकी जानकारी AVIP Business Solutions LLP के पास गोपनीय रखी जाती है।"), 12f, Ui.MUTED) {
            gravity = Gravity.CENTER
        }, lp().margins(this, b = 16))

        if (!registering) {
            form.addView(card(4) {
                addView(menuRow(tr("Change PIN", "PIN बदलें")) { startActivity(Intent(this@ProfileActivity, SetPinActivity::class.java)) })
                addView(menuRow(tr("Logout", "लॉगआउट")) { confirmLogout() })
                addView(menuRow(tr("Delete my account", "मेरा अकाउंट मिटाएं"), Ui.DANGER) { confirmDelete() })
            }, lp().margins(this, b = 14))
        }
        form.addView(grievanceCard(), lp().margins(this, b = 4))
        form.addView(privacyLink(), lp(WRAP, WRAP).margins(this, b = 20).apply { gravity = Gravity.CENTER_HORIZONTAL })

        setEdgeContent(root)
        showGstStatus()
    }

    /** AVIP Team member: who is logged in, Change PIN, Logout. */
    private fun buildTeam() {
        val root = vertical()
        root.addView(topBar(tr("My Profile", "मेरी प्रोफ़ाइल"), tr("AVIP Team login", "AVIP टीम लॉगिन"), right = listOf(langToggle())))
        val form = vertical(16)
        root.addView(ScrollView(this).apply { addView(form) }, lp(MATCH, 0, 1f))
        form.addView(vertical(16) {
            background = gradient(dpf(18f), 0xFF0B5A33.toInt(), 0xFF14935A.toInt())
            addView(text(Session.teamName(context), 19f, android.graphics.Color.WHITE, bold = true))
            addView(text(listOf(Session.teamDesignation(context), "AVIP " + Session.teamRoleLabel(context)).filter { it.isNotBlank() }.joinToString(" · "),
                13.5f, 0xEEFFFFFF.toInt()), lp().margins(context, t = 2))
            addView(text(Session.teamMobile(context), 13f, 0xDDFFFFFF.toInt()))
            addView(text(tr("Details come from the AVIP Team Directory.", "जानकारी AVIP Team Directory से आती है।"), 12f, 0xCCFFFFFF.toInt()), lp().margins(context, t = 8))
        })
        form.addView(card(4) {
            addView(menuRow(tr("Change PIN", "PIN बदलें")) { startActivity(Intent(this@ProfileActivity, SetPinActivity::class.java)) })
            addView(menuRow(tr("Logout", "लॉगआउट")) { confirmLogout() })
        }, lp().margins(this, t = 14, b = 20))
        setEdgeContent(root)
    }

    private fun menuRow(label: String, color: Int = Ui.TEXT, onClick: () -> Unit): View = horizontal {
        setPadding(dp(14), dp(16), dp(14), dp(16))
        foreground = ripple()
        addView(text(label, 15f, color, bold = true), lp(0, WRAP, 1f))
        addView(text("›", 22f, Ui.MUTED))
        setOnClickListener { onClick() }
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

    private fun save() {
        if (busy) return
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
        val body = JSONObject().put("company", c).put("contact", n).put("mobile", m).put("gst", g)
            .put("city", city.text.toString().trim()).put("address", address.text.toString().trim())
        busy = true
        val label = saveBtn.text
        saveBtn.text = tr("Please wait…", "कृपया प्रतीक्षा करें…")
        lifecycleScope.launch {
            try {
                if (registering) {
                    val o = Api.call(this@ProfileActivity, "register", body)
                    Session.saveSaathi(this@ProfileActivity, o.getString("token"), o.getJSONObject("profile"), true)
                    registeredDialog(m)
                } else {
                    val o = Api.call(this@ProfileActivity, "profile", body.put("token", Session.token(this@ProfileActivity)))
                    Vendor.store(this@ProfileActivity, o.getJSONObject("profile"))
                    Toast.makeText(this@ProfileActivity, tr("Saved", "सेव हो गया"), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: ApiException) {
                when (e.code) {
                    "ALREADY" -> AlertDialog.Builder(this@ProfileActivity).setMessage(e.message)
                        .setPositiveButton(tr("Go to login", "लॉगिन पर जाएं")) { _, _ ->
                            startActivity(Intent(this@ProfileActivity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_MOBILE, m)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            finish()
                        }.show()
                    "SESSION", "BLOCKED" -> goLogin(this@ProfileActivity, e.message)
                    else -> Toast.makeText(this@ProfileActivity, e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
                saveBtn.text = label
            }
        }
    }

    private fun registeredDialog(m: String) {
        AlertDialog.Builder(this)
            .setTitle(tr("✓ Registered!", "✓ रजिस्टर हो गया!"))
            .setMessage(tr("Your login: $m\nDefault PIN: 1234\n\nNow set your own PIN.", "आपका लॉगिन: $m\nडिफ़ॉल्ट PIN: 1234\n\nअब अपना PIN बनाएं।"))
            .setCancelable(false)
            .setPositiveButton(tr("Set my PIN", "मेरा PIN बनाएं")) { _, _ ->
                startActivity(Intent(this, SetPinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }.show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage(tr("Logout from Bihar Solar Circle?", "Bihar Solar Circle से लॉगआउट करें?"))
            .setPositiveButton(tr("Logout", "लॉगआउट")) { _, _ -> goLogin(this, null) }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete your account?", "अकाउंट मिटाएं?"))
            .setMessage(tr("This removes your registration and records from AVIP Business Solutions LLP, and deletes your business details, cost estimates, quotations and invoices from this phone. Copies already saved in your Google Drive stay there.\n\nYou can register again at any time.", "इससे AVIP Business Solutions LLP के पास आपका रजिस्ट्रेशन और रिकॉर्ड हट जाएगा, और इस फ़ोन से आपकी व्यवसाय जानकारी, लागत अनुमान, कोटेशन व इनवॉइस मिट जाएंगे। आपकी Google Drive में सेव कॉपी वहीं रहेंगी।\n\nआप कभी भी दोबारा रजिस्टर कर सकते हैं।"))
            .setPositiveButton(tr("Delete", "मिटाएं")) { _, _ ->
                Vendor.deleteAccount(this)
                Toast.makeText(this, tr("Your account has been deleted", "आपका अकाउंट मिटा दिया गया"), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null)
            .show()
    }
}
