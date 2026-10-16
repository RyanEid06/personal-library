# V1 verification status

## Final optimized completion pass — 16 October 2026

Starting clean main/origin/main: `41d9cf9e57dcb56715cce9a2af599be405c5a743`, whose five-job CI run 38913661940 succeeded. The inherited agent-authored size prototype was preserved in ignored release artifacts before replacement by tested PowerShell APK inspection.

- IMPLEMENTED: R8/resource shrinking with existing optimized default/consumer rules; opt-in ARM64 plus genuine universal APK splits; safe dual-artifact permanent-signing/publication command; nonofficial nondebuggable optimized runtime verification variant. No app feature/dependency changes or release keep/dontwarn rules. Instrumented optimized verification alone preserves the test runner's shared `androidx.tracing.Trace` public API.
- AUTOMATED VERIFIED locally: core/Android/lint/debug/test-APK baseline and backend typecheck/32 tests/build; R8 split assembly and optimized instrumentation APK compilation; exact ABI sets and native/offline asset hash preservation. Windows DPAPI 15, release safety 19 and APK inspection 9 tests pass in both PS7 and PS5.1. Review found corrupt nested universal provenance was accepted; five regressions reproduced and verified its correction.
- CI VERIFIED / EMULATOR VERIFIED: the final source requires its own terminal-success CI. Its API 35 emulator runs appearance/audio/PDF plus real OCR, Room/Tink backup/restore and initialization tests against debug and optimized code. Debug-only success cannot certify optimized runtime.
- LIVE BACKEND VERIFIED: HTTP 200/status ok at `944632929215f4a01f0404c85913acb52b80cc51`, auth/AI configured and expected models. Backend source unchanged; no forced redeployment.
- PHYSICAL DEVICE PENDING: no connected phone or local emulator; real WhatsApp/OEM voice/reminders/lock, paired Gemini, restore/accessibility/retrieval acceptance remains owner work.
- SIGNED ARTIFACTS PENDING: initial inspection found neither DPAPI profile nor process signing passwords. No permanent-signed APK is claimed until both Desktop files pass verification.

Final exact source SHA, CI results and any external signing limitation are recorded after verification in ignored `artifacts/release-v1/FINAL_VERIFICATION.md`. Actual signed hashes/sizes belong in `official-release.json` and `official-size-analysis.json`. Generated records identify exact HEAD without changing the source they certify. Historical sections below are earlier checkpoints.

Candidate `58d239e3af70abbfd7180196b74f9fd9cb450d67` passed core, Android, server and signing-workflow in [38921709237](https://github.com/RyanEid06/personal-library/actions/runs/38921709237). Its emulator job exposed a test-runner defect before the new runtime tests could execute: the backup coroutine test inferred a nullable `Unit` return and compiled to a JVM `kotlin.Unit` method rather than JUnit's required `void`. Explicit `runBlocking<Unit>` makes the return contract unambiguous; local recompilation confirms all three release test methods are `void`. Assertions and production code are unchanged. Both instrumentation variants and all required jobs must pass on the final source before signing.

The owner then requested removal of the Memora logo/name from the Library header in both themes. The item count and current date basis now occupy the top action row directly. The appearance test retains selection/recreation coverage and checks visible information/actions, absent branding, row alignment, and actual Light/Dark/System surface colors. Local Android JVM 54/54, lint 0 errors/80 existing warnings, debug/optimized assembly and both test-APK compilations pass. Actual split inspection preserves bundled OCR and the exact ABI sets. Hosted screenshots and exact-source debug/optimized runtime results remain gates for this revision; the prior candidate run is preserved.

Preserved run `38941689801` subsequently completed with debug instrumentation 8/8 and a successful untouched optimized app launch. Optimized instrumentation crashed at runner startup because R8 had inlined/removed the shared `androidx.tracing.Trace` class, then timed out. A narrowly scoped rule preserves that class's public API only with `memoraOptimizedTests=true`; ordinary release/optimized APK optimization is unchanged. Final hosted runtime execution remains required. Passing jobs are preserved; same-source retries rerun only failed jobs, while source fixes necessarily trigger a fresh exact-source workflow.

## Final permanent release pass — 16 October 2026

Starting SHA: `71dbfa187bcba5b37c7ed3ab7c834627915cd514`, clean main matching origin/main. The owner explicitly authorized stabilization, permanent signing using the existing canonical key, and the official Desktop APK. [Release procedure/audit](branding/memora/RELEASE.md), [public signing identity](branding/memora/SIGNING_IDENTITY.md), and [release ledger](superpowers/plans/2026-10-16-memora-v1-release.md) record this scope.

Both historical failed branding runs were test-APK compilation failures from missing explicit Espresso dependency. The dependency is now declared without changing the appearance test. Code checkpoint `22149d9ca2b8397d057ebf90bbd5d3c8d5371bcf`: core 46, Android JVM 54, backend 32 and API 35 instrumentation 5 passed, with zero failures/skips; lint 0 errors/80 warnings; both debug APK builds and four synthetic signing failure cases passed. All four jobs in [CI 38903199274](https://github.com/RyanEid06/personal-library/actions/runs/38903199274) succeeded. Its screenshot retrieval was invalid because Gradle uninstalled the app after testing; CI now retains only the disposable emulator install and rejects invalid PNG artifacts. Final documentation/guard/workflow updates require a repeated full exact-SHA run.

Local supplementary checks: 46 core and 3 theme tests passed; backend locked install/typecheck/32 tests/build passed outside sandbox. The Windows Java selector starts with a short socket-temp path; normal Gradle core initially lacked two cached dependencies and SDK tooling is being prepared. No local Android/device pass is claimed. No adb-connected device is present. Live Render is healthy at `9446329`, with configured gemini-3.8-flash/gemini-embedding-2; backend source is byte-unchanged since that deployment, so no forced redeployment is needed. Live paired Gemini quality remains owner acceptance.

The permanent keystore file hash matches the owner's record. The owner loaded credentials in a separate PowerShell process and explicitly requested stopping before the signed build. The added one-time Windows DPAPI setup consumes those credentials locally and stores only an encrypted profile under Documents/Memora-Signing with a current-user-only ACL. Future one-command builds decrypt it transiently, require exact-source CI, enforce the permanent identity and verify the APK before Desktop publication. DPAPI/native-tool regressions pass 15 tests in both Windows PowerShell 5.1 and PowerShell 7; a Windows CI job repeats them. Owner-local production-profile provisioning and the final signed build are not claimed as performed. **Official APK/signature/hash/size are pending; physical acceptance is pending.** The generated `artifacts/release-v1/official-release.json`, after the owner runs the build, records exact final source SHA, CI, apksigner, package/version, certificate, official path, size and hash.

Checkpoint `c30d6ec6ddd390742f51a005f5070ca051b20ea4` passed all four jobs in [38905956716](https://github.com/RyanEid06/personal-library/actions/runs/38905956716), including valid Light/Dark screenshots inspected from the API 35 emulator. Local Gradle core now passes 46/46 after dependency resolution; verified SDK packages are installed locally, and local Android gates are being repeated. The later DPAPI workflow changes require their own exact-source CI run. The final pre-sign verification record is written to ignored `artifacts/release-v1/PRE_SIGN_VERIFICATION.md` so its full SHA/results can be recorded without changing the source being released.

Fresh review of the DPAPI addition found three release safety issues, each reproduced before fixing: a locked staging file could skip credential cleanup; malformed previous-release JSON could bypass version checks; and publication could precede durable bookkeeping. Nested cleanup, validated typed provenance and atomic ledger-before-publication fix them. All 12 release safety regressions plus 15 DPAPI/native invocation tests pass in both PowerShell versions; Windows CI runs both suites/runtimes. No production signing password was accessed by the agent.

The initial DPAPI CI run `38910223591` passed core/server/Android, but Windows PowerShell's safety test failed only when preceded by the PowerShell 7 test in the same CI shell. That exact sequence reproduced locally: test-fixture cleanup passed null through a string overload, leaving an empty environment variable for the child runtime. The fixture now removes it explicitly with NullString; the same-shell sequence passes all 27 tests in both runtimes without weakening assertions. The final source repeats all CI gates. Owner-authorized obsolete-branch deletion preserved its complete history first; only main remains remotely.

All five jobs at `3459e93b63aafe34d853d865edf8f48f5e35a7c9` succeeded in [38911251166](https://github.com/RyanEid06/personal-library/actions/runs/38911251166): core 46, Android JVM 54, server 32, instrumentation 5, Windows workflow 27 in each runtime; zero failures/skips, lint 0 errors/80 warnings and both APK assemblies. Complete log audit then found an upstream Node 20 warning from setup-android v3. Updating it to maintained Node 24/v4 is the last workflow-only maintenance change; exact-source CI repeats before owner signing.

## Memora branding follow-up

The owner subsequently supplied final Memora artwork and authorized a bounded appearance update: shared launcher icon, matching Library wordmarks, user-facing name, and saved Light/Dark/System selection. Version code advances to 2; app/database/preferences/auth/backup identifiers are preserved. Theme resolution regressed from the old system-only behavior (3 tests, 2 failures), then all 3 passed. Two Android preference regressions and one API 35 interactive appearance/recreation test extend the existing gates; final exact-commit results and screenshots accompany the replacement Desktop APK. [Artwork, resource preparation and stable RGB palette](branding/memora/README.md).

The tables below retain the prior V1 finalization evidence. The later final-release authorization above supersedes the earlier debug-only handoff scope; current exact-source verification and permanent signing remain explicit gates.

## Prior V1 finalization

Updated 16 October 2026 after all four Android/core code checkpoint CI jobs passed and the final backend cancellation regression passed locally. **V1 CODE-COMPLETE; owner phone acceptance, freeze and official signing pending.** No unfinished coding item remains from the finalization objective. Any defect discovered during acceptance must be fixed and reverified before freeze.

Starting main: `aeacb1c2e241122b69a9f781411367ffe3ef779f`.
Verified Android/core checkpoint: `4217f95dc42f9f3fc6d7769bbb99e78f168379e4`.
[Checkpoint CI 38876784338](https://github.com/RyanEid06/personal-library/actions/runs/38876784338): terminal success for core, Android, server (31 tests) and emulator. The final server-only correction reproduced stalled upload ingress after 504, then passed 32 tests/typecheck/build locally and received a read-only review with no actionable finding. Final delivery repeats every hosted gate, including the 32 backend tests.

Documentation follows verified code. Delivery repeats the same gates on the documentation commit. The Desktop acceptance record accompanying the APK identifies the final delivery SHA, CI run, Render revision, artifact size and SHA-256.

## Verification

| Gate | Evidence |
| --- | --- |
| Core JVM | 46 passed, 0 failures/ignored |
| Android JVM/Robolectric | 49 passed, 0 failures/ignored |
| Backend | 32 passed locally, 0 failures/skips; typecheck/build passed. Earlier hosted checkpoint: 31 passed. Final delivery repeats all 32 in CI. |
| Android lint | Passed: 0 errors, 68 warnings |
| APK assembly | Debug and debug-instrumentation APK builds passed |
| API 35 emulator | 4 passed, 0 failures/skips: bounded audio (3) and PDF page/local-only/legacy reindex (1) |
| Whole-change review | Important findings reproduced, fixed and reviewed again; no surviving actionable finding reported at final code candidate |
| Repository audit | No software production signer, optional auth proof, destructive migration, ignored test, Android provider key or web/Vite source found; only backend package lock retained |
| Artifact integrity | Code APK downloaded and matched its CI checksum; must be repeated for the delivery commit, with evidence beside the Desktop APK |

Lint warnings remain visible: guarded exact-alarm calls, locale-sensitive size display, dependency/catalog suggestions, KTX style, SDK compatibility suggestions, launcher resources, typography and unused resources. No blanket suppression was added. The pinned stack was preserved.

Local Windows Gradle fails before project configuration on its loopback connection; this workstation has no Android SDK. Hosted pinned Gradle/SDK results provide Android proof. Supplementary local core and backend checks passed separately.

## Objective and evidence

| Objective | Implemented behavior and regression coverage |
| --- | --- |
| Auth / signer | Mandatory nonblank P-256 ECDSA proof; credential fingerprint and challenge consumption; missing/invalid/mismatched proof and signed restart renewal tests. Keystore fails closed; test signers explicitly injected. |
| Backup / restore | Missing originals fail; exact frame/manifest set, sizes and SHA-256 including empty assets; date-only portability; corruption/bounds tests; physical blobs, rollback orphans, shared references, safe collection remap and atomic snapshot tested with real Room. Reindex and schedule after commit. |
| WhatsApp import | SAF text/attachment selection and preview; explicit needed date order, raw provenance, deterministic IDs and dedup. Replaced attachments invalidate old jobs/chunks while preserving owner metadata. |
| Reminders | Contextual permission, blocked state, date-only 09:00 recomputation, obsolete alarm cancellation and exact fallback; permission/timezone regressions. |
| Dates / browse | Custom inclusive calendar range converted to half-open instants, independent source sort both directions, retained list/grid state; date/DAO regressions. |
| AI privacy / jobs | Item/collection exclusion keeps local indexing; atomic memberships; pause/resume, live versioned work preservation, persisted retry ceiling, explicit retry and stale-publication guards tested. |
| Media / pages | Bounded images and four video frames; supported audio first five minutes to bounded AAC; PDF batches <=10 pages within initial 100-page extraction. Explicit partial/unsupported states. API 35 tests verify audio and local-only PDF page provenance/legacy reindex. |
| Search / previews / voice | Semantic, AI-plan lexical and local FTS provenance separated; compatibility/filter fixtures; local snippets and video wired; on-device speech availability checked first, service visibility declared, editable English transcript/fallback tested. |
| Capture | Foreground copy until durable metadata; interrupted/cancel/partial failure recovery; admission released after teardown; consecutive service batches and transactional failure/refcount regressions. |
| Server cancellation | Response deadline and provider AbortSignal propagation; body parser releases buffered bytes/listeners on abort, then incomplete ingress closes after the single 504 response is flushed. Regression verifies full JSON, request closure and no provider invocation. Provider-side billing cancellation is not guaranteed. |
| Migrations / FTS | Explicit 2->3 and 3->4 preservation; real Room Unicode/operator FTS and semantic compatibility tests. |
| Cleanup | Removed unused root applet lockfile and old stateless-backend device JSON; explained retained metadata and preserved historical branch. |

## Live service

Before final delivery, health returned HTTP 200, status `ok`, version `1.0.0`, revision `bc26bb00eb0b0f13cb5de913dd6173e9e3adc121`, auth/AI configuration and key-presence booleans true, and required Flash/embedding model names. Anonymous AI access returned 401. The final server cancellation correction and documentation trigger a fresh Render deploy; final delivery requires health revision to match that commit and records it beside the APK.

These checks prove deployment/configuration and the anonymous boundary. **Live authenticated Gemini generation/embedding/search is not certified here.** The owner must test it through the paired phone with disposable content. CI uses controlled provider fakes.

## Owner gates

Follow [HANDOFF.md](HANDOFF.md): real WhatsApp/media/sticker/batch sharing, native codecs/voice, OEM/background/reboot reminders, actual Keystore pairing/live Gemini, app lock, fresh-device restore, accessibility, visual quality and realistic retrieval/latency. Neither the retrieval quality target nor 10,000-item p95 has been measured on the owner's phone. These remain acceptance limitations, not automated passes.

Owner-approved sequence: agent checks green -> Desktop debug APK -> owner acceptance -> V1 freeze -> official signed version. No permanent key or official release belongs to this handoff. See the [implementation plan](superpowers/plans/2026-10-13-v1-implementation.md) and [red/green finalization ledger](superpowers/plans/2026-10-15-v1-finalization.md).
