package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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
class AiAnswerGatewayTest {
    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var gateway: AiGateway
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val evidence = listOf(AnswerEvidence("source-1", "item-1", "Sarah birthday", "August 19"))
    private var reply: (String) -> Pair<Int, String> = { 200 to answered }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().putBoolean("ai_wifi_only", false).commit()
        context.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        gateway = AiGateway(context, DeviceIdentityManager(context, object : DeviceSigner {
            override fun getPublicKeyBase64() = "synthetic-public-key"
            override fun signData(data: ByteArray) = byteArrayOf(1, 2, 3)
        }))
        gateway.identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += path to exchange.requestBody.bufferedReader().use { it.readText() }
            val (code, body) = reply(path)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, 0) // Chunked: client cannot rely on Content-Length.
            try { exchange.responseBody.use { it.write(bytes) } } catch (_: java.io.IOException) { exchange.close() }
        }
        server.start()
        gateway.backendBaseUrl = "http://127.0.0.1:${server.address.port}"
    }
    @After fun close() {
        server.stop(0)
        for (name in listOf("personal_library_settings", "device_identity_prefs", "ai_gateway_prefs")) context.getSharedPreferences(name, 0).edit().clear().commit()
    }

    @Test fun sendsOnlyQuestionAndSelectedEvidenceAndReturnsValidatedSources() = runBlocking {
        val result = gateway.answer("When is Sarah's birthday?", evidence)
        assertEquals("ANSWERED", result.status)
        assertEquals(listOf("source-1"), result.sourceIds)
        assertEquals("/v1/ai/answer", requests.single().first)
        val payload = Json.parseToJsonElement(requests.single().second).jsonObject
        assertEquals(setOf("question", "evidence"), payload.keys)
        assertEquals("August 19", payload.getValue("evidence").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun rejectsInvalidQuestionsAndEvidenceBeforeAnyNetworkDispatch() = runBlocking {
        val invalid = listOf(
            " " to evidence, "q".repeat(2001) to evidence, "question" to emptyList(),
            "question" to evidence.map { it.copy(title = " ") },
            "question" to evidence.map { it.copy(title = "a".repeat(201)) },
            "question" to evidence.map { it.copy(text = " ") },
            "question" to evidence.map { it.copy(text = "a".repeat(1201)) },
            "question" to evidence.map { it.copy(sourceId = "x".repeat(65)) },
            "question" to evidence.map { it.copy(itemRef = " ") },
            "question" to evidence.map { it.copy(pageNumber = 0) },
            "question" to (evidence + evidence.single()),
            "question" to List(4) { evidence.single().copy(sourceId = "s-$it") },
            "question" to List(13) { evidence.single().copy(sourceId = "s-$it", itemRef = "i-$it") },
            "question" to List(11) { evidence.single().copy(sourceId = "s-$it", itemRef = "i-$it", text = "a".repeat(1200)) },
            "question" to List(12) { evidence.single().copy(sourceId = "s-$it", itemRef = "i-$it", text = "中".repeat(1000)) },
            "question" to List(12) { evidence.single().copy(sourceId = "s-$it", itemRef = "i-$it", text = "\u0001".repeat(1000)) }
        )
        for ((question, chunks) in invalid) expectValidation { gateway.answer(question, chunks) }
        assertTrue(requests.isEmpty())
    }

    @Test fun rejectsInventedDuplicateAndStatusInconsistentCitations() = runBlocking {
        val invalid = listOf(
            """{"status":"ANSWERED","answer":"August 19","sourceIds":["invented"]}""",
            """{"status":"ANSWERED","answer":"August 19","sourceIds":["source-1","source-1"]}""",
            """{"status":"ANSWERED","answer":"August 19","sourceIds":[]}""",
            """{"status":"ANSWERED","answer":" ","sourceIds":["source-1"]}""",
            """{"status":"CONFLICTING","answer":"Two dates","sourceIds":["source-1"]}""",
            """{"status":"INSUFFICIENT","answer":"Invented fact","sourceIds":[]}""",
            """{"status":"INSUFFICIENT","answer":"","sourceIds":["source-1"]}""",
            """{"status":"UNKNOWN","answer":"","sourceIds":[]}""",
            """{"status":"ANSWERED","answer":"${"a".repeat(4001)}","sourceIds":["source-1"]}""",
            "{malformed"
        )
        for (body in invalid) { reply = { 200 to body }; expectValidation { gateway.answer("Sarah birthday", evidence) } }
    }

    @Test fun acceptsInsufficientAndConflictingOnlyWithRequiredStructure() = runBlocking {
        reply = { 200 to """{"status":"INSUFFICIENT","answer":"","sourceIds":[]}""" }
        assertEquals("INSUFFICIENT", gateway.answer("Sarah birthday", evidence).status)
        reply = { 200 to """{"status":"CONFLICTING","answer":"Two dates are saved","sourceIds":["source-1","source-2"]}""" }
        assertEquals(listOf("source-1", "source-2"), gateway.answer("Sarah birthday", evidence + evidence.single().copy(sourceId = "source-2", itemRef = "item-2")).sourceIds)
    }

    @Test fun capsChunkedResponseBytesBeforeParsingEvenWhenJsonWouldBeValid() = runBlocking {
        reply = { 200 to answered + " ".repeat(32769) }
        expectValidation { gateway.answer("Sarah birthday", evidence) }
    }

    @Test fun unavailableEndpointHasExplicitSafeFailure() = runBlocking {
        reply = { 404 to "{}" }
        try { gateway.answer("Sarah birthday", evidence); fail("Missing endpoint must be explicit") }
        catch (_: AiGatewayException.AnswerUnavailableException) { }
    }

    @Test fun blockedCallerPreventsAnswerDispatch() = runBlocking {
        try { gateway.answer("Sarah birthday", evidence) { throw AiGatewayException.RequestBlockedException() }; fail("Must block") }
        catch (_: AiGatewayException.RequestBlockedException) { }
        assertTrue(requests.isEmpty())
    }

    @Test fun policyChangeAfter401StopsAuthAndAnswerRetry() = runBlocking {
        setAiTestNetwork(context, unmetered = false)
        reply = { context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_wifi_only", true).commit(); 401 to "{}" }
        try { gateway.answer("Sarah birthday", evidence); fail("Must block renewed dispatch") }
        catch (_: AiGatewayException.NetworkPolicyException) { }
        assertEquals(listOf("/v1/ai/answer"), requests.map { it.first })
    }

    @Test fun successful401RenewalGuardsEveryAnswerAndAuthDispatch() = runBlocking {
        var count = 0
        var guards = 0
        reply = { path -> when(path) {
            "/v1/ai/answer" -> if (count++ == 0) 401 to "{}" else 200 to answered
            "/v1/auth/challenge" -> 200 to """{"deviceId":"synthetic","nonce":"synthetic-nonce"}"""
            else -> 200 to """{"status":"OK","deviceId":"synthetic","sessionToken":"renewed-synthetic-token"}"""
        } }
        assertEquals("ANSWERED", gateway.answer("Sarah birthday", evidence) { guards++ }.status)
        assertEquals(listOf("/v1/ai/answer", "/v1/auth/challenge", "/v1/auth/session", "/v1/ai/answer"), requests.map { it.first })
        assertEquals(4, guards)
    }

    @Test fun cancellationStopsPendingAnswerWithoutPublishing() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        reply = { entered.countDown(); release.await(10, TimeUnit.SECONDS); 200 to answered }
        var published = false
        val job = launch(Dispatchers.IO) { gateway.answer("Sarah birthday", evidence); published = true }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertFalse(published)
        } finally { release.countDown(); job.cancel() }
    }

    private suspend fun expectValidation(block: suspend () -> Unit) {
        try { block(); fail("Invalid answer protocol must fail validation") }
        catch (_: AiGatewayException.ValidationException) { }
    }
    companion object { private const val answered = """{"status":"ANSWERED","answer":"Sarah's birthday is August 19","sourceIds":["source-1"]}""" }
}
