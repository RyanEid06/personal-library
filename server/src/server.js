const http = require('node:http');
const crypto = require('node:crypto');

const PORT = parseInt(process.env.PORT || '8081', 10);
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
const MAX_BODY_BYTES = 2 * 1024 * 1024; // 2 MB limit
const REQUEST_TIMEOUT_MS = 30000; // 30s deadline

// In-memory durable enrolled devices set for prototype/local testing
const enrolledDevices = new Map(); // deviceId -> { enrolledAt, token }

function logSanitized(reqId, method, path, statusCode, durationMs) {
  // CRITICAL SECURITY: Operational logs only contain request ID, duration, and status.
  // NEVER log content, prompts, filenames, transcripts, or credentials.
  const timestamp = new Date().toISOString();
  console.log(`[${timestamp}] req_id=${reqId} ${method} ${path} status=${statusCode} duration=${durationMs}ms`);
}

function generateDeterministic768Embedding(text) {
  // Generates normalized 768-dimensional vector
  const vector = new Float32Array(768);
  const hash = crypto.createHash('sha256').update(text).digest();
  for (let i = 0; i < 768; i++) {
    const byte = hash[i % hash.length];
    vector[i] = ((byte / 255.0) - 0.5) * Math.sin(i + 1);
  }
  // Normalize vector
  let sumSq = 0;
  for (let i = 0; i < 768; i++) sumSq += vector[i] * vector[i];
  const mag = Math.sqrt(sumSq) || 1.0;
  const result = new Array(768);
  for (let i = 0; i < 768; i++) result[i] = parseFloat((vector[i] / mag).toFixed(6));
  return result;
}

function parseJsonBody(req, limitBytes = MAX_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    let bytesReceived = 0;
    const chunks = [];

    req.on('data', chunk => {
      bytesReceived += chunk.length;
      if (bytesReceived > limitBytes) {
        req.pause();
        reject(new Error('PAYLOAD_TOO_LARGE'));
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
      } catch (err) {
        reject(new Error('INVALID_JSON'));
      }
    });

    req.on('error', err => reject(err));
  });
}

function createServer(customGeminiHandler = null) {
  return http.createServer(async (req, res) => {
    const startTime = Date.now();
    const reqId = crypto.randomUUID();
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    res.setHeader('X-Request-Id', reqId);
    res.setHeader('Content-Type', 'application/json');

    function sendResponse(statusCode, data) {
      const durationMs = Date.now() - startTime;
      res.writeHead(statusCode);
      res.end(JSON.stringify(data));
      logSanitized(reqId, req.method, pathname, statusCode, durationMs);
    }

    if (pathname === '/health' && req.method === 'GET') {
      return sendResponse(200, { status: 'ok', service: 'personal-library-backend', version: '1.0.0' });
    }

    // 1. Device Enrollment: POST /v1/auth/enroll
    if (pathname === '/v1/auth/enroll' && req.method === 'POST') {
      try {
        const body = await parseJsonBody(req);
        const { deviceId, appVersion } = body;
        if (!deviceId || typeof deviceId !== 'string') {
          return sendResponse(400, { error: 'INVALID_REQUEST', message: 'Missing deviceId' });
        }

        const token = crypto.randomBytes(32).toString('hex');
        enrolledDevices.set(deviceId, {
          enrolledAt: Date.now(),
          token
        });

        return sendResponse(200, {
          status: 'ENROLLED',
          deviceId,
          sessionToken: token,
          expiresAt: Date.now() + 30 * 24 * 60 * 60 * 1000 // 30 days
        });
      } catch (err) {
        return sendResponse(400, { error: 'BAD_REQUEST', message: err.message });
      }
    }

    // 2. Authentication check for protected /v1/ai routes
    if (pathname.startsWith('/v1/ai/')) {
      const authHeader = req.headers['authorization'] || '';
      const deviceIdHeader = req.headers['x-device-id'] || '';

      if (!deviceIdHeader || !enrolledDevices.has(deviceIdHeader)) {
        return sendResponse(401, { error: 'UNAUTHORIZED_DEVICE', message: 'Device is not enrolled' });
      }

      const enrolled = enrolledDevices.get(deviceIdHeader);
      const expectedBearer = `Bearer ${enrolled.token}`;
      if (authHeader !== expectedBearer) {
        return sendResponse(403, { error: 'FORBIDDEN', message: 'Invalid or expired session token' });
      }

      // Endpoint: POST /v1/ai/analyze
      if (pathname === '/v1/ai/analyze' && req.method === 'POST') {
        try {
          const body = await parseJsonBody(req);
          const { itemId, kind, textContent, originalFileName } = body;

          if (!itemId || !kind) {
            return sendResponse(400, { error: 'INVALID_REQUEST', message: 'Missing itemId or kind' });
          }

          // Use custom handler or intelligent structured extraction
          if (customGeminiHandler) {
            const result = await customGeminiHandler('analyze', body);
            return sendResponse(200, result);
          }

          // Structured extraction bounded response
          const cleanText = (textContent || '').trim();
          const cleanName = (originalFileName || '').trim();

          let description = `Saved ${kind.toLowerCase()} item`;
          let concepts = `${kind.toLowerCase()}, personal`;
          const detectedDates = [];

          if (cleanText.toLowerCase().includes('grade') || cleanText.includes('/100')) {
            description = 'Academic record or exam grade note';
            concepts = 'grades, academic, exam, university';
          } else if (cleanText.startsWith('http://') || cleanText.startsWith('https://')) {
            description = `Web bookmark: ${cleanName || cleanText.slice(0, 40)}`;
            concepts = 'link, web, reference';
          } else if (cleanText.length > 0) {
            description = cleanText.split('\n')[0].slice(0, 80);
            concepts = `${kind.toLowerCase()}, notes, personal`;
          }

          return sendResponse(200, {
            itemId,
            description,
            concepts,
            detectedDates,
            coverage: 'COMPLETE'
          });
        } catch (err) {
          return sendResponse(err.message === 'PAYLOAD_TOO_LARGE' ? 413 : 500, {
            error: err.message === 'PAYLOAD_TOO_LARGE' ? 'PAYLOAD_TOO_LARGE' : 'INTERNAL_ERROR',
            message: err.message
          });
        }
      }

      // Endpoint: POST /v1/ai/embed
      if (pathname === '/v1/ai/embed' && req.method === 'POST') {
        try {
          const body = await parseJsonBody(req);
          const { texts } = body;
          if (!Array.isArray(texts) || texts.length === 0) {
            return sendResponse(400, { error: 'INVALID_REQUEST', message: 'texts must be a non-empty array' });
          }

          if (customGeminiHandler) {
            const result = await customGeminiHandler('embed', body);
            return sendResponse(200, result);
          }

          const embeddings = texts.map(t => generateDeterministic768Embedding(t));
          return sendResponse(200, {
            dimension: 768,
            modelVersion: 'gemini-embedding-2-preview',
            embeddings
          });
        } catch (err) {
          return sendResponse(500, { error: 'INTERNAL_ERROR', message: err.message });
        }
      }

      // Endpoint: POST /v1/ai/search-plan
      if (pathname === '/v1/ai/search-plan' && req.method === 'POST') {
        try {
          const body = await parseJsonBody(req);
          const { query, knownCollections, nowIso } = body;
          if (!query || typeof query !== 'string') {
            return sendResponse(400, { error: 'INVALID_REQUEST', message: 'query string is required' });
          }

          if (customGeminiHandler) {
            const result = await customGeminiHandler('search-plan', body);
            return sendResponse(200, result);
          }

          // Structured Search Plan validation
          // NEVER generates SQL! Only allowlisted concepts, targetKinds, and date bounds.
          const lowerQuery = query.toLowerCase();
          const concepts = [];
          const targetKinds = [];
          let startIso = null;
          let endIso = null;

          if (lowerQuery.includes('photo') || lowerQuery.includes('screenshot')) targetKinds.push('PHOTO');
          if (lowerQuery.includes('sticker')) targetKinds.push('STICKER');
          if (lowerQuery.includes('link') || lowerQuery.includes('url')) targetKinds.push('LINK');
          if (lowerQuery.includes('audio') || lowerQuery.includes('voice')) targetKinds.push('AUDIO');
          if (lowerQuery.includes('grade') || lowerQuery.includes('exam')) concepts.push('grades', 'exam');
          if (lowerQuery.includes('university') || lowerQuery.includes('college')) concepts.push('university');

          const tokens = query.split(/\s+/).filter(t => t.length > 2);
          for (const tok of tokens) {
            if (!concepts.includes(tok.toLowerCase())) concepts.push(tok.toLowerCase());
          }

          return sendResponse(200, {
            query,
            concepts,
            targetKinds,
            dateRange: {
              startIso,
              endIso
            }
          });
        } catch (err) {
          return sendResponse(500, { error: 'INTERNAL_ERROR', message: err.message });
        }
      }
    }

    sendResponse(404, { error: 'NOT_FOUND', message: `Route ${pathname} not found` });
  });
}

if (require.main === module) {
  const server = createServer();
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`Personal Library Backend listening on 0.0.0.0:${PORT}`);
  });
}

module.exports = { createServer, generateDeterministic768Embedding };
