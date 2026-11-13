package com.avip.estimatemaker

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Who is logged in on this phone:
 *  • SAATHI — a Surya Saathi (B2B customer), mobile + PIN from the "Surya Saathi" tab;
 *  • TEAM   — an AVIP Manager / Founder, mobile + PIN from the common AVIP login.
 */
object Session {
    enum class Kind { NONE, SAATHI, TEAM }

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun kind(c: Context): Kind = runCatching { Kind.valueOf(p(c).getString("kind", "NONE") ?: "NONE") }.getOrDefault(Kind.NONE)
    fun isTeam(c: Context) = kind(c) == Kind.TEAM
    fun isSaathi(c: Context) = kind(c) == Kind.SAATHI
    fun token(c: Context) = p(c).getString("token", "") ?: ""
    fun mustSetPin(c: Context) = p(c).getBoolean("must_set_pin", false)

    // Team member
    fun teamName(c: Context) = p(c).getString("team_name", "") ?: ""
    fun teamMobile(c: Context) = p(c).getString("team_mobile", "") ?: ""
    fun teamDesignation(c: Context) = p(c).getString("team_designation", "") ?: ""
    fun founder(c: Context) = p(c).getBoolean("founder", false)
    fun teamRoleLabel(c: Context) = if (founder(c)) tr("Founder", "फ़ाउंडर") else tr("Manager", "मैनेजर")

    /** The name to use in messages: the Saathi's contact person, or the team member. */
    fun myName(c: Context) = if (isTeam(c)) teamName(c) else Vendor.contact(c)
    fun myCompany(c: Context) = if (isTeam(c)) "AVIP Business Solutions LLP" else Vendor.company(c)

    fun saveSaathi(c: Context, token: String, profile: JSONObject, mustSet: Boolean) {
        val mobile = profile.optString("mobile")
        switchOwner(c, "S:$mobile")
        Vendor.store(c, profile)
        p(c).edit().putString("kind", Kind.SAATHI.name).putString("token", token).putBoolean("must_set_pin", mustSet).apply()
    }

    fun saveTeam(c: Context, token: String, user: JSONObject, mustSet: Boolean, founder: Boolean) {
        switchOwner(c, "T:" + user.optString("mobile").filter { it.isDigit() }.takeLast(10))
        p(c).edit().putString("kind", Kind.TEAM.name).putString("token", token).putBoolean("must_set_pin", mustSet)
            .putString("team_name", user.optString("name").trim())
            .putString("team_mobile", user.optString("mobile").filter { it.isDigit() }.takeLast(10))
            .putString("team_designation", user.optString("designation").trim())
            .putBoolean("founder", founder).apply()
    }

    fun pinChanged(c: Context, token: String) {
        p(c).edit().putString("token", token).putBoolean("must_set_pin", false).apply()
    }

    /** Logs out. Data on the phone stays for the same person's next login. */
    fun logout(c: Context) {
        val owner = p(c).getString("owner", "")
        p(c).edit().clear().putString("owner", owner).apply()
        TeamTarget.clear(c)
    }

    /**
     * A different person is logging in on this phone: the previous person's cart, cost
     * estimates, customer quotations, invoices, business details and Drive link are removed
     * from the phone (their Google Drive copies stay in their Drive).
     */
    private fun switchOwner(c: Context, owner: String) {
        val before = p(c).getString("owner", null)
        if (before != null && before != owner) wipeLocal(c)
        if (before == null && Vendor.isRegistered(c) && owner != "S:" + Vendor.mobile(c)) wipeLocal(c)
        p(c).edit().putString("owner", owner).apply()
    }

    fun wipeLocal(c: Context) {
        Cart.clear(c)
        EstimateStore.deleteAll(c)
        SalesStore.deleteAll(c)
        Business.clear(c)
        DriveSync.unlink(c)
        TeamTarget.clear(c)
        val keepId = Vendor.installId(c)
        c.applicationContext.getSharedPreferences("vendor", Context.MODE_PRIVATE).edit()
            .clear().putString("install_id", keepId).apply()
    }
}

/** A readable error from the office; [code] is e.g. "ALREADY", "BADPIN", "SESSION", "BLOCKED", "NET". */
class ApiException(val code: String, message: String) : Exception(message)

/** Calls to the office web app (App Settings → saathi_log_url) that need an answer now. */
object Api {
    private const val SHARED_KEY = "avip-estimate-9Fk2"

    suspend fun call(c: Context, kind: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = Catalog.settings["saathi_log_url"]
        if (!endpoint.startsWith("https://")) throw ApiException("SETUP", tr("Login service is not set up yet. Please contact AVIP.", "लॉगिन सेवा अभी चालू नहीं है। कृपया AVIP से संपर्क करें।"))
        body.put("key", SHARED_KEY).put("kind", kind)
            .put("appVersion", AppInfo.versionName(c)).put("installId", Vendor.installId(c))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
        val text = try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw ApiException("NET", tr("No internet connection. Please try again.", "इंटरनेट कनेक्शन नहीं है। फिर से कोशिश करें।"))
        }
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ApiException("NET", tr("The office server didn't answer. Try again in a minute.", "ऑफिस सर्वर से जवाब नहीं आया। एक मिनट बाद फिर कोशिश करें।"))
        if (!o.optBoolean("ok")) {
            val raw = o.optString("error", "Something went wrong")
            if (raw.contains("unknown kind")) throw ApiException("SETUP", tr("AVIP is updating the login service. Please try again later.", "AVIP लॉगिन सेवा अपडेट कर रहा है। कृपया बाद में कोशिश करें।"))
            val code = Regex("^([A-Z]{3,10}): ").find(raw)?.groupValues?.get(1) ?: "ERR"
            throw ApiException(code, friendly(code, raw.removePrefix("$code: ")))
        }
        o
    }

    private fun friendly(code: String, en: String): String = if (!Lang.hindi) en else when (code) {
        "ALREADY" -> "यह मोबाइल नंबर पहले से रजिस्टर है। कृपया अपने PIN से लॉगिन करें।"
        "NOTFOUND" -> "यह मोबाइल नंबर रजिस्टर नहीं है। पहले रजिस्टर करें।"
        "BADPIN" -> "गलत PIN। भूल गए? AVIP से PIN रीसेट करवाएं।"
        "LOCKED" -> "बहुत बार गलत PIN। 15 मिनट बाद कोशिश करें या AVIP से PIN रीसेट करवाएं।"
        "BLOCKED" -> "आपका अकाउंट रोका गया है। कृपया AVIP से संपर्क करें।"
        "SESSION" -> "कृपया फिर से लॉगिन करें।"
        "NOTEAM" -> "Bihar Solar Circle केवल AVIP Managers और Founders के लिए है।"
        else -> en
    }
}

/** A B2B buyer on a cost estimate (the Surya Saathi, or the customer a manager picked). */
data class Buyer(
    val company: String, val contact: String, val mobile: String,
    val gst: String, val city: String, val address: String,
    val registered: Long = 0, val lastLogin: Long = 0, val status: String = "Active", val addedBy: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("company", company).put("contact", contact).put("mobile", mobile)
        .put("gst", gst).put("city", city).put("address", address).put("registered", registered)
        .put("lastLogin", lastLogin).put("status", status).put("addedBy", addedBy)

    companion object {
        fun fromJson(o: JSONObject?): Buyer? = o?.let {
            Buyer(it.optString("company"), it.optString("contact"), it.optString("mobile"), it.optString("gst"),
                it.optString("city"), it.optString("address"), it.optLong("registered"), it.optLong("lastLogin"),
                it.optString("status", "Active"), it.optString("addedBy"))
        }

        fun ofSaathi(c: Context) = Buyer(Vendor.company(c), Vendor.contact(c), Vendor.mobile(c), Vendor.gst(c), Vendor.city(c), Vendor.address(c))
    }
}

/** AVIP Team: the B2B customer the current cost estimate is for. */
object TeamTarget {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("team_target", Context.MODE_PRIVATE)
    fun get(c: Context): Buyer? = runCatching { Buyer.fromJson(JSONObject(p(c).getString("buyer", "") ?: "")) }.getOrNull()
    fun set(c: Context, b: Buyer) = p(c).edit().putString("buyer", b.toJson().toString()).apply()
    fun clear(c: Context) = p(c).edit().clear().apply()
}

/** The team's list of Surya Saathis (B2B customers), kept on the phone for offline use. */
object SaathiDirectory {
    @Volatile var list: List<Buyer> = emptyList(); private set
    private fun file(c: Context) = File(c.filesDir, "saathis.json")

    fun loadCached(c: Context) {
        if (list.isNotEmpty()) return
        list = runCatching { parse(JSONArray(file(c).readText())) }.getOrDefault(emptyList())
    }

    suspend fun refresh(c: Context): Boolean = try {
        val o = Api.call(c, "saathiList", JSONObject().put("token", Session.token(c)))
        val arr = o.optJSONArray("saathis") ?: JSONArray()
        list = parse(arr)
        withContext(Dispatchers.IO) { file(c).writeText(arr.toString()) }
        true
    } catch (e: ApiException) {
        loadCached(c)
        if (e.code == "SESSION" || e.code == "NOTEAM") throw e
        false
    }

    fun removed(c: Context, mobile: String) {
        list = list.filter { it.mobile != mobile }
        runCatching { file(c).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
    }

    fun added(c: Context, b: Buyer) {
        list = listOf(b) + list.filter { it.mobile != b.mobile }
        runCatching { file(c).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
    }

    private fun parse(arr: JSONArray) = (0 until arr.length()).mapNotNull { Buyer.fromJson(arr.optJSONObject(it)) }
}
