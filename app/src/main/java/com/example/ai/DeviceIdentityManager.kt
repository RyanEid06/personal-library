package com.example.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID

class DeviceIdentityManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("device_identity_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "personal_library_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = "device_${UUID.randomUUID().toString().take(12)}"
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    val deviceCredential: String?
        get() = prefs.getString("device_credential", null)

    val sessionToken: String?
        get() = prefs.getString("session_token", null)

    val sessionExpiresAt: Long
        get() = prefs.getLong("session_expires_at", 0L)

    val isPaired: Boolean
        get() = !deviceCredential.isNullOrBlank()

    val isSessionValid: Boolean
        get() {
            val token = sessionToken
            return !token.isNullOrBlank() && sessionExpiresAt > System.currentTimeMillis() + 60_000L
        }

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateKeystoreKey()
            }
        } catch (_: Exception) {
            // In unit tests / Robolectric where AndroidKeyStore might not be registered, generate local keypair
            if (prefs.getString("fallback_pub_key", null) == null) {
                try {
                    val kpg = KeyPairGenerator.getInstance("EC")
                    kpg.initialize(256)
                    val kp = kpg.generateKeyPair()
                    val pubBytes = kp.public.encoded
                    val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
                    prefs.edit().putString("fallback_pub_key", pubB64).apply()
                } catch (_: Exception) {}
            }
        }
    }

    private fun generateKeystoreKey() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    fun getPublicKeyBase64(): String {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val cert = keyStore.getCertificate(KEY_ALIAS)
            if (cert != null) {
                Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
            } else {
                prefs.getString("fallback_pub_key", "mock_key") ?: "mock_key"
            }
        } catch (_: Exception) {
            prefs.getString("fallback_pub_key", "mock_key") ?: "mock_key"
        }
    }

    fun signData(data: ByteArray): ByteArray? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            if (privateKey != null) {
                val signature = Signature.getInstance("SHA256withECDSA")
                signature.initSign(privateKey)
                signature.update(data)
                signature.sign()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun savePairing(credential: String, token: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString("device_credential", credential)
            .putString("session_token", token)
            .putLong("session_expires_at", System.currentTimeMillis() + (expiresInSeconds * 1000L))
            .apply()
    }

    fun saveSessionToken(token: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString("session_token", token)
            .putLong("session_expires_at", System.currentTimeMillis() + (expiresInSeconds * 1000L))
            .apply()
    }

    fun clearPairing() {
        prefs.edit()
            .remove("device_credential")
            .remove("session_token")
            .remove("session_expires_at")
            .apply()
    }
}
