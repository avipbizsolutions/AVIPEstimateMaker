package com.avip.estimatemaker

/**
 * GSTIN checks done on the phone: structure, state code, embedded PAN pattern and
 * the official check digit (15th character). Catches typos and made-up numbers.
 */
object Gstin {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PATTERN = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")

    private val STATES = mapOf(
        "01" to "Jammu & Kashmir", "02" to "Himachal Pradesh", "03" to "Punjab",
        "04" to "Chandigarh", "05" to "Uttarakhand", "06" to "Haryana", "07" to "Delhi",
        "08" to "Rajasthan", "09" to "Uttar Pradesh", "10" to "Bihar", "11" to "Sikkim",
        "12" to "Arunachal Pradesh", "13" to "Nagaland", "14" to "Manipur", "15" to "Mizoram",
        "16" to "Tripura", "17" to "Meghalaya", "18" to "Assam", "19" to "West Bengal",
        "20" to "Jharkhand", "21" to "Odisha", "22" to "Chhattisgarh", "23" to "Madhya Pradesh",
        "24" to "Gujarat", "26" to "Dadra & Nagar Haveli and Daman & Diu", "27" to "Maharashtra",
        "29" to "Karnataka", "30" to "Goa", "31" to "Lakshadweep", "32" to "Kerala",
        "33" to "Tamil Nadu", "34" to "Puducherry", "35" to "Andaman & Nicobar Islands",
        "36" to "Telangana", "37" to "Andhra Pradesh", "38" to "Ladakh", "97" to "Other Territory",
    )

    /** Null when valid, otherwise a short reason to show the user. */
    fun problem(raw: String): String? {
        val g = raw.trim().uppercase()
        if (g.isEmpty()) return "GST number is required"
        if (g.length != 15) return "GSTIN must be 15 characters (you entered ${g.length})"
        if (!PATTERN.matches(g)) return "This doesn't look like a GSTIN (format: 10ABCDE1234F1Z5)"
        if (g.substring(0, 2) !in STATES) return "Unknown state code ${g.substring(0, 2)}"
        if (checkDigit(g.substring(0, 14)) != g[14]) return "GSTIN check digit doesn't match — please re-check the number"
        return null
    }

    fun state(raw: String): String? = STATES[raw.trim().take(2)]

    fun pan(raw: String): String = raw.trim().uppercase().substring(2, 12)

    private fun checkDigit(first14: String): Char {
        var sum = 0
        first14.forEachIndexed { i, c ->
            val product = CHARS.indexOf(c) * (if (i % 2 == 0) 1 else 2)
            sum += product / 36 + product % 36
        }
        return CHARS[(36 - sum % 36) % 36]
    }
}
