package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Login: Surya Saathi (B2B customer) or AVIP Team (Managers / Founders). */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MOBILE = "mobile"
        const val EXTRA_TEAM = "team"
        const val EXTRA_MESSAGE = "message"
    }

    private var team = false
    private lateinit var tabs: LinearLayout
    private lateinit var mobile: EditText
    private lateinit var pin: EditText
    private lateinit var errorView: TextView
    private lateinit var loginBtn: TextView
    private lateinit var saathiLinks: LinearLayout
    private lateinit var teamNote: TextView
    private var busy = false
    private val boxes = mutableListOf<TextView>()
    private var showPin = false
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private fun lastMobile(forTeam: Boolean) = prefs.getString(if (forTeam) "last_mobile_team" else "last_mobile_saathi", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        team = intent.getBooleanExtra(EXTRA_TEAM, false)

        val root = vertical {
            background = gradient(0f, 0xFF0B5A33.toInt(), 0xFF14935A.toInt())
        }
        root.addView(horizontal {
            gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(12), 0)
            addView(langToggle())
        })
        val form = vertical(22)
        root.addView(ScrollView(this).apply { addView(form); isFillViewport = true }, lp(MATCH, 0, 1f))

        form.addView(ImageView(this).apply {
            setImageResource(R.drawable.bsc_logo)
            adjustViewBounds = true
        }, lp(dp(150), dp(150)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        form.addView(text(tr("B2B solar prices & tools", "B2B सोलर रेट व टूल्स"), 14f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER },
            lp().margins(this, t = 6, b = 18))

        form.addView(card(18) {
            tabs = horizontal { background = rounded(0xFFEFF3F1.toInt(), dpf(22f)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
            addView(tabs)

            mobile = input(tr("10-digit mobile number", "10 अंकों का मोबाइल नंबर"), InputType.TYPE_CLASS_PHONE,
                intent.getStringExtra(EXTRA_MOBILE) ?: lastMobile(team).ifBlank { Vendor.mobile(context) }).apply {
                filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10))
            }
            // Four PIN boxes; an invisible field on top takes the typing. Logs in on the 4th digit.
            pin = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
                setTextColor(Color.TRANSPARENT)
                setBackgroundColor(Color.TRANSPARENT)
                isCursorVisible = false
                isLongClickable = false
                setOnEditorActionListener { _, _, _ -> login(); true }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        showBoxes()
                        if (s?.length == 4 && mobile.text.filter { it.isDigit() }.length == 10) login()
                    }
                })
            }
            addView(text(tr("Mobile number", "मोबाइल नंबर"), 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 16, b = 6))
            addView(mobile)
            addView(text(tr("4-digit PIN", "4 अंकों का PIN"), 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 12, b = 6))
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                addView(android.widget.FrameLayout(context).apply {
                    addView(horizontal {
                        repeat(4) { k ->
                            val b = text("", 24f, Ui.TEXT, bold = true) {
                                gravity = Gravity.CENTER
                                background = rounded(0xFFF7FAF8.toInt(), dpf(12f), Ui.LINE, dp(2))
                            }
                            boxes += b
                            addView(b, lp(0, dp(56), 1f).margins(context, r = if (k < 3) 10 else 0))
                        }
                    }, android.widget.FrameLayout.LayoutParams(MATCH, dp(56)))
                    addView(pin, android.widget.FrameLayout.LayoutParams(MATCH, dp(56)))
                }, lp(0, WRAP, 1f))
                addView(text("👁", 20f, Ui.MUTED) {
                    setPadding(dp(12), dp(8), dp(4), dp(8))
                    setOnClickListener { showPin = !showPin; showBoxes() }
                })
            })
            errorView = text(intent.getStringExtra(EXTRA_MESSAGE) ?: "", 12.5f, Ui.DANGER)
            addView(errorView, lp().margins(context, t = 8))
            loginBtn = pillButton(tr("Login", "लॉगिन"), Ui.BRAND, Color.WHITE, 52) { login() }
            addView(loginBtn, lp().margins(context, t = 12))

            saathiLinks = vertical {
                addView(link(tr("New here? Register as Surya Saathi  ›", "नए हैं? सूर्य साथी के रूप में रजिस्टर करें  ›")) {
                    startActivity(Intent(this@LoginActivity, ProfileActivity::class.java)
                        .putExtra(ProfileActivity.EXTRA_MOBILE, mobile.text.toString()))
                })
                addView(link(tr("Forgot PIN? WhatsApp AVIP  ›", "PIN भूल गए? AVIP को WhatsApp करें  ›")) {
                    val m = mobile.text.toString().filter { it.isDigit() }
                    EstimateStore.chatAvip(this@LoginActivity,
                        "Hello AVIP, please reset my Bihar Solar Circle PIN. My registered mobile number is ${m.ifBlank { "____" }}.")
                })
            }
            addView(saathiLinks, lp().margins(context, t = 10))
            teamNote = text(tr("Use the same mobile number and PIN as in AVIP Solar Sales. For Managers and Founders. Forgot PIN? Ask the office to reset it in the Team Directory.",
                "वही मोबाइल नंबर और PIN डालें जो AVIP Solar Sales में है। Managers और Founders के लिए। PIN भूल गए? ऑफिस से Team Directory में रीसेट करवाएं।"), 12f, Ui.MUTED)
            addView(teamNote, lp().margins(context, t = 12))
        })
        form.addView(View(this), lp(MATCH, dp(24)))
        setEdgeContent(root, lightBars = false)
        showTabs()
        showBoxes()
        if (mobile.text.length == 10) pin.requestFocus()
    }

    private fun link(t: String, onClick: () -> Unit) = text(t, 13.5f, Ui.BRAND, bold = true) {
        setPadding(0, dp(10), 0, dp(4))
        setOnClickListener { onClick() }
    }

    /** Draws the typed digits (or dots) into the four boxes; the next empty box is highlighted. */
    private fun showBoxes() {
        val t = pin.text.toString()
        boxes.forEachIndexed { i, b ->
            b.text = if (i < t.length) (if (showPin) t[i].toString() else "●") else ""
            b.background = rounded(0xFFF7FAF8.toInt(), dpf(12f), if (i == t.length.coerceAtMost(3)) Ui.BRAND else Ui.LINE, dp(2))
        }
    }

    private fun showTabs() {
        tabs.removeAllViews()
        listOf(false to tr("Surya Saathi", "सूर्य साथी"), true to tr("AVIP Team", "AVIP टीम")).forEach { (isTeam, label) ->
            val on = team == isTeam
            tabs.addView(text(label, 14f, if (on) Color.WHITE else Ui.TEXT, bold = true) {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = if (on) rounded(Ui.BRAND, dpf(20f)) else null
                setOnClickListener {
                    if (team != isTeam) {
                        // Each tab remembers its own last mobile number.
                        val other = lastMobile(isTeam)
                        if (other.isNotBlank() && (mobile.text.isBlank() || mobile.text.toString() == lastMobile(team))) mobile.setText(other)
                    }
                    team = isTeam; errorView.text = ""; showTabs()
                }
            }, lp(0, WRAP, 1f))
        }
        saathiLinks.visibility = if (team) View.GONE else View.VISIBLE
        teamNote.visibility = if (team) View.VISIBLE else View.GONE
    }

    private fun login() {
        if (busy) return
        val m = mobile.text.toString().filter { it.isDigit() }
        val p = pin.text.toString()
        if (!Regex("[6-9][0-9]{9}").matches(m)) { mobile.error = tr("Enter a valid 10-digit mobile", "सही 10 अंकों का मोबाइल लिखें"); mobile.requestFocus(); return }
        if (!Regex("[0-9]{4}").matches(p)) { errorView.text = tr("Enter your 4-digit PIN", "अपना 4 अंकों का PIN लिखें"); pin.requestFocus(); return }
        busy = true
        errorView.text = ""
        loginBtn.text = tr("Logging in…", "लॉगिन हो रहा है…")
        lifecycleScope.launch {
            try {
                if (team) {
                    val o = Api.call(this@LoginActivity, "teamLogin", JSONObject().put("mobile", m).put("pin", p).put("deviceId", Vendor.installId(this@LoginActivity)))
                    Session.saveTeam(this@LoginActivity, o.getString("token"), o.getJSONObject("user"), o.optBoolean("mustSetPin"), o.optBoolean("founder"))
                } else {
                    val o = Api.call(this@LoginActivity, "login", JSONObject().put("mobile", m).put("pin", p))
                    Session.saveSaathi(this@LoginActivity, o.getString("token"), o.getJSONObject("profile"), o.optBoolean("mustSetPin"))
                }
                prefs.edit().putString(if (team) "last_mobile_team" else "last_mobile_saathi", m).apply()
                startActivity(Intent(this@LoginActivity,
                    if (Session.mustSetPin(this@LoginActivity)) SetPinActivity::class.java else MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: ApiException) {
                pin.setText("")
                errorView.text = e.message
                if (e.code == "NOTFOUND") errorView.text = e.message + "\n" + tr("Tap \"Register as Surya Saathi\" below.", "नीचे \"सूर्य साथी के रूप में रजिस्टर करें\" दबाएं।")
            } finally {
                busy = false
                loginBtn.text = tr("Login", "लॉगिन")
            }
        }
    }
}
