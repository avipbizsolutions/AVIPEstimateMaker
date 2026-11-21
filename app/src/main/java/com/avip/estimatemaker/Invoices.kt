package com.avip.estimatemaker

import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * GST tax invoices made from cost estimates (Founders and Sales Managers).
 * The office register ("Tax Invoices" tab) issues the number; the office adds the e-invoice IRN there.
 */
object Invoices {

    /** One row of the invoice register. */
    class Inv(val o: JSONObject) {
        val number = o.optString("number")
        val date = o.optLong("date")
        val status = o.optString("status", "Issued")
        val cancelled get() = status.equals("Cancelled", true)
        val company = o.optString("company")
        val mobile = o.optString("mobile")
        val gst = o.optString("gst")
        val total = o.optDouble("total", 0.0)
        val received = o.optDouble("received", 0.0)
        val balance = o.optDouble("balance", 0.0)
        val payment = o.optString("payment")
        val madeBy = o.optString("madeBy")
        val estimateNo = o.optString("estimateNo")
        val irn = o.optString("irn")

        /** The full invoice for the PDF: saved data + what the office added (IRN, payment, status). */
        fun doc(): EstimateDoc? = runCatching { EstimateDoc.fromJson(JSONObject(o.optString("doc"))) }.getOrNull()?.let { d ->
            d.copy(invoice = (d.invoice ?: InvoiceInfo(number, date)).copy(
                number = number, date = date, received = received, status = status,
                irn = irn, ackNo = o.optString("ackNo"), ackDate = o.optString("ackDate"), qr = o.optString("qr"),
                cancelReason = o.optString("cancelReason"), vehicle = o.optString("vehicle"), transporter = o.optString("transporter"),
            ))
        }
    }

    fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Makes the PDF on this phone. */
    fun pdf(a: AppCompatActivity, doc: EstimateDoc, then: (File) -> Unit) {
        a.lifecycleScope.launch {
            val f = try {
                withContext(Dispatchers.IO) {
                    File(File(a.cacheDir, "shared").apply { mkdirs() }, safeName(doc.invoice?.number ?: doc.number) + ".pdf")
                        .also { PdfMaker(a).make(doc, it) }
                }
            } catch (x: Exception) {
                Toast.makeText(a, tr("Couldn't create PDF: ", "PDF नहीं बना: ") + x.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            then(f)
        }
    }

    private fun latestBuyer(b: Buyer): Buyer {
        SaathiDirectory.list.firstOrNull { it.mobile == b.mobile }?.let { s ->
            return b.copy(company = s.company.ifBlank { b.company }, contact = s.contact.ifBlank { b.contact },
                gst = s.gst.ifBlank { b.gst }, city = s.city.ifBlank { b.city }, address = s.address.ifBlank { b.address })
        }
        return b
    }

    /** Converts a cost estimate into a tax invoice: asks vehicle, transporter and payment, then issues the number. */
    fun convert(a: AppCompatActivity, estimate: EstimateDoc, fallbackBuyer: Buyer, onDone: (Inv) -> Unit) {
        SaathiDirectory.loadCached(a)
        val buyer = latestBuyer(estimate.buyer ?: fallbackBuyer)
        val totals = Calc.compute(estimate.lines)
        val vehicle = a.input(tr("Vehicle no. (optional)", "गाड़ी नंबर (वैकल्पिक)"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val transporter = AutoCompleteTextView(a).apply {
            hint = tr("Transporter (optional)", "ट्रांसपोर्टर (वैकल्पिक)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            threshold = 1
            setAdapter(ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line,
                Directory.transporters.map { listOf(it.name, it.vehicleType, it.phone).filter { s -> s.isNotBlank() }.joinToString(" · ") }))
        }
        val received = a.input(tr("Amount received now ₹ (0 if unpaid)", "अभी मिली राशि ₹ (बाकी हो तो 0)"),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "0")
        val form = a.vertical(18) {
            addView(a.text(buyer.company, 15f, Ui.TEXT, bold = true))
            addView(a.text(if (buyer.gst.isNotBlank()) "GSTIN ${buyer.gst}" else tr("No GSTIN: this will be a B2C invoice", "GSTIN नहीं: यह B2C इनवॉइस होगा"),
                12.5f, if (buyer.gst.isNotBlank()) Ui.MUTED else 0xFFB7791F.toInt()))
            if (buyer.address.isBlank() && buyer.city.isBlank()) addView(a.text(tr("Address missing: add it in Surya Saathis → ⋮ → Edit before invoicing.",
                "पता नहीं है: इनवॉइस से पहले सूर्य साथी → ⋮ → बदलें में पता डालें।"), 12f, Ui.DANGER))
            addView(a.text(tr("${estimate.lines.size} items · Invoice total ", "${estimate.lines.size} आइटम · इनवॉइस कुल ") + Fmt.inr(totals.grandTotal, true),
                13.5f, Ui.BRAND_DARK, bold = true), lp().margins(a, t = 6, b = 8))
            addView(vehicle, lp().margins(a, b = 8))
            addView(transporter, lp().margins(a, b = 8))
            addView(received, lp().margins(a, b = 8))
            addView(a.text(tr("The invoice number is issued by the office register and cannot be deleted — only cancelled by a Founder.",
                "इनवॉइस नंबर ऑफिस रजिस्टर से मिलता है और हटाया नहीं जा सकता — केवल फ़ाउंडर रद्द कर सकते हैं।"), 12f, Ui.MUTED))
        }
        val d = AlertDialog.Builder(a).setTitle(tr("Tax invoice from ${estimate.number}", "${estimate.number} से टैक्स इनवॉइस"))
            .setView(android.widget.ScrollView(a).apply { addView(form) })
            .setPositiveButton(tr("Issue invoice", "इनवॉइस बनाएं"), null)
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).create()
        val requestId = UUID.randomUUID().toString()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
                val paid = received.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                if (paid < 0 || paid > totals.grandTotal + 0.5) { received.error = tr("More than the invoice total", "इनवॉइस कुल से ज़्यादा"); return@setOnClickListener }
                btn.isEnabled = false
                val sellerState = Catalog.settings["gstin"].take(2).ifBlank { "10" }
                val buyerState = buyer.gst.take(2).takeIf { it.length == 2 && it.all(Char::isDigit) } ?: sellerState
                val inter = buyerState != sellerState
                val gst = totals.gstTotal
                val half = round2(gst / 2)
                val info = InvoiceInfo(number = "", date = 0, estimateNo = estimate.number,
                    vehicle = vehicle.text.toString().trim().uppercase(), transporter = transporter.text.toString().trim(), received = paid)
                val invDoc = estimate.copy(buyer = buyer, invoice = info, madeBy = Session.teamName(a))
                a.lifecycleScope.launch {
                    try {
                        val o = Api.call(a, "invoiceCreate", JSONObject().put("token", Session.token(a)).put("requestId", requestId)
                            .put("estimateNo", estimate.number).put("company", buyer.company).put("contact", buyer.contact)
                            .put("mobile", buyer.mobile).put("gst", buyer.gst)
                            .put("address", listOf(buyer.address, buyer.city).filter { it.isNotBlank() }.joinToString(", "))
                            .put("placeOfSupply", buyerState).put("taxable", totals.subTotal)
                            .put("cgst", if (inter) 0.0 else half).put("sgst", if (inter) 0.0 else round2(gst - half)).put("igst", if (inter) gst else 0.0)
                            .put("roundOff", totals.roundOff).put("total", totals.grandTotal).put("received", paid)
                            .put("vehicle", info.vehicle).put("transporter", info.transporter)
                            .put("doc", invDoc.toJson().toString()))
                        d.dismiss()
                        // Number/date from the register; keep the saved data for later copies.
                        o.put("doc", invDoc.toJson().toString())
                        val inv = Inv(o)
                        Toast.makeText(a, tr("Invoice ${inv.number} issued", "इनवॉइस ${inv.number} बना"), Toast.LENGTH_LONG).show()
                        onDone(inv)
                        actions(a, inv) {}
                    } catch (e: ApiException) {
                        btn.isEnabled = true
                        Toast.makeText(a, e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        d.show()
    }

    /** Open / send / payment / cancel for one invoice. */
    fun actions(a: AppCompatActivity, inv: Inv, onChanged: (Inv) -> Unit) {
        val doc = inv.doc() ?: run {
            Toast.makeText(a, tr("Invoice data missing in the register.", "रजिस्टर में इनवॉइस डेटा नहीं है।"), Toast.LENGTH_LONG).show(); return
        }
        val buyer = doc.buyer
        val opts = mutableListOf<Pair<String, () -> Unit>>(
            tr("Open PDF", "PDF खोलें") to { pdf(a, doc) { EstimateStore.open(a, it) } },
            tr("Send to customer on WhatsApp", "ग्राहक को WhatsApp पर भेजें") to {
                pdf(a, doc) {
                    EstimateStore.whatsapp(a, it, buyer?.mobile ?: inv.mobile,
                        "Namaste ${buyer?.contact?.substringBefore(' ') ?: ""} ji, sharing AVIP tax invoice ${inv.number} for ${inv.company}" +
                            " (${Fmt.inr(inv.total, true)}). — ${Session.teamName(a)}, AVIP Business Solutions LLP")
                }
            },
        )
        if (!inv.cancelled) opts += tr("Record payment received", "मिला भुगतान दर्ज करें") to { payment(a, inv, onChanged) }
        if (!inv.cancelled && Can.cancelInvoice(a)) opts += tr("Cancel invoice", "इनवॉइस रद्द करें") to { cancel(a, inv, onChanged) }
        AlertDialog.Builder(a).setTitle(inv.number + if (inv.cancelled) " · " + tr("Cancelled", "रद्द") else "")
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    private fun payment(a: AppCompatActivity, inv: Inv, onChanged: (Inv) -> Unit) {
        val amt = a.input(tr("Total received so far ₹", "अब तक कुल मिला ₹"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, Fmt.num(inv.received))
        AlertDialog.Builder(a).setTitle(tr("Payment · ", "भुगतान · ") + inv.number)
            .setMessage(tr("Invoice total ", "इनवॉइस कुल ") + Fmt.inr(inv.total, true))
            .setView(a.vertical(18) { addView(amt) })
            .setPositiveButton(tr("Save", "सेव")) { _, _ ->
                val v = amt.text.toString().replace(",", "").toDoubleOrNull() ?: return@setPositiveButton
                a.lifecycleScope.launch {
                    try {
                        val o = Api.call(a, "invoicePayment", JSONObject().put("token", Session.token(a)).put("number", inv.number).put("received", v))
                        o.put("doc", inv.o.optString("doc"))
                        onChanged(Inv(o))
                        Toast.makeText(a, tr("Saved: ", "सेव: ") + o.optString("payment"), Toast.LENGTH_SHORT).show()
                    } catch (e: ApiException) { Toast.makeText(a, e.message, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton(tr("Cancel", "रद्द करें"), null).show()
    }

    private fun cancel(a: AppCompatActivity, inv: Inv, onChanged: (Inv) -> Unit) {
        val reason = a.input(tr("Reason (required)", "कारण (ज़रूरी)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        AlertDialog.Builder(a).setTitle(tr("Cancel ", "रद्द करें ") + inv.number + "?")
            .setMessage(tr("The invoice stays in the register marked Cancelled; its number is not used again. If an IRN was generated, cancel it on the e-invoice portal too (allowed within 24 hours).",
                "इनवॉइस रजिस्टर में 'Cancelled' के रूप में रहेगा; उसका नंबर दोबारा नहीं मिलेगा। अगर IRN बना था तो उसे भी e-invoice पोर्टल पर रद्द करें (24 घंटे के अंदर)।"))
            .setView(a.vertical(18) { addView(reason) })
            .setPositiveButton(tr("Cancel invoice", "इनवॉइस रद्द करें")) { _, _ ->
                a.lifecycleScope.launch {
                    try {
                        val o = Api.call(a, "invoiceCancel", JSONObject().put("token", Session.token(a)).put("number", inv.number)
                            .put("reason", reason.text.toString().trim()))
                        o.put("doc", inv.o.optString("doc"))
                        onChanged(Inv(o))
                        Toast.makeText(a, tr("Invoice cancelled", "इनवॉइस रद्द हुआ"), Toast.LENGTH_SHORT).show()
                    } catch (e: ApiException) { Toast.makeText(a, e.message, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton(tr("Back", "वापस"), null).show()
    }
}
