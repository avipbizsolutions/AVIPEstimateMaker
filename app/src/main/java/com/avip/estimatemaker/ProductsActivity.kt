package com.avip.estimatemaker

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Product list for one category, or search across all products. */
class ProductsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_SEARCH = "search"
    }

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var bar: View
    private lateinit var cartIcon: View
    private var category: String? = null
    private var query = ""
    private var brand: String? = null
    private val cartListener = { bar.refreshTag(); cartIcon.refreshTag() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        category = intent.getStringExtra(EXTRA_CATEGORY)
        val searchMode = intent.getBooleanExtra(EXTRA_SEARCH, false)

        val root = vertical()
        cartIcon = cartButton()
        val count = Catalog.products.count { category == null || it.category == category }
        root.addView(topBar(category ?: "Search Products", if (category != null) "$count items · rates before GST" else "All categories", right = listOf(cartIcon)))

        if (searchMode) {
            val box = input("Type product, brand or size…", InputType.TYPE_CLASS_TEXT).apply {
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
            box.requestFocus()
            box.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        chips = horizontal { setPadding(dp(12), dp(10), dp(12), dp(2)) }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        list = vertical(12)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        bar = cartBar()
        root.addView(bar)
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        Cart.addListener(cartListener)
        cartListener()
        render()
    }

    override fun onPause() {
        super.onPause()
        Cart.removeListener(cartListener)
    }

    private fun source(): List<Product> =
        if (category != null) Catalog.products.filter { it.category == category }
        else Catalog.search(query)

    private fun render() {
        val items = source()
        // Brand chips
        chips.removeAllViews()
        val brands = items.map { it.brand }.filter { it.isNotBlank() }.distinct()
        if (brand != null && brand !in brands) brand = null
        if (brands.size > 1) {
            (listOf<String?>(null) + brands).forEach { b ->
                val selected = brand == b
                chips.addView(text(b ?: "All brands", 13f, if (selected) Color.WHITE else Ui.TEXT, bold = selected) {
                    background = rounded(if (selected) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (selected) 0 else dp(1))
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { brand = b; render() }
                }, lp(WRAP, WRAP).margins(this, r = 8))
            }
            chips.visibility = View.VISIBLE
        } else chips.visibility = View.GONE

        list.removeAllViews()
        val shown = items.filter { brand == null || it.brand == brand }
            .sortedBy { if (it.available) 0 else 1 }
        if (shown.isEmpty()) {
            list.addView(text(if (category == null && query.isBlank()) "Start typing to search all products" else "No products found",
                15f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 60))
            return
        }
        var lastCat = ""
        shown.forEach { p ->
            if (category == null && p.category != lastCat) {
                lastCat = p.category
                list.addView(text(p.category, 13f, Ui.BRAND, bold = true), lp().margins(this, t = 8, b = 6, l = 4))
            }
            list.addView(productCard(this, p) { render() }, lp().margins(this, b = 10))
        }
    }
}

/** A product card with price and an Add / − qty + control. Used by list screens. */
fun productCard(a: Activity, p: Product, onChange: () -> Unit): View = with(a) {
    card(12) {
        addView(horizontal {
            gravity = Gravity.TOP
            addView(productPhoto(p, p.icon, 76))
            addView(vertical {
                if (p.brand.isNotBlank()) addView(text(p.brand.uppercase(), 10.5f, Ui.categoryColor(p.icon), bold = true) {
                    letterSpacing = 0.06f
                })
                addView(text(p.name, 15f, Ui.TEXT, bold = true))
                val sub = listOf(p.spec, "per ${p.unit.lowercase()}").filter { it.isNotBlank() }.joinToString(" · ")
                addView(text(sub, 12f, Ui.MUTED), lp().margins(context, t = 2))
                if (p.notes.isNotBlank() && p.available) addView(text(p.notes, 11.5f, 0xFFB26A00.toInt()), lp().margins(context, t = 2))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
        })

        addView(horizontal {
            if (p.available) {
                addView(vertical {
                    addView(text(Fmt.inr(p.unitPrice!!, decimals = p.unitPrice!! < 100), 19f, Ui.BRAND_DARK, bold = true))
                    val extra = if (p.perWp) "₹${Fmt.num(p.rate!!)}/Wp · +${Fmt.num(p.gst)}% GST" else "+${Fmt.num(p.gst)}% GST"
                    addView(text(extra, 11.5f, Ui.MUTED))
                }, lp(0, WRAP, 1f))
                addView(qtyControl(a, p, onChange))
            } else {
                addView(vertical {
                    addView(text("Rate on request", 15f, Ui.MUTED, bold = true))
                    addView(text(if (p.inStock) "Price not listed right now" else "Currently not available", 11.5f, Ui.MUTED))
                }, lp(0, WRAP, 1f))
                addView(pillButton("Ask AVIP", 0xFF25D366.toInt(), Color.WHITE, 38) {
                    EstimateStore.chatAvip(a, "Hello AVIP, please share the rate for ${p.title} ${p.spec}.")
                })
            }
        }, lp().margins(context, t = 10))
    }
}

/** "ADD" button, or − qty + stepper once the product is in the estimate. */
fun qtyControl(a: Activity, p: Product, onChange: () -> Unit): View = with(a) {
    val qty = Cart.qtyOf(p.id)
    val min = p.minQty.toDouble()
    if (qty <= 0) {
        pillButton(if (p.minQty > 1) "ADD ${p.minQty}" else "ADD", Ui.SUN, Ui.TEXT, 40) {
            Cart.setQty(a, p, min); onChange()
        }.apply { minWidth = dp(96) }
    } else horizontal {
        background = rounded(0xFFE8F3EC.toInt(), dpf(20f))
        fun step(res: Int, block: () -> Unit) = icon(res, Ui.BRAND, 40).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { block() }
        }
        addView(step(R.drawable.ic_minus) {
            val next = qty - 1
            Cart.setQty(a, p, if (next < min) 0.0 else next); onChange()
        })
        addView(text(Fmt.qty(qty), 16f, Ui.BRAND_DARK, bold = true) {
            gravity = Gravity.CENTER
            minWidth = dp(40)
            setOnClickListener { askQty(a, p.title, p.unit, qty) { v -> Cart.setQty(a, p, if (v > 0 && v < min) min else v); onChange() } }
        })
        addView(step(R.drawable.ic_plus) { Cart.setQty(a, p, qty + 1); onChange() })
    }
}

/** Dialog to type an exact quantity. */
fun askQty(a: Activity, title: String, unit: String, current: Double, done: (Double) -> Unit) {
    val box = a.input("Quantity", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.qty(current))
    val wrap = a.vertical(20) { addView(box) }
    AlertDialog.Builder(a)
        .setTitle(title)
        .setMessage("Enter quantity (${unit.lowercase()})")
        .setView(wrap)
        .setPositiveButton("OK") { _, _ ->
            box.text.toString().toDoubleOrNull()?.let { if (it >= 0) done(it) }
        }
        .setNegativeButton("Cancel", null)
        .show()
    box.selectAll()
}
