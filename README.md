# Memora

A native Android library for saving WhatsApp shares, notes, links, media, documents, and arbitrary files. Originals and the authoritative library stay on the phone. Local search works offline; Gemini enrichment helps retrieve saved content by meaning.

Memora is the owner's chosen product name. Settings -> Appearance offers saved **Light / Dark / System** choices (System by default). The same launcher icon serves both modes; the Library header selects the matching transparent wordmark. The existing fixed palette keeps branding independent of wallpaper colors. [Artwork originals, preparation and exact RGB colors](docs/branding/memora/README.md) are preserved in the repository. Stable application/database/preference identifiers and encrypted backup format remain unchanged.

V1 implementation is complete. The owner has supplied the permanent signing identity and authorized the final official V1 pass. Exact-source automated verification and the owner-local signed build must finish before an official APK is delivered; physical-phone acceptance remains separate. See [verification status](docs/STATUS.md), [permanent release procedure](docs/branding/memora/RELEASE.md), and [phone handoff](docs/HANDOFF.md).

## V1 capabilities

Final release packaging enables R8 and resource shrinking. One source/version produces `Memora-v1.0.0-arm64-official.apk` for ARM64 phones and `Memora-v1.0.0-universal.apk` retaining all four existing ABIs. The [owner release command](docs/branding/memora/RELEASE.md) verifies both permanently signed APKs before Desktop publication. `optimizedCheck` is a nonofficial, nondebuggable, debug-signed verification build; CI tests optimized code as well as debug without production signing credentials.

- Share Preview and foreground single/multiple-file capture, explicit cancellation/partial failures, durable originals, SHA-256 deduplication, and transactional reference accounting.
- Library list/grid, collections, favorites, Trash, Saved/Source dates, custom ranges, grouping, and retained browse position.
- Room schema 4 with explicit migrations, FTS4, bundled Latin OCR, bounded text/PDF extraction, page references, local snippets, and media previews with external Open/Share.
- WhatsApp exported `.txt` file selection, optional separately selected attachments, preview before confirmation, conservative dates, provenance, and idempotent Random Links import. Extract ZIP packages before selecting files.
- Versioned durable AI jobs, pause/resume, item/collection exclusions, Wi-Fi constraints, bounded retries and explicit Retry analysis. Exclusions preserve local extraction.
- Bounded image/video/audio/PDF analysis, local 768-dimensional vectors, and distinct labels for Gemini semantic results, AI-plan lexical results, and local keyword search.
- English voice input preferring available on-device recognition, identified default-service fallback, and editable transcripts using the typed-search pipeline.
- Date-only reminders at 09:00 local, timed reminders, contextual notification permission, honest exact/inexact behavior, Snooze/Complete, and biometric/device-credential app lock.
- Passphrase-protected Tink streaming encrypted backup with staged validation and transactional restore. See [backup format](docs/BACKUP_FORMAT.md).

## Build and verify

Use JDK 21, Android platform `android-36.1` and build tools `36.0.0`. Pinned versions: Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, KSP 2.3.5, Room 2.7.0; Java 17 bytecode. Minimum API 29; target 36. Application ID: `com.personallibrary.app`; Android source namespace: `com.example`.

```powershell
./gradlew -PcoreOnly=true :core:test
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# With an emulator/device connected:
./gradlew :app:connectedDebugAndroidTest
# Nonofficial optimized runtime verification:
./gradlew :app:connectedOptimizedCheckAndroidTest -PmemoraOptimizedTests=true --no-configuration-cache
```

Backend checks from `server/`:

```powershell
npm ci
npm run typecheck
npm test
npm run build
```

[GitHub Actions](https://github.com/RyanEid06/personal-library/actions/workflows/android.yml) publishes exact-commit reports, debug APK and SHA-256. CI uses a fake provider; media instrumentation uses an API 35 x86_64 Google APIs emulator. Phone acceptance is separate.

## Backend and privacy

The default [Render backend](https://personal-library-backend-49w7.onrender.com/health) uses `gemini-3.8-flash` and `gemini-embedding-2`. Provider keys never belong in Android. Pair in Settings using the operator-managed enrollment secret entered privately on the phone; device authentication requires Android Keystore proof. See [backend setup](server/README.md).

Automatic AI analysis sends bounded representations of eligible saved content to the backend/provider. Pause AI or exclude items/collections to control future uploads. Originals stay local. Local fallback is labeled separately from semantic AI. The backend keeps no content-library database; challenge replay tracking and per-device rate limits are in memory. Environment-configured device revocations survive restart.

`metadata.json` is retained as legacy AI Studio descriptive metadata; Android/backend runtimes do not consume it. There is no web/Vite app. The owner requested removing obsolete `codex/v1`; its complete history was verified in a local Git bundle before deleting the remote branch. Finalization is on owner-authorized `main`; see the release ledger for recovery evidence.

The owner-created permanent release key stays outside Git. No release tag or public GitHub Release is part of this pass. Export an encrypted backup before a signing transition and follow the [handoff guidance](docs/HANDOFF.md) if Android reports a signature conflict.
