package com.avip.estimatemaker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Small helpers so screens can be built in code with one consistent look. */
object Ui {
    const val BRAND = 0xFF0E6B3A.toInt()
    const val BRAND_DARK = 0xFF08402A.toInt()
    const val SUN = 0xFFF7A21B.toInt()
    const val BG = 0xFFF3F6F4.toInt()
    const val TEXT = 0xFF17211B.toInt()
    const val MUTED = 0xFF66746B.toInt()
    const val LINE = 0xFFE1E7E3.toInt()
    const val DANGER = 0xFFD64545.toInt()

    /** Colour per category icon. */
    fun categoryColor(icon: String): Int = when (icon) {
        "panel" -> 0xFF1565C0.toInt()
        "kit" -> 0xFFF7A21B.toInt()
        "inverter" -> 0xFF00897B.toInt()
        "hybrid" -> 0xFF6A1B9A.toInt()
        "battery" -> 0xFF2E7D32.toInt()
        "offgrid" -> 0xFFEF6C00.toInt()
        "structure" -> 0xFF546E7A.toInt()
        "box" -> 0xFFC62828.toInt()
        "pipe" -> 0xFF0277BD.toInt()
        "hardware" -> 0xFF5D4037.toInt()
        "earthing" -> 0xFF795548.toInt()
        "cable" -> 0xFFAD1457.toInt()
        "service" -> 0xFF0E6B3A.toInt()
        else -> 0xFF607D8B.toInt()
    }

    fun categoryIcon(icon: String): Int = when (icon) {
        "panel" -> R.drawable.cat_panel
        "kit" -> R.drawable.cat_kit
        "inverter" -> R.drawable.cat_inverter
        "hybrid" -> R.drawable.cat_hybrid
        "battery" -> R.drawable.cat_battery
        "offgrid" -> R.drawable.cat_offgrid
        "structure" -> R.drawable.cat_structure
        "box" -> R.drawable.cat_box
        "pipe" -> R.drawable.cat_pipe
        "hardware" -> R.drawable.cat_hardware
        "earthing" -> R.drawable.cat_earthing
        "cable" -> R.drawable.cat_cable
        "service" -> R.drawable.cat_service
        else -> R.drawable.cat_other
    }
}

/** Standard touch ripple drawn over a view. */
fun Context.ripple(): android.graphics.drawable.Drawable? {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return ContextCompat.getDrawable(this, tv.resourceId)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun gradient(radius: Float, vararg colors: Int) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

fun Context.vertical(padding: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    block()
}

fun Context.horizontal(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun Context.text(
    value: CharSequence, size: Float = 14f, color: Int = Ui.TEXT,
    bold: Boolean = false, block: TextView.() -> Unit = {},
) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    block()
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Coloured circle/rounded square with a white category icon inside. */
fun Context.iconBadge(icon: String, sizeDp: Int = 48, square: Boolean = false): View {
    val color = Ui.categoryColor(icon)
    return FrameLayout(this).apply {
        background = if (square) rounded(color, dpf(14f)) else GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        addView(ImageView(context).apply {
            setImageResource(Ui.categoryIcon(icon))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(sizeDp * 55 / 100), dp(sizeDp * 55 / 100), Gravity.CENTER))
    }
}

fun Context.card(padding: Int = 14, block: LinearLayout.() -> Unit = {}) = vertical(padding) {
    background = rounded(Color.WHITE, dpf(16f))
    elevation = dpf(2f)
    block()
}

fun Context.pillButton(
    label: String, bg: Int = Ui.BRAND, fg: Int = Color.WHITE, heightDp: Int = 48,
    onClick: () -> Unit,
) = TextView(this).apply {
    text = label
    setTextColor(fg)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    background = rounded(bg, dpf(heightDp / 2f))
    minHeight = dp(heightDp)
    setPadding(dp(18), 0, dp(18), 0)
    isClickable = true
    isFocusable = true
    foreground = context.ripple()
    setOnClickListener { onClick() }
}

fun Context.input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, value: String = "") =
    EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        setTextColor(Ui.TEXT)
        setHintTextColor(0xFF9AA59F.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(0xFFF7FAF8.toInt(), dpf(12f), Ui.LINE, dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(c: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(c.dp(l), c.dp(t), c.dp(r), c.dp(b))
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Green top bar with back arrow, title and optional right-side views. */
fun Activity.topBar(title: String, subtitle: String? = null, showBack: Boolean = true, right: List<View> = emptyList()): View =
    horizontal {
        background = gradient(0f, Ui.BRAND_DARK, Ui.BRAND)
        setPadding(dp(8), dp(10), dp(12), dp(10))
        minimumHeight = dp(60)
        if (showBack) {
            addView(icon(R.drawable.ic_back, Color.WHITE, 40).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { finish() }
            })
        } else {
            addView(View(context), lp(dp(8), 1))
        }
        addView(vertical {
            addView(text(title, 19f, Color.WHITE, bold = true) { maxLines = 1 })
            if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, 0xCCFFFFFF.toInt()) { maxLines = 1 })
        }, lp(0, WRAP, 1f).margins(context, l = 4))
        right.forEach { addView(it) }
    }

/** Cart icon with a count bubble, for top bars. */
fun Activity.cartButton(): View = FrameLayout(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_cart)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }, FrameLayout.LayoutParams(MATCH, MATCH))
    val badge = TextView(context).apply {
        setTextColor(Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SUN) }
    }
    addView(badge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
        setMargins(0, dp(4), dp(4), 0)
    })
    fun refresh() {
        badge.text = Cart.count.toString()
        badge.visibility = if (Cart.count > 0) View.VISIBLE else View.GONE
    }
    refresh()
    tag = Runnable { refresh() }
    setOnClickListener { startActivity(Intent(context, EstimateActivity::class.java)) }
}

/**
 * Sticky bottom bar: "3 items · ₹1,23,456  Make Estimate ›". Hidden when the cart is empty.
 * Call [refreshCartBar] when the cart changes.
 */
fun Activity.cartBar(): View = horizontal {
    background = gradient(0f, Ui.BRAND_DARK, Ui.BRAND)
    setPadding(dp(18), dp(12), dp(14), dp(12))
    elevation = dpf(8f)
    addView(icon(R.drawable.ic_cart, Color.WHITE, 26))
    val summary = text("", 15f, Color.WHITE, bold = true)
    addView(summary, lp(0, WRAP, 1f).margins(context, l = 12))
    addView(TextView(context).apply {
        text = "Make Estimate  ›"
        setTextColor(Ui.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = rounded(Ui.SUN, dpf(20f))
        setPadding(dp(16), dp(10), dp(16), dp(10))
    })
    setOnClickListener { startActivity(Intent(context, EstimateActivity::class.java)) }
    tag = Runnable {
        val n = Cart.count
        visibility = if (n > 0) View.VISIBLE else View.GONE
        summary.text = "$n item${if (n == 1) "" else "s"} · ${Fmt.inr(Cart.quickTotal())}"
    }
    (tag as Runnable).run()
}

fun View.refreshTag() { (tag as? Runnable)?.run() }
