import { test, describe, before, after } from 'node:test';
import assert from 'node:assert';
import http from 'node:http';
import { createServer, DeviceRegistry, FakeGeminiProvider } from '../src/server.js';

describe('Personal Library Authenticated Backend (TypeScript)', () => {
  let server: http.Server;
  let baseUrl: string;
  const deviceId = 'test-android-keystore-device-999';
  const sessionSecret = 'test-session-secret-constant-123';
  const enrollmentSecret = 'test-enrollment-secret-456';
  let sessionToken = '';
  let deviceCredential = '';

  before(async () => {
    const app = createServer({
      customProvider: new FakeGeminiProvider(),
      requestTimeoutMs: 2000,
      sessionSigningSecret: sessionSecret,
      deviceEnrollmentSecret: enrollmentSecret
    });
    server = app.server;
    await new Promise<void>(resolve => {
      server.listen(0, '127.0.0.1', () => {
        const addr = server.address() as any;
        baseUrl = `http://127.0.0.1:${addr.port}`;
        resolve();
      });
    });
  });

  after(done => {
    server.close(done);
  });

  function request(method: string, path: string, body: any = null, headers: Record<string, string> = {}): Promise<{ status: number; headers: any; body: any }> {
    return new Promise((resolve, reject) => {
      const url = new URL(path, baseUrl);
      const reqHeaders: Record<string, string> = { 'Content-Type': 'application/json', ...headers };
      const req = http.request(url, { method, headers: reqHeaders }, res => {
        let raw = '';
        res.on('data', chunk => raw += chunk);
        res.on('end', () => {
          let parsed: any;
          try {
            parsed = JSON.parse(raw);
          } catch {
            parsed = raw;
          }
          resolve({ status: res.statusCode || 500, headers: res.headers, body: parsed });
        });
      });
      req.on('error', reject);
      if (body) {
        req.write(typeof body === 'string' ? body : JSON.stringify(body));
      }
      req.end();
    });
  }

  test('GET /health returns 200 with service information and gemini-embedding-2', async () => {
    const res = await request('GET', '/health');
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'ok');
    assert.strictEqual(res.body.service, 'personal-library-backend');
    assert.strictEqual(res.body.geminiModel, 'gemini-3.8-flash');
    assert.strictEqual(res.body.geminiEmbeddingModel, 'gemini-embedding-2');
  });

  test('Protected endpoint /v1/ai/analyze rejects unauthenticated request with 401', async () => {
    const res = await request('POST', '/v1/ai/analyze', { itemId: '1', kind: 'TEXT' });
    assert.strictEqual(res.status, 401);
    assert.strictEqual(res.body.error, 'UNAUTHORIZED_DEVICE');
  });

  test('GET /v1/auth/challenge returns cryptographic nonce for device', async () => {
    const res = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.deviceId, deviceId);
    assert.ok(res.body.nonce);
    assert.strictEqual(typeof res.body.nonce, 'string');
  });

  test('POST /v1/auth/enroll successfully enrolls device and returns session token and device credential', async () => {
    // 1. Get challenge
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    // 2. Enroll with challenge and enrollment secret
    const res = await request('POST', '/v1/auth/enroll', {
      deviceId,
      appVersion: '1.0.0',
      challengeNonce: nonce,
      enrollmentSecret
    });
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'ENROLLED');
    assert.ok(res.body.sessionToken);
    assert.ok(res.body.deviceCredential);
    sessionToken = res.body.sessionToken;
    deviceCredential = res.body.deviceCredential;
  });

  test('POST /v1/auth/session renews session token using device credential', async () => {
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    const res = await request('POST', '/v1/auth/session', {
      deviceId,
      deviceCredential,
      challengeNonce: nonce
    });
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'AUTHENTICATED');
    assert.ok(res.body.sessionToken);
    // Use renewed session token
    sessionToken = res.body.sessionToken;
  });

  test('Stateless DeviceRegistry survives server restarts without persistent disk state', () => {
    // Create completely fresh registry instance simulating cold start/reboot with same secret
    const rebootedRegistry = new DeviceRegistry(sessionSecret, enrollmentSecret);
    assert.strictEqual(rebootedRegistry.validateSession(deviceId, sessionToken), true);
    assert.strictEqual(rebootedRegistry.verifyDeviceCredential(deviceId, deviceCredential), true);

    // Can issue new sessions on rebooted instance
    const freshToken = rebootedRegistry.renewSession(deviceId, deviceCredential);
    assert.ok(freshToken);
    assert.strictEqual(rebootedRegistry.validateSession(deviceId, freshToken), true);
  });

  test('POST /v1/ai/analyze succeeds with authenticated device session', async () => {
    const res = await request(
      'POST',
      '/v1/ai/analyze',
      {
        itemId: 'item_chem_101',
        kind: 'TEXT',
        textContent: 'Chemistry midterm grade: 94/100. Hard exam.',
        originalFileName: 'midterm_chem.txt'
      },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.itemId, 'item_chem_101');
    assert.ok(res.body.description);
    assert.ok(res.body.concepts.includes('grades'));
  });

  test('POST /v1/ai/embed returns 768-dimensional normalized vectors', async () => {
    const res = await request(
      'POST',
      '/v1/ai/embed',
      {
        texts: ['chemistry syllabus and grades', 'whatsapp photo notes']
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

    // Verify vector magnitude is normalized
    const v = res.body.embeddings[0];
    const mag = Math.sqrt(v.reduce((sum: number, x: number) => sum + x * x, 0));
    assert.ok(Math.abs(mag - 1.0) < 0.05, `Vector magnitude ${mag} should be approx 1.0`);
  });

  test('POST /v1/ai/search-plan returns safe allowlisted query plan without SQL', async () => {
    const res = await request(
      'POST',
      '/v1/ai/search-plan',
      {
        query: 'chemistry exam grades from university photo',
        knownCollections: ['University & Grades', 'Random Links'],
        nowIso: '2026-10-15T12:00:00Z'
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
    assert.ok(res.body.targetKinds.includes('PHOTO'));
    assert.strictEqual(typeof res.body.dateRange, 'object');
  });

  test('Rejects oversized body with 413 Payload Too Large', async () => {
    const huge = 'Z'.repeat(2.5 * 1024 * 1024);
    try {
      const res = await request(
        'POST',
        '/v1/ai/analyze',
        { itemId: 'huge', kind: 'TEXT', textContent: huge },
        {
          'X-Device-Id': deviceId,
          'Authorization': `Bearer ${sessionToken}`
        }
      );
      assert.strictEqual(res.status, 413);
    } catch (err: any) {
      assert.ok(err.code === 'ECONNRESET' || err.message.includes('ECONNRESET'));
    }
  });

  test('POST /v1/auth/revoke invalidates device and session', async () => {
    const revRes = await request('POST', '/v1/auth/revoke', { deviceId });
    assert.strictEqual(revRes.status, 200);

    // Subsequent authenticated request fails with 401
    const testRes = await request(
      'POST',
      '/v1/ai/analyze',
      { itemId: '2', kind: 'TEXT' },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
    assert.strictEqual(testRes.status, 401);
  });
});
