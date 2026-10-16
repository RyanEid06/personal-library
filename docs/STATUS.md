# V1 verification status

## Memora branding follow-up

The owner subsequently supplied final Memora artwork and authorized a bounded appearance update: shared launcher icon, matching Library wordmarks, user-facing name, and saved Light/Dark/System selection. Version code advances to 2; app/database/preferences/auth/backup identifiers are preserved. Theme resolution regressed from the old system-only behavior (3 tests, 2 failures), then all 3 passed. Two Android preference regressions and one API 35 interactive appearance/recreation test extend the existing gates; final exact-commit results and screenshots accompany the replacement Desktop APK. [Artwork, resource preparation and stable RGB palette](branding/memora/README.md).

The tables below retain the prior V1 finalization evidence; branding delivery must independently pass the same complete workflow before replacing the old Desktop handoff. No permanent signing or freeze is authorized by this update.

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
