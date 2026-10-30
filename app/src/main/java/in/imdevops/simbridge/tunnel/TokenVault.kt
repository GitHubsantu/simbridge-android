package `in`.imdevops.simbridge.tunnel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the Cloudflare tunnel token encrypted with an Android Keystore AES-GCM key. */
class TokenVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simbridge_tunnel_secret", Context.MODE_PRIVATE)

    fun hasToken(): Boolean = prefs.contains(KEY_BLOB)

    /** Returns false if the Keystore refused (rare on some OEM builds). */
    fun save(token: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        true
    } catch (_: Exception) {
        false
    }

    /** Returns null if there is no token or it can no longer be decrypted. */
    fun load(): String? = try {
        val blob = Base64.decode(prefs.getString(KEY_BLOB, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES)) }
        String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "simbridge_tunnel_token_key"
        const val KEY_BLOB = "token_blob"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

class TunnelPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simbridge_tunnel", Context.MODE_PRIVATE)
    var hostname: String
        get() = prefs.getString("hostname", "") ?: ""
        set(value) = prefs.edit().putString("hostname", value).apply()
}
