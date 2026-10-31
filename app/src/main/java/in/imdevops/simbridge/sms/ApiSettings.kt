package `in`.imdevops.simbridge.sms

/** User-configurable limits. Conservative defaults; changeable in the app's Security card. */
data class ApiSettings(
    val perMinute: Int = 5,
    val perDay: Int = 100,
    val maxMessageChars: Int = 320,
    /** SIM used when a request names none. Null = automatic (Android's default SMS SIM, or the only SIM). */
    val preferredSubscriptionId: Int? = null,
    /** Country prefixes like "+91" the gateway may send to. Empty = any country (built-in blocks still apply). */
    val allowedPrefixes: List<String> = emptyList(),
) {
    /** Returns a human-readable problem, or null if the values are acceptable. */
    fun validate(): String? = when {
        perMinute !in 1..60 -> "Per-minute limit must be 1–60."
        perDay !in 1..5000 -> "Per-day limit must be 1–5000."
        perDay < perMinute -> "Per-day limit cannot be below the per-minute limit."
        maxMessageChars !in 1..1000 -> "Maximum message length must be 1–1000."
        allowedPrefixes.any { DestinationPolicy.parse(it) !is DestinationPolicy.Parsed.Ok } -> "Invalid country prefix."
        else -> null
    }

    companion object {
        val DEFAULT = ApiSettings()
    }
}
