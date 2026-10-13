package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: greeting, search, System Builder, category tiles, estimates, AVIP contact. */
class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var bar: View
    private lateinit var cartIcon: View
    private val cartListener = { refreshCart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)

        val root = vertical()
        cartIcon = cartButton()
        val refreshBtn = icon(R.drawable.ic_refresh, Color.WHITE, 44).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { refreshPrices(true) }
        }
        val profileBtn = icon(R.drawable.ic_person, Color.WHITE, 44).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        }
        root.addView(topBar("AVIP Estimate Maker", "B2B Solar Price List", showBack = false,
            right = listOf(refreshBtn, profileBtn, cartIcon)))

        content = vertical(14)
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        bar = cartBar()
        root.addView(bar)
        setContentView(root)
        build()
        refreshPrices(false)
    }

    override fun onResume() {
        super.onResume()
        Cart.addListener(cartListener)
        refreshCart()
        Remote.flushAsync(this)
    }

    override fun onPause() {
        super.onPause()
        Cart.removeListener(cartListener)
    }

    private fun refreshCart() {
        bar.refreshTag()
        cartIcon.refreshTag()
    }

    private fun refreshPrices(manual: Boolean) {
        lifecycleScope.launch {
            val ok = Catalog.refresh(this@MainActivity)
            if (ok) { build(); refreshCart() }
            if (manual) Toast.makeText(this@MainActivity,
                if (ok) "Prices updated" else "Offline — showing saved prices", Toast.LENGTH_SHORT).show()
        }
    }

    private fun build() {
        content.removeAllViews()
        val s = Catalog.settings

        // Greeting card
        content.addView(vertical(16) {
            background = gradient(dpf(18f), 0xFF0B5A33.toInt(), 0xFF14935A.toInt())
            elevation = dpf(3f)
            addView(text("Namaste, ${Vendor.contact(context).substringBefore(' ').ifBlank { "Vendor" }} 👋", 20f, Color.WHITE, bold = true))
            addView(text(Vendor.company(context), 13f, 0xDDFFFFFF.toInt()), lp().margins(context, t = 2))
            val updated = s["price_last_updated"].ifBlank {
                SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(Catalog.updatedAt))
            }
            addView(horizontal {
                addView(text(if (Catalog.isLive) "● Live prices" else "● Saved prices (offline)", 11.5f,
                    if (Catalog.isLive) 0xFFB9F6CA.toInt() else 0xFFFFE082.toInt(), bold = true) {
                    background = rounded(0x33000000, dpf(10f))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                })
                addView(text("Price list: $updated", 11.5f, 0xDDFFFFFF.toInt()), lp(WRAP, WRAP).margins(context, l = 8))
            }, lp().margins(context, t = 12))
        })

        // Announcement from the sheet
        if (s["announcement"].isNotBlank()) {
            content.addView(card(12) {
                background = rounded(0xFFFFF4E0.toInt(), dpf(14f))
                addView(text("📢  " + s["announcement"], 13.5f, Ui.TEXT))
            }, lp().margins(this, t = 12))
        }

        // Search
        content.addView(horizontal {
            background = rounded(Color.WHITE, dpf(26f), Ui.LINE, dp(1))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dpf(1f)
            addView(icon(R.drawable.ic_search, Ui.MUTED, 22))
            addView(text("Search panels, inverters, cables…", 15f, 0xFF8A968F.toInt()), lp(0, WRAP, 1f).margins(context, l = 10))
            setOnClickListener {
                startActivity(Intent(context, ProductsActivity::class.java).putExtra(ProductsActivity.EXTRA_SEARCH, true))
            }
        }, lp().margins(this, t = 14))

        // System builder banner
        content.addView(horizontal {
            background = gradient(dpf(18f), 0xFFF7A21B.toInt(), 0xFFFFC94D.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dpf(3f)
            addView(FrameLayout(context).apply {
                background = rounded(0x33FFFFFF, dpf(14f))
                addView(icon(R.drawable.ic_bolt, Ui.TEXT, 30), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            }, lp(dp(54), dp(54)))
            addView(vertical {
                addView(text("Solar System Builder", 17f, Ui.TEXT, bold = true))
                addView(text("Pick kW → we add panels & inverter for you", 12.5f, 0xCC17211B.toInt()))
            }, lp(0, WRAP, 1f).margins(context, l = 14))
            addView(text("›", 30f, Ui.TEXT, bold = true))
            setOnClickListener { startActivity(Intent(context, BuilderActivity::class.java)) }
        }, lp().margins(this, t = 14))

        // Categories
        content.addView(text("Browse Products", 17f, Ui.TEXT, bold = true), lp().margins(this, t = 22, b = 10))
        val grid = GridLayout(this).apply { columnCount = 2 }
        Catalog.categories().forEach { cat ->
            val tile = vertical(14) {
                background = rounded(Color.WHITE, dpf(16f))
                elevation = dpf(2f)
                foreground = ripple()
                addView(iconBadge(cat.icon, 46, square = true))
                addView(text(cat.name, 14.5f, Ui.TEXT, bold = true) { maxLines = 2 }, lp().margins(context, t = 10))
                val from = cat.fromPrice
                addView(text("${cat.availableCount} items" + (from?.let { " · from ${Fmt.inr(it)}" } ?: ""), 11.5f, Ui.MUTED), lp().margins(context, t = 2))
                setOnClickListener {
                    startActivity(Intent(context, ProductsActivity::class.java).putExtra(ProductsActivity.EXTRA_CATEGORY, cat.name))
                }
            }
            grid.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        content.addView(grid, lp().margins(this, l = -5, r = -5))

        // My estimates
        content.addView(card {
            foreground = ripple()
            addView(horizontal {
                addView(FrameLayout(context).apply {
                    background = rounded(0xFFE8F3EC.toInt(), dpf(12f))
                    addView(icon(R.drawable.ic_history, Ui.BRAND, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }, lp(dp(46), dp(46)))
                addView(vertical {
                    addView(text("My Estimates", 15.5f, Ui.TEXT, bold = true))
                    addView(text("Open, share or edit saved PDFs", 12f, Ui.MUTED))
                }, lp(0, WRAP, 1f).margins(context, l = 12))
                addView(text("›", 26f, Ui.MUTED))
            })
            setOnClickListener { startActivity(Intent(context, HistoryActivity::class.java)) }
        }, lp().margins(this, t = 18))

        // Contact AVIP
        content.addView(card {
            addView(text("Need help or a special rate?", 15f, Ui.TEXT, bold = true))
            addView(text("Talk to the AVIP team" + (if (s["address"].isNotBlank()) "\n${s["address"]}" else ""), 12.5f, Ui.MUTED), lp().margins(context, t = 2, b = 12))
            addView(horizontal {
                addView(pillButton("Call AVIP", Ui.BRAND, Color.WHITE, 44) {
                    EstimateStore.call(this@MainActivity, s["phone"].ifBlank { "+919288176289" })
                }, lp(0, WRAP, 1f))
                addView(pillButton("WhatsApp", 0xFF25D366.toInt(), Color.WHITE, 44) {
                    EstimateStore.chatAvip(this@MainActivity, "Hello AVIP, I am ${Vendor.contact(this@MainActivity)} from ${Vendor.company(this@MainActivity)}. ")
                }, lp(0, WRAP, 1f).margins(context, l = 10))
            })
        }, lp().margins(this, t = 12, b = 20))
    }
}
