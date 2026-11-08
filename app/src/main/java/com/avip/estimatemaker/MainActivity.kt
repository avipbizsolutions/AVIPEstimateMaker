package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: greeting and the Surya Saathi's tools as cards. */
class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var bar: View
    private lateinit var cartIcon: View
    private val cartListener = { refreshCart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        Directory.loadCached(this)

        val root = vertical()
        cartIcon = cartButton()
        val refreshBtn = icon(R.drawable.ic_refresh, Color.WHITE, 44).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { refreshAll(true) }
        }
        val profileBtn = icon(R.drawable.ic_person, Color.WHITE, 44).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        }
        root.addView(topBar("Bihar Solar Circle",
            if (Session.isTeam(this)) tr("AVIP Team", "AVIP टीम") + " · " + Session.teamName(this) + " (" + Session.teamRoleLabel(this) + ")"
            else tr("B2B Solar Tools by AVIP", "AVIP के B2B सोलर टूल्स"), showBack = false,
            right = listOf(langToggle(), refreshBtn, profileBtn, cartIcon)))

        content = vertical(14)
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        bar = cartBar()
        root.addView(bar)
        setEdgeContent(root)
        build()
        refreshAll(false)
        Store.onHomeOpened(this)
        checkLogin()
    }

    /** Still allowed in? (PIN reset, blocked or removed in the sheet / directory → login again.) */
    private fun checkLogin() {
        lifecycleScope.launch {
            try {
                if (Session.isTeam(this@MainActivity)) {
                    Api.call(this@MainActivity, "teamMe", JSONObject().put("token", Session.token(this@MainActivity)))
                } else if (Session.isSaathi(this@MainActivity)) {
                    val o = Api.call(this@MainActivity, "me", JSONObject().put("token", Session.token(this@MainActivity)))
                    o.optJSONObject("profile")?.let { Vendor.store(this@MainActivity, it) }
                    if (o.optBoolean("mustSetPin")) startActivity(Intent(this@MainActivity, SetPinActivity::class.java))
                }
            } catch (e: ApiException) {
                if (e.code in listOf("SESSION", "BLOCKED", "NOTEAM")) goLogin(this@MainActivity, e.message)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Cart.addListener(cartListener)
        refreshCart()
        build()
        Remote.flushAsync(this)
        DriveSync.syncPendingAsync(this)
    }

    override fun onPause() {
        super.onPause()
        Cart.removeListener(cartListener)
    }

    private fun refreshCart() {
        bar.refreshTag()
        cartIcon.refreshTag()
    }

    private fun refreshAll(manual: Boolean) {
        lifecycleScope.launch {
            val ok = Catalog.refresh(this@MainActivity)
            Directory.refreshAll(this@MainActivity)
            if (ok) { build(); refreshCart() } else build()
            if (manual) Toast.makeText(this@MainActivity,
                if (ok) tr("Prices updated", "रेट अपडेट हो गए") else tr("Offline — showing saved prices", "इंटरनेट नहीं — सेव किए रेट दिख रहे हैं"),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(cls: Class<*>, block: Intent.() -> Unit = {}) =
        startActivity(Intent(this, cls).apply(block))

    private fun build() {
        content.removeAllViews()
        val s = Catalog.settings

        // Greeting card
        content.addView(vertical(16) {
            background = gradient(dpf(18f), 0xFF0B5A33.toInt(), 0xFF14935A.toInt())
            elevation = dpf(3f)
            val team = Session.isTeam(context)
            val first = Session.myName(context).substringBefore(' ').ifBlank { "Surya Saathi" }
            addView(text(tr("Namaste, $first 👋", "नमस्ते, $first 👋"), 20f, Color.WHITE, bold = true))
            addView(text(if (team) "AVIP Team · " + Session.teamRoleLabel(context) else "Surya Saathi · " + Vendor.company(context),
                13f, 0xDDFFFFFF.toInt()), lp().margins(context, t = 2))
            val updated = s["price_last_updated"].ifBlank {
                SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(Catalog.updatedAt))
            }
            addView(horizontal {
                addView(text(if (Catalog.isLive) tr("● Live prices", "● लाइव रेट") else tr("● Saved prices (offline)", "● सेव किए रेट (ऑफ़लाइन)"), 11.5f,
                    if (Catalog.isLive) 0xFFB9F6CA.toInt() else 0xFFFFE082.toInt(), bold = true) {
                    background = rounded(0x33000000, dpf(10f))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                })
                addView(text(tr("Price list: ", "रेट लिस्ट: ") + updated, 11.5f, 0xDDFFFFFF.toInt()), lp(WRAP, WRAP).margins(context, l = 8))
            }, lp().margins(context, t = 12))
        })

        // Announcement from the sheet
        if (s["announcement"].isNotBlank()) {
            content.addView(card(12) {
                background = rounded(0xFFFFF4E0.toInt(), dpf(14f))
                addView(text("📢  " + s["announcement"], 13.5f, Ui.TEXT))
            }, lp().margins(this, t = 12))
        }

        // GitHub build only: point users to the Google Play version once it is live
        // (App Settings → play_store_link).
        val playLink = s["play_store_link"]
        if (!Store.IS_PLAY && playLink.startsWith("https://")) {
            content.addView(card(14) {
                background = rounded(0xFFE8F3EC.toInt(), dpf(14f), Ui.BRAND, dp(1))
                addView(text(tr("Bihar Solar Circle is now on Google Play", "Bihar Solar Circle अब Google Play पर है"), 15f, Ui.TEXT, bold = true))
                addView(text(tr("Install the Play Store version to keep getting updates. Then register once more there and uninstall this copy.",
                    "अपडेट पाते रहने के लिए Play Store वाला ऐप इंस्टॉल करें। वहां एक बार फिर रजिस्टर करें और यह ऐप हटा दें।"), 12.5f, Ui.MUTED), lp().margins(context, t = 4, b = 10))
                addView(pillButton(tr("Get it on Google Play  ›", "Google Play से इंस्टॉल करें  ›"), Ui.BRAND, Color.WHITE, 44) { openWeb(playLink) })
            }, lp().margins(this, t = 12))
        }

        // Tools
        content.addView(heading(tr("My Tools", "मेरे टूल्स")), lp().margins(this, t = 20, b = 8))
        if (Session.isTeam(this)) {
            SaathiDirectory.loadCached(this)
            val target = TeamTarget.get(this)
            val n = SaathiDirectory.list.count { it.status.equals("Active", true) }
            content.addView(toolGrid(listOf(
                toolCard(R.drawable.ic_cart, Ui.BRAND,
                    tr("New Cost Estimate", "नया लागत अनुमान"),
                    tr("For a B2B customer, at AVIP rates", "B2B ग्राहक के लिए, AVIP रेट पर"),
                    badge = if (Cart.count > 0 && target != null) tr("${Cart.count} items", "${Cart.count} आइटम") else null) {
                    if (Cart.count > 0 && target != null) open(EstimateActivity::class.java)
                    else open(SaathiListActivity::class.java) { putExtra(SaathiListActivity.EXTRA_PICK, true) }
                },
                toolCard(R.drawable.ic_history, 0xFF00897B.toInt(),
                    tr("Cost Estimates I made", "मेरे बनाए लागत अनुमान"),
                    tr("Open, share or edit saved estimates", "सेव किए अनुमान खोलें, भेजें या बदलें")) {
                    open(HistoryActivity::class.java)
                },
                toolCard(R.drawable.ic_person, 0xFF1565C0.toInt(),
                    tr("Surya Saathis", "सूर्य साथी"),
                    if (n > 0) tr("$n active B2B customers", "$n सक्रिय B2B ग्राहक") else tr("Registered B2B customers", "रजिस्टर्ड B2B ग्राहक")) {
                    open(SaathiListActivity::class.java)
                },
                toolCard(R.drawable.ic_tools, 0xFFEF6C00.toInt(),
                    tr("Find Installers", "इंस्टॉलर खोजें"),
                    tr("${Directory.installers.size} installers · call or WhatsApp", "${Directory.installers.size} इंस्टॉलर · कॉल या WhatsApp")) {
                    open(DirectoryActivity::class.java) { putExtra(DirectoryActivity.EXTRA_KIND, Directory.Kind.INSTALLERS.name) }
                },
                toolCard(R.drawable.ic_truck, 0xFF546E7A.toInt(),
                    tr("Transporters", "ट्रांसपोर्टर"),
                    tr("${Directory.transporters.size} pickups & tempos", "${Directory.transporters.size} पिकअप व टेम्पो")) {
                    open(DirectoryActivity::class.java) { putExtra(DirectoryActivity.EXTRA_KIND, Directory.Kind.TRANSPORTERS.name) }
                },
            )), lp().margins(this, l = -5, r = -5, b = 20))
            return
        }
        val quotes = SalesStore.count(this, SaleType.QUOTE)
        val invoices = SalesStore.count(this, SaleType.INVOICE)
        val installers = Directory.installers.size
        val vehicles = Directory.transporters.size
        val drive = DriveSync.isLinked(this)
        val pending = if (drive) SalesStore.pendingDrive(this) else 0
        content.addView(toolGrid(listOf(
            toolCard(R.drawable.ic_cart, Ui.BRAND,
                tr("AVIP Cost Estimate", "AVIP से लागत अनुमान"),
                tr("See AVIP B2B rates and make a cost estimate PDF", "AVIP के B2B रेट देखें और लागत अनुमान PDF बनाएं"),
                badge = if (Cart.count > 0) tr("${Cart.count} items", "${Cart.count} आइटम") else null) {
                open(CatalogActivity::class.java)
            },
            toolCard(R.drawable.ic_history, 0xFF00897B.toInt(),
                tr("My Cost Estimates", "मेरे लागत अनुमान"),
                tr("Open, share or edit saved AVIP estimates", "सेव किए AVIP अनुमान खोलें, भेजें या बदलें")) {
                open(HistoryActivity::class.java)
            },
            toolCard(R.drawable.ic_quote, 0xFF1565C0.toInt(),
                tr("Customer Quotation", "ग्राहक कोटेशन"),
                tr("Your quotation to your own customer", "अपने ग्राहक के लिए अपना कोटेशन"),
                badge = if (quotes > 0) "$quotes" else null) {
                open(SalesListActivity::class.java) { putExtra(SalesListActivity.EXTRA_TYPE, SaleType.QUOTE.name) }
            },
            toolCard(R.drawable.ic_receipt, 0xFF6A1B9A.toInt(),
                tr("GST Tax Invoice", "GST टैक्स इनवॉइस"),
                tr("GST-compliant bill for your customer", "अपने ग्राहक के लिए GST नियमों वाला बिल"),
                badge = if (invoices > 0) "$invoices" else null) {
                open(SalesListActivity::class.java) { putExtra(SalesListActivity.EXTRA_TYPE, SaleType.INVOICE.name) }
            },
            toolCard(R.drawable.ic_tools, 0xFFEF6C00.toInt(),
                tr("Find Installers", "इंस्टॉलर खोजें"),
                if (installers > 0) tr("$installers installers · call or WhatsApp", "$installers इंस्टॉलर · कॉल या WhatsApp करें")
                else tr("Solar installation teams near you", "आपके पास के सोलर इंस्टॉलेशन टीम")) {
                open(DirectoryActivity::class.java) { putExtra(DirectoryActivity.EXTRA_KIND, Directory.Kind.INSTALLERS.name) }
            },
            toolCard(R.drawable.ic_truck, 0xFF546E7A.toInt(),
                tr("Transporters", "ट्रांसपोर्टर"),
                if (vehicles > 0) tr("$vehicles pickups & tempos for delivery", "सामान ढुलाई के लिए $vehicles पिकअप व टेम्पो")
                else tr("Pickup & tempo for material delivery", "सामान ढुलाई के लिए पिकअप व टेम्पो")) {
                open(DirectoryActivity::class.java) { putExtra(DirectoryActivity.EXTRA_KIND, Directory.Kind.TRANSPORTERS.name) }
            },
            toolCard(R.drawable.ic_cloud, 0xFF0F9D58.toInt(),
                "Google Drive",
                tr("Quotations & invoices saved to your Drive", "कोटेशन व इनवॉइस आपकी Drive में सेव"),
                badge = when {
                    !drive -> tr("Not linked", "लिंक नहीं")
                    pending > 0 -> tr("$pending to upload", "$pending बाकी")
                    else -> tr("Linked ✓", "लिंक है ✓")
                }) {
                open(DriveActivity::class.java)
            },
            toolCard(R.drawable.ic_person, 0xFF5D4037.toInt(),
                tr("My Business", "मेरा व्यवसाय"),
                tr("Address, bank & invoice settings", "पता, बैंक व इनवॉइस सेटिंग")) {
                open(BusinessActivity::class.java)
            },
        )), lp().margins(this, l = -5, r = -5))

        // Contact AVIP
        content.addView(card {
            addView(text(tr("Need help or a special rate?", "मदद या विशेष रेट चाहिए?"), 15f, Ui.TEXT, bold = true))
            addView(text(tr("Talk to the AVIP team", "AVIP टीम से बात करें") + (if (s["address"].isNotBlank()) "\n${s["address"]}" else ""), 12.5f, Ui.MUTED), lp().margins(context, t = 2, b = 12))
            addView(horizontal {
                addView(pillButton(tr("Call AVIP", "AVIP को कॉल करें"), Ui.BRAND, Color.WHITE, 44) {
                    EstimateStore.call(this@MainActivity, s["phone"].ifBlank { "+919288176289" })
                }, lp(0, WRAP, 1f))
                addView(pillButton("WhatsApp", 0xFF25D366.toInt(), Color.WHITE, 44) {
                    EstimateStore.chatAvip(this@MainActivity, "Hello AVIP, I am ${Vendor.contact(this@MainActivity)} from ${Vendor.company(this@MainActivity)}. I need help regarding order placement.")
                }, lp(0, WRAP, 1f).margins(context, l = 10))
            })
        }, lp().margins(this, t = 16))

        content.addView(grievanceCard(), lp().margins(this, t = 12))
        content.addView(privacyLink(), lp(WRAP, WRAP).margins(this, t = 8, b = 20).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
}
