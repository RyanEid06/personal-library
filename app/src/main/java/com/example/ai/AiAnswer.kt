package com.example.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AnswerEvidence(
    val sourceId: String,
    val itemRef: String,
    val title: String,
    val text: String,
    val pageNumber: Int? = null
)

@Serializable
data class AnswerRequest(val question: String, val evidence: List<AnswerEvidence>)

@Serializable
data class AnswerResponse(val status: String, val answer: String, val sourceIds: List<String>)

/** Shared client bounds; the backend independently enforces the same wire contract. */
internal object AnswerProtocol {
    const val MAX_RESPONSE_BYTES = 32768
    private val json = Json

    fun encode(request: AnswerRequest): String {
        requireValid(request.question.isNotBlank() && request.question.length <= 2000 && validUnicode(request.question))
        requireValid(request.evidence.size in 1..12)
        requireValid(request.evidence.map { it.sourceId }.distinct().size == request.evidence.size)
        requireValid(request.evidence.groupBy { it.itemRef }.values.all { it.size <= 3 })
        requireValid(request.evidence.sumOf { it.text.length.toLong() } <= 12000)
        requireValid(request.evidence.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } <= 32768)
        for (evidence in request.evidence) {
            requireValid(evidence.sourceId.isNotBlank() && evidence.sourceId.length <= 64 && validUnicode(evidence.sourceId))
            requireValid(evidence.itemRef.isNotBlank() && evidence.itemRef.length <= 64 && validUnicode(evidence.itemRef))
            requireValid(evidence.title.isNotBlank() && evidence.title.length <= 200 && validUnicode(evidence.title))
            requireValid(evidence.text.isNotBlank() && evidence.text.length <= 1200 && validUnicode(evidence.text))
            requireValid(evidence.pageNumber == null || evidence.pageNumber > 0)
        }
        val encoded = json.encodeToString(AnswerRequest.serializer(), request)
        requireValid(encoded.toByteArray(Charsets.UTF_8).size <= 65536)
        return encoded
    }

    fun decode(body: String, evidence: List<AnswerEvidence>): AnswerResponse {
        val response = try { json.decodeFromString(AnswerResponse.serializer(), body) }
        catch (_: Exception) { throw AiGatewayException.ValidationException("Invalid answer response") }
        requireValid(response.answer.length <= 4000 && validUnicode(response.answer))
        requireValid(response.sourceIds.size <= 12 && response.sourceIds.distinct().size == response.sourceIds.size)
        val allowedIds = evidence.map { it.sourceId }.toSet()
        requireValid(response.sourceIds.all { it in allowedIds })
        when (response.status) {
            "ANSWERED" -> requireValid(response.answer.isNotBlank() && response.sourceIds.isNotEmpty())
            "CONFLICTING" -> requireValid(response.answer.isNotBlank() && response.sourceIds.size >= 2)
            "INSUFFICIENT" -> requireValid(response.answer.isEmpty() && response.sourceIds.isEmpty())
            else -> requireValid(false)
        }
        return response
    }

    private fun requireValid(valid: Boolean) {
        if (!valid) throw AiGatewayException.ValidationException("Invalid or oversized answer protocol")
    }

    private fun validUnicode(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                if (index == text.length || !text[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }
}
