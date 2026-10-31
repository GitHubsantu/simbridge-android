package `in`.imdevops.simbridge.sms

/**
 * Limits where the gateway may send. Two layers:
 *  1. A short built-in list of international prefixes that are premium-rate or satellite/"global network"
 *     ranges commonly abused for toll fraud. Always blocked. Best effort: it cannot know every premium
 *     range inside individual countries.
 *  2. An owner-chosen allow-list of country prefixes (for example "+91"). Empty means any country.
 */
object DestinationPolicy {
    private val ALWAYS_BLOCKED = listOf("+979", "+881", "+882", "+883", "+870", "+878")
    private val PREFIX = Regex("^\\+[1-9][0-9]{0,5}$")

    /** Returns null if sending to [e164] is allowed, otherwise a short reason. */
    fun check(e164: String, allowedPrefixes: List<String>): String? {
        if (ALWAYS_BLOCKED.any { e164.startsWith(it) }) {
            return "This destination range is blocked because it is commonly used for premium-rate or satellite numbers."
        }
        if (allowedPrefixes.isNotEmpty() && allowedPrefixes.none { e164.startsWith(it) }) {
            return "This destination is outside the allowed country prefixes."
        }
        return null
    }

    sealed interface Parsed {
        data class Ok(val prefixes: List<String>) : Parsed
        data class Bad(val message: String) : Parsed
    }

    /** Parses "+91, +1" style text. Blank means no restriction. */
    fun parse(text: String): Parsed {
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val bad = parts.firstOrNull { !PREFIX.matches(it) }
        if (bad != null) return Parsed.Bad("“$bad” is not a country prefix like +91.")
        return Parsed.Ok(parts.distinct().take(20))
    }
}
