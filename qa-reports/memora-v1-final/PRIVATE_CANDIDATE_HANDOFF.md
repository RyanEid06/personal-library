# Memora private 1.0.2 candidate — 24 October 2026

**READY WITH KNOWN LIMITATIONS.** Ready for owner testing. Public release awaits owner acceptance and explicit authorization.

## Installable artifacts

Use the [ARM64 candidate](../../artifacts/release-v1.0.2-candidate/Memora-v1.0.2-arm64-candidate.apk) on the owner's ARM64 phone. The [universal fallback](../../artifacts/release-v1.0.2-candidate/Memora-v1.0.2-universal-candidate.apk) is retained locally. These files are ignored, private repository artifacts and have not been uploaded or published.

Package `com.personallibrary.app`, version **1.0.2**, versionCode **4**. Permanent signing certificate SHA-256: `98686D31BFE4370CC30024439B56D9E72DE71C081951D31F5D4FDD31D4EB0598`.

| Variant | Bytes | SHA-256 |
| --- | ---: | --- |
| ARM64 | 18,319,892 | `36B3F1D36222E360120086279A59D110AD7FEF7392C6F84AB9EF2390E6AE7CC6` |
| Universal | 48,356,815 | `04B0B8A8D326EEF25B2745181A457F22B5039C1B8C6DF367C00F6F9C2FBD4437` |

The same package/certificate and higher versionCode support an in-place update over official 1.0.1/code 3. Physical update acceptance remains pending. Ordinary installation cannot downgrade back to code 3. Code 4 is now reserved for this exact source; subsequent changed-source builds must satisfy the existing version-reuse guard.

## Source and observed verification

APK source: `df5f747afb91cc253f7cb438841a189fd9d5f105`, branch `codex/v1.0.1-final-qa`, matching the remote when built. [CI 39967483519](https://github.com/RyanEid06/personal-library/actions/runs/39967483519) completed successfully for all five required jobs: core, android, server, emulator and signing-workflow. Earlier red runs are historical; no CI rerun was dispatched in this continuation.

Downloaded test XML confirms debug **68 passed / 1 skipped / 0 failures / 0 errors** and optimized **68 passed / 1 skipped / 0 failures / 0 errors**. Each suite discovered 69 methods. `AppLockOverviewTest.enabledAppLockHidesAuthenticatedLibraryFromNativeOverview` is an explicit opt-in fixture restricted to the owned synthetic AVD; its skip is not acceptance. Separate prior credential/Overview evidence and its limits remain in [CASE_RESULTS.md](CASE_RESULTS.md).

The prior optimized failure was test linkage: exact referenced repository/Kotlin/Compose/ViewCompat entry points had been optimized away. Existing test-only TraceReferences rules were regenerated at the certified source, with no ordinary release-rule relaxation. This run proves hosted debug and optimized suites after that repair. The neighboring constrained-viewport and rendered-card fixture corrections also passed. No new product bug or source change was required by the current green results.

The existing private signing command completed successfully, with clean tracked source before and after building. Both APKs passed permanent signature, package/version, expected ABI, offline OCR preservation, ZIP alignment and ARM64 ELF 16 KiB checks; release mapping exists and R8/resource shrinking remain enabled. Both final files were independently rehashed and rechecked with aapt2, apksigner and zipalign. DEX and manifest contents differ from the official APK. No physical device was connected, installed, reset or changed by this continuation.

Local evidence:

- [Final pair/provenance report](../../artifacts/release-v1.0.2-candidate/candidate-release.json), [contents/size analysis](../../artifacts/release-v1.0.2-candidate/candidate-size-analysis.json), and [reservation ledger](../../artifacts/release-v1.0.2-candidate/candidate-last-build.json).
- [Successful CI job record](../../artifacts/qa-v101/ci-39967483519-success.json), [emulator XML inventory](../../artifacts/qa-v101/ci-39967483519-emulator-inventory.json), and downloaded reports in `artifacts/qa-v101/ci-39967483519-emulator-results/`.
- [Independent final APK verification](../../artifacts/qa-v101/c24-independent-candidate-verification.json) and [candidate/official content comparison](../../artifacts/qa-v101/c24-candidate-versus-official.json).

## Owner acceptance still required

Use the existing [private candidate acceptance checklist](COVERAGE_MAP.md#private-102-candidate-owner-acceptance-checklist) and record outcomes against its original case IDs. Prior official results remain **30 PASS / 11 FAIL / 7 incomplete**, separate from fixed-candidate results. Eleven confirmed bugs have local fixes; this delivery does not claim all 48 original cases passed.

- Complete WhatsApp JPEG/PNG/GIF and missing-original flows; real share applications, audio/video playback and external viewers.
- Check a real paired AI answer with exact source opening, permitted-network queue completion and failed-voice Retry/restart recovery. Prior unavailable/QUEUED observations did not establish the cause.
- Check real reminder delivery and lock-screen redaction.
- Test enrolled biometric/device-credential cancellation, rejection and success; rapid Home/Overview return, cold-start privacy and existing task snapshots. Enter credentials privately. App Lock intentionally restricts screenshots, recording and non-secure displays.
- Finish physical Light/Dark, large-font, rotation/RTL, onboarding/media/error/loading review, in-place update, repeated imports and sustained use.

Report-only checkpoint changes follow the certified APK source; they do not retroactively change its SHA or require rebuilding the delivered files. No main integration, tag, release upload or deployment occurred. Owner results are the next gate.
