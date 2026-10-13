package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Solar System Builder: choose a system size, panel and inverter; the app works out
 * how many panels are needed and adds everything to the estimate in one tap.
 */
class BuilderActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var summary: LinearLayout

    private var kw = 3.0
    private var dcr = true
    private var hybrid = false
    private var panel: Product? = null
    private var inverter: Product? = null
    private var battery: Product? = null
    private var batteries = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        val root = vertical()
        root.addView(topBar("Solar System Builder", "Panels + inverter in one go"))
        body = vertical(14)
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        summary = vertical { setPadding(dp(16), dp(12), dp(16), dp(14)); setBackgroundColor(Color.WHITE); elevation = dpf(10f) }
        root.addView(summary)
        setContentView(root)
        render()
    }

    private fun isDcr(p: Product) = p.category.lowercase().let { it.contains("dcr") && !it.contains("non") }

    private fun panelOptions(count: (Product) -> Int) = Catalog.products.filter {
        it.available && it.perWp && it.panelWp > 0 && isDcr(it) == dcr && it.minQty <= count(it)
    }

    private fun panelCount(p: Product) = ceil(kw * 1000.0 / p.panelWp - 1e-9).toInt().coerceAtLeast(1)

    private fun inverterOptions() = Catalog.products.filter {
        it.available && it.kw != null && it.icon == (if (hybrid) "hybrid" else "inverter")
    }.sortedBy { it.kw }

    private fun batteryOptions() = Catalog.products.filter { it.available && it.icon == "battery" }

    private fun render() {
        val panels = panelOptions { panelCount(it) }
        if (panel !in panels) panel = panels.minByOrNull { it.unitPrice!! * panelCount(it) }
        val invs = inverterOptions()
        if (inverter !in invs) {
            // smallest inverter that covers the system, else the biggest available
            inverter = invs.filter { it.kw!! >= kw * 0.9 }.minByOrNull { abs(it.kw!! - kw) * 1000 + it.unitPrice!! / 1000 }
                ?: invs.maxByOrNull { it.kw!! }
        }
        val bats = batteryOptions()
        if (battery !in bats) battery = bats.minByOrNull { it.unitPrice!! }

        body.removeAllViews()

        // 1. Size
        body.addView(step("1", "System size"))
        body.addView(card(16) {
            addView(horizontal {
                gravity = Gravity.CENTER
                addView(roundIcon(R.drawable.ic_minus) { if (kw > 1.0) { kw -= 0.5; render() } })
                addView(vertical {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(text(Fmt.num(kw), 40f, Ui.BRAND_DARK, bold = true))
                    addView(text("kW", 14f, Ui.MUTED, bold = true))
                }, lp(dp(140), WRAP))
                addView(roundIcon(R.drawable.ic_plus) { if (kw < 100) { kw += 0.5; render() } })
            })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(horizontal {
                    listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).forEach { v ->
                        addView(chip("${Fmt.num(v)} kW", v == kw) { kw = v; render() }, lp(WRAP, WRAP).margins(context, r = 8))
                    }
                })
            }, lp().margins(context, t = 14))
        }, lp().margins(this, b = 18))

        // 2. Panels
        body.addView(step("2", "Solar panels"))
        body.addView(horizontal {
            addView(chip("DCR (for PM Surya Ghar)", dcr) { dcr = true; panel = null; render() }, lp(WRAP, WRAP).margins(context, r = 8))
            addView(chip("Non-DCR", !dcr) { dcr = false; panel = null; render() })
        }, lp().margins(this, b = 10))
        if (panels.isEmpty()) body.addView(text("No panels with a listed rate right now.", 13f, Ui.MUTED), lp().margins(this, b = 18))
        panels.forEach { p ->
            val n = panelCount(p)
            body.addView(option(
                p.icon, p.title + " " + p.spec,
                "$n panels = ${Fmt.num(n * p.panelWp / 1000)} kWp" + if (p.minQty > 1) " · pallet rate" else "",
                Fmt.inr(p.unitPrice!! * n), "₹${Fmt.num(p.rate!!)}/Wp", p == panel
            ) { panel = p; render() }, lp().margins(this, b = 8))
        }

        // 3. Inverter
        body.addView(step("3", "Inverter"), lp().margins(this, t = 10))
        body.addView(horizontal {
            addView(chip("On-grid", !hybrid) { hybrid = false; inverter = null; render() }, lp(WRAP, WRAP).margins(context, r = 8))
            addView(chip("Hybrid (with battery)", hybrid) { hybrid = true; inverter = null; render() })
        }, lp().margins(this, b = 10))
        if (invs.isEmpty()) body.addView(text("No inverters with a listed rate right now.", 13f, Ui.MUTED))
        invs.forEach { p ->
            val warn = if (p.kw!! < kw * 0.9) " · smaller than system" else ""
            body.addView(option(p.icon, p.title, p.spec + warn, Fmt.inr(p.unitPrice!!), "+${Fmt.num(p.gst)}% GST", p == inverter) {
                inverter = p; render()
            }, lp().margins(this, b = 8))
        }

        // 4. Battery (hybrid only)
        if (hybrid) {
            body.addView(step("4", "Lithium battery"), lp().margins(this, t = 10))
            bats.forEach { p ->
                body.addView(option(p.icon, p.title, p.spec, Fmt.inr(p.unitPrice!!), "+${Fmt.num(p.gst)}% GST", p == battery) {
                    battery = p; render()
                }, lp().margins(this, b = 8))
            }
            if (bats.isNotEmpty()) body.addView(horizontal {
                addView(text("Number of batteries", 14f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(roundIcon(R.drawable.ic_minus) { if (batteries > 1) { batteries--; render() } })
                addView(text("$batteries", 18f, Ui.BRAND_DARK, bold = true) { gravity = Gravity.CENTER }, lp(dp(44), WRAP))
                addView(roundIcon(R.drawable.ic_plus) { if (batteries < 20) { batteries++; render() } })
            }, lp().margins(this, t = 4, b = 8))
        }

        body.addView(text("Structure, ACDB/DCDB, cables and earthing can be added from the categories on the next screen.",
            12.5f, Ui.MUTED), lp().margins(this, t = 12, b = 20))

        renderSummary()
    }

    private fun renderSummary() {
        summary.removeAllViews()
        val p = panel
        val n = p?.let { panelCount(it) } ?: 0
        var total = 0.0
        if (p != null) total += p.unitPrice!! * n * (1 + p.gst / 100)
        inverter?.let { total += it.unitPrice!! * (1 + it.gst / 100) }
        if (hybrid) battery?.let { total += it.unitPrice!! * batteries * (1 + it.gst / 100) }
        summary.addView(horizontal {
            addView(vertical {
                addView(text(if (p != null) "$n panels · ${Fmt.num(n * p.panelWp / 1000)} kWp" else "Choose a panel", 14f, Ui.TEXT, bold = true))
                addView(text("≈ ${Fmt.inr(total)} incl. GST", 12.5f, Ui.MUTED))
            }, lp(0, WRAP, 1f))
            addView(pillButton("Add to Estimate", Ui.BRAND, Color.WHITE, 48) { addAll() })
        })
    }

    private fun addAll() {
        val p = panel ?: run { Toast.makeText(this, "Choose a panel first", Toast.LENGTH_SHORT).show(); return }
        Cart.setQty(this, p, panelCount(p).toDouble())
        inverter?.let { Cart.setQty(this, it, Cart.qtyOf(it.id) + 1) }
        if (hybrid) battery?.let { Cart.setQty(this, it, Cart.qtyOf(it.id) + batteries) }
        Toast.makeText(this, "Added to estimate", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, EstimateActivity::class.java))
        finish()
    }

    // ---- small views ----

    private fun step(n: String, title: String): View = horizontal {
        addView(text(n, 13f, Color.WHITE, bold = true) {
            gravity = Gravity.CENTER
            background = rounded(Ui.BRAND, dpf(13f))
        }, lp(dp(26), dp(26)))
        addView(text(title, 16.5f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, l = 10))
    }.also { it.layoutParams = lp().margins(this, b = 10) }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): View =
        text(label, 13.5f, if (selected) Color.WHITE else Ui.TEXT, bold = selected) {
            background = rounded(if (selected) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (selected) 0 else dp(1))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { onClick() }
        }

    private fun roundIcon(res: Int, onClick: () -> Unit): View =
        icon(res, Ui.BRAND, 46).apply {
            background = rounded(0xFFE8F3EC.toInt(), dpf(23f))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { onClick() }
        }

    private fun option(icon: String, title: String, sub: String, price: String, priceSub: String, selected: Boolean, onClick: () -> Unit): View =
        horizontal {
            background = rounded(if (selected) 0xFFE8F3EC.toInt() else Color.WHITE, dpf(14f),
                if (selected) Ui.BRAND else Ui.LINE, dp(if (selected) 2 else 1))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(iconBadge(icon, 38, square = true))
            addView(vertical {
                addView(text(title, 14f, Ui.TEXT, bold = true))
                addView(text(sub, 12f, Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 12, r = 8))
            addView(vertical {
                gravity = Gravity.END
                addView(text(price, 14.5f, Ui.BRAND_DARK, bold = true))
                addView(text(priceSub, 11f, Ui.MUTED))
            })
            setOnClickListener { onClick() }
        }
}
