package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/** AVIP Cost Estimate: search and product categories at AVIP B2B rates. */
class CatalogActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var bar: View
    private lateinit var cartIcon: View
    private val cartListener = { bar.refreshTag(); cartIcon.refreshTag() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        val root = vertical()
        cartIcon = cartButton()
        root.addView(topBar(tr("AVIP Cost Estimate", "AVIP से लागत अनुमान"),
            tr("AVIP B2B rates · before GST", "AVIP B2B रेट · GST से पहले"), right = listOf(cartIcon)))
        content = vertical(14)
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        bar = cartBar()
        root.addView(bar)
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        Cart.addListener(cartListener)
        cartListener()
        build()
    }

    override fun onPause() {
        super.onPause()
        Cart.removeListener(cartListener)
    }

    private fun build() {
        content.removeAllViews()
        content.addView(horizontal {
            background = rounded(Color.WHITE, dpf(26f), Ui.LINE, dp(1))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dpf(1f)
            addView(icon(R.drawable.ic_search, Ui.MUTED, 22))
            addView(text(tr("Search panels, inverters, cables…", "Panel, Inverter, Cable खोजें…"), 15f, 0xFF8A968F.toInt()), lp(0, WRAP, 1f).margins(context, l = 10))
            setOnClickListener {
                startActivity(Intent(context, ProductsActivity::class.java).putExtra(ProductsActivity.EXTRA_SEARCH, true))
            }
        })

        content.addView(heading(tr("Browse Products", "प्रोडक्ट देखें")), lp().margins(this, t = 20, b = 10))
        val grid = GridLayout(this).apply { columnCount = 2 }
        Catalog.categories().forEach { cat ->
            val tile = vertical(14) {
                background = rounded(Color.WHITE, dpf(16f))
                elevation = dpf(2f)
                foreground = ripple()
                addView(iconBadge(cat.icon, 46, square = true))
                addView(text(cat.name, 14.5f, Ui.TEXT, bold = true) { maxLines = 2 }, lp().margins(context, t = 10))
                val from = cat.fromPrice
                addView(text(tr("${cat.availableCount} items", "${cat.availableCount} आइटम") +
                    (from?.let { tr(" · from ", " · ") + Fmt.inr(it) + tr("", " से") } ?: ""), 11.5f, Ui.MUTED), lp().margins(context, t = 2))
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
        content.addView(grid, lp().margins(this, l = -5, r = -5, b = 20))
    }
}
