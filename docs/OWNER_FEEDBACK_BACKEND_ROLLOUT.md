# Prepared owner-feedback backend rollout

Status: prepared locally; **not deployed**. The approved owner-feedback candidate needs the new public signed registration route before automatic AI setup can work against the hosted service. The existing deployed backend and code 4 APK remain separate from this implementation. This document is the concrete rollout checklist; authorization to deploy is still required.

## Changes ready for review

- Add `POST /v1/auth/register` with a fresh challenge, device P-256 proof, separate registration signature domain and the existing session/credential format.
- Preserve secret enrollment and existing credential renewal for older apps. Keep provider credentials on the server.
- Bound anonymous registration, auth, provider calls, concurrency, replay bookkeeping and input/output. Synonym planning precedes Android candidate limits.
- No new service, account, package or paid infrastructure is required. Exact protocol and budgets are in [server/README.md](../server/README.md).

## Rollout steps after owner approval

1. Deploy the exact CI-verified `server/` source through the existing Render configuration. Preserve the current `SESSION_SIGNING_SECRET`, `DEVICE_ENROLLMENT_SECRET` and `GEMINI_API_KEY` values; never copy them into code, Android, logs or this receipt.
2. Preserve configured model names and `REVOKED_DEVICE_IDS`. Set `TRUSTED_PROXY_IPS` only when the adjacent proxy addresses have been independently verified. With no trusted proxy configuration, forwarded headers are ignored and users behind the same proxy share its IP budget.
3. Confirm `/health` revision matches the deployed commit and its configuration booleans are healthy. An unsigned AI request must remain unauthorized. These checks do not invoke paid providers.
4. On the private candidate, verify automatic registration, session renewal, network restriction, background pause and collection exclusions with synthetic content. Run a bounded live-provider question only with the owner's acceptance authorization; verify the cited item and page.
5. Check old enrolled clients can still renew. Keep main integration, public tags and releases behind the existing owner acceptance gate.

## Operational limits and recovery

Budgets and consumed challenge history are process-local; replicas do not share them and restart resets them. Runtime revocation is process-local; persistent denial requires the device ID in `REVOKED_DEVICE_IDS`. This is proof of possession with bounded anonymous admission, not app attestation. A trusted proxy change affects capacity and must be tested before general rollout.

Rollback to the previously deployed server revision preserves old clients when secrets are unchanged. The new candidate will show an ordinary retry/unavailable message until registration is available again; local library/search remain available. Source, CI and candidate provenance are recorded in the root [CODEX_WORKLOG.md](../CODEX_WORKLOG.md).
