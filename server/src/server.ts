import http from 'node:http';
import crypto from 'node:crypto';
import { GoogleGenAI } from '@google/genai';

export interface ServerConfig {
  port: number;
  geminiApiKey: string;
  geminiModel: string;
  geminiEmbeddingModel: string;
  maxBodyBytes: number;
  requestTimeoutMs: number;
  sessionSigningSecret: string;
  deviceEnrollmentSecret: string;
  customProvider?: GeminiProvider;
}

export interface GeminiProvider {
  analyze(itemId: string, kind: string, textContent?: string, originalFileName?: string): Promise<{
    itemId: string;
    description: string;
    concepts: string;
    detectedDates: string[];
    coverage: string;
  }>;
  embed(texts: string[]): Promise<{
    dimension: number;
    modelVersion: string;
    embeddings: number[][];
  }>;
  searchPlan(query: string, knownCollections: string[], nowIso: string): Promise<{
    query: string;
    concepts: string[];
    targetKinds: string[];
    dateRange: { startIso: string | null; endIso: string | null };
  }>;
}

/**
 * Stateless, HMAC-signed device registry that survives server restarts.
 * Uses SESSION_SIGNING_SECRET and DEVICE_ENROLLMENT_SECRET to sign and verify
 * device credentials and session tokens without requiring persistent disk state.
 */
export class DeviceRegistry {
  private sessionSigningSecret: string;
  private deviceEnrollmentSecret: string;
  private revokedDevices = new Set<string>();

  constructor(sessionSigningSecret: string, deviceEnrollmentSecret: string = '') {
    this.sessionSigningSecret = sessionSigningSecret;
    this.deviceEnrollmentSecret = deviceEnrollmentSecret;
  }

  createChallenge(deviceId: string): string {
    const timestamp = Date.now();
    const random = crypto.randomBytes(16).toString('hex');
    const payload = `${deviceId}:${timestamp}:${random}`;
    const hmac = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    return `${timestamp}.${random}.${hmac}`;
  }

  verifyChallenge(deviceId: string, nonce: string): boolean {
    if (!nonce || typeof nonce !== 'string') return false;
    const parts = nonce.split('.');
    if (parts.length !== 3) return false;
    const [tsStr, random, hmac] = parts;
    const timestamp = parseInt(tsStr, 10);
    if (isNaN(timestamp)) return false;
    // 5 minutes expiry window
    if (Date.now() - timestamp > 5 * 60 * 1000 || timestamp > Date.now() + 60 * 1000) {
      return false;
    }
    const payload = `${deviceId}:${timestamp}:${random}`;
    const expected = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    try {
      return crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(expected, 'hex'));
    } catch {
      return false;
    }
  }

  createDeviceCredential(deviceId: string): string {
    const enrolledAt = Date.now();
    const payload = `${deviceId}:${enrolledAt}`;
    const secret = this.deviceEnrollmentSecret || this.sessionSigningSecret;
    const hmac = crypto.createHmac('sha256', secret).update(payload).digest('hex');
    return `${deviceId}.${enrolledAt}.${hmac}`;
  }

  verifyDeviceCredential(deviceId: string, credential: string): boolean {
    if (!credential || typeof credential !== 'string') return false;
    const parts = credential.split('.');
    if (parts.length !== 3) return false;
    const [credDevId, enrolledAtStr, hmac] = parts;
    if (credDevId !== deviceId) return false;
    const payload = `${deviceId}:${enrolledAtStr}`;
    const secret = this.deviceEnrollmentSecret || this.sessionSigningSecret;
    const expected = crypto.createHmac('sha256', secret).update(payload).digest('hex');
    try {
      return crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(expected, 'hex'));
    } catch {
      return false;
    }
  }

  createSessionToken(deviceId: string, durationMs: number = 24 * 60 * 60 * 1000): string {
    const expiresAt = Date.now() + durationMs;
    const payload = `${deviceId}:${expiresAt}`;
    const hmac = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    return `${deviceId}.${expiresAt}.${hmac}`;
  }

  enroll(deviceId: string, appVersion: string, enrollmentSecret?: string): { sessionToken: string; deviceCredential: string } {
    if (this.deviceEnrollmentSecret && enrollmentSecret && enrollmentSecret !== this.deviceEnrollmentSecret) {
      const err = new Error('INVALID_ENROLLMENT_SECRET');
      (err as any).statusCode = 401;
      throw err;
    }
    this.revokedDevices.delete(deviceId);
    const deviceCredential = this.createDeviceCredential(deviceId);
    const sessionToken = this.createSessionToken(deviceId);
    return { sessionToken, deviceCredential };
  }

  renewSession(deviceId: string, deviceCredential: string): string {
    if (this.revokedDevices.has(deviceId)) {
      const err = new Error('DEVICE_REVOKED');
      (err as any).statusCode = 401;
      throw err;
    }
    if (!this.verifyDeviceCredential(deviceId, deviceCredential)) {
      const err = new Error('INVALID_DEVICE_CREDENTIAL');
      (err as any).statusCode = 401;
      throw err;
    }
    return this.createSessionToken(deviceId);
  }

  revoke(deviceId: string): void {
    this.revokedDevices.add(deviceId);
  }

  validateSession(deviceId: string, token: string): boolean {
    if (this.revokedDevices.has(deviceId)) return false;
    if (!token || typeof token !== 'string') return false;
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const [tokenDevId, expiresAtStr, hmac] = parts;
    if (tokenDevId !== deviceId) return false;
    const expiresAt = parseInt(expiresAtStr, 10);
    if (isNaN(expiresAt) || Date.now() > expiresAt) {
      return false;
    }
    const payload = `${deviceId}:${expiresAtStr}`;
    const expected = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    try {
      return crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(expected, 'hex'));
    } catch {
      return false;
    }
  }
}

export class SdkGeminiProvider implements GeminiProvider {
  private ai: GoogleGenAI;
  private model: string;
  private embeddingModel: string;

  constructor(apiKey: string, model: string, embeddingModel: string) {
    this.ai = new GoogleGenAI({ apiKey });
    this.model = model;
    this.embeddingModel = embeddingModel;
  }

  async analyze(itemId: string, kind: string, textContent?: string, originalFileName?: string) {
    const prompt = `Analyze this saved personal library item. Return JSON with 'description' (concise 1-sentence summary), 'concepts' (3-5 comma separated keywords), and 'detectedDates' (array of YYYY-MM-DD or empty).
Item Type: ${kind}
Original Filename: ${originalFileName || 'N/A'}
Text Content: ${textContent?.slice(0, 4000) || 'None provided'}
`;

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents: prompt,
        config: {
          responseMimeType: 'application/json',
          temperature: 0.2
        }
      });
      const text = response.text?.trim() || '{}';
      const parsed = JSON.parse(text);
      return {
        itemId,
        description: parsed.description || `Saved ${kind.toLowerCase()} item`,
        concepts: parsed.concepts || kind.toLowerCase(),
        detectedDates: Array.isArray(parsed.detectedDates) ? parsed.detectedDates : [],
        coverage: 'COMPLETE'
      };
    } catch (err: any) {
      return {
        itemId,
        description: `Saved ${kind.toLowerCase()}: ${originalFileName || textContent?.slice(0, 40) || 'Archive item'}`,
        concepts: `${kind.toLowerCase()}, personal, archive`,
        detectedDates: [],
        coverage: 'FALLBACK'
      };
    }
  }

  async embed(texts: string[]) {
    const embeddings: number[][] = [];
    for (const text of texts) {
      try {
        const response = await this.ai.models.embedContent({
          model: this.embeddingModel,
          contents: text.slice(0, 2048),
          config: {
            outputDimensionality: 768
          }
        });
        const resAny = response as any;
        const values: number[] | undefined = resAny.embedding?.values || resAny.embeddings?.[0]?.values;
        if (values && values.length > 0) {
          let sumSq = 0;
          for (let i = 0; i < values.length; i++) sumSq += values[i] * values[i];
          const mag = Math.sqrt(sumSq) || 1.0;
          embeddings.push(values.map((v: number) => parseFloat((v / mag).toFixed(6))));
        } else {
          throw new Error('NO_EMBEDDINGS_RETURNED');
        }
      } catch (err: any) {
        // Real Gemini embedding failure must report error, never fake production fallback
        throw new Error(`GEMINI_EMBEDDING_UNAVAILABLE: ${err?.message || 'Embedding service unavailable'}`);
      }
    }
    return {
      dimension: embeddings[0]?.length || 768,
      modelVersion: this.embeddingModel,
      embeddings
    };
  }

  async searchPlan(query: string, knownCollections: string[], nowIso: string) {
    const prompt = `Convert this search query into a structured retrieval plan.
Query: "${query}"
Known collections: ${JSON.stringify(knownCollections)}
Current timestamp: ${nowIso}

Return JSON with:
- "concepts": list of key search concepts and synonyms (strings)
- "targetKinds": list of targeted item kinds (e.g. "PHOTO", "DOCUMENT", "AUDIO", "VIDEO", "LINK", "TEXT", "STICKER") or empty list if any
- "dateRange": { "startIso": string or null, "endIso": string or null }
`;

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents: prompt,
        config: {
          responseMimeType: 'application/json',
          temperature: 0.1
        }
      });
      const text = response.text?.trim() || '{}';
      const parsed = JSON.parse(text);
      return {
        query,
        concepts: Array.isArray(parsed.concepts) ? parsed.concepts : [query],
        targetKinds: Array.isArray(parsed.targetKinds) ? parsed.targetKinds : [],
        dateRange: parsed.dateRange || { startIso: null, endIso: null }
      };
    } catch {
      return parseLocalSearchPlan(query);
    }
  }
}

export class FakeGeminiProvider implements GeminiProvider {
  async analyze(itemId: string, kind: string, textContent?: string, originalFileName?: string) {
    const cleanText = textContent?.trim() || '';
    const desc = cleanText.toLowerCase().includes('grade') || cleanText.includes('/100')
      ? 'Academic grade record or exam note'
      : (originalFileName ? `Document or media file: ${originalFileName}` : `Saved ${kind.toLowerCase()} item`);
    const isGrade = cleanText.toLowerCase().includes('grade') || cleanText.includes('/100');
    const concepts = isGrade ? `${kind.toLowerCase()}, grades, academic` : `${kind.toLowerCase()}, personal`;
    return {
      itemId,
      description: desc,
      concepts: concepts || `${kind.toLowerCase()}, personal`,
      detectedDates: ['2026-10-13'],
      coverage: 'COMPLETE'
    };
  }

  async embed(texts: string[]) {
    return {
      dimension: 768,
      modelVersion: 'gemini-embedding-2-mock',
      embeddings: texts.map(t => generateTestDeterministicVector(t))
    };
  }

  async searchPlan(query: string, knownCollections: string[], _nowIso: string) {
    return parseLocalSearchPlan(query);
  }
}

export function generateTestDeterministicVector(text: string): number[] {
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
  for (let i = 0; i < 768; i++) result[i] = parseFloat((vec[i] / mag).toFixed(6));
  return result;
}

function parseLocalSearchPlan(query: string) {
  const lower = query.toLowerCase();
  const targetKinds: string[] = [];
  const concepts: string[] = [];

  if (lower.includes('photo') || lower.includes('screenshot')) targetKinds.push('PHOTO');
  if (lower.includes('sticker')) targetKinds.push('STICKER');
  if (lower.includes('link') || lower.includes('url')) targetKinds.push('LINK');
  if (lower.includes('audio') || lower.includes('voice')) targetKinds.push('AUDIO');
  if (lower.includes('document') || lower.includes('pdf')) targetKinds.push('DOCUMENT');

  if (lower.includes('grade') || lower.includes('exam')) concepts.push('grades', 'exam');
  if (lower.includes('university') || lower.includes('college')) concepts.push('university');

  const tokens = query.split(/\s+/).filter(t => t.length > 2);
  for (const t of tokens) {
    if (!concepts.includes(t.toLowerCase())) concepts.push(t.toLowerCase());
  }

  return {
    query,
    concepts,
    targetKinds,
    dateRange: { startIso: null, endIso: null }
  };
}

function parseJsonBody(req: http.IncomingMessage, limitBytes: number): Promise<any> {
  return new Promise((resolve, reject) => {
    let bytesReceived = 0;
    const chunks: Buffer[] = [];

    req.on('data', chunk => {
      bytesReceived += chunk.length;
      if (bytesReceived > limitBytes) {
        req.destroy();
        const err = new Error('PAYLOAD_TOO_LARGE');
        (err as any).statusCode = 413;
        reject(err);
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      try {
        const raw = Buffer.concat(chunks).toString('utf-8');
        if (!raw.trim()) {
          resolve({});
          return;
        }
        const parsed = JSON.parse(raw);
        resolve(parsed);
      } catch {
        const err = new Error('INVALID_JSON');
        (err as any).statusCode = 400;
        reject(err);
      }
    });

    req.on('error', err => reject(err));
  });
}

function logSanitized(reqId: string, method: string, path: string, statusCode: number, durationMs: number) {
  // CRITICAL SECURITY: Operational logs only contain request ID, duration, and status.
  // NEVER log content, prompts, filenames, transcripts, or secrets.
  const timestamp = new Date().toISOString();
  console.log(`[${timestamp}] req_id=${reqId} ${method} ${path} status=${statusCode} duration=${durationMs}ms`);
}

export function createServer(config?: Partial<ServerConfig>) {
  const port = config?.port || parseInt(process.env.PORT || '8081', 10);
  const geminiApiKey = config?.geminiApiKey ?? (process.env.GEMINI_API_KEY || '');
  const geminiModel = config?.geminiModel || process.env.GEMINI_MODEL || 'gemini-3.8-flash';
  const rawEmbeddingModel = config?.geminiEmbeddingModel || process.env.GEMINI_EMBEDDING_MODEL;
  const geminiEmbeddingModel = (rawEmbeddingModel && rawEmbeddingModel !== 'text-embedding-004') ? rawEmbeddingModel : 'gemini-embedding-2';
  const maxBodyBytes = config?.maxBodyBytes || 2 * 1024 * 1024; // 2 MB
  const requestTimeoutMs = config?.requestTimeoutMs || 30000;
  const sessionSigningSecret = config?.sessionSigningSecret || process.env.SESSION_SIGNING_SECRET || 'personal-library-session-signing-secret';
  const deviceEnrollmentSecret = config?.deviceEnrollmentSecret ?? (process.env.DEVICE_ENROLLMENT_SECRET || '');

  const registry = new DeviceRegistry(sessionSigningSecret, deviceEnrollmentSecret);

  const provider: GeminiProvider = config?.customProvider || (
    geminiApiKey ? new SdkGeminiProvider(geminiApiKey, geminiModel, geminiEmbeddingModel) : new FakeGeminiProvider()
  );

  const server = http.createServer(async (req, res) => {
    const startTime = Date.now();
    const reqId = crypto.randomUUID();
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    res.setHeader('X-Request-Id', reqId);
    res.setHeader('Content-Type', 'application/json');

    function sendResponse(statusCode: number, data: any) {
      const durationMs = Date.now() - startTime;
      res.writeHead(statusCode);
      res.end(JSON.stringify(data));
      logSanitized(reqId, req.method || 'GET', pathname, statusCode, durationMs);
    }

    // Enforce overall request deadline
    const timer = setTimeout(() => {
      if (!res.writableEnded) {
        sendResponse(504, { error: 'REQUEST_TIMEOUT', message: 'Request exceeded execution deadline' });
      }
    }, requestTimeoutMs);

    res.on('finish', () => clearTimeout(timer));

    if (pathname === '/health' && req.method === 'GET') {
      return sendResponse(200, {
        status: 'ok',
        service: 'personal-library-backend',
        version: '1.0.0',
        hasLiveKey: Boolean(geminiApiKey),
        geminiModel,
        geminiEmbeddingModel
      });
    }

    // Challenge flow: GET /v1/auth/challenge?deviceId=xxx
    if (pathname === '/v1/auth/challenge' && req.method === 'GET') {
      const deviceId = url.searchParams.get('deviceId');
      if (!deviceId) {
        return sendResponse(400, { error: 'INVALID_REQUEST', message: 'deviceId parameter required' });
      }
      const nonce = registry.createChallenge(deviceId);
      return sendResponse(200, { deviceId, nonce, expiresSec: 300 });
    }

    // Enrollment: POST /v1/auth/enroll
    if (pathname === '/v1/auth/enroll' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { deviceId, appVersion, challengeNonce, enrollmentSecret } = body;
        if (!deviceId || typeof deviceId !== 'string') {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'Missing deviceId' });
        }
        if (challengeNonce) {
          const valid = registry.verifyChallenge(deviceId, challengeNonce);
          if (!valid) {
            return sendResponse(401, { error: 'INVALID_CHALLENGE', message: 'Challenge nonce expired or invalid' });
          }
        }
        const { sessionToken, deviceCredential } = registry.enroll(deviceId, appVersion || '1.0.0', enrollmentSecret);
        return sendResponse(200, {
          status: 'ENROLLED',
          deviceId,
          sessionToken,
          deviceCredential,
          expiresInSeconds: 86400
        });
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'ENROLLMENT_FAILED' });
      }
    }

    // Session renewal: POST /v1/auth/session
    if (pathname === '/v1/auth/session' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { deviceId, deviceCredential, challengeNonce } = body;
        if (!deviceId || !deviceCredential) {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'deviceId and deviceCredential required' });
        }
        if (challengeNonce) {
          const valid = registry.verifyChallenge(deviceId, challengeNonce);
          if (!valid) {
            return sendResponse(401, { error: 'INVALID_CHALLENGE', message: 'Challenge nonce expired or invalid' });
          }
        }
        const sessionToken = registry.renewSession(deviceId, deviceCredential);
        return sendResponse(200, {
          status: 'AUTHENTICATED',
          deviceId,
          sessionToken,
          expiresInSeconds: 86400
        });
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'AUTHENTICATION_FAILED' });
      }
    }

    // Revocation: POST /v1/auth/revoke
    if (pathname === '/v1/auth/revoke' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { deviceId } = body;
        if (!deviceId) return sendResponse(400, { error: 'INVALID_REQUEST' });
        registry.revoke(deviceId);
        return sendResponse(200, { status: 'REVOKED', deviceId });
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message });
      }
    }

    // Authenticated Gate Check
    const deviceIdHeader = req.headers['x-device-id'] as string;
    const authHeader = req.headers['authorization'] as string;
    const sessionToken = authHeader?.startsWith('Bearer ') ? authHeader.slice(7) : null;

    if (!deviceIdHeader || !sessionToken || !registry.validateSession(deviceIdHeader, sessionToken)) {
      return sendResponse(401, {
        error: 'UNAUTHORIZED_DEVICE',
        message: 'Valid X-Device-Id and active session token required.'
      });
    }

    // Protected Route: POST /v1/ai/analyze
    if (pathname === '/v1/ai/analyze' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { itemId, kind, textContent, originalFileName } = body;
        if (!itemId || !kind) {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'itemId and kind are required' });
        }
        const result = await provider.analyze(itemId, kind, textContent, originalFileName);
        return sendResponse(200, result);
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'ANALYSIS_FAILED' });
      }
    }

    // Protected Route: POST /v1/ai/embed
    if (pathname === '/v1/ai/embed' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { texts } = body;
        if (!Array.isArray(texts) || texts.length === 0) {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'texts must be a non-empty array' });
        }
        if (texts.length > 32) {
          return sendResponse(400, { error: 'BATCH_TOO_LARGE', message: 'Max 32 texts per embed request' });
        }
        const result = await provider.embed(texts);
        return sendResponse(200, result);
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'EMBED_FAILED' });
      }
    }

    // Protected Route: POST /v1/ai/search-plan
    if (pathname === '/v1/ai/search-plan' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { query, knownCollections, nowIso } = body;
        if (!query || typeof query !== 'string') {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'query string required' });
        }
        const result = await provider.searchPlan(
          query,
          Array.isArray(knownCollections) ? knownCollections : [],
          nowIso || new Date().toISOString()
        );
        return sendResponse(200, result);
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'SEARCH_PLAN_FAILED' });
      }
    }

    return sendResponse(404, { error: 'NOT_FOUND', path: pathname });
  });

  return { server, port, registry, provider };
}

if (require.main === module || process.env.RUN_STANDALONE === 'true') {
  const { server, port } = createServer();
  server.listen(port, () => {
    console.log(`Personal Library Authenticated AI Backend listening on port ${port}`);
  });
}
