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

export interface MediaPart {
  mimeType: string;
  dataBase64: string;
}

export interface GeminiProvider {
  analyze(itemId: string, kind: string, textContent?: string, originalFileName?: string, mediaParts?: MediaPart[], signal?: AbortSignal): Promise<{
    itemId: string;
    description: string;
    concepts: string;
    detectedDates: string[];
    coverage: string;
  }>;
  embed(texts: string[], signal?: AbortSignal): Promise<{
    dimension: number;
    modelVersion: string;
    embeddings: number[][];
  }>;
  searchPlan(query: string, knownCollections: string[], nowIso: string, signal?: AbortSignal): Promise<{
    provenance?: 'GEMINI' | 'LOCAL';
    query: string;
    concepts: string[];
    targetKinds: string[];
    dateRange: { startIso: string | null; endIso: string | null };
  }>;
}

const ALLOWED_TARGET_KINDS = new Set(['PHOTO', 'DOCUMENT', 'AUDIO', 'VIDEO', 'LINK', 'TEXT', 'STICKER']);
const ALLOWED_MEDIA_MIMES = new Set([
  'image/jpeg', 'image/png', 'image/webp', 'image/gif',
  'audio/mp3', 'audio/mpeg', 'audio/wav', 'audio/ogg', 'audio/aac', 'audio/mp4', 'audio/m4a'
]);

/**
 * Verify ECDSA SHA-256 signature against an X.509 SPKI public key (base64 encoded).
 */
export function verifyDeviceSignature(publicKeyBase64: string, canonicalString: string, signatureBase64: string): boolean {
  try {
    const keyDer = Buffer.from(publicKeyBase64, 'base64');
    const pubKey = crypto.createPublicKey({
      key: keyDer,
      format: 'der',
      type: 'spki'
    });
    if (pubKey.asymmetricKeyType !== 'ec' || pubKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') return false;
    const verifier = crypto.createVerify('SHA256');
    verifier.update(Buffer.from(canonicalString, 'utf-8'));
    return verifier.verify(pubKey, Buffer.from(signatureBase64, 'base64'));
  } catch {
    return false;
  }
}

/**
 * Stateless, HMAC-signed device registry with Keystore proof-of-possession.
 */
export class DeviceRegistry {
  private sessionSigningSecret: string;
  private deviceEnrollmentSecret: string;
  private revokedDevices = new Set<string>();
  private consumedNonces = new Map<string, number>();

  constructor(sessionSigningSecret: string, deviceEnrollmentSecret: string = '') {
    this.sessionSigningSecret = sessionSigningSecret;
    this.deviceEnrollmentSecret = deviceEnrollmentSecret;

    // Load revoked devices from environment configuration (REVOKED_DEVICE_IDS)
    const configuredRevoked = (process.env.REVOKED_DEVICE_IDS || process.env.REVOKED_DEVICES || '')
      .split(',')
      .map(s => s.trim())
      .filter(Boolean);
    for (const d of configuredRevoked) {
      this.revokedDevices.add(d);
    }
  }

  private cleanOldNonces(): void {
    const now = Date.now();
    for (const [nonce, ts] of this.consumedNonces.entries()) {
      if (now - ts > 10 * 60 * 1000) {
        this.consumedNonces.delete(nonce);
      }
    }
  }

  createChallenge(deviceId: string): string {
    const timestamp = Date.now();
    const random = crypto.randomBytes(16).toString('hex');
    const payload = `${deviceId}:${timestamp}:${random}`;
    const hmac = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    return `${timestamp}.${random}.${hmac}`;
  }

  verifyAndConsumeChallenge(deviceId: string, nonce: string): boolean {
    if (typeof nonce !== 'string' || !/^\d{13}\.[0-9a-f]{32}\.[0-9a-f]{64}$/.test(nonce)) return false;
    this.cleanOldNonces();

    // Check replay
    if (this.consumedNonces.has(nonce)) {
      return false;
    }

    const parts = nonce.split('.');
    if (parts.length !== 3) return false;
    const [tsStr, random, hmac] = parts;
    const timestamp = parseInt(tsStr, 10);
    if (isNaN(timestamp)) return false;

    // 5 minutes expiry window
    const now = Date.now();
    if (now - timestamp > 5 * 60 * 1000 || timestamp > now + 60 * 1000) {
      return false;
    }

    const payload = `${deviceId}:${timestamp}:${random}`;
    const expected = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    try {
      const match = crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(expected, 'hex'));
      if (match) {
        this.consumedNonces.set(nonce, now);
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }

  computePublicKeyFingerprint(publicKeyBase64: string): string {
    return crypto.createHash('sha256').update(Buffer.from(publicKeyBase64, 'base64')).digest('hex').slice(0, 32);
  }

  createDeviceCredential(deviceId: string, publicKeyFingerprint: string): string {
    const enrolledAt = Date.now();
    const payload = `${deviceId}:${publicKeyFingerprint}:${enrolledAt}:v1`;
    const secret = this.deviceEnrollmentSecret || this.sessionSigningSecret;
    const hmac = crypto.createHmac('sha256', secret).update(payload).digest('hex');
    return `${deviceId}.${publicKeyFingerprint}.${enrolledAt}.v1.${hmac}`;
  }

  verifyDeviceCredential(deviceId: string, credential: string): { valid: boolean; fingerprint?: string } {
    if (!credential || typeof credential !== 'string') return { valid: false };
    const parts = credential.split('.');
    if (parts.length === 3) {
      // Legacy format fallback
      const [credDevId, enrolledAtStr, hmac] = parts;
      if (credDevId !== deviceId) return { valid: false };
      const payload = `${deviceId}:${enrolledAtStr}`;
      const secret = this.deviceEnrollmentSecret || this.sessionSigningSecret;
      const expected = crypto.createHmac('sha256', secret).update(payload).digest('hex');
      try {
        const ok = crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(expected, 'hex'));
        return { valid: ok, fingerprint: 'legacy' };
      } catch {
        return { valid: false };
      }
    }
    if (parts.length !== 5) return { valid: false };
    const [credDevId, fingerprint, enrolledAtStr, version, hmac] = parts;
    if (credDevId !== deviceId || version !== 'v1') return { valid: false };
    const payload = `${deviceId}:${fingerprint}:${enrolledAtStr}:v1`;
    const secret = this.deviceEnrollmentSecret || this.sessionSigningSecret;
    const expected = crypto.createHmac('sha256', secret).update(payload).digest('hex');
    try {
      const ok = crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(expected, 'hex'));
      return { valid: ok, fingerprint };
    } catch {
      return { valid: false };
    }
  }

  createSessionToken(deviceId: string, durationMs: number = 30 * 60 * 1000): string {
    const expiresAt = Date.now() + durationMs;
    const payload = `${deviceId}:${expiresAt}`;
    const hmac = crypto.createHmac('sha256', this.sessionSigningSecret).update(payload).digest('hex');
    return `${deviceId}.${expiresAt}.${hmac}`;
  }

  enroll(
    deviceId: string,
    appVersion: string,
    challengeNonce: string,
    enrollmentSecret: string | undefined,
    publicKey: string | undefined,
    signature: string | undefined
  ): { sessionToken: string; deviceCredential: string; expiresInSeconds: number } {
    if (!this.deviceEnrollmentSecret) {
      const err = new Error('BACKEND_UNCONFIGURED');
      (err as any).statusCode = 503;
      throw err;
    }
    if (!enrollmentSecret || enrollmentSecret !== this.deviceEnrollmentSecret) {
      const err = new Error('INVALID_ENROLLMENT_SECRET');
      (err as any).statusCode = 401;
      throw err;
    }
    if (this.revokedDevices.has(deviceId)) {
      const err = new Error('DEVICE_REVOKED');
      (err as any).statusCode = 401;
      throw err;
    }

    // Require and verify ECDSA signature
    if (typeof publicKey !== 'string' || !publicKey.trim()) {
      const err = new Error('PUBLIC_KEY_REQUIRED');
      (err as any).statusCode = 400;
      throw err;
    }

    if (typeof signature !== 'string' || !signature.trim()) {
      const err = new Error('SIGNATURE_REQUIRED');
      (err as any).statusCode = 400;
      throw err;
    }
    if (!this.verifyAndConsumeChallenge(deviceId, challengeNonce)) {
      const err = new Error('INVALID_CHALLENGE');
      (err as any).statusCode = 401;
      throw err;
    }
    {
      const canonicalString = `PLIB-ENROLL-V1\n${deviceId}\n${challengeNonce}\n${appVersion || '1.0.0'}\n${publicKey}`;
      const validSig = verifyDeviceSignature(publicKey, canonicalString, signature);
      if (!validSig) {
        const err = new Error('INVALID_DEVICE_SIGNATURE');
        (err as any).statusCode = 401;
        throw err;
      }
    }

    const fingerprint = this.computePublicKeyFingerprint(publicKey);
    const deviceCredential = this.createDeviceCredential(deviceId, fingerprint);
    const expiresInSeconds = 1800; // 30 minutes short-lived session
    const sessionToken = this.createSessionToken(deviceId, expiresInSeconds * 1000);
    return { sessionToken, deviceCredential, expiresInSeconds };
  }

  renewSession(
    deviceId: string,
    deviceCredential: string,
    challengeNonce: string,
    publicKey: string | undefined,
    signature: string | undefined
  ): { sessionToken: string; expiresInSeconds: number } {
    if (this.revokedDevices.has(deviceId)) {
      const err = new Error('DEVICE_REVOKED');
      (err as any).statusCode = 401;
      throw err;
    }
    const credCheck = this.verifyDeviceCredential(deviceId, deviceCredential);
    if (!credCheck.valid) {
      const err = new Error('INVALID_DEVICE_CREDENTIAL');
      (err as any).statusCode = 401;
      throw err;
    }

    if (typeof publicKey !== 'string' || !publicKey.trim()) {
      const err = new Error('PUBLIC_KEY_REQUIRED');
      (err as any).statusCode = 400;
      throw err;
    }
    if (typeof signature !== 'string' || !signature.trim()) {
      const err = new Error('SIGNATURE_REQUIRED');
      (err as any).statusCode = 400;
      throw err;
    }
    if (!this.verifyAndConsumeChallenge(deviceId, challengeNonce)) {
      const err = new Error('INVALID_CHALLENGE');
      (err as any).statusCode = 401;
      throw err;
    }
    {
      const canonicalString = `PLIB-SESSION-V1\n${deviceId}\n${challengeNonce}\n${deviceCredential}`;
      const validSig = verifyDeviceSignature(publicKey, canonicalString, signature);
      if (!validSig) {
        const err = new Error('INVALID_DEVICE_SIGNATURE');
        (err as any).statusCode = 401;
        throw err;
      }
      // Ensure public key fingerprint matches credential
      const fp = this.computePublicKeyFingerprint(publicKey);
      if (credCheck.fingerprint !== fp) {
        const err = new Error('DEVICE_CREDENTIAL_KEY_MISMATCH');
        (err as any).statusCode = 401;
        throw err;
      }
    }

    const expiresInSeconds = 1800;
    const sessionToken = this.createSessionToken(deviceId, expiresInSeconds * 1000);
    return { sessionToken, expiresInSeconds };
  }

  revoke(deviceId: string): void {
    this.revokedDevices.add(deviceId);
  }

  isRevoked(deviceId: string): boolean {
    return this.revokedDevices.has(deviceId);
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
    this.ai = new GoogleGenAI({ apiKey, httpOptions: { timeout: 60_000 } });
    this.model = model;
    this.embeddingModel = embeddingModel;
  }

  async analyze(itemId: string, kind: string, textContent?: string, originalFileName?: string, mediaParts?: MediaPart[], signal?: AbortSignal) {
    signal?.throwIfAborted();
    const prompt = `Analyze this saved personal library item. Return JSON with 'description' (concise 1-sentence summary under 200 characters), 'concepts' (3-5 comma separated keywords), and 'detectedDates' (array of YYYY-MM-DD or empty).
Item Type: ${kind}
Original Filename: ${originalFileName || 'N/A'}
Text Content: ${textContent?.slice(0, 32000) || 'None provided'}
`;

    const contents: any[] = [{ text: prompt }];

    if (Array.isArray(mediaParts) && mediaParts.length > 0) {
      for (const part of mediaParts.slice(0, 6)) {
        if (ALLOWED_MEDIA_MIMES.has(part.mimeType.toLowerCase()) && part.dataBase64) {
          contents.push({
            inlineData: {
              mimeType: part.mimeType.toLowerCase(),
              data: part.dataBase64
            }
          });
        }
      }
    }

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents,
        config: {
          abortSignal: signal,
          responseMimeType: 'application/json',
          temperature: 0.2
        }
      });
      const text = response.text?.trim() || '{}';
      const parsed = JSON.parse(text);

      const description = typeof parsed.description === 'string'
        ? parsed.description.slice(0, 500)
        : `Saved ${kind.toLowerCase()} item`;
      const concepts = typeof parsed.concepts === 'string'
        ? parsed.concepts.slice(0, 300)
        : kind.toLowerCase();
      const detectedDates: string[] = Array.isArray(parsed.detectedDates)
        ? parsed.detectedDates.filter((d: any) => typeof d === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(d)).slice(0, 10)
        : [];

      return {
        itemId,
        description,
        concepts,
        detectedDates,
        coverage: 'COMPLETE'
      };
    } catch (err: any) {
      const error = new Error(`PROVIDER_UNAVAILABLE: ${err?.message || 'Gemini generation failed'}`);
      (error as any).statusCode = 502;
      throw error;
    }
  }

  async embed(texts: string[], signal?: AbortSignal) {
    const embeddings: number[][] = [];
    for (const text of texts) {
      signal?.throwIfAborted();
      try {
        const response = await this.ai.models.embedContent({
          model: this.embeddingModel,
          contents: text.slice(0, 8192),
          config: {
            abortSignal: signal,
            outputDimensionality: 768
          }
        });
        const resAny = response as any;
        const values: number[] | undefined = resAny.embedding?.values || resAny.embeddings?.[0]?.values;
        if (values && values.length === 768) {
          let sumSq = 0;
          for (let i = 0; i < values.length; i++) {
            const v = values[i];
            if (!Number.isFinite(v)) throw new Error('NON_FINITE_VECTOR_VALUE');
            sumSq += v * v;
          }
          const mag = Math.sqrt(sumSq) || 1.0;
          embeddings.push(values.map((v: number) => parseFloat((v / mag).toFixed(6))));
        } else {
          throw new Error('INVALID_EMBEDDING_DIMENSION');
        }
      } catch (err: any) {
        const error = new Error(`PROVIDER_UNAVAILABLE: ${err?.message || 'Gemini embedding unavailable'}`);
        (error as any).statusCode = 502;
        throw error;
      }
    }
    return {
      dimension: 768,
      modelVersion: this.embeddingModel,
      embeddings
    };
  }

  async searchPlan(query: string, knownCollections: string[], nowIso: string, signal?: AbortSignal) {
    signal?.throwIfAborted();
    const prompt = `Convert this search query into a structured retrieval plan.
Query: "${query.slice(0, 500)}"
Known collections: ${JSON.stringify(knownCollections.slice(0, 50))}
Current timestamp: ${nowIso}

Return JSON with:
- "concepts": list of key search concepts and synonyms (strings)
- "targetKinds": list of targeted item kinds (only from: "PHOTO", "DOCUMENT", "AUDIO", "VIDEO", "LINK", "TEXT", "STICKER") or empty list if any
- "dateRange": { "startIso": string or null, "endIso": string or null }
`;

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents: prompt,
        config: {
          abortSignal: signal,
          responseMimeType: 'application/json',
          temperature: 0.1
        }
      });
      const text = response.text?.trim() || '{}';
      const parsed = JSON.parse(text);

      const concepts = Array.isArray(parsed.concepts)
        ? parsed.concepts.filter((c: any) => typeof c === 'string').map((c: string) => c.slice(0, 60)).slice(0, 20)
        : [query];

      const targetKinds = Array.isArray(parsed.targetKinds)
        ? parsed.targetKinds.filter((k: any) => typeof k === 'string' && ALLOWED_TARGET_KINDS.has(k.toUpperCase())).map((k: string) => k.toUpperCase())
        : [];

      let startIso: string | null = null;
      let endIso: string | null = null;
      if (parsed.dateRange && typeof parsed.dateRange === 'object') {
        if (typeof parsed.dateRange.startIso === 'string' && !isNaN(Date.parse(parsed.dateRange.startIso))) {
          startIso = parsed.dateRange.startIso;
        }
        if (typeof parsed.dateRange.endIso === 'string' && !isNaN(Date.parse(parsed.dateRange.endIso))) {
          endIso = parsed.dateRange.endIso;
        }
      }

      return {
        query,
        provenance: 'GEMINI' as const,
        concepts,
        targetKinds,
        dateRange: { startIso, endIso }
      };
    } catch {
      signal?.throwIfAborted();
      return parseLocalSearchPlan(query);
    }
  }
}

export class FakeGeminiProvider implements GeminiProvider {
  async analyze(itemId: string, kind: string, textContent?: string, originalFileName?: string, mediaParts?: MediaPart[]) {
    const cleanText = textContent?.trim() || '';
    const isGrade = cleanText.toLowerCase().includes('grade') || cleanText.includes('/100');
    const desc = isGrade
      ? 'Academic grade record or exam note'
      : (originalFileName ? `Document or media file: ${originalFileName}` : `Saved ${kind.toLowerCase()} item`);
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

  async searchPlan(query: string, _knownCollections: string[], _nowIso: string) {
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
  if (lower.includes('video')) targetKinds.push('VIDEO');

  if (lower.includes('grade') || lower.includes('exam')) concepts.push('grades', 'exam');
  if (lower.includes('university') || lower.includes('college')) concepts.push('university');

  const tokens = query.split(/\s+/).filter(t => t.length > 2);
  for (const t of tokens) {
    if (!concepts.includes(t.toLowerCase())) concepts.push(t.toLowerCase());
  }

  return {
    query,
    provenance: 'LOCAL' as const,
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
  const timestamp = new Date().toISOString();
  console.log(`[${timestamp}] req_id=${reqId} ${method} ${path} status=${statusCode} duration=${durationMs}ms`);
}

// Simple in-memory rate limiter per deviceId
class RateLimiter {
  private requests = new Map<string, number[]>();
  private maxRequestsPerMinute: number;

  constructor(maxRequestsPerMinute: number = 60) {
    this.maxRequestsPerMinute = maxRequestsPerMinute;
  }

  checkRateLimit(key: string): boolean {
    const now = Date.now();
    const windowStart = now - 60 * 1000;
    let timestamps = this.requests.get(key) || [];
    timestamps = timestamps.filter(t => t > windowStart);
    if (timestamps.length >= this.maxRequestsPerMinute) {
      return false; // limited
    }
    timestamps.push(now);
    this.requests.set(key, timestamps);
    return true;
  }
}

export function createServer(config?: Partial<ServerConfig>) {
  const port = config?.port || parseInt(process.env.PORT || '8081', 10);
  const geminiApiKey = config?.geminiApiKey ?? (process.env.GEMINI_API_KEY || '');
  const geminiModel = config?.geminiModel || process.env.GEMINI_MODEL || 'gemini-3.8-flash';
  const rawEmbeddingModel = config?.geminiEmbeddingModel || process.env.GEMINI_EMBEDDING_MODEL;
  const geminiEmbeddingModel = (rawEmbeddingModel && rawEmbeddingModel !== 'text-embedding-004') ? rawEmbeddingModel : 'gemini-embedding-2';
  const maxBodyBytes = config?.maxBodyBytes || 5 * 1024 * 1024; // 5 MB for bounded media support
  const requestTimeoutMs = config?.requestTimeoutMs || 30000;

  // Secrets fail-closed in production
  const sessionSigningSecret = config?.sessionSigningSecret ?? (process.env.SESSION_SIGNING_SECRET || '');
  const deviceEnrollmentSecret = config?.deviceEnrollmentSecret ?? (process.env.DEVICE_ENROLLMENT_SECRET || '');

  const isAuthConfigured = Boolean(sessionSigningSecret && deviceEnrollmentSecret);

  // If secret not configured in test/dev, generate random temporary secret
  const activeSessionSecret = sessionSigningSecret || crypto.randomBytes(32).toString('hex');
  const activeEnrollmentSecret = deviceEnrollmentSecret;

  const registry = new DeviceRegistry(activeSessionSecret, activeEnrollmentSecret);
  const rateLimiter = new RateLimiter(60);

  let provider: GeminiProvider | null = config?.customProvider || null;
  if (!provider && geminiApiKey) {
    provider = new SdkGeminiProvider(geminiApiKey, geminiModel, geminiEmbeddingModel);
  }

  const server = http.createServer(async (req, res) => {
    const controller = new AbortController();
    const startTime = Date.now();
    const reqId = crypto.randomUUID();
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    res.setHeader('X-Request-Id', reqId);
    res.setHeader('Content-Type', 'application/json');

    function sendResponse(statusCode: number, data: any) {
      if (res.writableEnded || res.destroyed) return;
      const durationMs = Date.now() - startTime;
      res.writeHead(statusCode);
      res.end(JSON.stringify(data));
      logSanitized(reqId, req.method || 'GET', pathname, statusCode, durationMs);
    }

    const timer = setTimeout(() => {
      if (!res.writableEnded) {
        controller.abort(new Error('REQUEST_TIMEOUT'));
        sendResponse(504, { error: 'REQUEST_TIMEOUT', message: 'Request exceeded execution deadline' });
      }
    }, requestTimeoutMs);

    res.on('finish', () => clearTimeout(timer));
    res.on('close', () => {
      clearTimeout(timer);
      if (!res.writableEnded) controller.abort(new Error('CLIENT_DISCONNECTED'));
    });
    req.on('aborted', () => controller.abort(new Error('CLIENT_DISCONNECTED')));

    if (pathname === '/health' && req.method === 'GET') {
      return sendResponse(200, {
        status: 'ok',
        service: 'personal-library-backend',
        version: '1.0.0',
        revision: /^[0-9a-f]{40}$/.test(process.env.RENDER_GIT_COMMIT || '') ? process.env.RENDER_GIT_COMMIT : null,
        hasLiveKey: Boolean(geminiApiKey),
        geminiModel,
        geminiEmbeddingModel,
        aiConfigured: Boolean(provider),
        authConfigured: isAuthConfigured
      });
    }

    // Fail closed if auth is not configured for non-health endpoints
    if (!isAuthConfigured && !config?.sessionSigningSecret) {
      return sendResponse(503, {
        error: 'BACKEND_UNCONFIGURED',
        message: 'Auth secrets are not configured on this server.'
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
        const { deviceId, appVersion, challengeNonce, enrollmentSecret, publicKey, signature } = body;
        if (!deviceId || typeof deviceId !== 'string') {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'Missing deviceId' });
        }
        if (!challengeNonce || typeof challengeNonce !== 'string') {
          return sendResponse(400, { error: 'CHALLENGE_REQUIRED', message: 'challengeNonce is mandatory' });
        }
        const { sessionToken, deviceCredential, expiresInSeconds } = registry.enroll(
          deviceId,
          appVersion || '1.0.0',
          challengeNonce,
          enrollmentSecret,
          publicKey,
          signature
        );
        return sendResponse(200, {
          status: 'ENROLLED',
          deviceId,
          sessionToken,
          deviceCredential,
          expiresInSeconds
        });
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'ENROLLMENT_FAILED' });
      }
    }

    // Session renewal: POST /v1/auth/session
    if (pathname === '/v1/auth/session' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { deviceId, deviceCredential, challengeNonce, publicKey, signature } = body;
        if (!deviceId || !deviceCredential) {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'deviceId and deviceCredential required' });
        }
        if (!challengeNonce || typeof challengeNonce !== 'string') {
          return sendResponse(400, { error: 'CHALLENGE_REQUIRED', message: 'challengeNonce is mandatory' });
        }
        const { sessionToken, expiresInSeconds } = registry.renewSession(
          deviceId,
          deviceCredential,
          challengeNonce,
          publicKey,
          signature
        );
        return sendResponse(200, {
          status: 'AUTHENTICATED',
          deviceId,
          sessionToken,
          expiresInSeconds
        });
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message || 'AUTHENTICATION_FAILED' });
      }
    }

    // Revocation: POST /v1/auth/revoke (Authenticated only)
    if (pathname === '/v1/auth/revoke' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { deviceId } = body;
        if (!deviceId) return sendResponse(400, { error: 'INVALID_REQUEST' });

        const deviceIdHeader = req.headers['x-device-id'] as string;
        const authHeader = req.headers['authorization'] as string;
        const token = authHeader?.startsWith('Bearer ') ? authHeader.slice(7) : null;

        const isAdmin = req.headers['x-admin-secret'] === activeEnrollmentSecret && Boolean(activeEnrollmentSecret);
        const isDeviceSelf = deviceIdHeader === deviceId && token && registry.validateSession(deviceId, token);

        if (!isAdmin && !isDeviceSelf) {
          return sendResponse(401, { error: 'UNAUTHORIZED', message: 'Revocation requires valid session or admin authorization' });
        }

        registry.revoke(deviceId);
        return sendResponse(200, { status: 'REVOKED', deviceId });
      } catch (err: any) {
        return sendResponse(err.statusCode || 500, { error: err.message });
      }
    }

    // Authenticated Gate Check for AI Endpoints
    const deviceIdHeader = req.headers['x-device-id'] as string;
    const authHeader = req.headers['authorization'] as string;
    const sessionToken = authHeader?.startsWith('Bearer ') ? authHeader.slice(7) : null;

    if (!deviceIdHeader || !sessionToken || !registry.validateSession(deviceIdHeader, sessionToken)) {
      return sendResponse(401, {
        error: 'UNAUTHORIZED_DEVICE',
        message: 'Valid X-Device-Id and active session token required.'
      });
    }

    // Check device rate limit
    if (!rateLimiter.checkRateLimit(deviceIdHeader)) {
      return sendResponse(429, {
        error: 'RATE_LIMITED',
        message: 'Device exceeded maximum request rate (60 requests/minute).'
      });
    }

    // Check if Gemini provider is configured on server
    if (!provider) {
      return sendResponse(503, {
        error: 'BACKEND_UNCONFIGURED',
        message: 'Gemini API is not configured on this server. Set GEMINI_API_KEY on the backend.'
      });
    }

    // Protected Route: POST /v1/ai/analyze
    if (pathname === '/v1/ai/analyze' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req, maxBodyBytes);
        const { itemId, kind, textContent, originalFileName, mediaParts } = body;
        if (!itemId || !kind) {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'itemId and kind are required' });
        }

        // Validate mediaParts if present
        if (mediaParts) {
          if (!Array.isArray(mediaParts) || mediaParts.length > 6) {
            return sendResponse(400, { error: 'INVALID_REQUEST', message: 'mediaParts must be an array of at most 6 parts' });
          }
          for (const p of mediaParts) {
            if (!p.mimeType || !ALLOWED_MEDIA_MIMES.has(p.mimeType.toLowerCase())) {
              return sendResponse(400, { error: 'UNSUPPORTED_MIME_TYPE', message: `MIME type ${p.mimeType} not supported for media analysis` });
            }
            if (p.dataBase64 && p.dataBase64.length > 2 * 1024 * 1024) {
              return sendResponse(413, { error: 'PAYLOAD_TOO_LARGE', message: 'Individual media part exceeds size limit' });
            }
          }
        }

        controller.signal.throwIfAborted();
        const result = await provider.analyze(itemId, kind, textContent, originalFileName, mediaParts, controller.signal);
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
        for (const t of texts) {
          if (typeof t !== 'string' || t.length > 8192) {
            return sendResponse(400, { error: 'TEXT_TOO_LONG', message: 'Max 8192 characters per embedding text' });
          }
        }
        controller.signal.throwIfAborted();
        const result = await provider.embed(texts, controller.signal);
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
          nowIso || new Date().toISOString(),
          controller.signal
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
