package com.personallibrary.core

import com.personallibrary.core.model.SemanticVectorMath
import org.junit.Assert.*
import org.junit.Test

class SemanticCompatibilityTest {
    private val query = FloatArray(768) { if (it == 0) 1f else 0f }

    @Test fun rejectsLegacyDimensionsModelsAndNonFiniteVectors() {
        assertEquals(0f, SemanticVectorMath.compatibleSimilarity(query, query, 768, "text-embedding-004"), 0f)
        assertEquals(0f, SemanticVectorMath.compatibleSimilarity(query, query.copyOf(767), 768, "gemini-embedding-2"), 0f)
        assertEquals(0f, SemanticVectorMath.compatibleSimilarity(query, query, 1536, "gemini-embedding-2"), 0f)
        assertEquals(0f, SemanticVectorMath.compatibleSimilarity(query.copyOf().apply { this[0] = Float.NaN }, query, 768, "gemini-embedding-2"), 0f)
        assertEquals(0f, SemanticVectorMath.compatibleSimilarity(query, FloatArray(768), 768, "gemini-embedding-2"), 0f)
    }

    @Test fun acceptsCompatibleChunkAndDistinguishesAnUnrelatedChunk() {
        assertEquals(1f, SemanticVectorMath.compatibleSimilarity(query, query, 768, "gemini-embedding-2"), 0.0001f)
        val unrelated = FloatArray(768) { if (it == 1) 1f else 0f }
        assertEquals(0f, SemanticVectorMath.compatibleSimilarity(query, unrelated, 768, "gemini-embedding-2"), 0.0001f)
    }
}
