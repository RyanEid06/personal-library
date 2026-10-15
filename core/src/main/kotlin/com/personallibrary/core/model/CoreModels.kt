package com.personallibrary.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sqrt

enum class ItemKind(val displayName: String) {
    PHOTO("Photos"),
    STICKER("Stickers"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    TEXT("Notes & Text"),
    LINK("Links"),
    DOCUMENT("Documents"),
    FILE("Other Files")
}

@Serializable
data class StoredAsset(
    val hash: String,
    val relativePath: String,
    val sizeBytes: Long,
    val originalName: String,
    val mimeType: String,
    val kind: ItemKind
)

data class DateRange(
    val startInclusive: Instant,
    val endExclusive: Instant
) {
    fun contains(instant: Instant): Boolean {
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive)
    }

    companion object {
        fun allTime(): DateRange {
            return DateRange(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE - 1000000L))
        }

        fun month(year: Int, month: Int, zone: ZoneId): DateRange {
            val startZdt = LocalDate.of(year, month, 1).atStartOfDay(zone)
            val endZdt = startZdt.plusMonths(1)
            return DateRange(startZdt.toInstant(), endZdt.toInstant())
        }

        fun year(year: Int, zone: ZoneId): DateRange {
            val startZdt = LocalDate.of(year, 1, 1).atStartOfDay(zone)
            val endZdt = startZdt.plusYears(1)
            return DateRange(startZdt.toInstant(), endZdt.toInstant())
        }

        fun custom(start: Instant, end: Instant): DateRange {
            require(!end.isBefore(start)) { "End instant must not be before start instant" }
            return DateRange(start, end)
        }
    }
}

object SearchQuery {
    /**
     * Sanitizes and transforms raw user query into safe FTS prefix syntax.
     * Escapes quotes, removes dangerous SQL/FTS operators, handles non-Latin/emoji tokens.
     * Returns null if no searchable tokens remain.
     */
    fun fts(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // Remove dangerous characters
        val sanitized = trimmed
            .replace("\"", "")
            .replace("'", "")
            .replace("*", "")
            .replace("^", "")
            .replace("(", " ")
            .replace(")", " ")
            .replace(":", " ")
            .replace("-", " ")
            .replace("+", " ")
            .replace("OR", " ")
            .replace("AND", " ")
            .replace("NOT", " ")

        val tokens = sanitized.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        // Format each token with prefix matching
        return tokens.joinToString(" ") { token -> "\"$token\"*" }
    }
}

object SemanticVectorMath {
    const val DIMENSION = 768

    fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (v in vector) {
            sumSquares += (v * v).toDouble()
        }
        val magnitude = sqrt(sumSquares).toFloat()
        if (magnitude == 0f || magnitude.isNaN()) return vector.copyOf()

        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = vector[i] / magnitude
        }
        return normalized
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        val denominator = sqrt((normA * normB).toDouble()).toFloat()
        if (denominator == 0f || denominator.isNaN()) return 0f
        return (dot / denominator).coerceIn(-1f, 1f)
    }
}
