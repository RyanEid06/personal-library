# Memora V1 final optimized release implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline. The owner's final release-completion brief authorizes implementation on main, exact-SHA CI, and permanent signing when credentials are locally available.

**Goal:** Deliver verified optimized ARM64 and genuine universal Memora V1 APKs, or stop only at an established external limitation.

**Architecture:** Preserve the app/backend and permanent identity. Enable release R8/resource shrinking, opt-in ABI splits, and a separate explicitly nonofficial debug-signed optimized verification build with identical optimization settings. Run instrumentation against optimized code as well as debug. The owner release entry point verifies both outputs before publishing them.

**Tech Stack:** Existing pinned Android/Compose/Room/ML Kit/Tink stack; PowerShell current-user DPAPI; GitHub Actions API 35 emulator.

**Spec:** Owner's MEMORA V1 FINAL RELEASE COMPLETION PASS in this chat; permanent identity in docs/branding/memora/SIGNING_IDENTITY.md.

## Global constraints

- main only; applicationId com.personallibrary.app; versionName 1.0.0; versionCode 2.
- Existing canonical Documents PKCS12, alias memora-release, documented file/certificate SHA-256. No key generation or secret output/persistence in Git.
- ARM64 primary and universal arm64-v8a/armeabi-v7a/x86/x86_64; same source/version/features/certificate.
- No feature additions, redesign, dependency upgrades, weakened tests, public release or phone uninstall.
- Optimized verification artifacts must never be named or described as official.

## Review focus

1. Split configuration must preserve all four ABIs in universal and only ARM64 in primary, including OCR.
2. Release-only shrinking must preserve Room, serialization, JNI/OCR, WorkManager and encrypted backup behavior.
3. Both APKs must pass signature/package/version/alignment checks before either is published.
4. Missing/corrupt credentials, invalid prior provenance or source drift must fail closed.
5. Documentation must distinguish automated/emulator proof from owner phone/live Gemini acceptance.

## Tasks

- [x] 1. Audit existing systems/current CI and preserve starting state; inspect source/test evidence and forbidden-pattern search.
- [x] 2. Add APK-content verification regressions, observe rejection of current unoptimized/universal-only evidence; enable optimization, opt-in splits and nonofficial optimized verification build; build/inspect outputs and fix actual R8 failures.
- [x] 3. Extend safe PowerShell publication to verify/stage both artifacts; regress incorrect ABI, incomplete artifact set and failed verification/publication. Preserve all existing signing safety tests.
- [ ] 4. Local core/Android/lint/debug/test-APK/server/PowerShell gates passed; optimized runtime instrumentation awaits hosted API 35 emulator (none local).
- [ ] 5. Review changes, update truthful docs after observed verification, commit intended files and push main. Require all jobs terminal-success on exact final SHA and healthy unchanged Render backend.
- [ ] 6. Build/sign only after verified source using existing credentials; inspect both signatures/metadata/ABIs/16 KB alignment/actual sizes, then Desktop publication and final cleanliness. If credentials absent, leave exact verified owner command and report NOT READY.

## Progress ledger

- Starting main/origin/main: 41d9cf9e57dcb56715cce9a2af599be405c5a743, fetched; baseline CI 38913661940 success. Earlier untracked scripts/verify_apk_optimization.py is an agent-authored prototype, inspected and preserved until its replacement is verified.
- No DPAPI credential profile exists at initial inspection. Canonical keystore remains outside repository. Signing is deferred until actual signing step; no password retrieval attempted.
- Ruling: user explicitly selected main and supplied the approved release design and execution sequence; reuse this checkout and execute without another design/worktree approval. Cost if wrong: changes land on main, as requested.
- Source freeze audit: existing native Room v4/migrations, FTS4/chunks/jobs, SHA-256 store, collections, privacy/queue/media/capture/voice/lock/reminders and auth/backup/server contracts found intact. Forbidden-pattern search found only legitimate legacy embedding rejection/compatibility fixture. No disabled tests, production software signer, destructive migration, tracked key or web source.
- Local fresh tests: core 46/46 and Android JVM 54/54, zero failures/skips; lint 0 errors/80 unsuppressed warnings; debug/test APK assembly passes. Backend locked install/typecheck/32 tests/build passes; source unchanged. Render /health is ok/configured at 944632929215f4a01f0404c85913acb52b80cc51. No local adb device/emulator.
- First R8 assembly succeeded without custom keep/dontwarn rules. Actual split metadata and ZIP contents confirm ARM64 only and all four universal ABIs. Verification sizes: 18,512,896 / 48,549,819 bytes. Native libraries and offline OCR assets match baseline; generated baseline profile additions initially caused an intentionally strict checker failure, reproduced in a regression and corrected by excluding only the two generated profile paths.
- APK content regressions 9/9, DPAPI 15/15 and release safety 19/19 pass in PS7 and PS5.1 (43 each). Inherited Python prototype was copied/hash-verified into ignored artifacts before exact-file removal; no owner work discarded.
- Independent read-only final review: one Important finding (nested pair provenance validation), reproduced by five failing corrupt-ledger tests then fixed; all 43 regressions green in both runtimes. No Critical finding. Minor publication interruption can leave one valid file after the second move fails; documented safe same-source retry and requirement for both final files/report, with no success claim on failure.
- Final review rulings: physical phone/live Gemini, exact-source CI, signing and Desktop delivery require actual external/runtime evidence; static review does not certify them. Cost of skipping these gates would be false acceptance, so they remain explicit.
- Action audit: existing supported majors already removed the Node 20 runtime warning. SDK Manager CLI/third-party node-domexception and emulator metrics notices remain upstream tool/dependency notices, not obsolete action-major failures; no unnecessary application/toolchain upgrade.
- Continuation: preserved run `38921709237` at `58d239e3af70abbfd7180196b74f9fd9cb450d67` through completion. Core/server/Android/signing-workflow succeeded; emulator failed loading ReleaseRuntimeTest because its backup coroutine method returned JVM `kotlin.Unit` instead of `void`. The existing runtime test is the observed failing case. Explicit `runBlocking<Unit>` fixes the test method contract without changing assertions or production behavior; local debug and optimized instrumentation APK compilation and javap confirm all three methods now return void. The unchanged Android JVM suite was up-to-date locally; fresh execution comes from the next complete CI run. Hosted debug and optimized execution remain mandatory on the next exact source.
- Ruling: continue the existing ledger and final review result rather than restart plan/worktree scaffolding. The remaining change is the observed instrumentation signature fix and documentation consistency; full CI and the generated final verification record provide the outstanding acceptance evidence. No additional production feature or test-only production helper is introduced.
