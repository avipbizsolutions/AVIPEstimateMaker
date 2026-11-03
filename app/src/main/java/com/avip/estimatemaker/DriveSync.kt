package com.avip.estimatemaker

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves the Surya Saathi's customer quotations and tax invoices to THEIR OWN Google Drive,
 * in a "Bihar Solar Circle" folder with "Customer Quotations" and "Tax Invoices" inside.
 *
 * Uses the drive.file permission only: the app can see and change just the files it
 * created itself, nothing else in the person's Drive.
 * Needs a one-time Google Cloud setup (Android OAuth client for this app's package and
 * signing key) — see README "Google Drive setup".
 */
object DriveSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)

    fun isLinked(c: Context) = p(c).getBoolean("linked", false)
    fun email(c: Context) = p(c).getString("email", "") ?: ""
    fun rootFolder(c: Context) = p(c).getString("folder_root", "") ?: ""
    fun lastError(c: Context) = p(c).getString("last_error", "") ?: ""

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Asks Google for Drive access (may need the person to pick an account / allow). */
    suspend fun authorize(c: Context): AuthorizationResult =
        Identity.getAuthorizationClient(c).authorize(request()).await()

    /** Called once access was granted: remembers the account and uploads everything waiting. */
    suspend fun onAuthorized(c: Context, token: String) = withContext(Dispatchers.IO) {
        val email = runCatching {
            JSONObject(http("GET", "$API/about?fields=user(emailAddress)", token).second)
                .optJSONObject("user")?.optString("emailAddress") ?: ""
        }.getOrDefault("")
        p(c).edit().putBoolean("linked", true).putString("email", email).remove("last_error").apply()
        syncPending(c)
    }

    fun unlink(c: Context) {
        p(c).edit().clear().apply()
        // Files already uploaded stay in the person's Drive; they will be uploaded again
        // (as new copies) only if Drive is linked again.
        SalesStore.all(c).forEach { if (it.driveSynced || it.driveId.isNotBlank()) { it.driveSynced = false; it.driveId = ""; SalesStore.save(c, it) } }
    }

    /** A fresh access token without showing anything, or null if the person must act. */
    private suspend fun silentToken(c: Context): String? = try {
        val r = authorize(c)
        if (r.hasResolution()) null else r.accessToken
    } catch (_: Exception) {
        null
    }

    fun syncPendingAsync(c: Context) {
        if (!isLinked(c)) return
        val app = c.applicationContext
        scope.launch { runCatching { syncPending(app) } }
    }

    /** Uploads every document not yet in Drive. Returns how many were uploaded. */
    suspend fun syncPending(c: Context): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!isLinked(c)) return@withContext 0
            val waiting = SalesStore.all(c).filter { !it.driveSynced }
            if (waiting.isEmpty()) return@withContext 0
            val token = silentToken(c) ?: run {
                p(c).edit().putString("last_error", "auth").apply(); return@withContext 0
            }
            var done = 0
            for (doc in waiting) {
                val pdf = SalesStore.pdfFile(c, doc)
                if (!pdf.exists()) continue
                try {
                    val folder = folderFor(c, token, doc.type)
                    val name = "${if (doc.type == SaleType.INVOICE) "Invoice" else "Quotation"} ${doc.number.replace('/', '-')} - ${doc.customer.name}.pdf"
                    doc.driveId = upload(token, doc.driveId, folder, name, pdf.readBytes())
                    doc.driveSynced = true
                    SalesStore.save(c, doc)
                    done++
                } catch (e: Exception) {
                    p(c).edit().putString("last_error", e.message ?: "upload").apply()
                    break
                }
            }
            if (done == waiting.size) p(c).edit().remove("last_error").apply()
            done
        }
    }

    // ---- folders ----

    private fun folderFor(c: Context, token: String, type: SaleType): String {
        val root = ensureFolder(c, token, "folder_root", "Bihar Solar Circle", null)
        return if (type == SaleType.INVOICE) ensureFolder(c, token, "folder_invoice", "Tax Invoices", root)
        else ensureFolder(c, token, "folder_quote", "Customer Quotations", root)
    }

    /** Re-uses the saved folder unless it was deleted / trashed; otherwise creates it. */
    private fun ensureFolder(c: Context, token: String, key: String, name: String, parent: String?): String {
        val saved = p(c).getString(key, "") ?: ""
        if (saved.isNotBlank()) {
            val (code, body) = http("GET", "$API/files/$saved?fields=id,trashed", token)
            if (code == 200 && !JSONObject(body).optBoolean("trashed")) return saved
        }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parent != null) meta.put("parents", org.json.JSONArray().put(parent))
        val (code, body) = http("POST", "$API/files?fields=id", token, meta.toString().toByteArray(), "application/json")
        if (code !in 200..299) error("folder $code")
        val id = JSONObject(body).getString("id")
        p(c).edit().putString(key, id).apply()
        return id
    }

    // ---- upload ----

    /** Creates the file, or replaces its content when [existingId] is still there. Returns the file id. */
    private fun upload(token: String, existingId: String, folder: String, name: String, bytes: ByteArray): String {
        if (existingId.isNotBlank()) {
            val meta = JSONObject().put("name", name)
            val (code, body) = multipart("PATCH", "$UPLOAD/files/$existingId?uploadType=multipart&fields=id", token, meta, bytes)
            if (code in 200..299) return JSONObject(body).getString("id")
            if (code != 404) error("update $code")
        }
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(folder))
        val (code, body) = multipart("POST", "$UPLOAD/files?uploadType=multipart&fields=id", token, meta, bytes)
        if (code !in 200..299) error("upload $code")
        return JSONObject(body).getString("id")
    }

    private fun multipart(method: String, url: String, token: String, meta: JSONObject, pdf: ByteArray): Pair<Int, String> {
        val boundary = "bsc" + System.nanoTime()
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
        w("--$boundary\r\nContent-Type: application/pdf\r\n\r\n")
        out.write(pdf)
        w("\r\n--$boundary--\r\n")
        return http(method, url, token, out.toByteArray(), "multipart/related; boundary=$boundary")
    }

    private fun http(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Google APIs accept the override header.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
