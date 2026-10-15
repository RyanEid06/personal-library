import express from 'express';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { GoogleGenAI } from '@google/genai';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const port = parseInt(process.env.PORT || '3000', 10);
const geminiApiKey = process.env.GEMINI_API_KEY || '';
const geminiModel = process.env.GEMINI_MODEL || 'gemini-3.8-flash';
const geminiEmbeddingModel = process.env.GEMINI_EMBEDDING_MODEL || 'text-embedding-004';

app.use(express.json({ limit: '20mb' }));

let aiClient: GoogleGenAI | null = null;
if (geminiApiKey) {
  try {
    aiClient = new GoogleGenAI({ apiKey: geminiApiKey });
  } catch (err) {
    console.warn('Failed to initialize GoogleGenAI with key:', err);
  }
}

// Fallback normalized 768 vector generator
function generateDeterministicVector(text: string): number[] {
  const vec = new Float32Array(768);
  const hash = crypto.createHash('sha256').update(text).digest();
  for (let i = 0; i < 768; i++) {
    const byte = hash[i % hash.length];
    vec[i] = ((byte / 255.0) - 0.5) * Math.sin(i + 1);
  }
  let sumSq = 0;
  for (let i = 0; i < 768; i++) sumSq += vec[i] * vec[i];
  const mag = Math.sqrt(sumSq) || 1.0;
  const result = new Array(768);
  for (let i = 0; i < 768; i++) {
    result[i] = parseFloat((vec[i] / mag).toFixed(6));
  }
  return result;
}

function parseLocalSearchPlan(query: string) {
  const lower = query.toLowerCase();
  const targetKinds: string[] = [];
  const concepts: string[] = [];

  if (lower.includes('photo') || lower.includes('screenshot') || lower.includes('picture') || lower.includes('image')) targetKinds.push('PHOTO');
  if (lower.includes('sticker')) targetKinds.push('STICKER');
  if (lower.includes('link') || lower.includes('url') || lower.includes('website')) targetKinds.push('LINK');
  if (lower.includes('audio') || lower.includes('voice') || lower.includes('recording')) targetKinds.push('AUDIO');
  if (lower.includes('video') || lower.includes('clip')) targetKinds.push('VIDEO');
  if (lower.includes('document') || lower.includes('pdf') || lower.includes('doc')) targetKinds.push('DOCUMENT');
  if (lower.includes('note') || lower.includes('text')) targetKinds.push('TEXT');

  if (lower.includes('grade') || lower.includes('exam') || lower.includes('score')) concepts.push('grades', 'exam', 'academic');
  if (lower.includes('university') || lower.includes('college') || lower.includes('school')) concepts.push('university', 'study');
  if (lower.includes('receipt') || lower.includes('invoice') || lower.includes('bill')) concepts.push('receipt', 'expense');

  const tokens = query.split(/\s+/).filter(t => t.length > 2);
  for (const t of tokens) {
    const clean = t.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (clean.length > 2 && !concepts.includes(clean)) {
      concepts.push(clean);
    }
  }

  return {
    query,
    concepts,
    targetKinds,
    dateRange: { startIso: null, endIso: null }
  };
}

// Health check
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'personal-library',
    hasLiveKey: Boolean(geminiApiKey),
    geminiModel,
    embeddingModel: geminiEmbeddingModel
  });
});

// Analyze Content Endpoint
app.post('/api/ai/analyze', async (req, res) => {
  const { itemId, kind, textContent, originalFileName } = req.body || {};
  if (!itemId || !kind) {
    return res.status(400).json({ error: 'itemId and kind are required' });
  }

  if (aiClient) {
    try {
      const prompt = `Analyze this saved personal library item. Return JSON with 'description' (concise 1-sentence summary), 'concepts' (3-5 comma separated keywords), and 'detectedDates' (array of YYYY-MM-DD or empty).
Item Type: ${kind}
Original Filename: ${originalFileName || 'N/A'}
Text Content: ${(textContent || '').slice(0, 4000) || 'None provided'}
`;
      const response = await aiClient.models.generateContent({
        model: geminiModel,
        contents: prompt,
        config: {
          responseMimeType: 'application/json',
          temperature: 0.2
        }
      });
      const parsed = JSON.parse(response.text?.trim() || '{}');
      return res.json({
        itemId,
        description: parsed.description || `Saved ${kind.toLowerCase()} item`,
        concepts: parsed.concepts || kind.toLowerCase(),
        detectedDates: Array.isArray(parsed.detectedDates) ? parsed.detectedDates : [],
        coverage: 'COMPLETE'
      });
    } catch (err: any) {
      console.warn('Gemini analysis failed, using fallback:', err?.message);
    }
  }

  // Fallback heuristic analysis
  const cleanText = (textContent || '').trim();
  const isGrade = cleanText.toLowerCase().includes('grade') || cleanText.includes('/100');
  const desc = isGrade
    ? 'Academic grade record or exam note'
    : originalFileName
    ? `Document or media file: ${originalFileName}`
    : cleanText ? `Saved ${kind.toLowerCase()}: ${cleanText.slice(0, 60)}` : `Saved ${kind.toLowerCase()} item`;
  const concepts = isGrade
    ? `${kind.toLowerCase()}, grades, academic`
    : `${kind.toLowerCase()}, personal${originalFileName ? `, ${originalFileName.split('.')[0]}` : ''}`;

  return res.json({
    itemId,
    description: desc,
    concepts,
    detectedDates: [],
    coverage: 'FALLBACK'
  });
});

// Embed Endpoint
app.post('/api/ai/embed', async (req, res) => {
  const { texts } = req.body || {};
  if (!Array.isArray(texts) || texts.length === 0) {
    return res.status(400).json({ error: 'texts must be a non-empty array' });
  }

  const embeddings: number[][] = [];
  for (const text of texts.slice(0, 32)) {
    if (aiClient) {
      try {
        const response = await aiClient.models.embedContent({
          model: geminiEmbeddingModel,
          contents: text.slice(0, 2048)
        });
        const resAny = response as any;
        const values: number[] | undefined = resAny.embedding?.values || resAny.embeddings?.[0]?.values;
        if (values && values.length > 0) {
          let sumSq = 0;
          for (let i = 0; i < values.length; i++) sumSq += values[i] * values[i];
          const mag = Math.sqrt(sumSq) || 1.0;
          embeddings.push(values.map(v => parseFloat((v / mag).toFixed(6))));
          continue;
        }
      } catch (err: any) {
        console.warn('Gemini embed failed, using deterministic fallback:', err?.message);
      }
    }
    embeddings.push(generateDeterministicVector(text));
  }

  return res.json({
    dimension: 768,
    modelVersion: aiClient ? geminiEmbeddingModel : 'local-deterministic-768',
    embeddings
  });
});

// Search Plan Endpoint
app.post('/api/ai/search-plan', async (req, res) => {
  const { query, knownCollections, nowIso } = req.body || {};
  if (!query || typeof query !== 'string') {
    return res.status(400).json({ error: 'query string required' });
  }

  if (aiClient) {
    try {
      const prompt = `Convert this search query into a structured retrieval plan.
Query: "${query}"
Known collections: ${JSON.stringify(knownCollections || [])}
Current timestamp: ${nowIso || new Date().toISOString()}

Return JSON with:
- "concepts": list of key search concepts and synonyms (strings)
- "targetKinds": list of targeted item kinds (e.g. "PHOTO", "DOCUMENT", "AUDIO", "VIDEO", "LINK", "TEXT", "STICKER") or empty list if any
- "dateRange": { "startIso": string or null, "endIso": string or null }
`;
      const response = await aiClient.models.generateContent({
        model: geminiModel,
        contents: prompt,
        config: {
          responseMimeType: 'application/json',
          temperature: 0.1
        }
      });
      const parsed = JSON.parse(response.text?.trim() || '{}');
      return res.json({
        query,
        concepts: Array.isArray(parsed.concepts) ? parsed.concepts : [query],
        targetKinds: Array.isArray(parsed.targetKinds) ? parsed.targetKinds : [],
        dateRange: parsed.dateRange || { startIso: null, endIso: null }
      });
    } catch (err: any) {
      console.warn('Gemini search-plan failed, using local plan:', err?.message);
    }
  }

  return res.json(parseLocalSearchPlan(query));
});

async function startServer() {
  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true, host: '0.0.0.0', port },
      appType: 'spa'
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.join(__dirname, 'dist')));
    app.get('*', (req, res) => {
      res.sendFile(path.join(__dirname, 'dist', 'index.html'));
    });
  }

  app.listen(port, '0.0.0.0', () => {
    console.log(`Personal Library server listening on http://0.0.0.0:${port}`);
  });
}

startServer();
