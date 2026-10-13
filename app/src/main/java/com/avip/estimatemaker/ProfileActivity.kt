package com.avip.estimatemaker

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** First-launch registration, and later "My business details". */
class ProfileActivity : AppCompatActivity() {

    private lateinit var company: EditText
    private lateinit var contact: EditText
    private lateinit var mobile: EditText
    private lateinit var gst: EditText
    private lateinit var city: EditText
    private lateinit var address: EditText
    private lateinit var logoView: ImageView
    private lateinit var logoHint: View

    private val firstRun by lazy { !Vendor.isRegistered(this) }

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp != null) {
                Vendor.saveLogo(this, bmp)
                showLogo()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()
        root.addView(
            topBar(
                if (firstRun) "Welcome, Vendor!" else "My Business Details",
                if (firstRun) "Register once to start making estimates" else "Printed on your estimates",
                showBack = !firstRun
            )
        )
        val form = vertical(16)
        val scroll = ScrollView(this).apply { addView(form); isFillViewport = true }
        root.addView(scroll, lp(MATCH, 0, 1f))

        if (firstRun) {
            form.addView(card {
                addView(horizontal {
                    addView(iconBadge("kit", 44))
                    addView(text("Get AVIP B2B prices for solar panels, inverters, batteries, structure, cables and more — and make PDF estimates for your customers.", 13.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 12))
                })
            }, lp().margins(this, b = 14))
        }

        company = input("e.g. Sun Power Solutions", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.company(this))
        contact = input("Your full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, Vendor.contact(this))
        mobile = input("10-digit mobile number", InputType.TYPE_CLASS_PHONE, Vendor.mobile(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(10))
        }
        gst = input("15-character GSTIN (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, Vendor.gst(this)).apply {
            filters = arrayOf<InputFilter>(InputFilter.AllCaps(), InputFilter.LengthFilter(15))
        }
        city = input("e.g. Patna", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, Vendor.city(this))
        address = input("Shop / office address (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, Vendor.address(this))

        form.addView(card {
            field(this, "Company Name *", company)
            field(this, "Contact Person *", contact)
            field(this, "Mobile Number *", mobile)
            field(this, "GST Number", gst)
            field(this, "City / District", city)
            field(this, "Address", address)
        })

        // Logo for customer estimates
        form.addView(card {
            addView(text("Your logo (optional)", 14f, Ui.TEXT, bold = true))
            addView(text("Shown on estimates you make for your own customers.", 12f, Ui.MUTED), lp().margins(context, t = 2, b = 10))
            addView(horizontal {
                logoView = ImageView(context).apply {
                    background = rounded(0xFFF1F4F2.toInt(), dpf(12f), Ui.LINE, dp(1))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { pickLogo.launch("image/*") }
                }
                addView(logoView, lp(dp(84), dp(84)))
                addView(vertical {
                    logoHint = text("Tap to choose", 13f, Ui.MUTED)
                    addView(logoHint)
                    addView(horizontal {
                        addView(pillButton("Choose", Ui.BRAND, Color.WHITE, 38) { pickLogo.launch("image/*") })
                        addView(pillButton("Remove", 0xFFEFF3F1.toInt(), Ui.TEXT, 38) {
                            Vendor.removeLogo(this@ProfileActivity); showLogo()
                        }, lp(WRAP, WRAP).margins(context, l = 8))
                    }, lp(WRAP, WRAP).margins(context, t = 8))
                }, lp(0, WRAP, 1f).margins(context, l = 14))
            })
        }, lp().margins(this, t = 14))

        form.addView(pillButton(if (firstRun) "Start Making Estimates  ›" else "Save", Ui.BRAND) { save() },
            lp().margins(this, t = 18, b = 8))
        form.addView(text("Your details are shared with AVIP Business Solutions LLP so our team can assist you.", 11.5f, Ui.MUTED) {
            gravity = Gravity.CENTER
        }, lp().margins(this, b = 20))

        setContentView(root)
        showLogo()
    }

    private fun field(parent: android.widget.LinearLayout, label: String, view: EditText) {
        parent.addView(text(label, 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(this, t = if (parent.childCount == 0) 0 else 12, b = 6))
        parent.addView(view, lp())
    }

    private fun showLogo() {
        val bmp = Vendor.logo(this)
        if (bmp != null) {
            logoView.setImageBitmap(bmp)
            logoView.imageTintList = null
            logoHint.visibility = View.GONE
        } else {
            logoView.setImageResource(R.drawable.ic_add_custom)
            logoView.imageTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
            logoHint.visibility = View.VISIBLE
        }
    }

    private fun save() {
        val c = company.text.toString().trim()
        val n = contact.text.toString().trim()
        val m = mobile.text.toString().filter { it.isDigit() }
        val g = gst.text.toString().trim().uppercase()
        when {
            c.length < 2 -> { company.error = "Enter company name"; company.requestFocus(); return }
            n.length < 2 -> { contact.error = "Enter contact person"; contact.requestFocus(); return }
            !Regex("[6-9][0-9]{9}").matches(m) -> { mobile.error = "Enter a valid 10-digit mobile"; mobile.requestFocus(); return }
            g.isNotEmpty() && !Regex("[0-9]{2}[A-Z0-9]{10}[0-9A-Z]{3}").matches(g) -> {
                gst.error = "GSTIN should be 15 characters"; gst.requestFocus(); return
            }
        }
        Vendor.save(this, c, n, m, g, city.text.toString().trim(), address.text.toString().trim())
        if (firstRun) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
