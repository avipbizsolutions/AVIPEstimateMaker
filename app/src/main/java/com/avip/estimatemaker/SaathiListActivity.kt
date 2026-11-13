package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
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
 * AVIP Team: the registered Surya Saathis (B2B customers).
 * With EXTRA_PICK: choose the customer for a new cost estimate (or add a new one).
 */
class SaathiListActivity : AppCompatActivity() {

    companion object { const val EXTRA_PICK = "pick" }

    private var pick = false
    private var query = ""
    private var loading = true
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val df = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        SaathiDirectory.loadCached(this)

        val root = vertical()
        root.addView(topBar(
            if (pick) tr("Choose B2B customer", "B2B ग्राहक चुनें") else tr("Surya Saathis", "सूर्य साथी"),
            if (pick) tr("Who is this cost estimate for?", "यह लागत अनुमान किसके लिए है?") else tr("Registered B2B customers", "रजिस्टर्ड B2B ग्राहक")))
        val box = input(tr("Search name, mobile, GSTIN or city…", "नाम, मोबाइल, GSTIN या शहर खोजें…"), InputType.TYPE_CLASS_TEXT).apply {
            background = rounded(Color.WHITE, dpf(24f), Ui.LINE, dp(1))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString(); render() }
            })
        }
        root.addView(box, lp().margins(this, 12, 12, 12, 0))
        status = text("", 11.5f, Ui.MUTED) { setPadding(dp(16), dp(6), dp(16), 0) }
        root.addView(status)
        list = vertical(12)
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) { reload() }, lp(MATCH, 0, 1f))
        root.addView(vertical {
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dpf(8f)
            addView(pillButton(tr("+ New B2B customer", "+ नया B2B ग्राहक"), Ui.BRAND, Color.WHITE, 48) { addDialog() })
        })
        setEdgeContent(root)
        render()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch { reload() }
    }

    private suspend fun reload() {
        loading = true; render()
        val ok = try { SaathiDirectory.refresh(this) } catch (e: ApiException) { goLogin(this, e.message); return }
        loading = false; render()
        if (!ok) status.text = tr("Offline — showing the list saved earlier", "ऑफ़लाइन — पहले सेव की गई लिस्ट दिख रही है")
    }

    private fun render() {
        val all = SaathiDirectory.list
        status.text = if (loading) tr("Checking for new registrations…", "नए रजिस्ट्रेशन जांच रहे हैं…")
        else tr("${all.size} Surya Saathis · ${all.count { it.status.equals("Active", true) }} active",
            "${all.size} सूर्य साथी · ${all.count { it.status.equals("Active", true) }} सक्रिय")
        list.removeAllViews()
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val shown = all.filter { b -> words.all { w -> "${b.company} ${b.contact} ${b.mobile} ${b.gst} ${b.city}".lowercase().contains(w) } }
        if (shown.isEmpty()) {
            list.addView(text(if (loading && all.isEmpty()) tr("Loading…", "लोड हो रहा है…") else tr("No one found", "कोई नहीं मिला"),
                15f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40))
            return
        }
        shown.forEach { list.addView(row(it), lp().margins(this, b = 10)) }
    }

    private fun row(b: Buyer): View = card(14) {
        foreground = ripple()
        val blocked = b.status.equals("Blocked", true)
        addView(horizontal {
            addView(vertical {
                addView(text(b.company, 15.5f, if (blocked) Ui.MUTED else Ui.TEXT, bold = true))
                addView(text(listOf(b.contact, b.city).filter { it.isNotBlank() }.joinToString(" · "), 12.5f, Ui.MUTED))
                if (b.gst.isNotBlank()) addView(text("GSTIN ${b.gst}", 11.5f, Ui.MUTED))
                val dates = listOfNotNull(
                    b.registered.takeIf { it > 0 }?.let { tr("Since ", "से: ") + df.format(Date(it)) },
                    if (b.lastLogin > 0) tr("Last login ", "आखिरी लॉगिन ") + df.format(Date(b.lastLogin)) else tr("Not logged in yet", "अभी लॉगिन नहीं किया"),
                ).joinToString("  ·  ")
                addView(text(dates, 11f, Ui.MUTED), lp().margins(context, t = 2))
            }, lp(0, WRAP, 1f))
            if (blocked) addView(text(tr("Blocked", "रोका गया"), 11f, Ui.DANGER, bold = true))
            addView(text("⋮", 24f, Ui.MUTED, bold = true) {
                setPadding(dp(14), dp(2), dp(4), dp(8))
                contentDescription = tr("Edit or delete", "बदलें या हटाएं")
                setOnClickListener { manage(b) }
            })
        })
        if (!pick) addView(horizontal {
            addView(pillButton(tr("Call", "कॉल"), Ui.BRAND, Color.WHITE, 40) { EstimateStore.call(this@SaathiListActivity, b.mobile) }, lp(0, WRAP, 1f))
            addView(pillButton("WhatsApp", 0xFF25D366.toInt(), Color.WHITE, 40) {
                EstimateStore.chatNumber(this@SaathiListActivity, b.mobile,
                    "Namaste ${b.contact.substringBefore(' ')} ji, this is ${Session.teamName(this@SaathiListActivity)} from AVIP Business Solutions LLP (Bihar Solar Circle).")
            }, lp(0, WRAP, 1f).margins(context, l = 8))
            addView(pillButton(tr("Estimate", "अनुमान"), 0xFFEFF3F1.toInt(), Ui.TEXT, 40) { choose(b) }, lp(0, WRAP, 1f).margins(context, l = 8))
        }, lp().margins(context, t = 10))
        setOnClickListener {
            if (blocked) Toast.makeText(this@SaathiListActivity, tr("This account is blocked in the Surya Saathi sheet", "यह अकाउंट Surya Saathi शीट में रोका गया है"), Toast.LENGTH_SHORT).show()
            else if (pick) choose(b)
        }
    }

    /** Sets the customer for the cost estimate and opens the catalog. */
    private fun choose(b: Buyer) {
        val before = TeamTarget.get(this)
        val go = {
            TeamTarget.set(this, b)
            startActivity(Intent(this, if (Cart.count > 0) EstimateActivity::class.java else CatalogActivity::class.java))
            finish()
        }
        Cart.load(this)
        if (Cart.count > 0 && before != null && before.mobile != b.mobile) {
            AlertDialog.Builder(this)
                .setMessage(tr("The current estimate has ${Cart.count} items for ${before.company}. Keep these items for ${b.company}?",
                    "मौजूदा अनुमान में ${before.company} के लिए ${Cart.count} आइटम हैं। क्या ये आइटम ${b.company} के लिए रखें?"))
                .setPositiveButton(tr("Keep items", "आइटम रखें")) { _, _ -> go() }
                .setNegativeButton(tr("Start empty", "खाली शुरू करें")) { _, _ -> Cart.clear(this); go() }
                .show()
        } else go()
    }

    private fun addDialog() = customerDialog(null)

    /** Edit / delete menu for one Surya Saathi. */
    private fun manage(b: Buyer) {
        AlertDialog.Builder(this)
            .setTitle(b.company)
            .setItems(arrayOf(tr("Edit details", "जानकारी बदलें"), tr("Delete Surya Saathi", "सूर्य साथी हटाएं"))) { _, i ->
                if (i == 0) customerDialog(b) else confirmDelete(b)
            }.show()
    }

    private fun confirmDelete(b: Buyer) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete ${b.company}?", "${b.company} हटाएं?"))
            .setMessage(tr("Their registration is removed from the Surya Saathi sheet and they can no longer log in (they can register again). Cost estimates already made for them stay in the Quotations Log.",
                "उनका रजिस्ट्रेशन Surya Saathi शीट से हट जाएगा और वे लॉगिन नहीं कर पाएंगे (वे दोबारा रजिस्टर कर सकते हैं)। उनके लिए बने लागत अनुमान Quotations Log में रहेंगे।"))
            .setPositiveButton(tr("Delete", "हटाएं")) { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.call(this@SaathiListActivity, "deleteSaathi", JSONObject().put("token", Session.token(this@SaathiListActivity))
                            .put("mobile", b.mobile).put("company", b.company))
                        SaathiDirectory.removed(this@SaathiListActivity, b.mobile)
                        if (TeamTarget.get(this@SaathiListActivity)?.mobile == b.mobile) TeamTarget.clear(this@SaathiListActivity)
                        render()
                        Toast.makeText(this@SaathiListActivity, tr("${b.company} deleted", "${b.company} हटाया गया"), Toast.LENGTH_SHORT).show()
                    } catch (e: ApiException) {
                        if (e.code == "SESSION" || e.code == "NOTEAM") goLogin(this@SaathiListActivity, e.message)
                        else Toast.makeText(this@SaathiListActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
    }

    /** Add a new B2B customer ([existing] = null) or edit one. */
    private fun customerDialog(existing: Buyer?) {
        val company = input(tr("Company name *", "कंपनी का नाम *"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, existing?.company ?: "")
        val contact = input(tr("Contact person", "संपर्क व्यक्ति"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, existing?.contact ?: "")
        val mobile = input(tr("Mobile *", "मोबाइल *"), InputType.TYPE_CLASS_PHONE, existing?.mobile ?: "").apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10)) }
        val gst = input(tr("GSTIN (optional)", "GSTIN (वैकल्पिक)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, existing?.gst ?: "").apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(15))
        }
        val city = input(tr("City / District", "शहर / ज़िला"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, existing?.city ?: "")
        val address = input(tr("Address", "पता"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, existing?.address ?: "")
        val form = vertical(18) {
            listOf(company, contact, mobile, gst, city, address).forEach { addView(it, lp().margins(context, b = 8)) }
            addView(text(if (existing == null) tr("They can later login with this mobile number and PIN 1234.", "वे बाद में इसी मोबाइल नंबर और PIN 1234 से लॉगिन कर सकते हैं।")
                else tr("The mobile number is their login. If you change it, their PIN goes back to 1234.", "मोबाइल नंबर उनका लॉगिन है। इसे बदलने पर उनका PIN फिर से 1234 हो जाएगा।"), 12f, Ui.MUTED))
        }
        val d = AlertDialog.Builder(this).setTitle(if (existing == null) tr("New B2B customer", "नया B2B ग्राहक") else tr("Edit Surya Saathi", "सूर्य साथी बदलें"))
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(if (existing == null) tr("Add", "जोड़ें") else tr("Save", "सेव करें"), null)
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val m = mobile.text.toString().filter { it.isDigit() }
                val g = gst.text.toString().trim()
                when {
                    company.text.trim().length < 2 -> { company.error = tr("Required", "ज़रूरी"); return@setOnClickListener }
                    !Regex("[6-9][0-9]{9}").matches(m) -> { mobile.error = tr("Enter a valid 10-digit mobile", "सही 10 अंकों का मोबाइल लिखें"); return@setOnClickListener }
                    g.isNotEmpty() && Gstin.problem(g) != null -> { gst.error = Gstin.problem(g); return@setOnClickListener }
                }
                it.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val body = JSONObject().put("token", Session.token(this@SaathiListActivity))
                            .put("company", company.text.toString().trim()).put("contact", contact.text.toString().trim())
                            .put("gst", g).put("city", city.text.toString().trim()).put("address", address.text.toString().trim())
                        val o = if (existing == null) Api.call(this@SaathiListActivity, "addSaathi", body.put("mobile", m))
                        else Api.call(this@SaathiListActivity, "editSaathi", body.put("mobile", existing.mobile).put("newMobile", m))
                        val b = Buyer.fromJson(o.getJSONObject("saathi"))!!
                        if (existing != null) SaathiDirectory.removed(this@SaathiListActivity, existing.mobile)
                        SaathiDirectory.added(this@SaathiListActivity, b)
                        if (existing != null && TeamTarget.get(this@SaathiListActivity)?.mobile == existing.mobile) TeamTarget.set(this@SaathiListActivity, b)
                        d.dismiss()
                        render()
                        Toast.makeText(this@SaathiListActivity,
                            when {
                                existing == null -> tr("Added ${b.company}", "${b.company} जोड़ा गया")
                                o.optBoolean("mobileChanged") -> tr("Saved · new login ${b.mobile}, PIN 1234", "सेव · नया लॉगिन ${b.mobile}, PIN 1234")
                                else -> tr("Saved", "सेव हो गया")
                            }, Toast.LENGTH_LONG).show()
                        if (existing == null && pick) choose(b)
                    } catch (e: ApiException) {
                        it.isEnabled = true
                        if (e.code == "SESSION" || e.code == "NOTEAM") { d.dismiss(); goLogin(this@SaathiListActivity, e.message) }
                        else Toast.makeText(this@SaathiListActivity, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        d.show()
    }
}
