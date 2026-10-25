# Personal Library backend

Node/TypeScript Gemini proxy; originals and authoritative indexes stay on Android. Uses `gemini-3.8-flash` and `gemini-embedding-2` with a 768-dimensional contract. No content-library database.

## Setup

`render.yaml`: Node 22, root directory `server`, build `npm ci && npm run build`, start `npm start`. CI uses Node 24 and fake providers; the lockfile determines installed dependencies.

Configure process environment through host secret settings. Never put actual credentials in Git, Android, logs or chat.

| Variable | Purpose |
| --- | --- |
| `GEMINI_API_KEY` | Provider credential |
| `SESSION_SIGNING_SECRET` | Stable auth/challenge signing secret |
| `DEVICE_ENROLLMENT_SECRET` | Optional legacy operator pairing/admin secret; preserve existing value for credential compatibility |
| `GEMINI_MODEL` | `gemini-3.8-flash` |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-2` |
| `PORT` | Host port; local default 8081 |
| `REVOKED_DEVICE_IDS` | Optional comma-separated device IDs denied across restarts |
| `TRUSTED_PROXY_IPS` | Optional exact socket-peer IP allowlist; only these adjacent proxies may supply the final X-Forwarded-For IP |

Preserve existing host secrets. New automatic registration needs `SESSION_SIGNING_SECRET` and the provider key; the optional legacy enrollment secret must remain stable when already in use. Start does not automatically load `.env`; provide process environment. Temporary development secrets are unsuitable for stable production identity.

```powershell
npm ci
npm run typecheck
npm test
npm run build
npm start
```

## Auth and bounds

Android automatically calls public `POST /v1/auth/register` after privacy/network eligibility. Its JSON contains only `deviceId`, `appVersion`, `challengeNonce`, `publicKey` and `signature`; no account or shared secret is required. Proof signs `PLIB-REGISTER-V1\n<deviceId>\n<nonce>\n<appVersion>\n<publicKey>` with the device's P-256 key. Registration returns the existing credential and 30-minute session response. Secret-based `/v1/auth/enroll` remains compatible and signs the separate `PLIB-ENROLL-V1` domain. Renewal verifies the credential's key fingerprint and uses the unchanged `PLIB-SESSION-V1` protocol. Android Keystore fails closed. Stable server secrets preserve signed credentials across restart. Environment revocations reload on restart.

All auth endpoints allow 120 requests/minute/IP. Public registration additionally permits 12/minute/IP, 6/minute/public-key fingerprint and 120/minute/server; its body is at most 8 KiB. Provider requests retain 60/minute/device and additionally share 120 provider calls/minute/server and 1,000/day/server plus four concurrent request slots. An embedding batch reserves one unit per text because it invokes the provider separately for each text. A provider that ignores abort retains its slot until it settles, including after an HTTP timeout. Rate maps admit at most 10,000 live identities; the consumed-nonce ledger and runtime revocations cap at 20,000 and reject new admissions when full. Limits and nonce replay history are process-local and reset on restart; this is bounded abuse protection, not device/app attestation. Multiple instances have independent limits. No new paid service is required.

Forwarded IP headers are ignored by default. Configure only verified adjacent proxy IPs in `TRUSTED_PROXY_IPS` when hosting behind a proxy; an untrusted caller's spoofed header cannot create a new rate bucket. Without this configuration, users behind one proxy share its IP budget. Keep persistent revoked IDs in `REVOKED_DEVICE_IDS`.

Authenticated routes: `/v1/ai/analyze`, `/v1/ai/embed`, `/v1/ai/search-plan`, `/v1/ai/answer`. Defaults: 5 MiB body limit, 30-second response deadline, provider AbortSignal propagation and 60-second SDK HTTP bound. Cancellation settles body parsing and releases buffered bytes/listeners; timeout flushes one 504 response before closing incomplete ingress. Oversized uploads receive 413 before the connection closes. Disconnect/timeout handling avoids duplicate responses; provider-side billing cancellation is not guaranteed. Both sides enforce bounded input and validated output. A legacy `text-embedding-004` environment setting is substituted with `gemini-embedding-2`; Android rejects incompatible returned/stored embedding metadata.

Interactive search planning runs before Android selects its bounded candidates. It sends only the question, an empty collection-name list and current timestamp; names, cached item text and originals remain local until eligible answer evidence is selected. Planning accepts a nonblank question of at most 2,000 UTF-16 units, at most 50 collection names of 200 units for legacy clients, a valid optional timestamp and a 64 KiB body. Gemini gets a 1,024-output-token budget and instructions to retain names and expand abbreviations such as birthday/bday. Returned concepts are capped at 20 of 60 units each; kinds are deduplicated and allowlisted. Android applies bounded expanded terms to candidate, chunk and answer-evidence ranking before their respective caps. Provider availability and generated synonyms still require live acceptance.

## Bounded question answering

`POST /v1/ai/answer` uses the same device session, 60 requests/minute/device limiter and request deadline. Android retrieves and filters cached evidence locally before dispatch; this endpoint has no library database and receives no originals. Request shape:

```json
{
  "question": "When is Sarah's birthday?",
  "evidence": [
    { "sourceId": "s1", "itemRef": "i1", "title": "Birthday note", "text": "Sarah's birthday is August 19.", "pageNumber": 2 }
  ]
}
```

`sourceId` and `itemRef` are opaque request-local references; the client retains their mapping to stable local items and pages. Never send real stable item IDs. As defense in depth, the server replaces both reference fields with fresh random provider-only tokens per request, preserves item grouping and page metadata, validates citations only against those replacement source tokens, then maps valid citations back for the client response. Client-supplied reference values never reach the provider. Unknown top-level/evidence fields, including item IDs and media, are rejected. Limits are inclusive:

| Field/budget | Limit |
| --- | --- |
| Serialized body | 65,536 bytes, or the smaller configured global body limit |
| Question | Nonblank, 2,000 UTF-16 code units |
| Evidence | 1–12 chunks; at most 3 per `itemRef` |
| `sourceId` / `itemRef` | Nonblank, 64 UTF-16 code units each; unique `sourceId` |
| Title | Nonblank, 200 UTF-16 code units |
| Chunk text | Nonblank, 1,200 UTF-16 code units |
| All evidence text | 12,000 UTF-16 code units and 32,768 UTF-8 bytes |
| `pageNumber` | Optional/null, otherwise a positive safe integer |

Response shape is exactly `{ "status": "ANSWERED", "answer": "Sarah's birthday appears to be August 19.", "sourceIds": ["s1"] }`. Answer length is at most 4,000 UTF-16 code units. Cited IDs must be unique, at most 12, and present in this request's evidence. `ANSWERED` requires nonblank text and at least one citation; `CONFLICTING` requires nonblank text and at least two citations. `INSUFFICIENT` returns exactly an empty answer and empty citations; the client displays fixed local wording.

Provider instructions treat the question and evidence as untrusted quoted data, restrict answers to supplied evidence and require explicit insufficiency/conflict handling. The endpoint validates the output structure and citation allowlist; those checks do not prove factual grounding. Malformed/provider-error responses return a fixed 502 `ANSWER_PROVIDER_UNAVAILABLE` without provider error bodies or private payloads. Input violations return 400, oversized bodies 413, rate limits 429 and deadlines 504. Logs contain request metadata only. SDK generation uses the installed Gemini client, a 2,048 output-token budget and AbortSignal; raw answer JSON is bounded to 32 KiB before parsing. Fake-provider output is deterministically `INSUFFICIENT`; tests provide synthetic grounded/conflicting responses.

## Deployment checks

[Health](https://personal-library-backend-49w7.onrender.com/health) returns configuration booleans, model names and Render Git revision, never secret values. Require `revision` to match the delivered commit after deployment and anonymous AI requests to return 401. Those checks prove service/configuration and the auth boundary. Live authenticated generation/embedding/search requires the [paired-phone smoke](../docs/HANDOFF.md).

CI fake-provider proof is distinct from live Gemini proof. The unused `server/data/devices.json` was removed because current credentials are stateless server-signed records.
