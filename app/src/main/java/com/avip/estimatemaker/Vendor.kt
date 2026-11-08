package com.avip.estimatemaker

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** The logged-in Surya Saathi (B2B customer): details as kept in AVIP's "Surya Saathi" tab. */
object Vendor {
    private const val PREFS = "vendor"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRegistered(c: Context) = prefs(c).getBoolean("registered", false)

    /** Registered with a valid GSTIN (older installs registered without one). */
    fun isComplete(c: Context) = isRegistered(c) && Gstin.problem(gst(c)) == null
    fun company(c: Context) = prefs(c).getString("company", "") ?: ""
    fun contact(c: Context) = prefs(c).getString("contact", "") ?: ""
    fun mobile(c: Context) = prefs(c).getString("mobile", "") ?: ""
    fun gst(c: Context) = prefs(c).getString("gst", "") ?: ""
    fun city(c: Context) = prefs(c).getString("city", "") ?: ""
    fun address(c: Context) = prefs(c).getString("address", "") ?: ""

    fun installId(c: Context): String {
        val p = prefs(c)
        return p.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("install_id", it).apply()
        }
    }

    /** Keeps the Saathi's details from the office on the phone (after login / profile change). */
    fun store(c: Context, profile: JSONObject) {
        prefs(c).edit()
            .putBoolean("registered", true)
            .putString("company", profile.optString("company"))
            .putString("contact", profile.optString("contact"))
            .putString("mobile", profile.optString("mobile"))
            .putString("gst", profile.optString("gst"))
            .putString("city", profile.optString("city"))
            .putString("address", profile.optString("address"))
            .putLong("since", profile.optLong("since"))
            .apply()
    }

    fun since(c: Context) = prefs(c).getLong("since", 0L)
}

/**
 * Deletes the Surya Saathi's account: asks AVIP's sheet to remove their registration and
 * quotation records, and wipes their details, cart and saved quotations from this phone.
 * The request waits in the outbox until it can be sent.
 */
fun Vendor.deleteAccount(c: Context) {
    // Drop any not-yet-sent sign-up or quotation records first.
    Remote.keepOnly(c, "delete")
    Remote.queue(
        c, JSONObject()
            .put("kind", "delete")
            .put("mobile", mobile(c))
            .put("gst", gst(c))
            .put("installId", installId(c))
    )
    val keepId = installId(c)
    c.applicationContext.getSharedPreferences("vendor", Context.MODE_PRIVATE).edit()
        .clear().putString("install_id", keepId).apply()
    Cart.clear(c)
    EstimateStore.deleteAll(c)
    SalesStore.deleteAll(c)
    Business.clear(c)
    DriveSync.unlink(c)
    Session.logout(c)
}

/**
 * Sends Surya Saathi sign-ups and quotation summaries to the AVIP sheet through a
 * Google Apps Script web app (URL kept in App Settings → saathi_log_url).
 * Anything that can't be sent now waits on the phone and is retried later.
 */
object Remote {
    /** Must match SHARED_KEY in apps-script/Code.gs. */
    private const val SHARED_KEY = "avip-estimate-9Fk2"
    private const val PREFS = "remote"

    @Volatile private var sending = false

    fun queue(c: Context, item: JSONObject) {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
            arr.put(item)
            // keep the newest 200 at most
            val trimmed = JSONArray()
            for (i in maxOf(0, arr.length() - 200) until arr.length()) trimmed.put(arr.get(i))
            p.edit().putString("pending", trimmed.toString()).apply()
        }
        flushAsync(c)
    }

    /** Removes queued items except those of the given kind. */
    fun keepOnly(c: Context, kind: String) {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") == kind) kept.put(o)
            }
            p.edit().putString("pending", kept.toString()).apply()
        }
    }

    fun flushAsync(context: Context) {
        val c = context.applicationContext
        val endpoint = Catalog.settings["saathi_log_url"].ifBlank { Catalog.settings["vendor_log_url"] }
        if (endpoint.isBlank() || !endpoint.startsWith("https://") || sending) return
        sending = true
        Thread {
            try {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                while (true) {
                    val first = synchronized(this) {
                        val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
                        if (arr.length() == 0) null else arr.getJSONObject(0)
                    } ?: break
                    if (!post(endpoint, JSONObject(first.toString()).put("key", SHARED_KEY).toString())) break
                    // Remove exactly the item just sent (the queue may have changed meanwhile).
                    synchronized(this) {
                        val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
                        val sent = first.toString()
                        val rest = JSONArray()
                        var removed = false
                        for (i in 0 until arr.length()) {
                            val o = arr.get(i)
                            if (!removed && o.toString() == sent) { removed = true; continue }
                            rest.put(o)
                        }
                        p.edit().putString("pending", rest.toString()).apply()
                    }
                }
            } catch (_: Exception) {
            } finally {
                sending = false
            }
        }.start()
    }

    private fun post(endpoint: String, body: String): Boolean {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code in 200..299 && text.contains("\"ok\":true")
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
