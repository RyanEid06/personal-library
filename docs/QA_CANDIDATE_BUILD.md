# Owner-local V1.0.2 QA candidate

This private candidate contains the post-release QA fixes and approved 24 October owner-feedback changes for owner acceptance. The current official public release remains V1.0.1 / code 3. The earlier certified V1.0.2 / code 4 pair is preserved; changed candidate source uses V1.0.2 / code 5 and requires fresh exact-source CI.

After the final implementation and QA checkpoint is committed and pushed to `codex/v1.0.1-final-qa`, wait for that exact source SHA to have a completed successful `android.yml` run with all five required jobs (`core`, `android`, `server`, `emulator`, `signing-workflow`) completed successfully. Then use the existing owner-local signing setup:

```powershell
.\scripts\Build-MemoraRelease.ps1 -QaCandidate -VerifiedSourceSha <full-40-character-source-SHA>
```

Use JDK 21 and the prepared `.tools/android-sdk` (or the existing `-AndroidSdkRoot` option). Candidate mode requires the exact final QA branch and fetched `origin/codex/v1.0.1-final-qa` at that SHA. Tracked changes, staged changes, and unrelated untracked files are rejected both before and after the build. Only the preserved untracked `docs/qa/` files and exact root `classes.jar` are allowed; tracked or staged versions of those paths are rejected. Preserve them unstaged. Commit all release inputs before running this command. Do not pass `-OutputDirectory`: candidate destinations are fixed inside the repository.

The command uses the same permanent key, release Gradle task, R8 mapping, ARM64/universal packaging, certificate/package/version checks, ABI/OCR preservation, zip alignment and ARM64 ELF checks as the normal release command. It decrypts credentials only after source, CI, SDK, version and permanent-key preflight. It neither publishes a GitHub release nor merges or pushes any branch.

For source version `1.0.2` / code `5`, successful outputs are:

- `artifacts/release-v1.0.2-candidate/Memora-v1.0.2-arm64-candidate.apk`
- `artifacts/release-v1.0.2-candidate/Memora-v1.0.2-universal-candidate.apk`
- `artifacts/release-v1.0.2-candidate/candidate-release.json` (final pair verification and `releaseChannel: qa-candidate`)
- `artifacts/release-v1.0.2-candidate/candidate-size-analysis.json`
- `artifacts/release-v1.0.2-candidate/candidate-last-build.json` (candidate reservation/retry ledger)

This directory is ignored by Git. Filenames and directory version follow the explicit source versionName. Candidate mode reads the official Documents `memora-last-release.json` to reject version rollback/reuse but writes only its separate candidate ledger and reports. It also checks its own existing ledger, so retries of the same source are safe and changed source cannot silently reuse a reserved candidate versionCode. Both APKs must pass verification before publication begins; only both final files and the final pair report establish delivery. Physical-device acceptance remains pending.

Hand off the verified private APK with its exact source SHA, CI run, APK checksum, signing certificate and version, plus the remaining-case checklist in `qa-reports/memora-v1-final/COVERAGE_MAP.md` and observed results in `CASE_RESULTS.md`. Unreachable emulator cases and physical/manual checks may remain pending for this private handoff; keep their failures and incomplete evidence explicit. The owner tests those cases on the candidate before accepting an official public launch. Installing the same-package, same-certificate higher-code candidate over the official app supports an in-place update; returning to code 3 would be a downgrade. While App Lock is enabled, screenshot and Overview content protection also restricts screen recording and non-secure displays.

Owner acceptance and explicit authorization are required before main integration, tagging or a public release. A later changed-source build must pass the same version-reuse guard. Preserve the complete certified code 4 pair and its receipts in a verified subdirectory before candidate publication replaces the fixed output names. The automatic-registration backend changes require a separate approved rollout; local implementation does not deploy them or establish live provider acceptance.

The default command without `-QaCandidate` retains the clean-main requirement, Desktop official filenames, official Documents ledger and `artifacts/release-v1` reports. Candidate outputs do not establish an official release.

Run `scripts/Test-MemoraReleaseCandidate.ps1` for synthetic checkout/CI/entry-point checks and real temporary-file pair publication/isolation checks. These tests do not read production credentials, run Gradle, sign an APK, access Git/network or publish anything. `scripts/Test-MemoraReleaseSafety.ps1` continues to cover shared publication failure handling and provenance validation.
