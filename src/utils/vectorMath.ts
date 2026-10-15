/**
 * Vector math and full text tokenization utility.
 * Mirrors SemanticVectorMath & SearchQuery from Kotlin core.
 */

export const SemanticVectorMath = {
  DIMENSION: 768,

  normalize(vector: number[]): number[] {
    let sumSquares = 0;
    for (const v of vector) {
      sumSquares += v * v;
    }
    const magnitude = Math.sqrt(sumSquares);
    if (magnitude === 0 || isNaN(magnitude)) return [...vector];

    return vector.map(v => v / magnitude);
  },

  cosineSimilarity(v1: number[], v2: number[]): number {
    if (v1.length !== v2.length || v1.length === 0) return 0;
    let dot = 0;
    let normA = 0;
    let normB = 0;
    for (let i = 0; i < v1.length; i++) {
      dot += v1[i] * v2[i];
      normA += v1[i] * v1[i];
      normB += v2[i] * v2[i];
    }
    const denominator = Math.sqrt(normA * normB);
    if (denominator === 0 || isNaN(denominator)) return 0;
    const sim = dot / denominator;
    return Math.max(-1, Math.min(1, sim));
  },

  /**
   * Deterministic 768-dim vector generator for offline fallback
   */
  generateFallbackVector(text: string): number[] {
    const vec = new Float32Array(768);
    let hash = 0;
    for (let i = 0; i < text.length; i++) {
      hash = ((hash << 5) - hash) + text.charCodeAt(i);
      hash |= 0;
    }
    for (let i = 0; i < 768; i++) {
      const charCode = text.charCodeAt(i % Math.max(1, text.length)) || 32;
      const val = ((hash ^ (charCode * (i + 13))) % 255) / 255.0 - 0.5;
      vec[i] = val * Math.sin(i + 1);
    }
    let sumSq = 0;
    for (let i = 0; i < 768; i++) sumSq += vec[i] * vec[i];
    const mag = Math.sqrt(sumSq) || 1.0;
    const result: number[] = new Array(768);
    for (let i = 0; i < 768; i++) {
      result[i] = parseFloat((vec[i] / mag).toFixed(6));
    }
    return result;
  }
};

export const SearchQuery = {
  /**
   * Sanitizes and transforms raw user query into safe token array.
   */
  tokenize(raw: string): string[] {
    const trimmed = raw.trim();
    if (!trimmed) return [];

    const sanitized = trimmed
      .replace(/["'*^():+\-]/g, ' ')
      .replace(/\b(OR|AND|NOT)\b/gi, ' ');

    return sanitized
      .split(/\s+/)
      .map(t => t.toLowerCase().trim())
      .filter(t => t.length > 0);
  },

  /**
   * Checks if an item matches query tokens (prefix or substring match)
   */
  matches(textToSearch: string, tokens: string[]): boolean {
    if (tokens.length === 0) return true;
    const lower = textToSearch.toLowerCase();
    return tokens.every(token => lower.includes(token));
  }
};
