import { test, describe, before, after } from 'node:test';
import assert from 'node:assert';
import http from 'node:http';
import crypto from 'node:crypto';
import { createServer, DeviceRegistry, FakeGeminiProvider, SdkGeminiProvider, verifyDeviceSignature } from '../src/server.js';

describe('Personal Library Authenticated Backend (TypeScript)', () => {
  let server: http.Server;
  let baseUrl: string;
  const deviceId = 'test-android-keystore-device-999';
  const sessionSecret = 'test-session-secret-constant-123';
  const enrollmentSecret = 'test-enrollment-secret-456';
  let sessionToken = '';
  let deviceCredential = '';

  // Generate test EC keypair (NIST P-256 / secp256r1) matching Android Keystore
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1'
  });
  const pubKeySpkiDer = publicKey.export({ type: 'spki', format: 'der' });
  const pubKeyBase64 = pubKeySpkiDer.toString('base64');

  function signData(canonicalString: string): string {
    const signer = crypto.createSign('SHA256');
    signer.update(Buffer.from(canonicalString, 'utf-8'));
    return signer.sign(privateKey).toString('base64');
  }

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

  test('POST /v1/auth/enroll rejects missing challengeNonce', async () => {
    const res = await request('POST', '/v1/auth/enroll', {
      deviceId,
      appVersion: '1.0.0',
      enrollmentSecret,
      publicKey: pubKeyBase64
    });
    assert.strictEqual(res.status, 400);
    assert.strictEqual(res.body.error, 'CHALLENGE_REQUIRED');
  });

  test('POST /v1/auth/enroll rejects missing or wrong enrollment secret', async () => {
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    // Missing secret
    const resMissing = await request('POST', '/v1/auth/enroll', {
      deviceId,
      challengeNonce: nonce,
      publicKey: pubKeyBase64
    });
    assert.strictEqual(resMissing.status, 401);

    // Wrong secret
    const chRes2 = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const resWrong = await request('POST', '/v1/auth/enroll', {
      deviceId,
      challengeNonce: chRes2.body.nonce,
      enrollmentSecret: 'wrong-secret',
      publicKey: pubKeyBase64
    });
    assert.strictEqual(resWrong.status, 401);
  });

  test('POST /v1/auth/enroll rejects invalid ECDSA signature', async () => {
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    const res = await request('POST', '/v1/auth/enroll', {
      deviceId,
      appVersion: '1.0.0',
      challengeNonce: nonce,
      enrollmentSecret,
      publicKey: pubKeyBase64,
      signature: 'invalid_base64_signature_here'
    });
    assert.strictEqual(res.status, 401);
    assert.strictEqual(res.body.error, 'INVALID_DEVICE_SIGNATURE');
  });

  for (const missingSignature of [undefined, '', '   ']) {
    test(`enrollment rejects absent or blank signature (${JSON.stringify(missingSignature)})`, async () => {
      const challenge = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
      const res = await request('POST', '/v1/auth/enroll', {
        deviceId, appVersion: '1.0.0', enrollmentSecret, publicKey: pubKeyBase64,
        challengeNonce: challenge.body.nonce, signature: missingSignature
      });
      assert.strictEqual(res.status, 400);
      assert.strictEqual(res.body.error, 'SIGNATURE_REQUIRED');
    });
  }

  test('POST /v1/auth/enroll successfully enrolls device with valid ECDSA signature and returns 1800s session', async () => {
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    const canonicalString = `PLIB-ENROLL-V1\n${deviceId}\n${nonce}\n1.0.0\n${pubKeyBase64}`;
    const signature = signData(canonicalString);

    const res = await request('POST', '/v1/auth/enroll', {
      deviceId,
      appVersion: '1.0.0',
      challengeNonce: nonce,
      enrollmentSecret,
      publicKey: pubKeyBase64,
      signature
    });
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'ENROLLED');
    assert.ok(res.body.sessionToken);
    assert.ok(res.body.deviceCredential);
    assert.strictEqual(res.body.expiresInSeconds, 1800);
    sessionToken = res.body.sessionToken;
    deviceCredential = res.body.deviceCredential;
  });

  test('POST /v1/auth/enroll rejects challenge nonce replay', async () => {
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    const canonicalString = `PLIB-ENROLL-V1\n${deviceId}\n${nonce}\n1.0.0\n${pubKeyBase64}`;
    const signature = signData(canonicalString);

    const res1 = await request('POST', '/v1/auth/enroll', {
      deviceId,
      challengeNonce: nonce,
      enrollmentSecret,
      publicKey: pubKeyBase64,
      signature
    });
    assert.strictEqual(res1.status, 200);

    // Replaying same nonce
    const res2 = await request('POST', '/v1/auth/enroll', {
      deviceId,
      challengeNonce: nonce,
      enrollmentSecret,
      publicKey: pubKeyBase64,
      signature
    });
    assert.strictEqual(res2.status, 401);
    assert.strictEqual(res2.body.error, 'INVALID_CHALLENGE');
  });

  test('POST /v1/auth/session renews session token using device credential and Keystore signature', async () => {
    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    const canonicalString = `PLIB-SESSION-V1\n${deviceId}\n${nonce}\n${deviceCredential}`;
    const signature = signData(canonicalString);

    const res = await request('POST', '/v1/auth/session', {
      deviceId,
      deviceCredential,
      challengeNonce: nonce,
      publicKey: pubKeyBase64,
      signature
    });
    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'AUTHENTICATED');
    assert.ok(res.body.sessionToken);
    assert.strictEqual(res.body.expiresInSeconds, 1800);
    sessionToken = res.body.sessionToken;
  });

  test('POST /v1/auth/session rejects renewal when public key fingerprint does not match credential', async () => {
    const { publicKey: otherPub, privateKey: otherPriv } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const otherPubB64 = otherPub.export({ type: 'spki', format: 'der' }).toString('base64');

    const chRes = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const nonce = chRes.body.nonce;

    const canonicalString = `PLIB-SESSION-V1\n${deviceId}\n${nonce}\n${deviceCredential}`;
    const signature = crypto.sign('sha256', Buffer.from(canonicalString), otherPriv).toString('base64');

    const res = await request('POST', '/v1/auth/session', {
      deviceId,
      deviceCredential,
      challengeNonce: nonce,
      publicKey: otherPubB64,
      signature
    });
    assert.strictEqual(res.status, 401);
    assert.strictEqual(res.body.error, 'DEVICE_CREDENTIAL_KEY_MISMATCH');
  });

  for (const field of ['publicKey', 'signature'] as const) {
    for (const missing of [undefined, '', '   ']) {
      test(`renewal rejects missing or blank ${field} (${JSON.stringify(missing)})`, async () => {
        const challenge = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
        const nonce = challenge.body.nonce;
        const body = {
          deviceId, deviceCredential, challengeNonce: nonce, publicKey: pubKeyBase64,
          signature: signData(`PLIB-SESSION-V1\n${deviceId}\n${nonce}\n${deviceCredential}`)
        };
        body[field] = missing as string;
        const res = await request('POST', '/v1/auth/session', body);
        assert.strictEqual(res.status, 400);
        assert.strictEqual(res.body.error, field === 'publicKey' ? 'PUBLIC_KEY_REQUIRED' : 'SIGNATURE_REQUIRED');
      });
    }
  }

  test('renewal rejects invalid signature', async () => {
    const challenge = await request('GET', `/v1/auth/challenge?deviceId=${deviceId}`);
    const res = await request('POST', '/v1/auth/session', {
      deviceId, deviceCredential, challengeNonce: challenge.body.nonce,
      publicKey: pubKeyBase64, signature: 'invalid'
    });
    assert.strictEqual(res.status, 401);
    assert.strictEqual(res.body.error, 'INVALID_DEVICE_SIGNATURE');
  });

  test('Stateless DeviceRegistry survives server restarts without persistent disk state', () => {
    const rebootedRegistry = new DeviceRegistry(sessionSecret, enrollmentSecret);
    assert.strictEqual(rebootedRegistry.validateSession(deviceId, sessionToken), true);
    assert.strictEqual(rebootedRegistry.verifyDeviceCredential(deviceId, deviceCredential).valid, true);

    const nonce = rebootedRegistry.createChallenge(deviceId);
    const signature = signData(`PLIB-SESSION-V1\n${deviceId}\n${nonce}\n${deviceCredential}`);
    const fresh = rebootedRegistry.renewSession(deviceId, deviceCredential, nonce, pubKeyBase64, signature);
    assert.ok(fresh.sessionToken);
    assert.strictEqual(rebootedRegistry.validateSession(deviceId, fresh.sessionToken), true);
    assert.throws(() => rebootedRegistry.renewSession(deviceId, deviceCredential, nonce, pubKeyBase64, signature), /INVALID_CHALLENGE/);
    assert.throws(() => rebootedRegistry.renewSession(deviceId, deviceCredential, 'forged', pubKeyBase64, signature), /INVALID_CHALLENGE/);
  });

  test('challenge parser rejects alternate encodings and malformed timestamp suffixes', () => {
    const registry = new DeviceRegistry(sessionSecret, enrollmentSecret);
    const nonce = registry.createChallenge(deviceId);
    const [ts, random, mac] = nonce.split('.');
    assert.strictEqual(registry.verifyAndConsumeChallenge(deviceId, `${ts}junk.${random}.${mac}`), false);
    assert.strictEqual(registry.verifyAndConsumeChallenge(deviceId, `${ts}.${random}.${mac.toUpperCase()}`), false);
    assert.strictEqual(registry.verifyAndConsumeChallenge(deviceId, nonce), true);
    assert.strictEqual(registry.verifyAndConsumeChallenge(deviceId, nonce), false);
  });

  test('POST /v1/ai/analyze succeeds with authenticated device session and media parts', async () => {
    const res = await request(
      'POST',
      '/v1/ai/analyze',
      {
        itemId: 'item_chem_101',
        kind: 'TEXT',
        textContent: 'Chemistry midterm grade: 94/100. Hard exam.',
        originalFileName: 'midterm_chem.txt',
        mediaParts: [
          {
            mimeType: 'image/jpeg',
            dataBase64: Buffer.from('fake_image_bytes').toString('base64')
          }
        ]
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

  test('POST /v1/auth/revoke invalidates device and session when authenticated', async () => {
    const revRes = await request(
      'POST',
      '/v1/auth/revoke',
      { deviceId },
      {
        'X-Device-Id': deviceId,
        'Authorization': `Bearer ${sessionToken}`
      }
    );
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

describe('Production Server without Gemini Key or Secrets', () => {
  let server: http.Server;
  let baseUrl: string;

  before(async () => {
    const app = createServer({
      geminiApiKey: '',
      sessionSigningSecret: 'test-session-secret',
      deviceEnrollmentSecret: ''
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

  after(done => server.close(done));

  test('Health endpoint reports authConfigured false if enrollment secret missing', async () => {
    const res = await fetch(`${baseUrl}/health`);
    const body = await res.json() as any;
    assert.strictEqual(res.status, 200);
    assert.strictEqual(body.authConfigured, false);
  });
});

describe('Request cancellation', () => {
  test('deadline aborts the provider and responds only with a timeout', async () => {
    let aborted = false;
    class WaitingProvider extends FakeGeminiProvider {
      override async analyze(_id: string, _kind: string, _text?: string, _name?: string, _parts?: unknown[], signal?: AbortSignal): Promise<any> {
        return new Promise((_resolve, reject) => {
          signal?.addEventListener('abort', () => { aborted = true; reject(new Error('aborted')); }, { once: true });
        });
      }
    }
    const app = createServer({
      customProvider: new WaitingProvider(), requestTimeoutMs: 80,
      sessionSigningSecret: 'cancellation-test-session', deviceEnrollmentSecret: 'cancellation-test-enroll'
    });
    await new Promise<void>(resolve => app.server.listen(0, '127.0.0.1', resolve));
    const address = app.server.address() as any;
    try {
      const token = app.registry.createSessionToken('cancel-test');
      const response = await fetch(`http://127.0.0.1:${address.port}/v1/ai/analyze`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Device-Id': 'cancel-test', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ itemId: 'synthetic', kind: 'TEXT' })
      });
      assert.strictEqual(response.status, 504);
      assert.strictEqual((await response.json() as any).error, 'REQUEST_TIMEOUT');
      assert.strictEqual(aborted, true, 'provider must be aborted before the timeout response is sent');
    } finally {
      app.server.closeAllConnections();
      await new Promise<void>(resolve => app.server.close(() => resolve()));
    }
  });
});

describe('Search plan provenance', () => {
  test('successful SDK plan identifies Gemini provenance', async () => {
    const provider = new SdkGeminiProvider('synthetic-key', 'test-generation', 'test-embedding');
    (provider as any).ai = { models: { generateContent: async () => ({ text: JSON.stringify({ concepts: ['chemistry'], targetKinds: ['TEXT'] }) }) } };
    const plan = await provider.searchPlan('chemistry', [], '2026-10-15T00:00:00Z');
    assert.strictEqual((plan as any).provenance, 'GEMINI');
  });
  test('provider failure identifies deterministic local plan', async () => {
    const provider = new SdkGeminiProvider('synthetic-key', 'test-generation', 'test-embedding');
    (provider as any).ai = { models: { generateContent: async () => { throw new Error('synthetic provider unavailable'); } } };
    const plan = await provider.searchPlan('chemistry', [], '2026-10-15T00:00:00Z');
    assert.strictEqual((plan as any).provenance, 'LOCAL');
  });
});
