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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Screens where an AVIP Manager / Founder can set a product's photo. */
interface PhotoEditor {
    fun editPhoto(p: Product)
}

/** Product list for one category, or search across all products. */
class ProductsActivity : AppCompatActivity(), PhotoEditor {

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_SEARCH = "search"
        /** Show only products whose rate changed since the last visit. */
        const val EXTRA_CHANGED = "changed"
    }

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var bar: View
    private lateinit var cartIcon: View
    private var category: String? = null
    private var query = ""
    private var searchBox: android.widget.EditText? = null
    private val recentPrefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private fun recentSearches(): List<String> =
        (recentPrefs.getString("recent_searches", "") ?: "").split('\n').filter { it.isNotBlank() }

    /** Remembers a search that found something (last 6, newest first). */
    private fun rememberSearch() {
        val q = query.trim()
        if (searchBox == null || q.length < 2 || source().isEmpty()) return
        val list = (listOf(q) + recentSearches().filter { !it.equals(q, true) }).take(6)
        recentPrefs.edit().putString("recent_searches", list.joinToString("\n")).apply()
    }
    private var brand: String? = null
    private val cartListener = { bar.refreshTag(); cartIcon.refreshTag() }

    // ---- AVIP Team: set product photos ----
    private var photoTarget: Product? = null
    private var cameraUri: Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photoTarget; val u = cameraUri
        if (ok && p != null && u != null) upload(p, u)
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { u ->
        val p = photoTarget
        if (u != null && p != null) upload(p, u)
    }

    override fun editPhoto(p: Product) {
        photoTarget = p
        val opts = mutableListOf(tr("Take photo", "फ़ोटो खींचें"), tr("Choose from gallery", "गैलरी से चुनें"))
        val has = Catalog.photoFor(p).isNotBlank()
        if (has) opts += tr("Remove photo", "फ़ोटो हटाएं")
        AlertDialog.Builder(this)
            .setTitle(tr("Photo for ", "फ़ोटो: ") + p.title)
            .setItems(opts.toTypedArray()) { _, i ->
                when (i) {
                    0 -> {
                        val f = File(cacheDir, "shared/photo_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
                        cameraUri = u
                        takePhoto.launch(u)
                    }
                    1 -> pickPhoto.launch("image/*")
                    else -> AlertDialog.Builder(this)
                        .setMessage(tr("Remove the photo of ${p.title}? Everyone will see the category icon instead.",
                            "${p.title} की फ़ोटो हटाएं? सभी को कैटेगरी आइकन दिखेगा।"))
                        .setPositiveButton(tr("Remove", "हटाएं")) { _, _ -> send(p, "") }
                        .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
                }
            }.show()
    }

    private fun upload(p: Product, uri: Uri) {
        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) { runCatching { prepare(uri) }.getOrNull() }
            if (image == null) {
                Toast.makeText(this@ProductsActivity, tr("Couldn't read that photo", "वह फ़ोटो पढ़ी नहीं जा सकी"), Toast.LENGTH_LONG).show()
                return@launch
            }
            send(p, image)
        }
    }

    /** Photo → upright JPEG, at most 1200 px, as base64. */
    private fun prepare(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })!!
        val rotation = runCatching {
            when (android.media.ExifInterface(bytes.inputStream()).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, 1200f / maxOf(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Uploads (or with "" removes) the photo; it lands in AVIP's Drive and the Product Photos tab. */
    private fun send(p: Product, image: String) {
        val wait = AlertDialog.Builder(this)
            .setMessage(if (image.isBlank()) tr("Removing photo…", "फ़ोटो हट रही है…") else tr("Uploading photo…", "फ़ोटो अपलोड हो रही है…"))
            .setCancelable(false).show()
        lifecycleScope.launch {
            try {
                val o = Api.call(this@ProductsActivity, "setPhoto", JSONObject()
                    .put("token", Session.token(this@ProductsActivity))
                    .put("id", p.photoId.ifBlank { p.id }).put("category", p.category)
                    .put("product", p.name).put("brand", p.brand).put("image", image))
                Catalog.setLocalPhoto(p, o.optString("url"))
                Toast.makeText(this@ProductsActivity,
                    if (image.isBlank()) tr("Photo removed", "फ़ोटो हटा दी गई") else tr("Photo saved for everyone", "फ़ोटो सबके लिए सेव हो गई"),
                    Toast.LENGTH_SHORT).show()
                render()
            } catch (e: ApiException) {
                if (e.code == "SESSION" || e.code == "NOTEAM") goLogin(this@ProductsActivity, e.message)
                else Toast.makeText(this@ProductsActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                wait.dismiss()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cart.load(this)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        category = intent.getStringExtra(EXTRA_CATEGORY)
        val searchMode = intent.getBooleanExtra(EXTRA_SEARCH, false)

        val root = vertical()
        cartIcon = cartButton()
        val count = Catalog.products.count { category == null || it.category == category }
        if (changedOnly) category = null
        root.addView(topBar(if (changedOnly) tr("Rate changes", "रेट में बदलाव") else category ?: tr("Search Products", "प्रोडक्ट खोजें"), if (category != null) tr("$count items · rates before GST", "$count आइटम · रेट GST से पहले") else tr("All categories", "सभी कैटेगरी"), right = listOf(cartIcon)))

        if (searchMode) {
            val box = input(tr("Type product, brand or size…", "प्रोडक्ट, ब्रांड या साइज़ लिखें…"), InputType.TYPE_CLASS_TEXT).apply {
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
            searchBox = box
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
        root.addView(pullToRefresh(ScrollView(this).apply { addView(list) }) { Catalog.refresh(this); render() }, lp(MATCH, 0, 1f))
        bar = cartBar()
        root.addView(bar)
        setEdgeContent(root)
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
        rememberSearch()
        Cart.removeListener(cartListener)
    }

    private val changedOnly by lazy { intent.getBooleanExtra(EXTRA_CHANGED, false) }

    private fun source(): List<Product> = when {
        changedOnly -> Catalog.products.filter { Catalog.priceChanges.containsKey(it.id) }
        category != null -> Catalog.products.filter { it.category == category }
        else -> Catalog.search(query)
    }

    private fun render() {
        val items = source()
        // Brand chips
        chips.removeAllViews()
        val brands = items.map { it.brand }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        if (brand != null && brand !in brands) brand = null
        if (brands.size > 1) {
            (listOf<String?>(null) + brands).forEach { b ->
                val selected = brand == b
                chips.addView(text(b ?: tr("All brands", "सभी ब्रांड"), 13f, if (selected) Color.WHITE else Ui.TEXT, bold = selected) {
                    background = rounded(if (selected) Ui.BRAND else Color.WHITE, dpf(18f), Ui.LINE, if (selected) 0 else dp(1))
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { brand = b; render() }
                }, lp(WRAP, WRAP).margins(this, r = 8))
            }
            chips.visibility = View.VISIBLE
        } else chips.visibility = View.GONE

        list.removeAllViews()
        offlineBanner(this)?.let { list.addView(it, lp().margins(this, b = 10)) }
        if (Session.isTeam(this)) list.addView(text("📷 " + tr("AVIP Team: long-press a product photo to change it", "AVIP टीम: फ़ोटो बदलने के लिए प्रोडक्ट फ़ोटो को देर तक दबाएं"),
            11.5f, Ui.MUTED), lp().margins(this, b = 8))
        // Brand A → Z (no brand last); in search results, by category first so headings stay together.
        val catOrder = Catalog.categories().map { it.name }
        val shown = items.filter { brand == null || it.brand == brand }
            .sortedWith(compareBy<Product>(
                { if (category == null) catOrder.indexOf(it.category) else 0 },
                { if (it.available) 0 else 1 },          // sold-out / no rate at the end
                { if (it.brand.isBlank()) 1 else 0 },
                { it.brand.lowercase() },
            ))
        if (shown.isEmpty() && category == null && query.isBlank() && recentSearches().isNotEmpty()) {
            list.addView(text(tr("Recent searches", "हाल की खोज"), 13f, Ui.MUTED, bold = true), lp().margins(this, t = 8, b = 8))
            val wrap = vertical()
            recentSearches().forEach { q ->
                wrap.addView(horizontal {
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    foreground = ripple()
                    addView(icon(R.drawable.ic_history, Ui.MUTED, 18))
                    addView(text(q, 15f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 12))
                    setOnClickListener { searchBox?.setText(q); searchBox?.setSelection(q.length) }
                })
            }
            list.addView(wrap)
            return
        }
        if (shown.isEmpty()) {
            list.addView(text(if (category == null && query.isBlank()) tr("Start typing to search all products", "सभी प्रोडक्ट खोजने के लिए लिखना शुरू करें") else tr("No products found", "कोई प्रोडक्ट नहीं मिला"),
                15f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 60))
            return
        }
        var lastCat = ""
        val doneGroups = mutableSetOf<String>()
        shown.forEach { p ->
            val variants = Catalog.variantsOf(p)
            if (variants.size > 1 && !doneGroups.add(variants.joinToString("|") { it.id })) return@forEach
            if (category == null && p.category != lastCat) {
                lastCat = p.category
                list.addView(text(p.category, 13f, Ui.BRAND, bold = true), lp().margins(this, t = 8, b = 6, l = 4))
            }
            val card = if (variants.size > 1) variantCard(this, variants) { render() } else productCard(this, p) { render() }
            list.addView(card, lp().margins(this, b = 10))
        }
    }
}

/** Which version (packet / loose) each combined card is showing; kept while the app is open. */
private val chosenVariant = mutableMapOf<String, String>()

/** Label for the switch: "Packet", "Packet · 200pc", "Roll · 25mtr", "Loose · per pc",
 *  "Single panel" or "Pallet of 36". */
private fun variantLabel(p: Product): String {
    if (p.isLoose) return tr("Loose · per ${p.unit.lowercase()}", "खुला · प्रति ${p.unit.lowercase()}")
    if (p.isPallet) return tr("Pallet of ${p.minQty}", "${p.minQty} का पैलेट")
    if (p.unit.equals("Panel", true)) return tr("Single panel", "एक पैनल")
    val detail = Regex("\\(([^)]*)\\)\\s*$").find(p.name)?.groupValues?.get(1)?.trim() ?: ""
    return if (detail.isBlank() || detail.equals(p.unit, true) || detail.equals("packet", true)) p.unit
    else "${p.unit} · $detail"
}

/** One card for an item sold both by packet and loose, with a Packet | Loose switch. */
fun variantCard(a: Activity, variants: List<Product>, onChange: () -> Unit): View = with(a) {
    val key = variants.joinToString("|") { it.id }
    val inCart = variants.filter { Cart.qtyOf(it.id) > 0 }
    val selected = variants.firstOrNull { it.id == chosenVariant[key] }
        ?: inCart.firstOrNull()
        ?: variants.firstOrNull { it.available }
        ?: variants[0]

    val switch = horizontal {
        background = rounded(0xFFEFF3F1.toInt(), dpf(22f))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        variants.forEach { v ->
            val on = v == selected
            addView(vertical {
                gravity = Gravity.CENTER
                background = if (on) rounded(Ui.BRAND, dpf(18f)) else null
                setPadding(dp(8), dp(7), dp(8), dp(7))
                addView(text(variantLabel(v), 12.5f, if (on) Color.WHITE else Ui.TEXT, bold = true) {
                    gravity = Gravity.CENTER; maxLines = 1
                })
                val price = v.unitPrice
                val priceText = when {
                    price == null -> tr("Rate on request", "रेट पूछें")
                    v.perWp && v.rate != null -> "₹${Fmt.num(v.rate)}/Wp · ${v.spec}"
                    else -> Fmt.inr(price, price < 100)
                }
                addView(text(priceText,
                    11f, if (on) 0xDDFFFFFF.toInt() else Ui.MUTED) { gravity = Gravity.CENTER })
                setOnClickListener { chosenVariant[key] = v.id; onChange() }
            }, lp(0, WRAP, 1f))
        }
    }
    val other = variants.firstOrNull { it != selected && Cart.qtyOf(it.id) > 0 }
    val extra = vertical {
        addView(switch)
        val otherText = other?.let {
            val q = Fmt.qty(Cart.qtyOf(it.id))
            if (it.isPallet) tr("Also in estimate: $q panels at pallet rate", "अनुमान में पहले से: $q पैनल पैलेट रेट पर")
            else tr("Also in estimate: $q × ${variantLabel(it)}", "अनुमान में पहले से: $q × ${variantLabel(it)}")
        }
        if (otherText != null) addView(text(otherText,
            11.5f, Ui.BRAND, bold = true), lp().margins(context, t = 6))
    }
    productCard(a, selected, title = selected.group.ifBlank { selected.name }, extra = extra, onChange = onChange)
}

/** A product card with price and an Add / − qty + control. Used by list screens. */
/**
 * Compact product row: photo | brand, name, size, price | ADD / stepper.
 * Panels show ₹/Wp as the main price (how buyers compare panels).
 * AVIP team: long-press the photo to change it.
 */
fun productCard(a: Activity, p: Product, title: String = p.name, extra: View? = null, onChange: () -> Unit): View = with(a) {
    card(12) {
        if (!p.available) alpha = 0.6f
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(productPhoto(p, p.icon, 60).apply {
                if (Session.isTeam(a) && a is PhotoEditor) setOnLongClickListener { a.editPhoto(p); true }
            })
            addView(vertical {
                if (p.brand.isNotBlank()) addView(text(p.brand.uppercase(), 10.5f, Ui.categoryColor(p.icon), bold = true) {
                    letterSpacing = 0.06f
                })
                addView(text(title, 14.5f, Ui.TEXT, bold = true) { maxLines = 2 })
                val sub = listOf(p.spec, tr("per ", "प्रति ") + p.unit.lowercase()).filter { it.isNotBlank() }.joinToString(" · ")
                addView(text(sub, 11.5f, Ui.MUTED))
                if (p.notes.isNotBlank() && p.available) addView(text(p.notes, 11f, 0xFFB26A00.toInt()))
                if (p.available) {
                    val price = p.unitPrice!!
                    priceChangeView(a, p)?.let { addView(it, lp().margins(context, t = 4)) }
                    if (p.perWp && p.rate != null) {
                        addView(text("₹${Fmt.num(p.rate)}/Wp", 17f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 2))
                        addView(text(Fmt.inr(price, true) + tr(" per panel", " प्रति पैनल") + " · +${Fmt.num(p.gst)}% GST", 11f, Ui.MUTED))
                    } else {
                        addView(text(Fmt.inr(price, decimals = price < 100), 17f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 2))
                        addView(text("+${Fmt.num(p.gst)}% GST", 11f, Ui.MUTED))
                    }
                } else {
                    addView(text(tr("Rate on request", "रेट पूछें"), 14f, Ui.MUTED, bold = true), lp().margins(context, t = 4))
                    addView(text(if (p.inStock) tr("Price not listed right now", "अभी रेट उपलब्ध नहीं") else tr("Currently not available", "अभी स्टॉक में नहीं"), 11f, Ui.MUTED))
                }
            }, lp(0, WRAP, 1f).margins(context, l = 12, r = 8))
            if (p.available) addView(qtyControl(a, p, onChange))
            else addView(pillButton(tr("Ask AVIP", "AVIP से पूछें"), 0xFF25D366.toInt(), Color.WHITE, 38) {
                EstimateStore.chatAvip(a, "Hello AVIP, please share the rate for ${p.title} ${p.spec}.")
            })
        })
        if (extra != null) addView(extra, lp().margins(context, t = 10))
    }
}

/** Shown while the phone is offline: which day's prices are on screen. */
fun offlineBanner(a: Activity): View? {
    if (Catalog.isLive || Catalog.updatedAt <= 0) return null
    val day = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(Catalog.updatedAt))
    return a.card(10) {
        background = rounded(0xFFFFF4E0.toInt(), dpf(12f))
        addView(text(tr("📡 Offline — showing prices saved on $day. Pull down to refresh.", "📡 ऑफ़लाइन — $day को सेव हुए रेट दिख रहे हैं। रिफ्रेश के लिए नीचे खींचें।"),
            12.5f, 0xFF8A5A00.toInt()))
    }
}

/** "▲ up from ₹24.75/Wp" / "▼ down from …" when the rate changed since the last visit. */
fun priceChangeView(a: Activity, p: Product): View? {
    val old = Catalog.priceChanges[p.id] ?: return null
    val now = Catalog.compareRate(p) ?: return null
    val up = now > old
    val was = if (p.perWp) "₹${Fmt.num(old)}/Wp" else Fmt.inr(old, old < 100)
    val label = (if (up) "▲ " else "▼ ") +
        (if (up) tr("Up from $was", "बढ़ा · पहले $was था") else tr("Down from $was", "घटा · पहले $was था"))
    return a.text(label, 11f, if (up) Ui.DANGER else Ui.BRAND, bold = true) {
        background = rounded(if (up) 0xFFFDECEA.toInt() else 0xFFE8F3EC.toInt(), dpf(8f))
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }
}

/** "ADD" button, or − qty + stepper once the product is in the estimate. */
fun qtyControl(a: Activity, p: Product, onChange: () -> Unit): View = with(a) {
    val qty = Cart.qtyOf(p.id)
    val min = p.minQty.toDouble()
    if (qty <= 0) {
        pillButton(if (p.minQty > 1) tr("ADD ${p.minQty}", "${p.minQty} जोड़ें") else tr("ADD", "जोड़ें"), Ui.SUN, Ui.TEXT, 40) {
            Cart.setQty(a, p, min); onChange()
        }.apply { minWidth = dp(84) }
    } else horizontal {
        background = rounded(0xFFE8F3EC.toInt(), dpf(20f))
        fun step(res: Int, block: () -> Unit) = icon(res, Ui.BRAND, 40).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { block() }
        }
        // Pallets go up and down a whole pallet at a time.
        val stepBy = if (p.isPallet && min > 1) min else 1.0
        addView(step(R.drawable.ic_minus) {
            val next = qty - stepBy
            Cart.setQty(a, p, if (next < min) 0.0 else next); onChange()
        })
        addView(text(Fmt.qty(qty), 16f, Ui.BRAND_DARK, bold = true) {
            gravity = Gravity.CENTER
            minWidth = dp(44)
            // Looks tappable: tap to type the quantity.
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { askQty(a, p.title, p.unit, qty) { v -> Cart.setQty(a, p, if (v > 0 && v < min) min else v); onChange() } }
        })
        addView(step(R.drawable.ic_plus) { Cart.setQty(a, p, qty + stepBy); onChange() })
    }
}

/** Dialog to type an exact quantity. */
fun askQty(a: Activity, title: String, unit: String, current: Double, done: (Double) -> Unit) {
    val box = a.input(tr("Quantity", "मात्रा"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.qty(current))
    val wrap = a.vertical(20) { addView(box) }
    AlertDialog.Builder(a)
        .setTitle(title)
        .setMessage(tr("Enter quantity (${unit.lowercase()})", "मात्रा लिखें (${unit.lowercase()})"))
        .setView(wrap)
        .setPositiveButton(tr("OK", "ठीक है")) { _, _ ->
            box.text.toString().toDoubleOrNull()?.let { if (it >= 0) done(it) }
        }
        .setNegativeButton(tr("Cancel", "रद्द करें"), null)
        .show()
    box.selectAll()
}
