package `in`.imdevops.simbridge.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pure (Android-free) API key helpers so they can be unit tested on the JVM. */
object ApiKeys {
    const val PREFIX = "sbk_"
    private const val KEY_BYTES = 32
    private const val MAX_TOKEN_LENGTH = 256

    /** 256 bits from [SecureRandom], URL-safe Base64, with a recognisable prefix. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(KEY_BYTES)
        random.nextBytes(bytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** SHA-256 is sufficient because the key is 256 bits of randomness, not a human password. */
    fun hash(key: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))

    /** Constant-time comparison of hashes (not of the raw strings). */
    fun matches(candidate: String?, storedHash: ByteArray?): Boolean {
        if (candidate == null || storedHash == null) return false
        return MessageDigest.isEqual(hash(candidate), storedHash)
    }

    /** Extracts the token from an `Authorization: Bearer <token>` header, or null if malformed. */
    fun parseBearer(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) return null
        val token = header.substring(7).trim()
        return token.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH }
    }
}
