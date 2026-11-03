package com.avip.estimatemaker

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

/** Link the Surya Saathi's own Google Drive, see upload status, unlink. */
class DriveActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private var busy = false

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            val token = result.accessToken
            if (token != null) finishLinking(token) else { busy = false; render() }
        } catch (e: ApiException) {
            busy = false; render()
            if (e.statusCode != 16) showSetupProblem(e) // 16 = cancelled by the person
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()
        root.addView(topBar("Google Drive", tr("Your quotations & invoices backup", "आपके कोटेशन व इनवॉइस का बैकअप")))
        body = vertical(16)
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        val linked = DriveSync.isLinked(this)
        val pending = SalesStore.pendingDrive(this)

        body.addView(card(18) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(iconBadgeRes(R.drawable.ic_cloud, 0xFF0F9D58.toInt(), 60))
            addView(text(if (linked) tr("Google Drive is linked", "Google Drive लिंक है") else tr("Link your Google Drive", "अपनी Google Drive लिंक करें"),
                18f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 12))
            if (linked && DriveSync.email(context).isNotBlank())
                addView(text(DriveSync.email(context), 13.5f, Ui.BRAND_DARK, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 2))
            addView(text(
                if (linked) tr("Every customer quotation and tax invoice you make is saved as a PDF in the \"Bihar Solar Circle\" folder of your Drive. Only you can see it.",
                    "आपका हर ग्राहक कोटेशन और टैक्स इनवॉइस आपकी Drive के \"Bihar Solar Circle\" फ़ोल्डर में PDF के रूप में सेव होता है। इसे केवल आप देख सकते हैं।")
                else tr("Keep a safe copy of all your customer quotations and tax invoices in your own Google Drive — even if you change or lose your phone. The app can only see the files it creates; nothing else in your Drive.",
                    "अपने सभी ग्राहक कोटेशन और टैक्स इनवॉइस की सुरक्षित कॉपी अपनी Google Drive में रखें — फ़ोन बदलने या खोने पर भी। ऐप केवल अपनी बनाई फ़ाइलें देख सकता है; आपकी Drive की बाकी चीज़ें नहीं।"),
                13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 10))
        })

        if (!linked) {
            body.addView(pillButton(if (busy) tr("Linking…", "लिंक हो रहा है…") else tr("Link Google Drive", "Google Drive लिंक करें"), 0xFF0F9D58.toInt(), Color.WHITE, 52) {
                if (!busy) startLinking()
            }, lp().margins(this, t = 16))
            return
        }

        body.addView(card(14) {
            addView(text(tr("Upload status", "अपलोड स्थिति"), 15f, Ui.TEXT, bold = true))
            val err = DriveSync.lastError(context)
            addView(text(when {
                busy -> tr("Uploading…", "अपलोड हो रहा है…")
                pending == 0 -> tr("✓ All documents are saved in Drive", "✓ सभी डॉक्यूमेंट Drive में सेव हैं")
                err == "auth" -> tr("$pending waiting — please tap Upload now and allow access again", "$pending बाकी — अभी अपलोड करें दबाएं और फिर से अनुमति दें")
                else -> tr("$pending waiting to upload (needs internet)", "$pending अपलोड होना बाकी (इंटरनेट चाहिए)")
            }, 13f, if (pending == 0) Ui.BRAND else 0xFFB26A00.toInt()), lp().margins(context, t = 4))
            if (pending > 0) addView(pillButton(tr("Upload now", "अभी अपलोड करें"), Ui.BRAND, Color.WHITE, 44) { if (!busy) startLinking() },
                lp().margins(context, t = 10))
            val folder = DriveSync.rootFolder(context)
            if (folder.isNotBlank()) addView(pillButton(tr("Open my Drive folder", "मेरा Drive फ़ोल्डर खोलें"), Color.WHITE, Ui.BRAND, 44) {
                openWeb("https://drive.google.com/drive/folders/$folder")
            }.apply { background = rounded(Color.WHITE, dpf(22f), Ui.BRAND, dp(1)) }, lp().margins(context, t = 10))
        }, lp().margins(this, t = 14))

        body.addView(text(tr("Unlink Google Drive", "Google Drive अनलिंक करें"), 13.5f, Ui.DANGER, bold = true) {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                AlertDialog.Builder(this@DriveActivity)
                    .setMessage(tr("Stop saving to this Google Drive? Files already saved there stay in your Drive.",
                        "इस Google Drive में सेव करना बंद करें? जो फ़ाइलें पहले से सेव हैं वे आपकी Drive में रहेंगी।"))
                    .setPositiveButton(tr("Unlink", "अनलिंक करें")) { _, _ -> DriveSync.unlink(this@DriveActivity); render() }
                    .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
            }
        }, lp().margins(this, t = 8))
    }

    private fun startLinking() {
        busy = true; render()
        lifecycleScope.launch {
            try {
                val r = DriveSync.authorize(this@DriveActivity)
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else r.accessToken?.let { finishLinking(it) } ?: run { busy = false; render() }
            } catch (e: ApiException) {
                busy = false; render(); showSetupProblem(e)
            } catch (e: Exception) {
                busy = false; render()
                Toast.makeText(this@DriveActivity, tr("Couldn't reach Google. Check internet.", "Google से संपर्क नहीं हुआ। इंटरनेट जांचें।"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishLinking(token: String) {
        busy = true; render()
        lifecycleScope.launch {
            val n = runCatching { DriveSync.onAuthorized(this@DriveActivity, token) }.getOrDefault(0)
            busy = false; render()
            Toast.makeText(this@DriveActivity,
                if (n > 0) tr("Linked · $n documents uploaded", "लिंक हो गया · $n डॉक्यूमेंट अपलोड हुए") else tr("Google Drive linked", "Google Drive लिंक हो गई"),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun showSetupProblem(e: ApiException) {
        AlertDialog.Builder(this)
            .setTitle(tr("Google Drive not available yet", "Google Drive अभी उपलब्ध नहीं"))
            .setMessage(tr("Google didn't allow the link (code ${e.statusCode}). AVIP is completing the Google Drive setup — please try again later. Your documents are safe on this phone meanwhile.",
                "Google ने लिंक की अनुमति नहीं दी (कोड ${e.statusCode})। AVIP Google Drive सेटअप पूरा कर रहा है — कृपया बाद में फिर कोशिश करें। तब तक आपके डॉक्यूमेंट इस फ़ोन में सुरक्षित हैं।"))
            .setPositiveButton(tr("OK", "ठीक है"), null)
            .show()
    }
}
