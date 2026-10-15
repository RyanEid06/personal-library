package com.example.ai

import android.content.Context
import com.personallibrary.core.model.SemanticVectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class EnrollRequest(val deviceId: String, val appVersion: String)

@Serializable
data class EnrollResponse(val status: String, val deviceId: String, val sessionToken: String)

@Serializable
data class AnalyzeRequest(
    val itemId: String,
    val kind: String,
    val textContent: String? = null,
    val originalFileName: String? = null
)

@Serializable
data class AnalyzeResponse(
    val itemId: String,
    val description: String,
    val concepts: String,
    val detectedDates: List<String> = emptyList(),
    val coverage: String = "COMPLETE"
)

@Serializable
data class EmbedRequest(val texts: List<String>)

@Serializable
data class EmbedResponse(
    val dimension: Int = 768,
    val modelVersion: String = "gemini-embedding-2-preview",
    val embeddings: List<List<Float>>
)

@Serializable
data class SearchPlanRequest(
    val query: String,
    val knownCollections: List<String>,
    val nowIso: String
)

@Serializable
data class DateRangePlan(
    val startIso: String? = null,
    val endIso: String? = null
)

@Serializable
data class SearchPlanResponse(
    val query: String,
    val concepts: List<String>,
    val targetKinds: List<String>,
    val dateRange: DateRangePlan
)

class AiGateway(private val context: Context) {

    private val prefs = context.getSharedPreferences("ai_gateway_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Backend URL (default Android emulator localhost alias or configurable)
    var backendBaseUrl: String
        get() = prefs.getString("backend_url", "http://10.0.2.2:8081") ?: "http://10.0.2.2:8081"
        set(value) = prefs.edit().putString("backend_url", value).apply()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "device_${UUID.randomUUID().toString().take(12)}"
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getSessionToken(): String? {
        return prefs.getString("session_token", null)
    }

    private suspend fun ensureEnrolled(): String? = withContext(Dispatchers.IO) {
        val existingToken = getSessionToken()
        if (existingToken != null) return@withContext existingToken

        val deviceId = getDeviceId()
        val payload = json.encodeToString(EnrollRequest.serializer(), EnrollRequest(deviceId, "1.0.0"))
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/enroll")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val res = json.decodeFromString<EnrollResponse>(body)
                    prefs.edit().putString("session_token", res.sessionToken).apply()
                    return@withContext res.sessionToken
                }
            }
        } catch (_: Exception) {
            // Backend offline or unreachable
        }
        null
    }

    suspend fun analyze(itemId: String, kind: String, textContent: String?, originalFileName: String?): AnalyzeResponse = withContext(Dispatchers.IO) {
        val token = ensureEnrolled()
        if (token != null) {
            try {
                val payload = json.encodeToString(
                    AnalyzeRequest.serializer(),
                    AnalyzeRequest(itemId, kind, textContent, originalFileName)
                )
                val request = Request.Builder()
                    .url("$backendBaseUrl/v1/ai/analyze")
                    .addHeader("X-Device-Id", getDeviceId())
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            return@withContext json.decodeFromString<AnalyzeResponse>(body)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall through to local fallback
            }
        }

        // Local semantic extraction fallback
        val cleanText = textContent?.trim() ?: ""
        val desc = when {
            cleanText.contains("grade", true) || cleanText.contains("/100") -> "Academic grade record or exam note"
            cleanText.startsWith("http") -> "Web link bookmark: ${originalFileName ?: cleanText.take(40)}"
            cleanText.isNotBlank() -> cleanText.lines().first().take(80)
            else -> "Saved ${kind.lowercase()} item"
        }
        val concepts = listOf(kind.lowercase(), "personal", "archive").joinToString(", ")
        AnalyzeResponse(itemId, desc, concepts, emptyList(), "LOCAL_FALLBACK")
    }

    suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        val token = ensureEnrolled()
        if (token != null) {
            try {
                val payload = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(texts))
                val request = Request.Builder()
                    .url("$backendBaseUrl/v1/ai/embed")
                    .addHeader("X-Device-Id", getDeviceId())
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val res = json.decodeFromString<EmbedResponse>(body)
                            return@withContext res.embeddings.map { it.toFloatArray() }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall through to local deterministic embedding
            }
        }

        // Local deterministic 768-dim normalized embedding
        texts.map { text ->
            val vec = FloatArray(SemanticVectorMath.DIMENSION)
            val bytes = text.toByteArray(Charsets.UTF_8)
            for (i in 0 until SemanticVectorMath.DIMENSION) {
                val b = if (bytes.isNotEmpty()) bytes[i % bytes.size].toInt() else i
                vec[i] = ((b / 255.0f) - 0.5f) * kotlin.math.sin((i + 1).toDouble()).toFloat()
            }
            SemanticVectorMath.normalize(vec)
        }
    }

    suspend fun getSearchPlan(query: String, knownCollections: List<String>): SearchPlanResponse = withContext(Dispatchers.IO) {
        val token = ensureEnrolled()
        if (token != null) {
            try {
                val payload = json.encodeToString(
                    SearchPlanRequest.serializer(),
                    SearchPlanRequest(query, knownCollections, java.time.Instant.now().toString())
                )
                val request = Request.Builder()
                    .url("$backendBaseUrl/v1/ai/search-plan")
                    .addHeader("X-Device-Id", getDeviceId())
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            return@withContext json.decodeFromString<SearchPlanResponse>(body)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall through to local parsing
            }
        }

        // Safe local search plan parser
        val lower = query.lowercase()
        val targetKinds = mutableListOf<String>()
        val concepts = mutableListOf<String>()

        if (lower.contains("photo") || lower.contains("screenshot")) targetKinds.add("PHOTO")
        if (lower.contains("sticker")) targetKinds.add("STICKER")
        if (lower.contains("link") || lower.contains("url")) targetKinds.add("LINK")
        if (lower.contains("audio") || lower.contains("voice")) targetKinds.add("AUDIO")
        if (lower.contains("grade") || lower.contains("exam")) concepts.addAll(listOf("grades", "exam"))
        if (lower.contains("university") || lower.contains("college")) concepts.add("university")

        val tokens = query.split("\\s+".toRegex()).filter { it.length > 2 }
        for (tok in tokens) {
            if (!concepts.contains(tok.lowercase())) concepts.add(tok.lowercase())
        }

        SearchPlanResponse(
            query = query,
            concepts = concepts,
            targetKinds = targetKinds,
            dateRange = DateRangePlan(null, null)
        )
    }
}
