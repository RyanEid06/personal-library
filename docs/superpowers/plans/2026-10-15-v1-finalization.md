# Personal Library V1 finalization plan and evidence ledger

Goal: execute the owner's finalization objective without replacing the existing native Android architecture.

Authority: `C:\Users\Administrator\.codex\attachments\4bafd5b7-7ce6-42d2-b585-0e998592985c\goal-objective.md`, plus the 2026-10-13 design and implementation plan. Owner explicitly requests work on and final push to main. Execute inline with a fresh final reviewer.

Starting branch: main. Starting SHA: aeacb1c2e241122b69a9f781411367ffe3ef779f. Starting working tree: clean.

## Constraints and rulings

- Preserve Room v4, explicit migrations, SHA-256 managed blobs, FTS4, durable indexing/AI, Tink streaming backup, and the current Material UI.
- No V2, web app, new provider, permanent signing key, secret disclosure, blind historical merge, or deletion of codex/v1.
- Ruling: the supplied objective is the execution brief for bounded fixes to existing flows; its explicit instruction to finish all implementable work authorizes execution without another design checkpoint. The original implementation plan also specifies inline execution. Cost if wrong: targeted changes need owner revision.
- Shared interfaces: backup records feed repository restore and reminder scheduling; capture publication feeds Room references and indexing; AI privacy and pause state feed worker scheduling and UI; extracted PDF pages feed chunks, AI batching, and retrieval provenance. Change producers and consumers together.
- Keep release claims out of STATUS/README/HANDOFF until final verification. This file records work in progress only.

## Work sequence

For each behavior: add a regression, observe failure, implement the smallest fix, run the relevant suite, record evidence here.

- [ ] 1. Baseline: core tests; Android JVM tests/lint/debug assembly; server install/typecheck/tests/build.
- [x] 2. Server authentication: mandatory nonblank EC proof, matching credential fingerprint, restart-safe signed renewal; negative HTTP/direct registry tests.
- [ ] 3. Production signer: fail closed on AndroidKeyStore errors; explicit injected test signers; controlled pairing/auth error.
- [x] 4. Backup integrity: missing originals fail export; exact manifest/frame sets, lengths, SHA-256 including empty assets; compatible date-only fields.
- [ ] 5. Restore integrity: existing physical blob checks; rollback orphan cleanup; safe collection collision remap; refcounts; reindex; schedule only after commit.
- [ ] 6. WhatsApp SAF import: choose text and attachments; preview counts/date problems/matches/missing/unmatched; explicit needed date order; provenance/idempotency/refcount preservation.
- [ ] 7. Reminders: contextual notification permission; honest denial/exact fallback; date-only 09:00 local date and time-zone recomputation; tests.
- [ ] 8. Browse/filter: custom inclusive calendar date range; source sort independent of filter basis with both directions; list/grid position restoration.
- [ ] 9. AI controls: item/collection exclusions retain local extraction; pause persists queue without retry loop; explicit resume queues work.
- [ ] 10. Media AI: bounded first-five-minute audio representation, honest unsupported/partial; PDF batches <=10 pages/100-page local ceiling; original preservation.
- [ ] 11. Search provenance: PDF page chunks; distinguish embedding semantic, AI-plan lexical, local FTS; local matching snippets.
- [ ] 12. Existing video preview dispatch and on-device English speech preference.
- [ ] 13. Durable share capture: inspect only useful historical mechanics; foreground lifecycle ownership; durable copy/metadata before Saved; no stale replay; explicit batch failures.
- [ ] 14. Capture transaction rollback/refcount/orphan failure injection regressions.
- [x] 15. Provider cancellation: bounded AbortSignal, one response, disconnect/timeout cancellation.
- [ ] 16. Recover focused migration/FTS/job/semantic regressions and Android/server CI with fake provider and no secrets.
- [ ] 17. Audit unused root lockfile/device JSON/metadata before narrow cleanup.
- [ ] 18. Final full gates and prohibited-pattern audit; fresh whole-change review; fix actionable findings.
- [ ] 19. Update STATUS/README/HANDOFF/BACKUP_FORMAT/original plan from verified facts.
- [ ] 20. Commit/push main; exact-SHA CI; Render deployment/health; exact-green-SHA debug APK and final verdict.

## Baseline evidence

- Git remote HEAD matched starting SHA via `git ls-remote origin HEAD`.
- Initial `gh auth status` reported invalid authentication; retry with actual network access before classifying as a credential blocker.
- Initial `npm ci` failed EPERM in the user-global npm cache; retry with repo-local cache.
- Gradle baseline commands launched; wrapper downloads Gradle 9.3.1. Current build versions override stale version claims in the original plan.

## Execution evidence

- Server baseline from untouched HEAD test file: 16/16 passed; typecheck/build passed after locked npm install (46 packages, audit 0 vulnerabilities).
- Auth regression run failed on missing/blank proofs and replayed direct renewal, then passed 26/26 after the fix. Typecheck/build also passed. Challenges are consumed inside registry methods so direct callers cannot bypass challenge validation. Fingerprint mismatch fixture now signs with the mismatched key, testing fingerprint enforcement independently from signature validity.
- Ruling: legacy credentials without a bound fingerprint must re-pair; accepting any public key for such a credential would preserve a proof-of-possession bypass. Cost: an old test-era pairing cannot renew.
- Cancellation regression failed before implementation and passed afterward: provider AbortSignal fires before 504; SDK calls receive that signal and have a 60-second HTTP timeout. SDK documents that client cancellation cannot guarantee provider-side billing cancellation. Server suite now 28/28 with typecheck/build green, including canonical nonce regression RED to GREEN.
- Fresh read-only security investigation confirmed the shared boundary and identified canonical nonce aliases (fixed with strict nonce parsing). In-memory replay protection intentionally does not persist across server restart; the credential remains stateless.
- Local Gradle 9.6.0 attempts fail before project configuration with Windows `Unable to establish loopback connection` / `UnixDomainSockets Invalid argument: connect`, including no-daemon and Windows-selector attempts. Android SDK is absent. Hosted CI is required for full build proof; restored workflow uses current pinned wrapper and Android 36.1. `coreOnly` now actually excludes the app module.
- Auth candidate independently reviewed by a fresh read-only reviewer: no surviving scoped auth bypass or regression; 28/28 tests plus direct key/canonical/legacy probes passed.
- CI checkpoint aae7c52: backend passed; Android setup failed because setup-android defaults to retired `tools`; fixed by explicitly requesting `platform-tools` in 173651d. Core original fixture used a fake hash for actual payload; corrected fixture uses SHA-256 of its fixed bytes.
- At 173651d, hosted core suite reproduced seven backup integrity failures (34 tests / 7 failures). New portable date-only JSON tests also reproduced their failure against the original engine via an isolated baseline source copy (36 / 9 failures).
- After backup fixes, direct JVM core run passes 36/36. Supplementary local command: `.tools/Test-Core.ps1`; uses bundled Kotlin compiler 2.3.21, real Tink 1.15.0 and serialization 1.7.3. Full pinned Gradle/Android proof remains a separate hosted CI gate.
- Repository failure injection regressions added for capture membership/item rollback, shared originals, restore missing original/transaction cleanup, and collection collision remapping. Only optional database injection added so tests use a real isolated Room database; behavior fixes pending.
- Existing video preview and local search snippet dispatch wired; voice prefers available on-device recognizer. Android compilation/testing pending.
- Hosted a385eb6: core and server passed; Android ran 19 tests and exposed exactly six expected failures: production signer plus five repository integrity regressions. These are now addressed in the next candidate; hosted verification pending.
- Signer candidate removes all software key generation from production; Keystore failures are lazy controlled IO exceptions and Settings pairing resets state in finally. Original KEY_ALIAS and pairing remain intact.
- Repository candidate serializes blob capture/restore/deletion across repository instances; commits capture metadata/memberships/refcounts in one Room transaction; cleans unreferenced publications after failures; verifies staged originals and uses managed paths; remaps unrelated collection ID collisions; carries date-only fields and rebuilds derived content after commit.
- Custom calendar day boundaries failed before `DateRange.days` existed; supplementary core suite now passes 37/37 including a 23-hour DST day. Range picker invokes the real ViewModel range setter; source ascending/descending use source dates independently of filter basis. Destination SaveableStateHolder retains list/grid position across navigation.
