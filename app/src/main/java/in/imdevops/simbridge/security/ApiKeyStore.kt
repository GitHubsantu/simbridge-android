package `in`.imdevops.simbridge.security

import android.content.Context
import java.util.Base64

/**
 * Stores only the SHA-256 hash of the API key. The plaintext key exists once, when it is
 * generated, and is shown to the user; it cannot be recovered afterwards.
 *
 * Milestone 4 will move the hash behind an Android Keystore-protected wrapper.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("simbridge_security", Context.MODE_PRIVATE)

    fun hasKey(): Boolean = prefs.contains(KEY_HASH)

    /** Replaces any existing key. Returns the new plaintext key; the caller must show it once. */
    fun createNewKey(): String {
        val key = ApiKeys.generate()
        val encoded = Base64.getEncoder().encodeToString(ApiKeys.hash(key))
        prefs.edit().putString(KEY_HASH, encoded).commit()
        return key
    }

    fun verify(candidate: String?): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        val hash = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return ApiKeys.matches(candidate, hash)
    }

    private companion object {
        const val KEY_HASH = "api_key_sha256_b64"
    }
}
