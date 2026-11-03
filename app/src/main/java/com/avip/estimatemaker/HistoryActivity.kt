package com.avip.estimatemaker

import android.content.Intent
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

/** Saved AVIP cost estimates: open, share, edit again or delete. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()
        root.addView(topBar(tr("My Cost Estimates", "मेरे लागत अनुमान"), tr("Saved on this phone", "इस फ़ोन में सेव")))
        list = vertical(14)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        setEdgeContent(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val all = EstimateStore.all(this)
        if (all.isEmpty()) {
            list.addView(vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_pdf, Ui.MUTED, 48))
                addView(text(tr("No cost estimates yet", "अभी कोई लागत अनुमान नहीं"), 16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 10))
                addView(text(tr("PDFs you create will appear here.", "आपके बनाए PDF यहां दिखेंगे।"), 13f, Ui.MUTED))
            }, lp().margins(this, t = 80))
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
        all.forEach { doc ->
            list.addView(card(12) {
                foreground = ripple()
                addView(horizontal {
                    addView(iconBadge("kit", 42, square = true))
                    addView(vertical {
                        addView(text(tr("AVIP Cost Estimate", "AVIP लागत अनुमान"), 15f, Ui.TEXT, bold = true))
                        addView(text("${doc.number} · ${fmt.format(Date(doc.createdAt))}", 11.5f, Ui.MUTED))
                        addView(text(tr("${doc.lines.size} items", "${doc.lines.size} आइटम"), 11.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f).margins(context, l = 12))
                    addView(text(Fmt.inr(doc.grandTotal), 15f, Ui.BRAND_DARK, bold = true))
                })
                setOnClickListener { actions(doc) }
            }, lp().margins(this, b = 10))
        }
    }

    private fun actions(doc: EstimateDoc) {
        val file = EstimateStore.pdfFile(this, doc.number)
        val options = arrayOf(tr("Open PDF", "PDF खोलें"), tr("Share PDF", "PDF भेजें"), tr("Edit as new cost estimate", "नए अनुमान के रूप में बदलें"), tr("Delete", "मिटाएं"))
        AlertDialog.Builder(this)
            .setTitle(doc.number)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (file.exists()) EstimateStore.open(this, file) else missing()
                    1 -> if (file.exists()) EstimateStore.share(this, file, "Cost estimate ${doc.number}") else missing()
                    2 -> {
                        val go = {
                            Cart.loadFrom(this, doc)
                            startActivity(Intent(this, EstimateActivity::class.java))
                        }
                        if (Cart.count > 0) AlertDialog.Builder(this)
                            .setMessage(tr("Replace the items in your current cost estimate?", "मौजूदा अनुमान के आइटम बदल दें?"))
                            .setPositiveButton(tr("Replace", "बदलें")) { _, _ -> go() }
                            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
                        else go()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setMessage(tr("Delete ${doc.number}?", "${doc.number} मिटाएं?"))
                        .setPositiveButton(tr("Delete", "मिटाएं")) { _, _ -> EstimateStore.delete(this, doc); render() }
                        .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
                }
            }
            .show()
    }

    private fun missing() = Toast.makeText(this, tr("PDF file not found", "PDF फ़ाइल नहीं मिली"), Toast.LENGTH_SHORT).show()

}
