# Memora V1 permanent release procedure and audit

Application ID is permanently `com.personallibrary.app`. First official version remains **1.0.0 / versionCode 2**. The existing owner key is the only permitted official signing identity; see [SIGNING_IDENTITY](SIGNING_IDENTITY.md). Do not generate another key or rename the package. Future updates use the same certificate and a larger versionCode.

## First official release — v1.0.0

The first official release is published at [GitHub Releases](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.0). It contains the permanently signed `Memora-v1.0.0-arm64-official.apk` and `Memora-v1.0.0-universal.apk`. Release assets include their SHA-256 checksums; the ignored `artifacts/release-v1/FINAL_VERIFICATION.md` keeps the full exact-source CI, APK, certificate and runtime audit. All five GitHub Actions jobs passed on the tag's source before signing. Both APKs passed package/version, permanent-certificate, ABI/OCR, zipalign and ARM64 16 KB ELF checks. Version 1.0.0 / versionCode 2 and application ID `com.personallibrary.app` remain fixed.

Physical-phone acceptance remains owner work. The CI/emulator evidence does not establish real WhatsApp/OEM behavior, paired live Gemini quality, fresh-device restore, accessibility or retrieval/performance acceptance.

## Release implementation and verification history

The implementation is on main and is published from the `v1.0.0` tag. R8 (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`) use the existing optimized default ProGuard and dependency consumer rules; no blanket keep/dontwarn rules were added. `-PmemoraPhoneApks=true` enables a standalone ARM64 split plus a genuine four-ABI universal output. Normal debug/emulator packaging is unchanged. No architecture branches/flavors or application dependency upgrades were introduced.

Optimized OCR runtime verification exposed missing public no-argument constructors in the three manifest-declared ML Kit registrars. The pinned Firebase consumer rule preserved their classes but not reflective constructors. Three exact `-keepclassmembers` rules now retain those constructors in release and optimized verification builds. This preserves bundled OCR without broad package rules or disabling shrinking; the existing real OCR instrumentation assertion remains mandatory.

The nonofficial `optimizedCheck` build inherits release optimization and is nondebuggable; its debug certificate is for verification only. CI inspects actual ABI sets, native/offline asset hashes, R8 mapping, signature and 16 KB alignment, comparing sizes with same-source debug. API 35 instrumentation runs debug and optimized builds, including UI/appearance, PDF/audio, bundled OCR and a Room/managed-original/Tink backup/restore round trip. Official Release tasks retain the permanent-identity guard.

First local optimized verification builds measured 18,512,896 bytes ARM64 (17.66 MiB) and 48,549,819 bytes universal (46.30 MiB), against 112,766,321 bytes (107.54 MiB) debug. These are measured verification artifacts, not guaranteed signed-output sizes. Native libraries and offline OCR assets matched byte-for-byte. Only AGP-generated `assets/dexopt/baseline.prof` and `baseline.profm` may differ from debug. Actual signed-output sizes/hashes are recorded in ignored `artifacts/release-v1/official-size-analysis.json` and `official-release.json`.

Historical permanent-signing pass starting source: `71dbfa187bcba5b37c7ed3ab7c834627915cd514`, clean main matching fetched origin/main. Both run [38887322456](https://github.com/RyanEid06/personal-library/actions/runs/38887322456) and the newer exact-starting-SHA run [38889923807](https://github.com/RyanEid06/personal-library/actions/runs/38889923807) failed Android test-APK compilation: MemoraAppearanceTest imports Espresso without its direct dependency. The fix declares `androidx.test.espresso:espresso-core:3.6.1`, matching the pinned AndroidX test stack. All appearance assertions, artwork and app behavior are preserved.

CI actions use current supported majors: checkout v7, setup-java v6, setup-node v7, upload-artifact v7, setup-android v4, and setup-gradle v6 with the basic caching provider. The remaining Node 20 warning in setup-android v3 was found in the complete final-checkpoint log; the maintained v4 uses Node 24. Gradle, AGP, Kotlin, Room, Compose and application dependencies stay pinned. The release workflow also checks that missing credentials, a wrong alias, a wrong path and configuration caching with signing credentials all fail. No production key is supplied to CI.

Historical checkpoint `22149d9ca2b8397d057ebf90bbd5d3c8d5371bcf` passed all four CI jobs: 46 core, 54 Android JVM, 32 backend and 5 API 35 instrumentation tests, zero failures/skips. Lint has zero errors and 80 unsuppressed warnings. Screenshot retrieval found a separate evidence bug: Gradle removed the emulator app before retrieval. The workflow now retains that disposable install and rejects non-PNG screenshots. The current pass also includes optimized runtime instrumentation. Its final exact-source gates and signing outcome are recorded in ignored `artifacts/release-v1/FINAL_VERIFICATION.md`; historical green evidence is not a pass for a later source SHA.

## One-time Windows DPAPI setup

From the owner's already-open PowerShell session containing the temporary signing environment variables, run once:

```powershell
& 'C:\Users\Administrator\Desktop\Projects\personal-library\scripts\Setup-MemoraSigning.ps1'
```

Setup validates the existing canonical key's file hash, private certificate and expected fingerprint without creating any key. It encrypts SecureString password fields with Windows **current-user DPAPI** and restricts the file ACL to the current Windows SID. The credential/config file is outside the repository:

`C:\Users\Administrator\Documents\Memora-Signing\memora-signing.credential.xml`

Only this Windows user on this PC can decrypt the DPAPI payload. No AES key, portable/plaintext fallback, permanent environment variable or repository credential is created. Setup uses existing process variables silently; if absent, it offers a hidden prompt only during setup. An absent PKCS12 key password uses the store password. After successful setup, the temporary password environment variables are removed from that PowerShell process. Setup never builds an APK. An intentional credential reset uses `-Replace`. Protect the original keystore/password independently: a DPAPI file is not a portable key backup. [Microsoft DPAPI/CLIXML behavior](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/export-clixml).

## One-command owner-local release

After required exact-source CI jobs pass, future releases need only:

```powershell
& 'C:\Users\Administrator\Desktop\Projects\personal-library\scripts\Build-MemoraRelease.ps1'
```

The script resolves HEAD automatically, verifies clean main and fetched origin/main, then requires core/android/server/emulator and the Windows signing-workflow job to succeed on that exact SHA. An optional `-VerifiedSourceSha <full-SHA>` pins an explicitly reviewed candidate. SDK selection uses ANDROID_HOME, ANDROID_SDK_ROOT, the prepared ignored `.tools/android-sdk`, then the usual local SDK directory; `-AndroidSdkRoot` can select it explicitly. SDK build-tools 36.0.0 and the canonical Documents key/hash remain required.

If Windows PowerShell execution policy blocks local scripts, use a process-only invocation: `powershell -NoProfile -ExecutionPolicy Bypass -File <script-path>`. No permanent policy change is needed. A child process inherits already-loaded process credentials for setup; close the old credential-loaded console afterward to discard its temporary plaintext environment. Future builds need only the DPAPI file.

Only after preflight does the script decrypt the local DPAPI profile and expose passwords in its process/Gradle child environment for the build. It never prompts for a future build password. Missing, corrupt or wrong-user profiles fail clearly. Environment values are restored and SecureStrings disposed in `finally`; configuration caching is disabled, native output is captured in memory only, and no password is passed as a command argument. Never put a password in a transcript, chat, source, documentation or GitHub; never enable Gradle debug logs/build scans for a signed build.

Package/certificate remain fixed. Version metadata is read from verified source and checked against the APK. The initial source still declares 1.0.0/code 2. A public `memora-last-release.json` beside the keystore prevents a changed source release reusing the previous versionCode; later versions must increase it. No install/uninstall occurs.

Gradle attaches permanent identity validation to every Release task, including aggregate/abbreviated invocations that select those tasks. Missing configuration cannot fall back to unsigned release output or a debug key. Validation checks the canonical path, file SHA-256, alias, private-key availability, RSA 4096-bit certificate, validity and expected certificate fingerprint. It emits only sanitized failure messages.

The release command builds `:app:assembleRelease -PmemoraPhoneApks=true` with configuration caching disabled. Both fresh outputs must pass apksigner, exact package/version/certificate, exact ABI contents, OCR presence, ARM64 ELF and zipalign `-c -P 16 4` checks before either is copied to:

`C:\Users\Administrator\Desktop\Memora-v1.0.0-arm64-official.apk`

`C:\Users\Administrator\Desktop\Memora-v1.0.0-universal.apk`

Both Desktop copies are staged and hashed first. Documents `memora-last-release.json` stores both artifacts before publication; a staging/ledger failure publishes neither, and same-source retries are safe. Pair provenance validates both artifact hashes/types and their common source/version/identity; legacy single-artifact records remain supported. After both Desktop copies pass their final hash checks, ignored `official-release.json` records source/CI/certificate/ABIs/sizes/hashes. If a later filesystem replacement fails, a valid first copy/reservation can remain; retry the same-source command. Only both verified Desktop copies plus the final report establish delivery. No debug/unsigned file is placed at an official filename. Future names use the source versionName and these ARM64/universal suffixes.

Credential selection prefers current-user DPAPI. If the profile is absent, the owner's temporary process signing environment is also supported without a password argument or output. If neither exists, signing stops clearly. Run Setup-MemoraSigning.ps1 once for future one-command updates. Both ABI artifacts share a versionCode; increase it for every changed-source update.

## V1 freeze audit

| Area | Existing implementation and regression evidence retained |
| --- | --- |
| Native storage/search | Native Android/Compose; Room v4 with explicit 1->2->3->4 migrations, many-to-many memberships, FTS4, SHA-256 ManagedAssetStore, LocalIndexer, AnalysisJobEntity and chunked SearchDocumentEntity. Migration, Room/FTS, asset, repository and core search tests remain enabled. |
| Security | Server enrollment and renewal require signatures; fingerprint match and signed restart renewal remain covered. Android identity uses lazy AndroidKeyStore with handled failure and no production software signer fallback. |
| Backup/restore | Tink streaming encryption; missing original/export size/hash and framed-manifest agreement verified; date-only data preserved; transactional restoration, safe collection remapping, orphan cleanup, physical-blob checks and post-commit reminders/reindexing retained. |
| WhatsApp | SAF text/attachment selection and preview; explicit ambiguous-date choice, deterministic IDs, raw provenance and attachment matching; repeat import refcounts and attachment replacement/index invalidation remain tested. |
| Reminders/library | Contextual notification permission and honest denial/exact capability; localDate/date-only 09:00/timezone recomputation; Snooze/Complete; inclusive custom range, independent Saved/Source dates and source sorting; retained navigation position. |
| AI/media | Item/collection exclusions retain local indexing and block uploads; pause/resume and bounded retries; version guards; 768-D contract; ML Kit OCR, bounded audio/PDF batches and page provenance; honest semantic/AI-plan lexical/local labels; PDF/audio/text/video previews. |
| Capture/voice/lock | Foreground durable capture with transient URI grants, committed-only Saved state, partial failures, cancellation/recovery and rollback tests; existing VIDEO dispatch; local snippets; on-device speech preference with honest fallback and common search pipeline; biometric/device-credential lock. |
| Backend | Existing selected Gemini configuration and Render backend; provider AbortSignal/deadline and single-response guard; stalled JSON ingress cleanup. Auth/cancellation/media/provenance tests remain enabled. Live authenticated Gemini quality still requires owner acceptance. |

Production searches found no unfinished-code markers, destructive migration, production software signer, disabled/ignored tests, emulator URL, direct Android Gemini key, tracked keystore or accidental Vite/web source. `text-embedding-004` remains only as explicit backend legacy-setting rejection/substitution and a semantic-incompatibility fixture; neither is the active embedding model. Secret-value searches are performed without printing matching values.

## Installation and physical acceptance

Physical-phone acceptance is pending. No connected device has been verified or uninstalled. A prior debug-signed installation cannot be updated by the permanent certificate: preserve/export its library before any owner-approved uninstall. The script does not install or uninstall anything. Once the permanent version is installed, future official updates must keep this package/certificate and increase versionCode. Real WhatsApp/native media/voice, OEM reminders, live paired Gemini, fresh-device restore, accessibility and retrieval/performance checks remain the [owner checklist](../../HANDOFF.md).
