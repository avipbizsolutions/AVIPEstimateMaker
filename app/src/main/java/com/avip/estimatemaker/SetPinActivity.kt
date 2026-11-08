package com.avip.estimatemaker

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Set your own PIN: required after the default 1234, also used for "Change PIN". */
class SetPinActivity : AppCompatActivity() {

    private lateinit var pin1: EditText
    private lateinit var pin2: EditText
    private lateinit var errorView: TextView
    private lateinit var saveBtn: TextView
    private var busy = false
    private var forced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forced = Session.mustSetPin(this)
        val root = vertical()
        root.addView(topBar(tr("Set your own PIN", "अपना PIN बनाएं"),
            if (forced) tr("Needed once, instead of 1234", "1234 की जगह, केवल एक बार") else tr("Change your login PIN", "लॉगिन PIN बदलें"),
            showBack = !forced))
        val form = vertical(18)
        root.addView(ScrollView(this).apply { addView(form) }, lp(MATCH, 0, 1f))

        fun pinBox() = input("• • • •", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
            transformationMethod = PasswordTransformationMethod.getInstance()
            letterSpacing = 0.4f
        }
        pin1 = pinBox()
        pin2 = pinBox()
        errorView = text("", 12.5f, Ui.DANGER)
        saveBtn = pillButton(tr("Save & continue", "सेव करें और आगे बढ़ें"), Ui.BRAND) { save() }
        form.addView(card(18) {
            addView(iconBadge("kit", 46).also { (it.layoutParams as android.widget.LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
            addView(text(
                if (Session.isTeam(context)) tr("This is your common AVIP login PIN: it changes for AVIP Solar Sales, Installation and Expense too.",
                    "यह आपका सामान्य AVIP लॉगिन PIN है: AVIP Solar Sales, Installation और Expense के लिए भी बदल जाएगा।")
                else tr("Choose a 4-digit PIN that only you know. You'll use it with your mobile number to login.",
                    "ऐसा 4 अंकों का PIN चुनें जो केवल आपको पता हो। लॉगिन के लिए मोबाइल नंबर के साथ इसका उपयोग करें।"),
                13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 10, b = 6))
            addView(text(tr("New PIN", "नया PIN"), 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 10, b = 6))
            addView(pin1)
            addView(text(tr("Confirm new PIN", "नया PIN दोबारा"), 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 12, b = 6))
            addView(pin2)
            addView(errorView, lp().margins(context, t = 8))
            addView(saveBtn, lp().margins(context, t = 12))
        })
        if (forced) form.addView(text(tr("Logout", "लॉगआउट"), 13.5f, Ui.MUTED, bold = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                Session.logout(this@SetPinActivity)
                startActivity(Intent(this@SetPinActivity, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        })
        setEdgeContent(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (forced) moveTaskToBack(true) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun save() {
        if (busy) return
        val a = pin1.text.toString()
        val b = pin2.text.toString()
        errorView.text = ""
        when {
            !Regex("[0-9]{4}").matches(a) -> { pin1.errorView = tr("4 digits", "4 अंक"); return }
            a == "1234" -> { pin1.errorView = tr("Choose a PIN other than 1234", "1234 के अलावा कोई PIN चुनें"); return }
            a.toSet().size == 1 || a in listOf("0123", "1234", "2345", "3456", "4567", "5678", "6789", "9876", "4321") ->
                { pin1.errorView = tr("Too easy to guess — choose another", "बहुत आसान है — कोई दूसरा चुनें"); return }
            a != b -> { pin2.errorView = tr("PINs don't match", "PIN मेल नहीं खाते"); return }
        }
        busy = true
        saveBtn.text = tr("Saving…", "सेव हो रहा है…")
        lifecycleScope.launch {
            try {
                val body = JSONObject().put("token", Session.token(this@SetPinActivity)).put("newPin", a)
                val o = Api.call(this@SetPinActivity, if (Session.isTeam(this@SetPinActivity)) "teamSetPin" else "setPin", body)
                Session.pinChanged(this@SetPinActivity, o.optString("token").ifBlank { Session.token(this@SetPinActivity) })
                o.optJSONObject("profile")?.let { Vendor.store(this@SetPinActivity, it) }
                Toast.makeText(this@SetPinActivity, tr("PIN saved", "PIN सेव हो गया"), Toast.LENGTH_SHORT).show()
                if (isTaskRoot || forced) startActivity(Intent(this@SetPinActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: ApiException) {
                errorView.text = e.message
                if (e.code == "SESSION" || e.code == "BLOCKED") goLogin(this@SetPinActivity, e.message)
            } finally {
                busy = false
                saveBtn.text = tr("Save & continue", "सेव करें और आगे बढ़ें")
            }
        }
    }
}

/** Session no longer valid (PIN reset, blocked, removed): back to the login screen. */
fun goLogin(a: android.app.Activity, message: String?) {
    val wasTeam = Session.isTeam(a)
    Session.logout(a)
    a.startActivity(Intent(a, LoginActivity::class.java)
        .putExtra(LoginActivity.EXTRA_MESSAGE, message ?: "")
        .putExtra(LoginActivity.EXTRA_TEAM, wasTeam)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    a.finish()
}
