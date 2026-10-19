package com.example.ai

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

sealed class AiGatewayException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class UnpairedException(msg: String = "Device not paired with backend AI") : AiGatewayException(msg)
    class BackendUnavailableException(msg: String = "Backend server unreachable") : AiGatewayException(msg)
    class AuthenticationException(msg: String = "Device authentication failed") : AiGatewayException(msg)
    class UnconfiguredException(msg: String = "Gemini API is not configured on backend") : AiGatewayException(msg)
    class ProviderException(msg: String = "Gemini provider error") : AiGatewayException(msg)
    class ValidationException(msg: String = "Response validation failed") : AiGatewayException(msg)
    class RateLimitedException(msg: String = "Request rate limit exceeded") : AiGatewayException(msg)
    class NetworkPolicyException(msg: String = "AI needs an unmetered connection while Wi-Fi Only is enabled.") : AiGatewayException(msg)
    class RequestBlockedException(msg: String = "AI request is no longer eligible. Try again with the current settings.") : AiGatewayException(msg)
    class AnswerUnavailableException(msg: String = "AI answers are not available on this backend yet.") : AiGatewayException(msg)
}

@Serializable
data class ChallengeResponse(val deviceId: String, val nonce: String, val expiresSec: Int = 300)

@Serializable
data class EnrollRequest(
    val deviceId: String,
    val appVersion: String,
    val challengeNonce: String,
    val enrollmentSecret: String? = null,
    val publicKey: String,
    val signature: String
)

@Serializable
data class EnrollResponse(
    val status: String,
    val deviceId: String,
    val sessionToken: String,
    val deviceCredential: String,
    val expiresInSeconds: Long = 1800
)

@Serializable
data class SessionRenewRequest(
    val deviceId: String,
    val deviceCredential: String,
    val challengeNonce: String,
    val publicKey: String,
    val signature: String
)

@Serializable
data class SessionRenewResponse(
    val status: String,
    val deviceId: String,
    val sessionToken: String,
    val expiresInSeconds: Long = 1800
)

@Serializable
data class MediaPart(
    val mimeType: String,
    val dataBase64: String
)

@Serializable
data class AnalyzeRequest(
    val itemId: String,
    val kind: String,
    val textContent: String? = null,
    val originalFileName: String? = null,
    val mediaParts: List<MediaPart> = emptyList()
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
    val dateRange: DateRangePlan = DateRangePlan(),
    val provenance: String = "LOCAL"
)

class AiGateway(
    private val context: Context,
    val identityManager: DeviceIdentityManager = DeviceIdentityManager(context)
) {
    private val prefs = context.getSharedPreferences("ai_gateway_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var backendBaseUrl: String
        get() = prefs.getString("backend_url", BuildConfig.DEFAULT_BACKEND_URL) ?: BuildConfig.DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString("backend_url", value).apply()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Each HTTP request must pass the current caller/network guard. Hidden
        // redirects or connection retries cannot reuse an earlier privacy check.
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private suspend fun <T> send(
        request: Request,
        beforeRequest: suspend () -> Unit,
        readResponse: (Response) -> T
    ): T {
        currentCoroutineContext().ensureActive()
        beforeRequest()
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val call = okHttpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            if (continuation.isActive) call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    // Keep cancellation attached until the body is consumed,
                    // rather than releasing the Call as soon as headers arrive.
                    try {
                        val value = response.use(readResponse)
                        continuation.resumeWith(Result.success(value))
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    suspend fun getChallenge(beforeRequest: suspend () -> Unit = {}): String = withContext(Dispatchers.IO) {
        val deviceId = identityManager.deviceId
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/challenge?deviceId=$deviceId")
            .get()
            .build()

        try {
            send(request, beforeRequest) { response ->
                if (!response.isSuccessful) {
                    throw AiGatewayException.BackendUnavailableException("Challenge request failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw AiGatewayException.BackendUnavailableException("Empty challenge response")
                val res = json.decodeFromString<ChallengeResponse>(body)
                res.nonce
            }
        } catch (e: CancellationException) { throw e
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
        val appVersion = "1.0.0"
        val signature = identityManager.signEnrollment(nonce, appVersion, pubKey)

        val req = EnrollRequest(
            deviceId = deviceId,
            appVersion = appVersion,
            challengeNonce = nonce,
            enrollmentSecret = enrollmentSecret,
            publicKey = pubKey,
            signature = signature
        )
        val payload = json.encodeToString(EnrollRequest.serializer(), req)
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/enroll")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            send(request, {}) { response ->
                if (response.code == 401) {
                    throw AiGatewayException.AuthenticationException("Invalid enrollment secret or signature")
                }
                if (!response.isSuccessful) {
                    throw AiGatewayException.BackendUnavailableException("Enrollment failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return@send false
                val res = json.decodeFromString<EnrollResponse>(body)
                identityManager.savePairing(res.deviceCredential, res.sessionToken, res.expiresInSeconds)
                true
            }
        } catch (e: CancellationException) { throw e
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Enrollment error: ${e.message}")
        }
    }

    suspend fun renewSession(beforeRequest: suspend () -> Unit = {}): String = withContext(Dispatchers.IO) {
        val cred = identityManager.deviceCredential ?: throw AiGatewayException.UnpairedException()
        val nonce = getChallenge(beforeRequest)
        val deviceId = identityManager.deviceId
        val pubKey = identityManager.getPublicKeyBase64()
        val signature = identityManager.signSessionRenew(nonce, cred)

        val req = SessionRenewRequest(
            deviceId = deviceId,
            deviceCredential = cred,
            challengeNonce = nonce,
            publicKey = pubKey,
            signature = signature
        )
        val payload = json.encodeToString(SessionRenewRequest.serializer(), req)
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/auth/session")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            send(request, beforeRequest) { response ->
                if (response.code == 401) {
                    identityManager.clearPairing()
                    throw AiGatewayException.AuthenticationException("Session renewal rejected: credential invalid or revoked. Please re-pair device.")
                }
                if (!response.isSuccessful) {
                    throw AiGatewayException.BackendUnavailableException("Session renewal failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw AiGatewayException.BackendUnavailableException("Empty session response")
                val res = json.decodeFromString<SessionRenewResponse>(body)
                identityManager.saveSessionToken(res.sessionToken, res.expiresInSeconds)
                res.sessionToken
            }
        } catch (e: CancellationException) { throw e
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Session renewal error: ${e.message}")
        }
    }

    suspend fun ensureSessionToken(beforeRequest: suspend () -> Unit = {}): String = withContext(Dispatchers.IO) {
        if (identityManager.isSessionValid) {
            return@withContext identityManager.sessionToken!!
        }
        renewSession(beforeRequest)
    }

    /**
     * Common authenticated-request helper:
     * 1. obtain cached valid session
     * 2. make request
     * 3. if response is 401:
     *    - clear session token only
     *    - perform signed session renewal
     *    - retry original request exactly once
     * 4. if renewal fails 401: clear pairing and throw AuthenticationException
     */
    private suspend fun <T> executeWithAuth(
        beforeRequest: suspend () -> Unit,
        action: suspend (token: String, guard: suspend () -> Unit) -> T
    ): T = withContext(Dispatchers.IO) {
        val guard: suspend () -> Unit = {
            beforeRequest()
            // A caller may suspend while checking Room. Re-read connectivity
            // afterwards, immediately before dispatch rather than before that wait.
            AiNetworkPolicy(context).requireAllowed()
        }
        var token = ensureSessionToken(guard)
        try {
            action(token, guard)
        } catch (e: AiGatewayException.AuthenticationException) {
            identityManager.clearSession()
            token = renewSession(guard)
            try {
                action(token, guard)
            } catch (retryEx: AiGatewayException.AuthenticationException) {
                identityManager.clearPairing()
                throw AiGatewayException.AuthenticationException("Authentication failed after renewal. Re-pair required.")
            }
        }
    }

    suspend fun analyze(
        itemId: String,
        kind: String,
        textContent: String?,
        originalFileName: String?,
        mediaParts: List<MediaPart> = emptyList(),
        beforeRequest: suspend () -> Unit = {}
    ): AnalyzeResponse = executeWithAuth(beforeRequest) { token, guard ->
        val payload = json.encodeToString(
            AnalyzeRequest.serializer(),
            AnalyzeRequest(itemId, kind, textContent, originalFileName, mediaParts)
        )
        val request = Request.Builder()
            .url("$backendBaseUrl/v1/ai/analyze")
            .addHeader("X-Device-Id", identityManager.deviceId)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            send(request, guard) { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: throw AiGatewayException.ValidationException("Empty analyze body")
                        json.decodeFromString<AnalyzeResponse>(body)
                    }
                    401 -> throw AiGatewayException.AuthenticationException("Unauthorized AI analyze request")
                    429 -> throw AiGatewayException.RateLimitedException("Rate limit reached on AI analysis")
                    503 -> throw AiGatewayException.UnconfiguredException("Gemini API not configured on backend")
                    else -> throw AiGatewayException.ProviderException("AI analyze failed with HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException.BackendUnavailableException("Analyze connection error: ${e.message}")
        }
    }

    suspend fun embed(texts: List<String>, beforeRequest: suspend () -> Unit = {}): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        executeWithAuth(beforeRequest) { token, guard ->
            val payload = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(texts))
            val request = Request.Builder()
                .url("$backendBaseUrl/v1/ai/embed")
                .addHeader("X-Device-Id", identityManager.deviceId)
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                send(request, guard) { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string() ?: throw AiGatewayException.ValidationException("Empty embed body")
                            val res = json.decodeFromString<EmbedResponse>(body)

                            if (res.dimension != 768 || res.modelVersion != "gemini-embedding-2") {
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
                        429 -> throw AiGatewayException.RateLimitedException("Rate limit reached on embeddings")
                        503 -> throw AiGatewayException.UnconfiguredException("Gemini API not configured on backend")
                        else -> throw AiGatewayException.ProviderException("Embed request failed with HTTP ${response.code}")
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: AiGatewayException) {
                throw e
            } catch (e: Exception) {
                throw AiGatewayException.BackendUnavailableException("Embed connection error: ${e.message}")
            }
        }
    }

    suspend fun answer(
        question: String,
        evidence: List<AnswerEvidence>,
        beforeRequest: suspend () -> Unit = {}
    ): AnswerResponse = withContext(Dispatchers.IO) {
        // Validate before session renewal, so invalid input cannot cause any dispatch.
        val selectedEvidence = evidence.toList()
        val payload = AnswerProtocol.encode(AnswerRequest(question, selectedEvidence))
        executeWithAuth(beforeRequest) { token, guard ->
            val request = Request.Builder()
                .url("$backendBaseUrl/v1/ai/answer")
                .addHeader("X-Device-Id", identityManager.deviceId)
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                send(request, guard) { response ->
                    when (response.code) {
                        200 -> AnswerProtocol.decode(readAnswerBody(response), selectedEvidence)
                        400, 413 -> throw AiGatewayException.ValidationException("Answer request rejected")
                        401 -> throw AiGatewayException.AuthenticationException("Unauthorized AI answer request")
                        404 -> throw AiGatewayException.AnswerUnavailableException()
                        429 -> throw AiGatewayException.RateLimitedException()
                        503 -> throw AiGatewayException.UnconfiguredException()
                        else -> throw AiGatewayException.ProviderException("AI answer request failed")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AiGatewayException) { throw failure
            } catch (_: Exception) {
                throw AiGatewayException.BackendUnavailableException("AI answer connection failed")
            }
        }
    }

    private fun readAnswerBody(response: Response): String {
        val body = response.body ?: throw AiGatewayException.ValidationException("Empty answer response")
        val limit = AnswerProtocol.MAX_RESPONSE_BYTES
        if (body.contentLength() > limit) throw AiGatewayException.ValidationException("Oversized answer response")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        body.byteStream().use { input ->
            while (true) {
                // One extra byte detects an oversized chunked/decompressed response,
                // without buffering an unrestricted body before validation.
                val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (read == -1) break
                output.write(buffer, 0, read)
                if (output.size() > limit) throw AiGatewayException.ValidationException("Oversized answer response")
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: Exception) { throw AiGatewayException.ValidationException("Invalid answer encoding") }
    }

    suspend fun getSearchPlan(query: String, knownCollections: List<String>, beforeRequest: suspend () -> Unit = {}): SearchPlanResponse = withContext(Dispatchers.IO) {
        val planFromNetwork = try {
            executeWithAuth(beforeRequest) { token, guard ->
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

                send(request, guard) { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            json.decodeFromString<SearchPlanResponse>(body)
                        } else null
                    } else if (response.code == 401) {
                        throw AiGatewayException.AuthenticationException("Unauthorized search-plan")
                    } else null
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (blocked: AiGatewayException.NetworkPolicyException) { throw blocked
        } catch (blocked: AiGatewayException.RequestBlockedException) { throw blocked
        } catch (_: Exception) {
            null
        }

        planFromNetwork ?: parseLocalSearchPlan(query)
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
