# Personal Library V1 finalization plan and evidence ledger

Goal: execute the owner's finalization objective without replacing the existing native Android architecture.

Authority: `C:\Users\Administrator\.codex\attachments\4bafd5b7-7ce6-42d2-b585-0e998592985c\goal-objective.md`, plus the 2026-10-13 design and implementation plan. Owner explicitly requests work on and final push to main. Execute inline with a fresh final reviewer.

Starting branch: main. Starting SHA: aeacb1c2e241122b69a9f781411367ffe3ef779f. Starting working tree: clean.

## Constraints and rulings

- Preserve Room v4, explicit migrations, SHA-256 managed blobs, FTS4, durable indexing/AI, Tink streaming backup, and the current Material UI.
- No V2, web app, new provider, permanent signing key, secret disclosure, blind historical merge, or deletion of codex/v1.
- Ruling: the supplied objective is the execution brief for bounded fixes to existing flows; its explicit instruction to finish all implementable work authorizes execution without another design checkpoint. The original implementation plan also specifies inline execution. Cost if wrong: targeted changes need owner revision.
- Shared interfaces: backup records feed repository restore and reminder scheduling; capture publication feeds Room references and indexing; AI privacy and pause state feed worker scheduling and UI; extracted PDF pages feed chunks, AI batching, and retrieval provenance. Change producers and consumers together.
- Keep completion claims out of STATUS/README/HANDOFF until code verification. This ledger preserves execution history; the final verification section supersedes earlier pending checkpoints.

## Work sequence

For each behavior: add a regression, observe failure, implement the smallest fix, run the relevant suite, record evidence here.

- [x] 1. Baseline attempts recorded; subsequent hosted core/Android and local/server gates verified below.
- [x] 2. Server authentication: mandatory nonblank EC proof, matching credential fingerprint, restart-safe signed renewal; negative HTTP/direct registry tests.
- [x] 3. Production signer: fail closed on AndroidKeyStore errors; explicit injected test signers; controlled pairing/auth error.
- [x] 4. Backup integrity: missing originals fail export; exact manifest/frame sets, lengths, SHA-256 including empty assets; compatible date-only fields.
- [x] 5. Restore integrity: existing physical blob checks; rollback orphan cleanup; safe collection collision remap; refcounts; reindex; schedule only after commit.
- [x] 6. WhatsApp SAF import: choose text and attachments; preview counts/date problems/matches/missing/unmatched; explicit needed date order; provenance/idempotency/refcount preservation.
- [x] 7. Reminders: contextual notification permission; honest denial/exact fallback; date-only 09:00 local date and time-zone recomputation; tests.
- [x] 8. Browse/filter: custom inclusive calendar date range; source sort independent of filter basis with both directions; list/grid position restoration.
- [x] 9. AI controls: item/collection exclusions retain local extraction; pause persists queue without retry loop; explicit resume queues work.
- [x] 10. Media AI: bounded first-five-minute audio representation, honest unsupported/partial; PDF batches <=10 pages/100-page local ceiling; original preservation.
- [x] 11. Search provenance: PDF page chunks; distinguish embedding semantic, AI-plan lexical, local FTS; local matching snippets.
- [x] 12. Existing video preview dispatch and on-device English speech preference.
- [x] 13. Durable share capture: inspect only useful historical mechanics; foreground lifecycle ownership; durable copy/metadata before Saved; no stale replay; explicit batch failures.
- [x] 14. Capture transaction rollback/refcount/orphan failure injection regressions.
- [x] 15. Provider cancellation: bounded AbortSignal, one response, disconnect/timeout cancellation.
- [x] 16. Recover focused migration/FTS/job/semantic regressions and Android/server CI with fake provider and no secrets.
- [x] 17. Audit unused root lockfile/device JSON/metadata before narrow cleanup.
- [x] 18. Final full gates and prohibited-pattern audit; fresh whole-change review; fix actionable findings.
- [x] 19. Update STATUS/README/HANDOFF/BACKUP_FORMAT/original plan from verified facts.

Delivery gate: commit/push documentation on main, repeat exact-SHA CI, require Render revision/health, and hand over a checksum-verified APK on Desktop. The final Desktop acceptance record records execution of this gate without a self-referential documentation commit.

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
- Hosted checkpoint a5e67fc86389912fde9418e015121d1598709920 passed ALL restored gates: core 37 tests, server 28 tests, Android 19 tests, lintDebug, assembleDebug. Run: https://github.com/RyanEid06/personal-library/actions/runs/38803287633. Fresh signer candidate review found no scoped bypass/regression. This is a checkpoint, not V1 completion.
- New WorkManager pause regression reproduced retry-loop behavior on hosted 7146f9e (21 tests / 1 expected failure); denied-notification test reproduced false deliverability on f8b7723 (23 / 2 expected failures including pause).
- Current candidate adds item/collection exclusion controls; persists EXCLUDED while retaining local extraction; cancels excluded queued work; resumes persisted jobs when pause turns off; paused workers finish successfully with QUEUED state; rechecks privacy before analysis and each embedding batch. Hosted verification pending.
- Current reminder candidate requests Android 13+ notification permission contextually, shows blocked state honestly, offers date-only or date+time, stores date-only local date/09:00, recomputes timezone changes, and shows approximate exact-access fallback. `ReminderDate` RED to GREEN with New York/Berlin timezones and invalid-date rejection.
- FTS operator-substring regression exposed corruption of CANDOR/ORGANIC. Whole-token operator removal preserves those and Unicode; supplementary full core suite now 40/40.
- 486d9fe hosted core/server passed (42/30); Android compiled and ran 24 tests with one raw-export display-name mismatch, fixed in the next candidate. Repeat-message attachment/refcount assertions passed up to that raw-export lookup. Original export bytes are now bounded and revalidated against preview before commit.
- PDF batches RED (missing bounds implementation) then supplementary GREEN 44/44. Page text now persists locally in numbered SearchDocument chunks; PDF AI uses <=10 pages/32,000 characters per request and bounded merged summaries/concepts. Semantic publication is now version-checked with metadata/chunks/job committed together; a stale-result regression is included for hosted validation.
- Audio candidate decodes supported platform formats to PCM and re-encodes <=5 minutes as AAC with byte/time bounds; inability to produce a safe representation yields UNSUPPORTED. Added emulator regressions for short WAV/AAC, >5-minute truncation, corrupt audio cleanup, and excluded-PDF page/FTS retention. Emulator proof pending; no live-Gemini or physical-device claim.
- c3b08d0 passed core/server/Android JVM/lint/debug assembly (44/31/25 tests). New emulator job reached instrumentation compilation and exposed PdfDocument not implementing Closeable; fixture cleanup now uses explicit close. Instrumentation APK compilation moved into the fast Android gate before emulator boot.
- Capture candidate ports only historical foreground-service/URI grant/cancellable provider mechanics. Confirmed copies outlive Activity; progress reports only Room commits, partial failures are explicit, cancellation closes streams, consumed share deliveries are not replayed after restore. Originals are flushed/fsynced before publication. Startup reconciles only managed orphan hashes/staging, and persisted item IDs are indexed by WorkManager; transient URI strings are never put into retry state.
- Added transient-source/cancel/partial-batch/state-restoration tests, migration 3->4 chain, actual Unicode/operator FTS, and shared-blob restore reference regression. Supplementary core suite passes 46/46 including late provider-stream cleanup and process-interruption storage reconciliation. Hosted Android/emulator validation pending.
- Hosted f6962e11970a88bb9d0909ecff6fce4e881fccc2 passed core 46, Android JVM 32, server 31, lint/debug/instrumentation APK compilation, and all four API 35 emulator tests. Run: https://github.com/RyanEid06/personal-library/actions/runs/38871239507.
- Fresh whole-change review found five Important interleavings: WhatsApp attachment replacement retained old index version; automatic queue recovery replaced live work/reset retries; consecutive capture admission preceded foreground teardown; membership replacement exposed a temporary unexcluded state; timezone recomputation retained an obsolete alarm. Hosted regressions reproduced six assertions plus one corrected capture-fixture assertion (41 tests / 7 failures at d812ffd).
- 377b1d6 fixes transactionally invalidate replaced originals and old chunks/jobs; preserve version-tagged live work and persisted retry attempts; release capture admission after serialized teardown; atomically replace memberships; cancel obsolete reminders even when the new due time is past. Adds explicit Retry analysis and resumes only unpaired failures after pairing. All four hosted jobs passed at https://github.com/RyanEid06/personal-library/actions/runs/38874046860.
- Follow-up review confirmed two existing gaps (legacy flat PDF page limits; inconsistent export metadata snapshot) and two small new queue/service edges. Hosted 8c09807 reproduced all three new assertions (47 Android tests / 3 failures). 260757f rebuilds legacy PDF pages before remote analysis, snapshots all backup metadata in one Room transaction while protecting blobs through streaming, preserves active fourth-attempt work, stops the service after cancel-command start IDs, and guards stale OCR checkpoints. Read-only reviewer found no surviving actionable issues in this delta. Exact candidate run https://github.com/RyanEid06/personal-library/actions/runs/38875148728 is still running; do not claim final success until terminal green.
- Live Render returned HTTP 200 and revision 377b1d6ba45bed96f3a493049a302ac13c157591 with auth/AI configuration true and the required model names. This verifies deployment/configuration, not a successful live Gemini analysis. Final code candidate changes Android only; final documentation will trigger another backend deployment for a final revision check.
- GitHub artifact transfer is working again: bounded download of the f696 Android reports completed successfully. Final APK CI now publishes its SHA-256 alongside the APK; Desktop handoff must verify those bytes against the exact final green SHA.
- Owner clarified the handoff: finish every agent-side check, place APK on Desktop, then owner performs phone acceptance; freeze and permanent release signing follow only after that acceptance is green.
- 260757f hosted core/server passed and Android ran 47 tests with one failure: Robolectric's PDF renderer reported zero pages for an invalid synthetic original, and extraction incorrectly called zero pages COMPLETE. All snapshot, version, retry and capture assertions passed. Require a readable PDF page before claiming extraction complete; retain the strict FAILED assertion. Final re-verification pending.
- Final platform inspection found missing Android 11+ speech-service package visibility and an early default-service availability check that prevented an available on-device recognizer from starting. Official API reference confirms the manifest query requirement. f0ad581 reproduced the availability bug (49 Android tests / 1 failure). Add the query, check on-device availability first, and identify the default service's possible internet use. Native speech quality remains a phone acceptance check.

## Final code verification and handoff decision

- 4217f95dc42f9f3fc6d7769bbb99e78f168379e4 passed all four jobs at https://github.com/RyanEid06/personal-library/actions/runs/38876784338: core 46, Android JVM 49, backend 31, API 35 emulator 4, zero test failures/skips; backend typecheck/build, Android lint and both APK builds passed. Lint: 0 errors, 68 warnings, retained visibly.
- Code candidate APK downloaded (112,643,378 bytes) and matched CI SHA-256 1056c7990afa74e336375642e1d948ea1d5ece72c8af792d672a5905ad5cbc2e. Delivery repeats verification for the documentation SHA.
- Fresh reviewer found no surviving actionable regressions after the review fixes and speech correction. No ignored tests or blanket lint suppressions were introduced.
- Live Render health: HTTP 200, configured expected models/auth; anonymous AI HTTP 401. At documentation preparation deployed revision was bc26bb00eb0b0f13cb5de913dd6173e9e3adc121 (subsequent code was Android-only). Require final documentation delivery revision before handoff.
- STATUS, README, HANDOFF, BACKUP_FORMAT, backend guide and original plan now describe actual verified implementation. No implementable finalization coding item remains. Physical/live-provider/visual/retrieval/performance acceptance is explicitly the owner's next gate; permanent signing/freeze follows only after it is green.
- Final documentation review corrected two wording issues (delivery verification was still a gate; legacy embedding environment setting is substituted server-side). It also exposed stalled request-body ingress after a timeout. Rather than retain that implementation gap, added a real incomplete HTTP upload regression: 32 tests / 1 failure before fix, then 32/32 plus typecheck/build green. Parser now consumes AbortSignal, releases buffered data/listeners and rejects promptly; timeout flushes 504 before destroying incomplete ingress. Reviewer found no actionable regression. Final delivery repeats all hosted gates and includes this server correction.
