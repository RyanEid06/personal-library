# Memora V1 stabilization and permanent release

Owner brief: final stabilization only, no new features/redesign/dependency modernization/branding regeneration/key generation. Work on main, preserve com.personallibrary.app, 1.0.0/code 2, and the documented permanent PKCS12 identity. Starting SHA: `71dbfa187bcba5b37c7ed3ab7c834627915cd514`; clean checkout and matching fetched origin/main verified.

## Tasks and gates

1. Diagnose latest exact-SHA Android failure using complete logs and reports. Keep every appearance test/assertion. Fix only the verified cause.
2. Update obsolete CI actions to supported runtime versions, without changing application/compiler/build-tool dependencies. Repeat all four hosted jobs.
3. Make release signing fail closed: missing/incomplete/wrong identity must fail; permanent key outside repository; no secret persistence/logging; safe owner PowerShell entry point. Verify negative cases in hosted SDK environment.
4. Audit existing finalization contracts and forbidden production patterns. Run available local gates; distinguish host limitations from passing checks.
5. Review final changes, push main, verify all jobs on exact final SHA and live backend health. Only then build/sign the official APK from that source using the canonical existing key and owner-local password. Verify signature/package/version/hash before Desktop publication.
6. Record truthful source/CI/local/emulator/live/signing/phone status. Phone acceptance remains pending unless actually exercised. Never uninstall owner data automatically.

## Evidence ledger

- Full logs retained in ignored `artifacts/release-v1/ci-3ab386e.log` and `ci-71dbfa1.log`. Both runs fail `:app:compileDebugAndroidTestKotlin`: `androidx.test.espresso.Espresso` unresolved in MemoraAppearanceTest. Latest run 38889923807: core/server success, Android failure, emulator skipped. Unit/lint tasks themselves completed; this is test-APK compilation, not a failing appearance assertion.
- Canonical keystore exists and file SHA-256 exactly matches `908E801EE64FAD19978A8D30E3E5951C4A3955BF59154AEA32182F0EF1C2FAD8`. All four signing environment variables are absent at process/user/machine scope. No password was read or printed; signing must wait for owner-local secure entry.
- Windows JDK selector initialization was repaired for this task with a process-only short `jdk.net.unixdomain.tmpdir`. Local Gradle configuration/`:app:help` now passes. Core Gradle still lacks two indexed cached dependencies; supplementary direct core 46/theme 3 and backend typecheck/32 tests/build pass separately. No local Android gate pass is claimed. Official Google platform/build-tools downloads are being verified; standalone adb reports no connected device.
- `d7c2676` adds the missing pinned Espresso dependency, supported Actions, permanent signing guard and safe owner script. Its CI exposed a Kotlin DSL `java` accessor/import collision; `22149d9` fixes explicit type imports. All four jobs in [38903199274](https://github.com/RyanEid06/personal-library/actions/runs/38903199274) pass: core 46, Android JVM 54, backend 32, API 35 instrumentation 5, zero failures/skips; lint zero errors/80 visible warnings; both debug APKs and four signing-negative cases pass.
- Complete final source audit is recorded in [RELEASE](../../branding/memora/RELEASE.md). Native finalization systems and all assertions remain intact. Render health is HTTP 200 at `9446329`; server source is unchanged. Independent final code/script review reported no actionable findings. No tracked private key, secret, ignored test or production stub was found.
- Evidence inspection found that green emulator screenshots were 49-byte `run-as: unknown package` errors after AGP test cleanup. The workflow now retains the disposable test install for retrieval and validates PNG signatures. The configuration-cache signing guard uses the supported BuildFeatures service and compiles locally. Final exact-SHA CI must repeat these changes before signing.
- Official signing/output remains blocked on owner-local hidden password entry. No official APK is claimed. After final CI, the generated ignored release acceptance record must identify the exact SHA/results and, only after signing, certificate/APK hash/size. Physical-phone acceptance remains pending.

## Review focus

Check release task aliases/aggregate tasks cannot bypass validation, secrets cannot be persisted in configuration cache/logs, debug CI requires no release secrets, expected certificate and keystore hash are enforced, publication requires exact-SHA green CI and verified artifact metadata, all branding/tests survive, and source documents distinguish automated proof from physical/live-provider acceptance.
