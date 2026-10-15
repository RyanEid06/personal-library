package com.example.ai

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class AiGatewayException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class UnpairedException(msg: String = "Device not paired with backend AI") : AiGatewayException(msg)
    class BackendUnavailableException(msg: String = "Backend server unreachable") : AiGatewayException(msg)
    class AuthenticationException(msg: String = "Device authentication failed") : AiGatewayException(msg)
    class UnconfiguredException(msg: String = "Gemini API is not configured on backend") : AiGatewayException(msg)
    class ProviderException(msg: String = "Gemini provider error") : AiGatewayException(msg)
    class ValidationException(msg: String = "Response validation failed") : AiGatewayException(msg)
}

@Serializable
data class ChallengeResponse(val deviceId: String, val nonce: String, val expiresSec: Int = 300)

@Serializable
data class EnrollRequest(
    val deviceId: String,
    val appVersion: String,
    val challengeNonce: String,
    val enrollmentSecret: String? = null,
    val publicKey: String? = null
)

@Serializable
data class EnrollResponse(
    val status: String,
    val deviceId: String,
    val sessionToken: String,
    val deviceCredential: String,
    val expiresInSeconds: Long = 86400
)

@Serializable
data class SessionRenewRequest(
    val deviceId: String,
    val deviceCredential: String,
    val challengeNonce: String
)

@Serializable
data class SessionRenewResponse(
    val status: String,
    val deviceId: String,
    val sessionToken: String,
    val expiresInSeconds: Long = 86400
)

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
    val modelVersion: String = "gemini-embedding-2",
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
    val dateRange: DateRangePlan = DateRangePlan()
)

class AiGateway(private val context: Context) {

    val identityManager = DeviceIdentityManager(context)
    private val prefs = context.getSharedPreferences("ai_gateway_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var backendBaseUrl: String
        get() = prefs.getString("backend_url", BuildConfig.DEFAULT_BACKEND_URL) ?: BuildConfig.DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString("backend_url", value).apply()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getChallenge(): String = withContext(Dispatchers.IO) {
        val deviceId = identityManager.deviceId
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/challenge?deviceId=$deviceId")
            .get()
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AiGatewayException.BackendUnavailableException("Challenge request failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw AiGatewayException.BackendUnavailableException("Empty challenge response")
                val res = json.decodeFromString<ChallengeResponse>(body)
                res.nonce
            }
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Failed to reach backend challenge endpoint: ${e.message}")
        }
    }

    suspend fun enrollWithSecret(enrollmentSecret: String): Boolean = withContext(Dispatchers.IO) {
        val nonce = getChallenge()
        val deviceId = identityManager.deviceId
        val pubKey = identityManager.getPublicKeyBase64()

        val req = EnrollRequest(
            deviceId = deviceId,
            appVersion = "1.0.0",
            challengeNonce = nonce,
            enrollmentSecret = enrollmentSecret,
            publicKey = pubKey
        )
        val payload = json.encodeToString(EnrollRequest.serializer(), req)
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/enroll")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    throw AiGatewayException.AuthenticationException("Invalid enrollment secret")
                }
                if (!response.isSuccessful) {
                    throw AiGatewayException.BackendUnavailableException("Enrollment failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext false
                val res = json.decodeFromString<EnrollResponse>(body)
                identityManager.savePairing(res.deviceCredential, res.sessionToken, res.expiresInSeconds)
                true
            }
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Enrollment error: ${e.message}")
        }
    }

    suspend fun ensureSessionToken(): String = withContext(Dispatchers.IO) {
        if (identityManager.isSessionValid) {
            return@withContext identityManager.sessionToken!!
        }

        val cred = identityManager.deviceCredential
        if (cred == null) {
            throw AiGatewayException.UnpairedException()
        }

        val nonce = getChallenge()
        val deviceId = identityManager.deviceId
        val req = SessionRenewRequest(deviceId, cred, nonce)
        val payload = json.encodeToString(SessionRenewRequest.serializer(), req)
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/session")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    identityManager.clearPairing()
                    throw AiGatewayException.AuthenticationException("Session renewal rejected: credential invalid or revoked")
                }
                if (!response.isSuccessful) {
                    throw AiGatewayException.BackendUnavailableException("Session renewal failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw AiGatewayException.BackendUnavailableException("Empty session response")
                val res = json.decodeFromString<SessionRenewResponse>(body)
                identityManager.saveSessionToken(res.sessionToken, res.expiresInSeconds)
                res.sessionToken
            }
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Session renewal error: ${e.message}")
        }
    }

    suspend fun analyze(
        itemId: String,
        kind: String,
        textContent: String?,
        originalFileName: String?
    ): AnalyzeResponse = withContext(Dispatchers.IO) {
        val token = ensureSessionToken()
        val payload = json.encodeToString(
            AnalyzeRequest.serializer(),
            AnalyzeRequest(itemId, kind, textContent, originalFileName)
        )
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/ai/analyze")
            .addHeader("X-Device-Id", identityManager.deviceId)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: throw AiGatewayException.ValidationException("Empty analyze body")
                        val res = json.decodeFromString<AnalyzeResponse>(body)
                        res
                    }
                    401 -> throw AiGatewayException.AuthenticationException("Unauthorized AI analyze request")
                    503 -> throw AiGatewayException.UnconfiguredException("Gemini API not configured on backend")
                    else -> throw AiGatewayException.ProviderException("AI analyze failed with HTTP ${response.code}")
                }
            }
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Analyze connection error: ${e.message}")
        }
    }

    suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val token = ensureSessionToken()
        val payload = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(texts))
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/ai/embed")
            .addHeader("X-Device-Id", identityManager.deviceId)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: throw AiGatewayException.ValidationException("Empty embed body")
                        val res = json.decodeFromString<EmbedResponse>(body)

                        if (res.dimension != 768) {
                            throw AiGatewayException.ValidationException("Expected 768 dimensions but received ${res.dimension}")
                        }
                        if (res.embeddings.size != texts.size) {
                            throw AiGatewayException.ValidationException("Embedding count mismatch: expected ${texts.size}, got ${res.embeddings.size}")
                        }

                        res.embeddings.map { vecList ->
                            if (vecList.size != 768) {
                                throw AiGatewayException.ValidationException("Vector length ${vecList.size} != 768")
                            }
                            for (v in vecList) {
                                if (v.isNaN() || v.isInfinite()) {
                                    throw AiGatewayException.ValidationException("Non-finite float value in vector")
                                }
                            }
                            vecList.toFloatArray()
                        }
                    }
                    401 -> throw AiGatewayException.AuthenticationException("Unauthorized embed request")
                    503 -> throw AiGatewayException.UnconfiguredException("Gemini API not configured on backend")
                    else -> throw AiGatewayException.ProviderException("Embed request failed with HTTP ${response.code}")
                }
            }
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Embed connection error: ${e.message}")
        }
    }

    suspend fun getSearchPlan(query: String, knownCollections: List<String>): SearchPlanResponse = withContext(Dispatchers.IO) {
        val token = try {
            ensureSessionToken()
        } catch (_: Exception) {
            null
        }

        if (token != null) {
            try {
                val payload = json.encodeToString(
                    SearchPlanRequest.serializer(),
                    SearchPlanRequest(query, knownCollections, java.time.Instant.now().toString())
                )
                val request = Request.Builder()
                    .url("$backendBaseUrl/v1/ai/search-plan")
                    .addHeader("X-Device-Id", identityManager.deviceId)
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
                // If backend search-plan fails, fall through to deterministic local query parser
            }
        }

        // Local query parsing without network requirement
        parseLocalSearchPlan(query)
    }

    private fun parseLocalSearchPlan(query: String): SearchPlanResponse {
        val lower = query.lowercase()
        val targetKinds = mutableListOf<String>()
        val concepts = mutableListOf<String>()

        if (lower.contains("photo") || lower.contains("screenshot") || lower.contains("image")) targetKinds.add("PHOTO")
        if (lower.contains("sticker")) targetKinds.add("STICKER")
        if (lower.contains("link") || lower.contains("url")) targetKinds.add("LINK")
        if (lower.contains("audio") || lower.contains("voice")) targetKinds.add("AUDIO")
        if (lower.contains("document") || lower.contains("pdf")) targetKinds.add("DOCUMENT")
        if (lower.contains("video")) targetKinds.add("VIDEO")

        if (lower.contains("grade") || lower.contains("exam")) concepts.addAll(listOf("grades", "exam"))
        if (lower.contains("university") || lower.contains("college")) concepts.add("university")

        val tokens = query.split("\\s+".toRegex()).filter { it.length > 2 }
        for (tok in tokens) {
            if (!concepts.contains(tok.lowercase())) concepts.add(tok.lowercase())
        }

        return SearchPlanResponse(
            query = query,
            concepts = concepts,
            targetKinds = targetKinds,
            dateRange = DateRangePlan(null, null)
        )
    }
}
