package com.example.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.PersonalLibraryApp
import com.example.ai.setAiTestNetwork
import com.example.data.ItemEntity
import com.example.data.SearchDocumentEntity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PersonalLibraryApp::class)
class AiAnswerFlowTest {
    private lateinit var app: PersonalLibraryApp
    private lateinit var store: ViewModelStore
    private lateinit var vm: MainViewModel
    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val prefs get() = app.getSharedPreferences("personal_library_settings", 0)
    private var relevantNetwork = true
    private var answerStatus = 200
    private var answerHandler: (JsonObject) -> String = { body ->
        val source = body.getValue("evidence").jsonArray.first { it.jsonObject["pageNumber"]?.toString() == "7" }
        """{"status":"ANSWERED","answer":"Sarah's birthday appears to be August 19.","sourceIds":[${source.jsonObject.getValue("sourceId")}]}"""
    }
    private val release = CountDownLatch(1)

    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().putBoolean("ai_paused", true).commit()
        app.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        setAiTestNetwork(app, unmetered = true)
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = ViewModelStore()
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        val vector = List(768) { if (it == 0) "1.0" else "0.0" }.joinToString(",")
        vm.repository.dao.insertItem(ItemEntity("local-source", "Sarah birthday", "August 19", "DOCUMENT",
            extractedText = "Sarah birthday August 19", localIndexVersion = 5, localIndexCoverage = "COMPLETE"))
        vm.repository.dao.insertSearchDocument(SearchDocumentEntity("doc_local-source_5_0", "local-source", 0,
            pageNumber = 7, textContent = "Sarah's birthday is August 19", embedding = vector))
        vm.repository.dao.insertItem(ItemEntity("local-private", "birthday", "NEVER_SEND_PRIVATE_FACT", "TEXT"))
        prefs.edit().putStringSet("excluded_items", setOf("local-private")).putBoolean("ai_paused", false).commit()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val path = exchange.requestURI.path
            requests += path to body
            val response = when {
                path.endsWith("/search-plan") -> """{"query":"birthday","concepts":${if (relevantNetwork) "[\"birthday\"]" else "[]"},"targetKinds":[],"provenance":"GEMINI"}"""
                path.endsWith("/embed") -> """{"embeddings":[[${if (relevantNetwork) vector else List(768) { "0.0" }.joinToString(",")}]]}"""
                path.endsWith("/answer") -> if (answerStatus == 200) answerHandler(Json.parseToJsonElement(body).jsonObject) else "{}"
                else -> "{}"
            }
            val bytes = response.toByteArray()
            try {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (path.endsWith("/answer")) answerStatus else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) { exchange.close() }
        }
        server.start()
        vm.aiGateway.backendBaseUrl = "http://127.0.0.1:${server.address.port}"
        vm.aiGateway.identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
        await { vm.items.value.size >= 2 }
    }

    @After fun close() {
        release.countDown()
        store.clear()
        server.stop(0)
        Dispatchers.resetMain()
        for (name in listOf("personal_library_settings", "device_identity_prefs", "ai_gateway_prefs")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    private suspend fun ask(question: String = "birthday") {
        vm.setSearchQuery(question)
        await { vm.resultsReady.value }
        vm.triggerAiSearch(question)
        await { !vm.isAiSearching.value }
    }

    @Test fun answerRetainsLocalSourceAndPageWithoutUploadingOriginalsOrExcludedEvidence() = runBlocking {
        ask()
        val answer = requireNotNull(vm.aiAnswer.value)
        assertEquals("ANSWERED", answer.response.status)
        assertTrue(answer.response.answer.contains("August 19"))
        assertEquals("local-source", answer.sources.single().item.item.id)
        assertEquals(7, answer.sources.single().pageNumber)
        assertTrue(vm.localSearchResults.value.any { it.item.id == "local-private" })
        assertTrue(vm.aiSearchResults.value.any { it.item.item.id == "local-source" })
        assertEquals(listOf("/v1/ai/search-plan", "/v1/ai/embed", "/v1/ai/answer"), requests.map { it.first })
        assertTrue(requests.none { it.second.contains("NEVER_SEND") || it.second.contains("mediaParts") })
        assertFalse(requests.last().second.contains("local-source"))
    }

    @Test fun backendWithoutAnswerEndpointKeepsLocalAndRelatedMatchesAndReportsUnavailable() = runBlocking {
        answerStatus = 404
        ask()
        assertNull(vm.aiAnswer.value)
        assertTrue(vm.aiSearchError.value.orEmpty().contains("backend", ignoreCase = true))
        assertTrue(vm.localSearchResults.value.any { it.item.id == "local-private" })
        assertTrue(vm.aiSearchResults.value.any { it.item.item.id == "local-source" })
    }

    @Test fun absentRelevantEvidenceReturnsInsufficientWithoutAnswerPost() = runBlocking {
        relevantNetwork = false
        ask("nonexistentquestionword")
        assertEquals("INSUFFICIENT", requireNotNull(vm.aiAnswer.value).response.status)
        assertTrue(vm.aiAnswer.value!!.sources.isEmpty())
        assertTrue(requests.none { it.first.endsWith("/answer") })
    }

    @Test fun privacyChangeDuringAnswerPreventsPublicationAndPreservesOfflineSearch() = runBlocking {
        val entered = CountDownLatch(1)
        answerHandler = { body ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            """{"status":"ANSWERED","answer":"Synthetic answer","sourceIds":[${body.getValue("evidence").jsonArray.first().jsonObject.getValue("sourceId")}]}"""
        }
        vm.setSearchQuery("birthday")
        await { vm.resultsReady.value }
        vm.triggerAiSearch("birthday")
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            prefs.edit().putStringSet("excluded_items", setOf("local-private", "local-source")).commit()
            await { !vm.isAiSearching.value }
            release.countDown()
            delay(100)
            assertNull(vm.aiAnswer.value)
            assertTrue(vm.aiSearchResults.value.isEmpty())
            assertTrue(vm.localSearchResults.value.any { it.item.id == "local-source" })
        } finally { release.countDown() }
    }

    @Test fun pausingBackgroundAiDoesNotCancelAnExplicitAnswer() = runBlocking {
        val entered = CountDownLatch(1)
        answerHandler = { body ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            """{"status":"ANSWERED","answer":"Synthetic answer","sourceIds":[${body.getValue("evidence").jsonArray.first().jsonObject.getValue("sourceId")}]}"""
        }
        vm.setSearchQuery("birthday")
        await { vm.resultsReady.value }
        vm.triggerAiSearch("birthday")
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            vm.setAiPaused(true)
            release.countDown()
            await { !vm.isAiSearching.value }
            assertEquals("ANSWERED", requireNotNull(vm.aiAnswer.value).response.status)
        } finally { release.countDown() }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(10) }
}
