# Personal Library backend

Node/TypeScript Gemini proxy; originals and authoritative indexes stay on Android. Uses `gemini-3.8-flash` and `gemini-embedding-2` with a 768-dimensional contract. No content-library database.

## Setup

`render.yaml`: Node 22, root directory `server`, build `npm ci && npm run build`, start `npm start`. CI uses Node 24 and fake providers; the lockfile determines installed dependencies.

Configure process environment through host secret settings. Never put actual credentials in Git, Android, logs or chat.

| Variable | Purpose |
| --- | --- |
| `GEMINI_API_KEY` | Provider credential |
| `SESSION_SIGNING_SECRET` | Stable auth/challenge signing secret |
| `DEVICE_ENROLLMENT_SECRET` | Operator-controlled initial phone pairing |
| `GEMINI_MODEL` | `gemini-3.8-flash` |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-2` |
| `PORT` | Host port; local default 8081 |
| `REVOKED_DEVICE_IDS` | Optional comma-separated device IDs denied across restarts |

Render already has the three required secrets; preserve them. Start does not automatically load `.env`; provide process environment. Temporary development secrets are unsuitable for stable production identity.

```powershell
npm ci
npm run typecheck
npm test
npm run build
npm start
```

## Auth and bounds

Pair from Android Settings. Enrollment and renewal require nonblank public key and valid P-256 ECDSA proof over a canonical challenge-bound string. Renewal verifies the credential's key fingerprint. Android Keystore fails closed. Stable server secrets preserve signed credentials across restart; challenge replay tracking and the 60 requests/minute/device limiter are in memory. Environment revocations reload on restart. Legacy credentials without a bound fingerprint require re-pairing.

Authenticated routes: `/v1/ai/analyze`, `/v1/ai/embed`, `/v1/ai/search-plan`. Defaults: 5 MiB body limit, 30-second response deadline, provider AbortSignal propagation and 60-second SDK HTTP bound. Cancellation settles body parsing and releases buffered bytes/listeners; timeout flushes one 504 response before closing incomplete ingress. Disconnect/timeout handling avoids duplicate responses; provider-side billing cancellation is not guaranteed. Both sides enforce bounded input and validated output. A legacy `text-embedding-004` environment setting is substituted with `gemini-embedding-2`; Android rejects incompatible returned/stored embedding metadata.

## Deployment checks

[Health](https://personal-library-backend-49w7.onrender.com/health) returns configuration booleans, model names and Render Git revision, never secret values. Require `revision` to match the delivered commit after deployment and anonymous AI requests to return 401. Those checks prove service/configuration and the auth boundary. Live authenticated generation/embedding/search requires the [paired-phone smoke](../docs/HANDOFF.md).

CI fake-provider proof is distinct from live Gemini proof. The unused `server/data/devices.json` was removed because current credentials are stateless server-signed records.
