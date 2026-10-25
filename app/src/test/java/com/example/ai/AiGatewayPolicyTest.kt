package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiGatewayPolicyTest {
    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var gateway: AiGateway
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private var answer: (String) -> Pair<Int, String> = { 200 to analysis }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().putBoolean("ai_wifi_only", false).commit()
        context.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        val signer = object : DeviceSigner {
            override fun getPublicKeyBase64() = "synthetic-public-key"
            override fun signData(data: ByteArray) = byteArrayOf(1, 2, 3)
        }
        gateway = AiGateway(context, DeviceIdentityManager(context, signer))
        gateway.identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val path = exchange.requestURI.path
            paths.add(path)
            val (status, body) = answer(path)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        gateway.backendBaseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After fun close() {
        server.stop(0)
        for (name in listOf("personal_library_settings", "device_identity_prefs", "ai_gateway_prefs")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun wifiOnlyRequiresCurrentUnmeteredNetwork() {
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        prefs.edit().putBoolean("ai_wifi_only", true).commit()
        setAiTestNetwork(context, unmetered = true)
        assertTrue(AiNetworkPolicy(context).isAllowed())
        setAiTestNetwork(context, unmetered = false)
        assertFalse(AiNetworkPolicy(context).isAllowed())
        setAiTestNetwork(context, unmetered = true, connected = false)
        assertFalse(AiNetworkPolicy(context).isAllowed())
        prefs.edit().putBoolean("ai_wifi_only", false).commit()
        assertTrue(AiNetworkPolicy(context).isAllowed())
    }

    @Test fun newInstallAllowsMobileButRetainsExplicitWifiOnlyPreference() {
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        prefs.edit().clear().commit()
        setAiTestNetwork(context, unmetered = false)
        assertTrue(AiNetworkPolicy(context).isAllowed())
        prefs.edit().putBoolean("ai_wifi_only", true).commit()
        assertFalse(AiNetworkPolicy(context).isAllowed())
    }

    @Test fun unpairedConcurrentRequestsAutomaticallyRegisterOnlyOnce() = runBlocking {
        gateway.identityManager.clearPairing()
        answer = { path -> when (path) {
            "/v1/auth/challenge" -> 200 to """{"deviceId":"synthetic","nonce":"synthetic-nonce"}"""
            "/v1/auth/register" -> 200 to """{"status":"ENROLLED","deviceId":"synthetic","deviceCredential":"automatic-credential","sessionToken":"automatic-token","expiresInSeconds":1800}"""
            else -> 200 to analysis
        } }
        val workerGateway = AiGateway(context, gateway.identityManager)
        coroutineScope { (1..8).map { index -> async {
            (if (index % 2 == 0) workerGateway else gateway).analyze("synthetic", "TEXT", "note", null)
        } }.awaitAll() }
        assertEquals(1, paths.count { it == "/v1/auth/challenge" })
        assertEquals(1, paths.count { it == "/v1/auth/register" })
        assertEquals(8, paths.count { it == "/v1/ai/analyze" })
        assertEquals("automatic-credential", gateway.identityManager.deviceCredential)
    }

    @Test fun privacyGuardBlocksUnpairedRegistrationBeforeAnyDispatch() = runBlocking {
        gateway.identityManager.clearPairing()
        try {
            gateway.analyze("synthetic", "TEXT", "private", null,
                beforeRequest = { throw AiGatewayException.RequestBlockedException() })
            fail("Registration requires privacy eligibility")
        } catch (_: AiGatewayException.RequestBlockedException) { }
        assertTrue(paths.isEmpty())
    }

    @Test fun privacyChangeDuringAutomaticChallengeStopsRegistrationAndContent() = runBlocking {
        gateway.identityManager.clearPairing()
        var eligible = true
        answer = { eligible = false; 200 to """{"deviceId":"synthetic","nonce":"synthetic-nonce"}""" }
        try {
            gateway.analyze("synthetic", "TEXT", "private", null,
                beforeRequest = { if (!eligible) throw AiGatewayException.RequestBlockedException() })
            fail("Changed privacy must stop automatic registration")
        } catch (_: AiGatewayException.RequestBlockedException) { }
        assertEquals(listOf("/v1/auth/challenge"), paths.toList())
        assertFalse(gateway.identityManager.isPaired)
    }

    @Test fun automaticRegistrationCanRetryAfterTransientFailureWithoutSavingCredentials() = runBlocking {
        gateway.identityManager.clearPairing()
        var registrations = 0
        answer = { path -> when (path) {
            "/v1/auth/challenge" -> 200 to """{"deviceId":"synthetic","nonce":"synthetic-nonce"}"""
            "/v1/auth/register" -> if (registrations++ == 0) 503 to "{}" else
                200 to """{"status":"ENROLLED","deviceId":"synthetic","deviceCredential":"automatic-credential","sessionToken":"automatic-token","expiresInSeconds":1800}"""
            else -> 200 to analysis
        } }
        try { gateway.analyze("synthetic", "TEXT", "note", null); fail("Transient registration must surface") }
        catch (_: AiGatewayException.BackendUnavailableException) { }
        assertFalse(gateway.identityManager.isPaired)
        assertEquals("synthetic", gateway.analyze("synthetic", "TEXT", "note", null).itemId)
        assertEquals(2, paths.count { it == "/v1/auth/register" })
        assertEquals(1, paths.count { it == "/v1/ai/analyze" })
    }

    @Test fun concurrentExpiredSessionsRenewOnceWithoutReregistering() = runBlocking {
        gateway.identityManager.savePairing("synthetic-credential", "expired-token", -1)
        answer = { path -> when (path) {
            "/v1/auth/challenge" -> 200 to """{"deviceId":"synthetic","nonce":"synthetic-nonce"}"""
            "/v1/auth/session" -> 200 to """{"status":"OK","deviceId":"synthetic","sessionToken":"renewed-token","expiresInSeconds":1800}"""
            else -> 200 to analysis
        } }
        coroutineScope { (1..8).map { async { gateway.analyze("synthetic", "TEXT", "note", null) } }.awaitAll() }
        assertEquals(1, paths.count { it == "/v1/auth/challenge" })
        assertEquals(1, paths.count { it == "/v1/auth/session" })
        assertEquals(0, paths.count { it == "/v1/auth/register" })
        assertEquals(8, paths.count { it == "/v1/ai/analyze" })
    }

    @Test fun privacyGuardRejectsBeforeInitialContentRequest() = runBlocking {
        try {
            gateway.analyze("synthetic", "TEXT", "private synthetic note", null,
                beforeRequest = { throw AiGatewayException.RequestBlockedException() })
            fail("Blocked content must not be sent")
        } catch (_: AiGatewayException.RequestBlockedException) { }
        assertTrue(paths.isEmpty())
    }

    @Test fun redirectsCannotReuseAnEarlierPrivacyCheck() = runBlocking {
        server.removeContext("/")
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            paths.add(exchange.requestURI.path)
            exchange.responseHeaders.add("Location", "/unapproved-redirect")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        var guards = 0
        try {
            gateway.analyze("synthetic", "TEXT", "synthetic note", null, beforeRequest = { guards++ })
            fail("A redirect is not a successful provider response")
        } catch (_: AiGatewayException.ProviderException) { }
        assertEquals(listOf("/v1/ai/analyze"), paths.toList())
        assertEquals(1, guards)
    }

    @Test fun policyChangeAfter401BlocksAuthenticationAndRetry() = runBlocking {
        answer = {
            context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_wifi_only", true).commit()
            401 to "{}"
        }
        setAiTestNetwork(context, unmetered = false)
        try {
            gateway.analyze("synthetic", "TEXT", "synthetic note", null)
            fail("New policy must stop auth renewal and retry")
        } catch (_: AiGatewayException.NetworkPolicyException) { }
        assertEquals(listOf("/v1/ai/analyze"), paths.toList())
    }

    @Test fun networkIsCheckedAfterTheCallerFinishesItsSuspendingGuard() = runBlocking {
        setAiTestNetwork(context, unmetered = false)
        try {
            gateway.analyze("synthetic", "TEXT", "synthetic note", null, beforeRequest = {
                delay(1)
                context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_wifi_only", true).commit()
            })
            fail("A policy change during the caller's database check must block dispatch")
        } catch (_: AiGatewayException.NetworkPolicyException) { }
        assertTrue(paths.isEmpty())
    }

    @Test fun privacyChangeDuringChallengeBlocksSessionAndContentRetry() = runBlocking {
        var eligible = true
        answer = { path ->
            when (path) {
                "/v1/ai/analyze" -> 401 to "{}"
                "/v1/auth/challenge" -> {
                    eligible = false
                    200 to "{\"deviceId\":\"synthetic\",\"nonce\":\"synthetic-nonce\"}"
                }
                else -> 500 to "{}"
            }
        }
        try {
            gateway.analyze("synthetic", "TEXT", "synthetic note", null,
                beforeRequest = { if (!eligible) throw AiGatewayException.RequestBlockedException() })
            fail("Changed eligibility must stop session renewal")
        } catch (_: AiGatewayException.RequestBlockedException) { }
        assertEquals(listOf("/v1/ai/analyze", "/v1/auth/challenge"), paths.toList())
    }

    @Test fun successful401RenewalChecksEveryConcreteDispatch() = runBlocking {
        var analyzeCount = 0
        var guards = 0
        answer = { path ->
            when (path) {
                "/v1/ai/analyze" -> if (analyzeCount++ == 0) 401 to "{}" else 200 to analysis
                "/v1/auth/challenge" -> 200 to "{\"deviceId\":\"synthetic\",\"nonce\":\"synthetic-nonce\"}"
                "/v1/auth/session" -> 200 to "{\"status\":\"OK\",\"deviceId\":\"synthetic\",\"sessionToken\":\"renewed-synthetic-token\"}"
                else -> 500 to "{}"
            }
        }
        val result = gateway.analyze("synthetic", "TEXT", "synthetic note", null, beforeRequest = { guards++ })
        assertEquals("synthetic", result.itemId)
        assertEquals(listOf("/v1/ai/analyze", "/v1/auth/challenge", "/v1/auth/session", "/v1/ai/analyze"), paths.toList())
        assertEquals("Exactly one last-moment guard per actual HTTP dispatch", 4, guards)
    }

    @Test fun searchPlanDoesNotSwallowCancellationOrPolicyBlocks() = runBlocking {
        try {
            gateway.getSearchPlan("synthetic question", emptyList(), beforeRequest = { throw CancellationException("Synthetic cancellation") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        try {
            gateway.getSearchPlan("synthetic question", emptyList(), beforeRequest = { throw AiGatewayException.RequestBlockedException() })
            fail("Privacy rejection must propagate")
        } catch (_: AiGatewayException.RequestBlockedException) { }
        assertTrue(paths.isEmpty())
    }

    @Test fun cancellationStopsOutstandingHttpWithoutPublishingResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        answer = { entered.countDown(); release.await(10, TimeUnit.SECONDS); 200 to analysis }
        var published = false
        val job = launch(Dispatchers.IO) {
            gateway.analyze("synthetic", "TEXT", "synthetic note", null)
            published = true
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertFalse(published)
        } finally { release.countDown(); job.cancel() }
    }

    @Test fun cancellationAlsoStopsAResponseBodyAfterHeadersArrive() = runBlocking {
        val headersSent = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.removeContext("/")
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = analysis.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.flush()
            headersSent.countDown()
            release.await(10, TimeUnit.SECONDS)
            try { exchange.responseBody.use { it.write(bytes) } }
            catch (_: java.io.IOException) { exchange.close() }
        }
        var published = false
        val job = launch(Dispatchers.IO) {
            gateway.analyze("synthetic", "TEXT", "synthetic note", null)
            published = true
        }
        try {
            assertTrue(headersSent.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertFalse(published)
        } finally { release.countDown(); job.cancel() }
    }

    companion object {
        private const val analysis = "{\"itemId\":\"synthetic\",\"description\":\"synthetic description\",\"concepts\":\"synthetic concept\"}"
    }
}
