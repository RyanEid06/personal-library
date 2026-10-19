package com.example.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.PersonalLibraryApp
import com.example.ai.setAiTestNetwork
import com.example.data.CollectionEntity
import com.example.data.ItemCollectionCrossRef
import com.example.data.ItemEntity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PersonalLibraryApp::class)
class InteractiveAiPayloadTest {
    @Test fun viewModelUsesQueryOnlyForPlanningAndBoundedPublicEvidenceForAnswer() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<PersonalLibraryApp>()
        val prefs = application.getSharedPreferences("personal_library_settings", 0)
        prefs.edit().clear().putBoolean("ai_paused", true).commit()
        application.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        setAiTestNetwork(application, unmetered = true)
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val payload = exchange.requestBody.bufferedReader().use { it.readText() }
            val path = exchange.requestURI.path
            requests += path to payload
            val response = when {
                path.endsWith("/search-plan") -> """{"query":"birthday","concepts":["birthday"],"targetKinds":[],"provenance":"GEMINI"}"""
                path.endsWith("/embed") -> """{"embeddings":[[${List(768) { "0.1" }.joinToString(",")}]]}"""
                path.endsWith("/answer") -> """{"status":"INSUFFICIENT","answer":"","sourceIds":[]}"""
                else -> "{}"
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[MainViewModel::class.java]
            val dao = viewModel.repository.dao
            dao.insertCollection(CollectionEntity("payload-private", "NEVER_SEND_PRIVATE_COLLECTION"))
            dao.insertCollection(CollectionEntity("payload-public", "UNNEEDED_PUBLIC_COLLECTION"))
            dao.insertItem(ItemEntity("payload-public", "birthday", "Synthetic public birthday note on August 19", "TEXT", localIndexCoverage = "COMPLETE"))
            dao.insertItem(ItemEntity("payload-private", "birthday", "NEVER_SEND_PRIVATE_BODY", "TEXT", localIndexCoverage = "COMPLETE"))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("payload-public", "payload-public"))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("payload-private", "payload-private"))
            prefs.edit().putStringSet("excluded_collections", setOf("payload-private")).putBoolean("ai_paused", false).commit()
            viewModel.aiGateway.backendBaseUrl = "http://127.0.0.1:${server.address.port}"
            viewModel.aiGateway.identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
            await { viewModel.items.value.any { it.item.id == "payload-private" } }
            viewModel.setSearchQuery("birthday")
            await { viewModel.resultsReady.value && viewModel.localSearchResults.value.any { it.item.id == "payload-private" } }
            viewModel.triggerAiSearch("birthday")
            await { !viewModel.isAiSearching.value }
            assertNull(viewModel.aiSearchError.value)
            assertEquals(listOf("/v1/ai/search-plan", "/v1/ai/embed", "/v1/ai/answer"), requests.map { it.first })
            val plan = Json.parseToJsonElement(requests.first().second).jsonObject
            assertEquals("birthday", plan.getValue("query").jsonPrimitive.content)
            assertTrue(plan.getValue("knownCollections").jsonArray.isEmpty())
            val embed = Json.parseToJsonElement(requests[1].second).jsonObject
            assertEquals(listOf("birthday"), embed.getValue("texts").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(requests.none { (_, body) -> body.contains("NEVER_SEND") || body.contains("UNNEEDED_PUBLIC_COLLECTION") })
            val evidence = Json.parseToJsonElement(requests.last().second).jsonObject.getValue("evidence").jsonArray
            assertTrue(evidence.isNotEmpty())
            assertTrue(evidence.all { it.jsonObject.getValue("text").jsonPrimitive.content.length <= 1200 })
            assertTrue(evidence.any { it.jsonObject.getValue("text").jsonPrimitive.content.contains("August 19") })
            assertFalse("Stable library IDs remain local", evidence.toString().contains("payload-public"))
            assertTrue(viewModel.aiSearchResults.value.any { it.item.item.id == "payload-public" })
            assertTrue(viewModel.aiSearchResults.value.none { it.item.item.id == "payload-private" })
            assertTrue(viewModel.localSearchResults.value.any { it.item.id == "payload-private" })
        } finally {
            store.clear()
            server.stop(0)
            Dispatchers.resetMain()
            for (name in listOf("personal_library_settings", "device_identity_prefs", "ai_gateway_prefs")) {
                application.getSharedPreferences(name, 0).edit().clear().commit()
            }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(10)
    }
}
