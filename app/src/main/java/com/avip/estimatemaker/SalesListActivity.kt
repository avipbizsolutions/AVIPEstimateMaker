package com.avip.estimatemaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Surya Saathi's customer quotations, or their tax invoices. */
class SalesListActivity : AppCompatActivity() {

    companion object { const val EXTRA_TYPE = "type" }

    private lateinit var type: SaleType
    private lateinit var list: LinearLayout
    private val isInvoice get() = type == SaleType.INVOICE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = runCatching { SaleType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrDefault(SaleType.QUOTE)
        if (!Catalog.isLoaded) Catalog.loadCached(this)
        val root = vertical()
        root.addView(topBar(
            if (isInvoice) tr("GST Tax Invoices", "GST टैक्स इनवॉइस") else tr("Customer Quotations", "ग्राहक कोटेशन"),
            tr("For your own customers", "आपके अपने ग्राहकों के लिए")))
        list = vertical(14)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        root.addView(vertical {
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dpf(8f)
            addView(pillButton(if (isInvoice) tr("+ New Tax Invoice", "+ नया टैक्स इनवॉइस") else tr("+ New Quotation", "+ नया कोटेशन"),
                if (isInvoice) 0xFF6A1B9A.toInt() else 0xFF1565C0.toInt(), Color.WHITE, 50) { newDoc() })
        })
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        DriveSync.syncPendingAsync(this)
    }

    private fun newDoc() {
        if (!Business.isReady(this)) {
            AlertDialog.Builder(this)
                .setTitle(tr("Add your business details first", "पहले अपने व्यवसाय की जानकारी भरें"))
                .setMessage(tr("Your address and PIN code must be printed on every quotation and invoice.", "हर कोटेशन और इनवॉइस पर आपका पता और पिन कोड छपना ज़रूरी है।"))
                .setPositiveButton(tr("Continue", "आगे बढ़ें")) { _, _ ->
                    startActivity(Intent(this, BusinessActivity::class.java).putExtra(BusinessActivity.EXTRA_THEN, type.name))
                }
                .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
            return
        }
        startActivity(Intent(this, SaleEditorActivity::class.java).putExtra(SaleEditorActivity.EXTRA_TYPE, type.name))
    }

    private fun render() {
        list.removeAllViews()
        if (!DriveSync.isLinked(this)) {
            list.addView(card(12) {
                background = rounded(0xFFE8F5EE.toInt(), dpf(14f))
                foreground = ripple()
                addView(horizontal {
                    addView(iconBadgeRes(R.drawable.ic_cloud, 0xFF0F9D58.toInt(), 38))
                    addView(text(tr("Link your Google Drive to keep a safe copy of every document.", "हर डॉक्यूमेंट की सुरक्षित कॉपी के लिए अपनी Google Drive लिंक करें।"),
                        12.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 10))
                    addView(text("›", 24f, Ui.MUTED))
                })
                setOnClickListener { startActivity(Intent(this@SalesListActivity, DriveActivity::class.java)) }
            }, lp().margins(this, b = 12))
        }
        val all = SalesStore.all(this, type)
        if (all.isEmpty()) {
            list.addView(vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(iconBadgeRes(if (isInvoice) R.drawable.ic_receipt else R.drawable.ic_quote, Ui.MUTED, 56))
                addView(text(if (isInvoice) tr("No invoices yet", "अभी कोई इनवॉइस नहीं") else tr("No quotations yet", "अभी कोई कोटेशन नहीं"),
                    16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 12))
                addView(text(
                    if (isInvoice) tr("Make a GST invoice for your customer. Tip: open a quotation and tap \"Convert to Tax Invoice\".",
                        "अपने ग्राहक के लिए GST इनवॉइस बनाएं। सुझाव: कोई कोटेशन खोलें और \"टैक्स इनवॉइस बनाएं\" दबाएं।")
                    else tr("Pick products from the AVIP catalog, set your own selling price and send a PDF to your customer.",
                        "AVIP कैटलॉग से प्रोडक्ट चुनें, अपना बिक्री रेट डालें और ग्राहक को PDF भेजें।"),
                    13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 4))
            }, lp().margins(this, t = 50))
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        all.forEach { d ->
            list.addView(card(12) {
                foreground = ripple()
                addView(horizontal {
                    addView(vertical {
                        addView(text(d.customer.name.ifBlank { "—" }, 15f, Ui.TEXT, bold = true) { maxLines = 1 })
                        addView(text("${d.number} · ${fmt.format(Date(d.date))}", 11.5f, Ui.MUTED))
                        addView(text(
                            if (d.driveSynced) tr("☁ Saved to Drive", "☁ Drive में सेव")
                            else if (DriveSync.isLinked(context)) tr("☁ Waiting to upload", "☁ अपलोड बाकी")
                            else tr("On this phone", "इस फ़ोन में"),
                            11f, if (d.driveSynced) 0xFF0F9D58.toInt() else Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(Fmt.inr(d.total), 15f, Ui.BRAND_DARK, bold = true))
                })
                setOnClickListener { actions(d) }
            }, lp().margins(this, b = 10))
        }
    }

    private fun actions(d: SaleDoc) {
        val file = SalesStore.pdfFile(this, d)
        fun opt(label: String, action: () -> Unit) = label to action
        val opts = mutableListOf(
            opt(tr("Open PDF", "PDF खोलें")) { if (file.exists()) EstimateStore.open(this, file) else missing() },
            opt(tr("Send to customer on WhatsApp", "ग्राहक को WhatsApp पर भेजें")) {
                if (file.exists()) EstimateStore.whatsapp(this, file, d.customer.mobile, shareText(d)) else missing()
            },
            opt(tr("Share…", "भेजें…")) { if (file.exists()) EstimateStore.share(this, file, shareText(d)) else missing() },
            opt(tr("Edit", "बदलें")) { edit(d.id, convert = false) },
        )
        if (!isInvoice) opts += opt(tr("Convert to Tax Invoice", "टैक्स इनवॉइस बनाएं")) { edit(d.id, convert = true) }
        opts += opt(tr("Delete", "मिटाएं")) {
            AlertDialog.Builder(this)
                .setMessage(if (isInvoice) tr("Delete invoice ${d.number}? Under GST, invoice numbers should not have gaps — prefer editing it instead. A copy already in Google Drive is not deleted.",
                    "इनवॉइस ${d.number} मिटाएं? GST में इनवॉइस नंबरों में खाली जगह नहीं होनी चाहिए — इसे बदलना बेहतर है। Google Drive की कॉपी नहीं मिटेगी।")
                else tr("Delete quotation ${d.number}?", "कोटेशन ${d.number} मिटाएं?"))
                .setPositiveButton(tr("Delete", "मिटाएं")) { _, _ -> SalesStore.delete(this, d); render() }
                .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
            Unit
        }
        AlertDialog.Builder(this)
            .setTitle("${d.number} · ${d.customer.name}")
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .show()
    }

    private fun edit(id: String, convert: Boolean) {
        startActivity(Intent(this, SaleEditorActivity::class.java)
            .putExtra(SaleEditorActivity.EXTRA_TYPE, if (convert) SaleType.INVOICE.name else type.name)
            .putExtra(if (convert) SaleEditorActivity.EXTRA_FROM_QUOTE else SaleEditorActivity.EXTRA_ID, id))
    }

    private fun missing() = Toast.makeText(this, tr("PDF file not found — open Edit and save again", "PDF फ़ाइल नहीं मिली — बदलें खोलकर फिर से सेव करें"), Toast.LENGTH_LONG).show()
}

fun shareText(d: SaleDoc): String =
    "${if (d.type == SaleType.INVOICE) "Tax Invoice" else "Quotation"} ${d.number} for ${d.customer.name} · ${Fmt.inr(d.total)}"
