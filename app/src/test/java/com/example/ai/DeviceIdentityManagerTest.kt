package com.example.ai

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdentityManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun productionSignerConstructsWithoutKeystoreButFailsClosedWhenSigning() {
        val identity = DeviceIdentityManager(context)
        identity.savePairing("existing-credential", "existing-session", 1800)
        assertTrue(identity.isPaired)
        try {
            identity.getPublicKeyBase64()
            fail("Robolectric has no AndroidKeyStore; production must not generate a software identity")
        } catch (expected: java.io.IOException) {
            assertTrue(identity.isPaired)
            assertEquals("existing-credential", identity.deviceCredential)
        }
    }

    @Test fun explicitlyInjectedSignerProducesCanonicalProofAndRetainsIdentity() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = object : DeviceSigner {
            override fun getPublicKeyBase64() = Base64.encodeToString(keys.public.encoded, Base64.NO_WRAP)
            override fun signData(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private); update(data); sign()
            }
        }
        val identity = DeviceIdentityManager(context, signer)
        val publicKey = identity.getPublicKeyBase64()
        val proof = identity.signEnrollment("challenge", "1.0.0", publicKey)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keys.public)
            update("PLIB-ENROLL-V1\n${identity.deviceId}\nchallenge\n1.0.0\n$publicKey".toByteArray())
        }
        assertTrue(verifier.verify(Base64.decode(proof, Base64.NO_WRAP)))
        assertEquals(identity.deviceId, DeviceIdentityManager(context, signer).deviceId)
    }
}
