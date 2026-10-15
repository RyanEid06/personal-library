import { ItemEntity, ItemKind } from '../types';
import { SemanticVectorMath } from '../utils/vectorMath';

export interface AnalysisResult {
  description: string;
  concepts: string;
  detectedDates: string[];
  coverage: string;
}

export interface SearchPlanResult {
  query: string;
  concepts: string[];
  targetKinds: string[];
  dateRange: { startIso: string | null; endIso: string | null };
}

export const AiGateway = {
  async analyze(item: ItemEntity): Promise<AnalysisResult> {
    try {
      const res = await fetch('/api/ai/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          itemId: item.id,
          kind: item.kind,
          textContent: item.content,
          originalFileName: item.originalFileName,
        }),
      });
      if (res.ok) {
        return await res.json();
      }
    } catch {
      // Local fallback
    }

    // Heuristic analysis
    const cleanText = (item.content || '').trim();
    const isGrade = cleanText.toLowerCase().includes('grade') || cleanText.includes('/100');
    const desc = isGrade
      ? 'Academic grade record or exam note'
      : item.originalFileName
      ? `Document or media file: ${item.originalFileName}`
      : cleanText
      ? `Saved ${item.kind.toLowerCase()}: ${cleanText.slice(0, 60)}`
      : `Saved ${item.kind.toLowerCase()} item`;

    return {
      description: desc,
      concepts: `${item.kind.toLowerCase()}, personal${item.originalFileName ? `, ${item.originalFileName.split('.')[0]}` : ''}`,
      detectedDates: [],
      coverage: 'FALLBACK',
    };
  },

  async embed(texts: string[]): Promise<number[][]> {
    try {
      const res = await fetch('/api/ai/embed', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ texts }),
      });
      if (res.ok) {
        const data = await res.json();
        if (data.embeddings && Array.isArray(data.embeddings)) {
          return data.embeddings;
        }
      }
    } catch {
      // Offline fallback
    }

    return texts.map(t => SemanticVectorMath.generateFallbackVector(t));
  },

  async getSearchPlan(query: string, knownCollections: string[]): Promise<SearchPlanResult> {
    try {
      const res = await fetch('/api/ai/search-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query,
          knownCollections,
          nowIso: new Date().toISOString(),
        }),
      });
      if (res.ok) {
        return await res.json();
      }
    } catch {
      // Offline fallback
    }

    const lower = query.toLowerCase();
    const targetKinds: string[] = [];
    const concepts: string[] = [];

    if (lower.includes('photo') || lower.includes('screenshot') || lower.includes('image')) targetKinds.push('PHOTO');
    if (lower.includes('sticker')) targetKinds.push('STICKER');
    if (lower.includes('link') || lower.includes('url')) targetKinds.push('LINK');
    if (lower.includes('audio') || lower.includes('voice')) targetKinds.push('AUDIO');
    if (lower.includes('video')) targetKinds.push('VIDEO');
    if (lower.includes('document') || lower.includes('pdf')) targetKinds.push('DOCUMENT');

    if (lower.includes('grade') || lower.includes('exam')) concepts.push('grades', 'exam', 'academic');
    if (lower.includes('university') || lower.includes('college')) concepts.push('university', 'study');
    if (lower.includes('receipt') || lower.includes('bill')) concepts.push('receipt', 'expense');

    const tokens = query.split(/\s+/).filter(t => t.length > 2);
    for (const t of tokens) {
      const c = t.toLowerCase().replace(/[^a-z0-9]/g, '');
      if (c.length > 2 && !concepts.includes(c)) concepts.push(c);
    }

    return {
      query,
      concepts,
      targetKinds,
      dateRange: { startIso: null, endIso: null },
    };
  },
};
