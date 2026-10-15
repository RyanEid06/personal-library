const { test, describe, before, after } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { createServer } = require('../src/server.js');

describe('Personal Library Backend API', () => {
  let server;
  let baseUrl;
  let deviceId = 'test-android-device-123';
  let sessionToken = '';

  before(async () => {
    server = createServer();
    await new Promise(resolve => {
      server.listen(0, '127.0.0.1', () => {
        const port = server.address().port;
        baseUrl = `http://127.0.0.1:${port}`;
        resolve();
      });
    });
  });

  after(done => {
    server.close(done);
  });

  function request(method, path, body = null, headers = {}) {
    return new Promise((resolve, reject) => {
      const url = new URL(path, baseUrl);
      const reqHeaders = { 'Content-Type': 'application/json', ...headers };
      const req = http.request(url, { method, headers: reqHeaders }, res => {
        let raw = '';
        res.on('data', chunk => raw += chunk);
        res.on('end', () => {
          let parsed;
          try {
            parsed = JSON.parse(raw);
          } catch (_) {
            parsed = raw;
          }
          resolve({ status: res.statusCode, headers: res.headers, body: parsed });
        });
      });
      req.on('error', reject);
      if (body) {
        req.write(typeof body === 'string' ? body : JSON.stringify(body));
      }
      req.end();
    });
  }

  test('GET /health returns 200 OK', async () => {
    const res = await request('GET', '/health');
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'ok');
  });

  test('Protected endpoint /v1/ai/analyze rejects unenrolled device with 401', async () => {
    const res = await request('POST', '/v1/ai/analyze', { itemId: '1', kind: 'TEXT' });
    assert.strictEqual(res.status, 401);
    assert.strictEqual(res.body.error, 'UNAUTHORIZED_DEVICE');
  });

  test('POST /v1/auth/enroll enrolls device and returns session token', async () => {
    const res = await request('POST', '/v1/auth/enroll', { deviceId, appVersion: '1.0.0' });
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'ENROLLED');
    assert.ok(res.body.sessionToken);
    sessionToken = res.body.sessionToken;
  });

  test('POST /v1/ai/analyze succeeds with valid auth', async () => {
    const res = await request(
      'POST',
      '/v1/ai/analyze',
      {
        itemId: 'item_chemistry_grades',
        kind: 'TEXT',
        textContent: 'Chemistry midterm grade: 94/100. Hard exam.'
      },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.itemId, 'item_chemistry_grades');
    assert.ok(res.body.description);
    assert.ok(res.body.concepts.includes('grades'));
  });

  test('POST /v1/ai/embed generates 768-dimensional normalized vectors', async () => {
    const res = await request(
      'POST',
      '/v1/ai/embed',
      {
        texts: ['chemistry syllabus', 'photos of notes']
      },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.dimension, 768);
    assert.strictEqual(res.body.embeddings.length, 2);
    assert.strictEqual(res.body.embeddings[0].length, 768);

    // Verify vector normalization (magnitude ≈ 1.0)
    const v = res.body.embeddings[0];
    const mag = Math.sqrt(v.reduce((sum, x) => sum + x * x, 0));
    assert.ok(Math.abs(mag - 1.0) < 0.05, `Vector magnitude ${mag} should be approx 1.0`);
  });

  test('POST /v1/ai/search-plan returns safe structured plan without SQL', async () => {
    const res = await request(
      'POST',
      '/v1/ai/search-plan',
      {
        query: 'find my chemistry grades from university',
        knownCollections: ['University & Grades', 'Random Links'],
        nowIso: '2026-10-15T15:00:00Z'
      },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
    assert.strictEqual(res.status, 200);
    assert.ok(Array.isArray(res.body.concepts));
    assert.ok(res.body.concepts.includes('grades'));
    assert.ok(res.body.concepts.includes('university'));
    assert.strictEqual(typeof res.body.dateRange, 'object');
  });

  test('Rejects payload exceeding maximum size limit', async () => {
    const hugeString = 'X'.repeat(2.5 * 1024 * 1024); // 2.5 MB
    const res = await request(
      'POST',
      '/v1/ai/analyze',
      { itemId: '1', kind: 'TEXT', textContent: hugeString },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
    assert.strictEqual(res.status, 413);
  });
});
