package com.avip.estimatemaker

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Find Installers / Transporters: lists from AVIP's sheets, refreshed every time it opens. */
class DirectoryActivity : AppCompatActivity() {

    companion object { const val EXTRA_KIND = "kind" }

    private lateinit var kind: Directory.Kind
    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private var query = ""
    private var type: String? = null
    private var loading = true

    private val installers get() = kind == Directory.Kind.INSTALLERS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = runCatching { Directory.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }.getOrDefault(Directory.Kind.INSTALLERS)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        Directory.loadCached(this)

        val root = vertical()
        val refreshBtn = icon(R.drawable.ic_refresh, Color.WHITE, 44).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { refresh() }
        }
        root.addView(topBar(
            if (installers) tr("Find Installers", "इंस्टॉलर खोजें") else tr("Transporters", "ट्रांसपोर्टर"),
            if (installers) tr("Solar installation teams", "सोलर इंस्टॉलेशन टीम") else tr("Pickup & tempo for delivery", "सामान ढुलाई के लिए पिकअप व टेम्पो"),
            right = listOf(refreshBtn)))

        val box = input(if (installers) tr("Search name or location…", "नाम या जगह खोजें…") else tr("Search name, location or vehicle no…", "नाम, जगह या गाड़ी नंबर खोजें…"),
            InputType.TYPE_CLASS_TEXT).apply {
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
        chips = horizontal { setPadding(dp(12), dp(10), dp(12), dp(2)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        status = text("", 11.5f, Ui.MUTED) { setPadding(dp(16), dp(6), dp(16), 0) }
        root.addView(status)
        list = vertical(12)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        setEdgeContent(root)
        render()
        refresh()
    }

    private fun refresh() {
        loading = true
        render()
        lifecycleScope.launch {
            val ok = Directory.refresh(this@DirectoryActivity, kind)
            loading = false
            render()
            if (!ok) status.text = tr("Offline — showing the list saved earlier", "ऑफ़लाइन — पहले सेव की गई लिस्ट दिख रही है")
        }
    }

    private fun render() {
        val all = Directory.list(kind)
        status.text = if (loading) tr("Checking for updates…", "अपडेट जांच रहे हैं…")
        else tr("${all.size} listed · up to date", "${all.size} लिस्टेड · अपडेटेड")

        // Vehicle-type chips (transporters)
        chips.removeAllViews()
        val types = if (installers) emptyList() else all.map { it.vehicleType }.filter { it.isNotBlank() }.distinct()
        if (types.size > 1) {
            (listOf<String?>(null) + types).forEach { t ->
                val on = type == t
                chips.addView(text(t ?: tr("All", "सभी"), 13f, if (on) Color.WHITE else Ui.TEXT, bold = on) {
                    background = rounded(if (on) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (on) 0 else dp(1))
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { type = t; render() }
                }, lp(WRAP, WRAP).margins(this, r = 8))
            }
            chips.visibility = View.VISIBLE
        } else chips.visibility = View.GONE

        list.removeAllViews()
        list.addView(card(12) {
            background = rounded(0xFFFFF4E0.toInt(), dpf(14f))
            addView(text(tr("Listed by AVIP for your convenience. Please agree rates and work terms directly with them.",
                "AVIP द्वारा आपकी सुविधा के लिए सूची। रेट और काम की शर्तें सीधे उनसे तय करें।"), 12f, Ui.TEXT))
        }, lp().margins(this, b = 10))

        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val shown = all.filter { e ->
            (type == null || e.vehicleType == type) &&
                words.all { w -> "${e.name} ${e.location} ${e.area} ${e.vehicleType} ${e.vehicleNo}".lowercase().contains(w) }
        }
        if (shown.isEmpty()) {
            list.addView(text(if (loading && all.isEmpty()) tr("Loading…", "लोड हो रहा है…") else tr("No one found", "कोई नहीं मिला"),
                15f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40))
            return
        }
        shown.forEach { list.addView(entryCard(it), lp().margins(this, b = 10)) }
    }

    private fun entryCard(e: Directory.Entry): View = card(14) {
        addView(horizontal {
            addView(iconBadgeRes(if (installers) R.drawable.ic_tools else R.drawable.ic_truck,
                if (installers) 0xFFEF6C00.toInt() else 0xFF546E7A.toInt()))
            addView(vertical {
                addView(text(e.name, 15.5f, Ui.TEXT, bold = true))
                val line1 = listOf("📍 " + e.location.ifBlank { "—" },
                    if (!installers) e.vehicleType else "").filter { it.isNotBlank() }.joinToString("  ·  ")
                addView(text(line1, 12.5f, Ui.MUTED), lp().margins(context, t = 2))
                if (installers && e.area.isNotBlank()) addView(text(tr("Works in: ", "काम का क्षेत्र: ") + e.area, 12.5f, Ui.MUTED))
                if (!installers && e.vehicleNo.isNotBlank()) addView(text(e.vehicleNo, 12.5f, Ui.BRAND_DARK, bold = true) {
                    letterSpacing = 0.05f
                })
            }, lp(0, WRAP, 1f).margins(context, l = 12))
        })
        addView(horizontal {
            addView(pillButton(tr("Call", "कॉल") + "  ${e.phone}", Ui.BRAND, Color.WHITE, 42) {
                EstimateStore.call(this@DirectoryActivity, e.phone)
            }, lp(0, WRAP, 1f))
            addView(pillButton("WhatsApp", 0xFF25D366.toInt(), Color.WHITE, 42) {
                EstimateStore.chatNumber(this@DirectoryActivity, e.phone, message(e))
            }, lp(0, WRAP, 0.8f).margins(context, l = 10))
        }, lp().margins(context, t = 12))
    }

    private fun message(e: Directory.Entry): String {
        val me = Vendor.contact(this)
        val co = Vendor.company(this)
        val first = e.name.substringBefore(' ')
        return if (installers) tr(
            "Namaste $first ji, I am $me from $co (Surya Saathi, Bihar Solar Circle). I need a team for solar installation.",
            "नमस्ते $first जी, मैं $co से $me (सूर्य साथी, Bihar Solar Circle) बोल रहा हूं। मुझे सोलर इंस्टॉलेशन के लिए टीम चाहिए।")
        else tr(
            "Namaste $first ji, I am $me from $co (Surya Saathi, Bihar Solar Circle). I need a ${e.vehicleType.ifBlank { "vehicle" }.lowercase()} for solar material delivery.",
            "नमस्ते $first जी, मैं $co से $me (सूर्य साथी, Bihar Solar Circle) बोल रहा हूं। मुझे सोलर सामान ढुलाई के लिए ${e.vehicleType.ifBlank { "गाड़ी" }} चाहिए।")
    }
}

/** Coloured rounded square with a white drawable icon. */
fun android.content.Context.iconBadgeRes(res: Int, color: Int, sizeDp: Int = 46): View =
    android.widget.FrameLayout(this).apply {
        background = rounded(color, dpf(14f))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        addView(icon(res, Color.WHITE, sizeDp * 55 / 100),
            android.widget.FrameLayout.LayoutParams(dp(sizeDp * 55 / 100), dp(sizeDp * 55 / 100), Gravity.CENTER))
    }
