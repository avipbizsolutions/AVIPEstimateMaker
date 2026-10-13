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

/** Saved estimates: open, share, edit again or delete. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()
        root.addView(topBar("My Estimates", "Saved on this phone"))
        list = vertical(14)
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        setContentView(root)
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
                addView(text("No estimates yet", 16f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 10))
                addView(text("PDFs you create will appear here.", 13f, Ui.MUTED))
            }, lp().margins(this, t = 80))
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
        all.forEach { doc ->
            val isAvip = doc.type == EstimateType.AVIP_QUOTE
            list.addView(card(12) {
                foreground = ripple()
                addView(horizontal {
                    addView(iconBadge(if (isAvip) "kit" else "service", 42, square = true))
                    addView(vertical {
                        addView(text(if (isAvip) "AVIP Quotation" else doc.customerName.ifBlank { "Customer estimate" }, 15f, Ui.TEXT, bold = true))
                        addView(text("${doc.number} · ${fmt.format(Date(doc.createdAt))}", 11.5f, Ui.MUTED))
                        addView(text("${doc.lines.size} items", 11.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f).margins(context, l = 12))
                    addView(text(Fmt.inr(doc.grandTotal), 15f, Ui.BRAND_DARK, bold = true))
                })
                setOnClickListener { actions(doc) }
            }, lp().margins(this, b = 10))
        }
    }

    private fun actions(doc: EstimateDoc) {
        val file = EstimateStore.pdfFile(this, doc.number)
        val options = arrayOf("Open PDF", "Share PDF", "Edit as new estimate", "Delete")
        AlertDialog.Builder(this)
            .setTitle(doc.number)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (file.exists()) EstimateStore.open(this, file) else missing()
                    1 -> if (file.exists()) EstimateStore.share(this, file, "Estimate ${doc.number}") else missing()
                    2 -> {
                        val go = {
                            Cart.loadFrom(this, doc)
                            startActivity(Intent(this, EstimateActivity::class.java))
                        }
                        if (Cart.count > 0) AlertDialog.Builder(this)
                            .setMessage("Replace the items in your current estimate?")
                            .setPositiveButton("Replace") { _, _ -> go() }
                            .setNegativeButton("Cancel", null).show()
                        else go()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setMessage("Delete ${doc.number}?")
                        .setPositiveButton("Delete") { _, _ -> EstimateStore.delete(this, doc); render() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .show()
    }

    private fun missing() = Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show()

}
