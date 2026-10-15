package com.example.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

interface DeviceSigner {
    fun getPublicKeyBase64(): String
    fun signData(data: ByteArray): ByteArray
}

class DeviceIdentityUnavailableException(cause: Throwable) : java.io.IOException(
    "Android Keystore is unavailable. Unlock your device and retry pairing or authentication.", cause
)

class AndroidKeystoreDeviceSigner : DeviceSigner {

    companion object {
        private const val KEY_ALIAS = "personal_library_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private fun ensureKeyExists(keyStore: KeyStore) {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
    }

    private fun <T> withKeystore(action: (KeyStore) -> T): T = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ensureKeyExists(keyStore)
        action(keyStore)
    } catch (error: Exception) {
        throw DeviceIdentityUnavailableException(error)
    }

    override fun getPublicKeyBase64(): String = withKeystore { keyStore ->
        val cert = keyStore.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("Keystore certificate missing for $KEY_ALIAS")
        Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    override fun signData(data: ByteArray): ByteArray = withKeystore { keyStore ->
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Keystore private key missing for $KEY_ALIAS")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        signature.sign()
    }
}

class DeviceIdentityManager(
    private val context: Context,
    private val signer: DeviceSigner = AndroidKeystoreDeviceSigner()
) {

    private val prefs = context.getSharedPreferences("device_identity_prefs", Context.MODE_PRIVATE)

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

    fun getPublicKeyBase64(): String {
        return signer.getPublicKeyBase64()
    }

    fun signData(data: ByteArray): ByteArray {
        return signer.signData(data)
    }

    fun signEnrollment(challengeNonce: String, appVersion: String, publicKeyBase64: String): String {
        val canonical = "PLIB-ENROLL-V1\n$deviceId\n$challengeNonce\n$appVersion\n$publicKeyBase64"
        val sigBytes = signData(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sigBytes, Base64.NO_WRAP)
    }

    fun signSessionRenew(challengeNonce: String, credential: String): String {
        val canonical = "PLIB-SESSION-V1\n$deviceId\n$challengeNonce\n$credential"
        val sigBytes = signData(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sigBytes, Base64.NO_WRAP)
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

    fun clearSession() {
        prefs.edit()
            .remove("session_token")
            .remove("session_expires_at")
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
