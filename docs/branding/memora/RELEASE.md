# Memora V1 permanent release procedure and audit

Application ID is permanently `com.personallibrary.app`. First official version remains **1.0.0 / versionCode 2**. The existing owner key is the only permitted official signing identity; see [SIGNING_IDENTITY](SIGNING_IDENTITY.md). Do not generate another key or rename the package. Future updates use the same certificate and a larger versionCode.

## Current release pass

Starting source: `71dbfa187bcba5b37c7ed3ab7c834627915cd514`, clean main matching fetched origin/main. Both run [38887322456](https://github.com/RyanEid06/personal-library/actions/runs/38887322456) and the newer exact-starting-SHA run [38889923807](https://github.com/RyanEid06/personal-library/actions/runs/38889923807) failed Android test-APK compilation: MemoraAppearanceTest imports Espresso without its direct dependency. The fix declares `androidx.test.espresso:espresso-core:3.6.1`, matching the pinned AndroidX test stack. All appearance assertions, artwork and app behavior are preserved.

CI actions use current supported majors: checkout v7, setup-java v6, setup-node v7, upload-artifact v7, and setup-gradle v6 with the basic caching provider. Gradle, AGP, Kotlin, Room, Compose and application dependencies stay pinned. The release workflow also checks that missing credentials, a wrong alias, a wrong path and configuration caching with signing credentials all fail. No production key is supplied to CI.

Checkpoint `22149d9ca2b8397d057ebf90bbd5d3c8d5371bcf` passed all four CI jobs: 46 core, 54 Android JVM, 32 backend and 5 API 35 instrumentation tests, zero failures/skips. Lint has zero errors and 80 unsuppressed warnings. Screenshot retrieval found a separate evidence bug: Gradle removed the emulator app before retrieval. The workflow now retains that disposable install and rejects non-PNG screenshots. Final guard/documentation/workflow verification and official signing remain pending until recorded in the generated release acceptance record. Historical green evidence is not a pass for a later source SHA.

## Safe owner-local build

After all four required CI jobs pass on the exact source SHA, run from a normal owner PowerShell console:

```powershell
.\scripts\Build-MemoraRelease.ps1 -VerifiedSourceSha <full-40-character-green-SHA> -AndroidSdkRoot <local-Android-SDK-directory>
```

The script verifies clean main, fetched origin/main and all four required CI jobs before signing. It requires SDK build-tools 36.0.0 and the canonical Documents keystore with its expected file hash. It consumes the four release environment variables when supplied, or asks for a hidden password on the local console. For this PKCS12 key, an absent key-password environment value uses the entered store password. Never put a password in the command, a transcript, chat, source, documentation or GitHub. Never enable Gradle debug logs/build scans for a signed build. Secrets are restored/cleared from the script's process environment on exit; configuration caching is disabled.

Gradle attaches permanent identity validation to every Release task, including aggregate/abbreviated invocations that select those tasks. Missing configuration cannot fall back to unsigned release output or a debug key. Validation checks the canonical path, file SHA-256, alias, private-key availability, RSA 4096-bit certificate, validity and expected certificate fingerprint. It emits only sanitized failure messages.

Only a freshly built `app/build/outputs/apk/release/app-release.apk` that passes apksigner and exact package/version/certificate checks can be copied to:

`C:\Users\Administrator\Desktop\Memora-v1.0.0-official.apk`

The Desktop copy is staged and hashed before publication, then hashed again. The script writes a public, secret-free record to ignored `artifacts/release-v1/official-release.json` with exact source SHA, CI URL, signature result, size and APK hash. That record is evidence only after a successful build; no debug/unsigned APK is placed at the official filename.

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
