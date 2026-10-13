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

/** Saved estimates (JSON + PDF) in the app's private storage. */
object EstimateStore {

    private fun dir(c: Context) = File(c.filesDir, "estimates").apply { mkdirs() }

    private fun safe(number: String) = number.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun pdfFile(c: Context, number: String) = File(dir(c), safe(number) + ".pdf")
    private fun jsonFile(c: Context, number: String) = File(dir(c), safe(number) + ".json")

    /** e.g. AQ-261014-03 (AVIP quote) or CE-261014-01 (customer estimate). */
    fun nextNumber(c: Context, type: EstimateType): String {
        val p = c.getSharedPreferences("numbers", Context.MODE_PRIVATE)
        val day = SimpleDateFormat("yyMMdd", Locale.ENGLISH).format(Date())
        val prefix = if (type == EstimateType.AVIP_QUOTE) "AQ" else "CE"
        val key = "$prefix-$day"
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

    // ---- sharing ----

    fun uri(c: Context, file: File): Uri =
        FileProvider.getUriForFile(c, c.packageName + ".files", file)

    fun open(a: Activity, file: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(a, file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            a.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, "No PDF viewer found. Use Share instead.", Toast.LENGTH_LONG).show()
        }
    }

    fun share(a: Activity, file: File, message: String) {
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri(a, file))
            .putExtra(Intent.EXTRA_TEXT, message)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "Share estimate"))
    }

    /** Sends the PDF straight to a WhatsApp chat (falls back to the share sheet). */
    fun whatsapp(a: Activity, file: File, number: String, message: String) {
        val digits = number.filter { it.isDigit() }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                .setPackage(pkg)
                .putExtra(Intent.EXTRA_STREAM, uri(a, file))
                .putExtra(Intent.EXTRA_TEXT, message)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (digits.isNotEmpty()) i.putExtra("jid", "$digits@s.whatsapp.net")
            try {
                a.startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        share(a, file, message)
    }

    /** Opens a WhatsApp chat with AVIP with a typed message (no file). */
    fun chatAvip(a: Activity, message: String) {
        val number = Catalog.settings["whatsapp"].filter { it.isDigit() }.ifBlank { "919288176289" }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=" + Uri.encode(message)))
        try {
            a.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, "WhatsApp not found", Toast.LENGTH_SHORT).show()
        }
    }

    fun call(a: Activity, number: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })))
        } catch (_: Exception) {
        }
    }
}
