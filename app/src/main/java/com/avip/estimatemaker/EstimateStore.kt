package com.avip.estimatemaker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved quotations (JSON + PDF) in the app's private storage. */
object EstimateStore {

    private fun dir(c: Context) = File(c.filesDir, "estimates").apply { mkdirs() }

    private fun safe(number: String) = number.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun pdfFile(c: Context, number: String) = File(dir(c), safe(number) + ".pdf")
    private fun jsonFile(c: Context, number: String) = File(dir(c), safe(number) + ".json")

    /** e.g. CE-261014-03 (older ones: AQ-…) */
    fun nextNumber(c: Context): String {
        val p = c.getSharedPreferences("numbers", Context.MODE_PRIVATE)
        val day = SimpleDateFormat("yyMMdd", Locale.ENGLISH).format(Date())
        val key = "CE-$day"
        val n = p.getInt(key, 0) + 1
        p.edit().putInt(key, n).apply()
        return "$key-${n.toString().padStart(2, '0')}"
    }

    fun save(c: Context, doc: EstimateDoc): File {
        val pdf = pdfFile(c, doc.number)
        PdfMaker(c).make(doc, pdf)
        jsonFile(c, doc.number).writeText(doc.toJson().toString())
        return pdf
    }

    fun all(c: Context): List<EstimateDoc> =
        dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull {
            runCatching { EstimateDoc.fromJson(JSONObject(it.readText())) }.getOrNull()
        }.sortedByDescending { it.createdAt }

    fun delete(c: Context, doc: EstimateDoc) {
        pdfFile(c, doc.number).delete()
        jsonFile(c, doc.number).delete()
    }

    /** Deletes every saved quotation (used when the Surya Saathi deletes their account). */
    fun deleteAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    // ---- sharing ----

    fun uri(c: Context, file: File): Uri =
        FileProvider.getUriForFile(c, c.packageName + ".files", file)

    fun open(a: Activity, file: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(a, file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            a.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, tr("No PDF viewer found. Use Share instead.", "PDF देखने का ऐप नहीं मिला। भेजें (Share) का उपयोग करें।"), Toast.LENGTH_LONG).show()
        }
    }

    fun share(a: Activity, file: File, message: String) {
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri(a, file))
            .putExtra(Intent.EXTRA_TEXT, message)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, tr("Share", "भेजें")))
    }

    /** Sends the PDF straight to a WhatsApp chat (falls back to the share sheet). */
    private val WA = listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business")

    private fun installed(a: Activity, pkg: String) = try {
        a.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    /** Asks "WhatsApp or WhatsApp Business?" when both are on the phone; null when neither is. */
    fun pickWhatsApp(a: Activity, then: (String?) -> Unit) {
        val apps = WA.filter { installed(a, it.first) }
        when (apps.size) {
            0 -> then(null)
            1 -> then(apps[0].first)
            else -> androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle(tr("Send with", "किससे भेजें"))
                .setItems(apps.map { it.second }.toTypedArray()) { _, k -> then(apps[k].first) }
                .setNegativeButton(tr("Cancel", "रद्द करें"), null)
                .show()
        }
    }

    private fun waDigits(number: String): String {
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        return digits
    }

    fun whatsapp(a: Activity, file: File, number: String, message: String) {
        val digits = waDigits(number)
        pickWhatsApp(a) { pkg ->
            if (pkg == null) { share(a, file, message); return@pickWhatsApp }
            val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                .setPackage(pkg)
                .putExtra(Intent.EXTRA_STREAM, uri(a, file))
                .putExtra(Intent.EXTRA_TEXT, message)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (digits.isNotEmpty()) i.putExtra("jid", "$digits@s.whatsapp.net")
            try { a.startActivity(i) } catch (_: ActivityNotFoundException) { share(a, file, message) }
        }
    }

    fun chatAvip(a: Activity, message: String) =
        chatNumber(a, Catalog.settings["whatsapp"].ifBlank { "919288176289" }, message)

    fun chatNumber(a: Activity, number: String, message: String) {
        val url = Uri.parse("https://wa.me/${waDigits(number)}?text=" + Uri.encode(message))
        pickWhatsApp(a) { pkg ->
            val i = Intent(Intent.ACTION_VIEW, url)
            if (pkg != null) i.setPackage(pkg)
            try {
                a.startActivity(i)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(a, tr("WhatsApp not found", "WhatsApp नहीं मिला"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun call(a: Activity, number: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })))
        } catch (_: Exception) {
        }
    }
}
