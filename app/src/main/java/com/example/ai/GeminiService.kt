package com.example.ai

import com.example.BuildConfig
import com.example.model.ItemKind
import com.example.model.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null
)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val temperature: Float? = null
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList()
)

@Serializable
data class Candidate(
    val content: Content? = null
)

interface GeminiApi {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

data class AiEnrichmentResult(
    val description: String,
    val concepts: String
)

data class SearchResultItem(
    val item: LibraryItem,
    val matchExplanation: String,
    val relevanceScore: Float
)

class GeminiService {

    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: GeminiApi? by lazy {
        try {
            Retrofit.Builder()
                .baseUrl("https://generativelanguage.googleapis.com/")
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(GeminiApi::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun getApiKey(): String {
        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            (field.get(null) as? String)?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun analyzeItem(title: String, content: String, kind: ItemKind): AiEnrichmentResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNotBlank() && api != null) {
            try {
                val prompt = """
                    Analyze this saved personal library item. Return a concise 1-sentence description and 3-5 keywords or concepts.
                    Type: ${kind.displayName}
                    Title: $title
                    Content: $content
                    
                    Format: Description: <sentence> | Concepts: <comma separated keywords>
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                    generationConfig = GenerationConfig(temperature = 0.2f)
                )

                val response = api!!.generateContent(apiKey, request)
                val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                if (text.isNotBlank()) {
                    val desc = text.substringAfter("Description:").substringBefore("|").trim()
                    val concepts = text.substringAfter("Concepts:").trim()
                    return@withContext AiEnrichmentResult(
                        description = desc.ifBlank { "Personal ${kind.displayName.lowercase()} item" },
                        concepts = concepts.ifBlank { kind.name.lowercase() }
                    )
                }
            } catch (e: Exception) {
                // Fall back to local analysis
            }
        }

        // Local heuristics analysis
        val cleanContent = content.take(120).replace("\n", " ")
        val localDesc = when (kind) {
            ItemKind.LINK -> "Web link bookmark: ${title.ifBlank { cleanContent }}"
            ItemKind.PHOTO -> "Visual media image"
            ItemKind.STICKER -> "Saved sticker/reaction"
            ItemKind.AUDIO -> "Voice note / audio clip"
            ItemKind.VIDEO -> "Video recording"
            ItemKind.DOCUMENT -> "Document file: $title"
            ItemKind.TEXT -> if (content.contains("grade", true)) "Academic record / grade note" else "Personal note: $cleanContent"
            ItemKind.FILE -> "Archived file: $title"
        }
        val localConcepts = listOf(kind.name.lowercase(), "personal", "archive").joinToString(", ")

        AiEnrichmentResult(description = localDesc, concepts = localConcepts)
    }

    suspend fun assistedSearch(query: String, items: List<LibraryItem>): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val results = mutableListOf<SearchResultItem>()
        val queryTokens = cleanQuery.split("\\s+".toRegex()).filter { it.length > 1 }

        for (item in items) {
            val titleLower = item.title.lowercase()
            val contentLower = item.content.lowercase()
            val aiDescLower = (item.aiDescription ?: "").lowercase()
            val aiConceptsLower = (item.aiConcepts ?: "").lowercase()
            val fileNameLower = (item.originalFileName ?: "").lowercase()

            var score = 0f
            val matchReasons = mutableListOf<String>()

            // Exact query match
            if (titleLower.contains(cleanQuery)) {
                score += 10f
                matchReasons.add("Title matches query")
            }
            if (contentLower.contains(cleanQuery)) {
                score += 8f
                matchReasons.add("Matched in note or extracted text")
            }
            if (fileNameLower.contains(cleanQuery)) {
                score += 7f
                matchReasons.add("Matched filename '${item.originalFileName}'")
            }
            if (aiDescLower.contains(cleanQuery)) {
                score += 6f
                matchReasons.add("AI description matches: '${item.aiDescription}'")
            }
            if (aiConceptsLower.contains(cleanQuery)) {
                score += 5f
                matchReasons.add("Matched concept tags")
            }

            // Token level matching
            for (token in queryTokens) {
                if (titleLower.contains(token)) score += 2f
                if (contentLower.contains(token)) score += 1.5f
                if (aiDescLower.contains(token)) score += 1.5f
                if (aiConceptsLower.contains(token)) score += 1f
            }

            // Natural language date or category hints (e.g. "grades", "photo", "university", "link")
            if (cleanQuery.contains("grade") && (contentLower.contains("grade") || titleLower.contains("grade"))) {
                score += 4f
                matchReasons.add("Identified academic grade reference")
            }
            if (cleanQuery.contains("photo") && item.kind == ItemKind.PHOTO) {
                score += 3f
                matchReasons.add("Filtered by photo type")
            }
            if (cleanQuery.contains("link") && item.kind == ItemKind.LINK) {
                score += 3f
                matchReasons.add("Filtered by link type")
            }
            if (cleanQuery.contains("audio") && item.kind == ItemKind.AUDIO) {
                score += 3f
                matchReasons.add("Filtered by audio recording")
            }

            if (score > 0f) {
                val explanation = matchReasons.distinct().take(2).joinToString(" • ")
                results.add(
                    SearchResultItem(
                        item = item,
                        matchExplanation = explanation.ifBlank { "Semantic concept match" },
                        relevanceScore = score
                    )
                )
            }
        }

        results.sortedByDescending { it.relevanceScore }
    }
}
