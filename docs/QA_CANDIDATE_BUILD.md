# Owner-local V1.0.1 QA candidate

After the final implementation checkpoint is committed and pushed to `codex/v1.0.1-hardening`, wait for that exact source SHA to have a completed successful `android.yml` run with all five required jobs (`core`, `android`, `server`, `emulator`, `signing-workflow`) completed successfully. Then use the existing owner-local signing setup:

```powershell
.\scripts\Build-MemoraRelease.ps1 -QaCandidate -VerifiedSourceSha <full-40-character-source-SHA>
```

Use JDK 21 and the prepared `.tools/android-sdk` (or the existing `-AndroidSdkRoot` option). Candidate mode requires the exact hardening branch and fetched `origin/codex/v1.0.1-hardening` at that SHA. Tracked changes, staged changes, and unrelated untracked files are rejected both before and after the build. Only the preserved untracked `docs/qa/` files are allowed. Commit all release inputs before running this command. Do not pass `-OutputDirectory`: candidate destinations are fixed inside the repository.

The command uses the same permanent key, release Gradle task, R8 mapping, ARM64/universal packaging, certificate/package/version checks, ABI/OCR preservation, zip alignment and ARM64 ELF checks as the normal release command. It decrypts credentials only after source, CI, SDK, version and permanent-key preflight. It neither publishes a GitHub release nor merges or pushes any branch.

For source version `1.0.1`, successful outputs are:

- `artifacts/release-v1.0.1-candidate/Memora-v1.0.1-arm64-candidate.apk`
- `artifacts/release-v1.0.1-candidate/Memora-v1.0.1-universal-candidate.apk`
- `artifacts/release-v1.0.1-candidate/candidate-release.json` (final pair verification and `releaseChannel: qa-candidate`)
- `artifacts/release-v1.0.1-candidate/candidate-size-analysis.json`
- `artifacts/release-v1.0.1-candidate/candidate-last-build.json` (candidate reservation/retry ledger)

This directory is ignored by Git. Filenames and directory version follow the explicit source versionName. Candidate mode reads the official Documents `memora-last-release.json` to reject version rollback/reuse but writes only its separate candidate ledger and reports. It also checks its own existing ledger, so retries of the same source are safe and changed source cannot silently reuse a reserved candidate versionCode. Both APKs must pass verification before publication begins; only both final files and the final pair report establish delivery. Physical-device acceptance remains pending.

The default command without `-QaCandidate` retains the clean-main requirement, Desktop official filenames, official Documents ledger and `artifacts/release-v1` reports. Candidate outputs do not establish an official release.

Run `scripts/Test-MemoraReleaseCandidate.ps1` for synthetic checkout/CI/entry-point checks and real temporary-file pair publication/isolation checks. These tests do not read production credentials, run Gradle, sign an APK, access Git/network or publish anything. `scripts/Test-MemoraReleaseSafety.ps1` continues to cover shared publication failure handling and provenance validation.
