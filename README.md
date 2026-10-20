# Memora

A native Android library for saving WhatsApp shares, notes, links, media, documents, and arbitrary files. Originals and the authoritative library stay on the phone. Local search works offline; Gemini enrichment helps retrieve saved content by meaning.

Memora is the owner's chosen product name. Settings -> Appearance offers saved **Light / Dark / System** choices (System by default). The same launcher icon serves both modes. The Library header shows the current item count and date basis directly alongside its controls. The existing fixed palette is independent of wallpaper colors. [Artwork originals, preparation and exact RGB colors](docs/branding/memora/README.md) are preserved in the repository. Stable application/database/preference identifiers and encrypted backup format remain unchanged.

The verified permanent-signed V1.0.1/code3 ARM64 and universal APKs use the [V1.0.1 release channel](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.1) for public downloads after the publication gate below. APK source `5ac62b4ccbcdacdf6144827c8f09a3cfdeca5125` passed all five jobs in [CI 39389151689](https://github.com/RyanEid06/personal-library/actions/runs/39389151689); the later release-tag record changes documentation only and requires its own green workflow. The existing package, certificate and update compatibility are preserved. Its [immutable specification](docs/CODEX_V1_0_1_MASTER_PROMPT.md), [current evidence and limitations](docs/CODEX_V1_0_1_WORKLOG.md), [requirement audit](docs/CODEX_V1_0_1_AUDIT.md), and [physical QA checklist](docs/CODEX_V1_0_1_PHYSICAL_QA.md) track this release and its outstanding device acceptance. See [verification status](docs/STATUS.md), [permanent release procedure](docs/branding/memora/RELEASE.md), and [phone handoff](docs/HANDOFF.md).

## V1 capabilities

Final release packaging enables R8 and resource shrinking. One source/version produces `Memora-v1.0.1-arm64-official.apk` for ARM64 phones and `Memora-v1.0.1-universal.apk` retaining all four existing ABIs. The [owner release command](docs/branding/memora/RELEASE.md) verifies both permanently signed APKs before publication; only ARM64 is delivered to Desktop for this release. `optimizedCheck` is a nonofficial, nondebuggable, debug-signed verification build; CI tests optimized code as well as debug without production signing credentials.

- Share Preview and foreground single/multiple-file capture, explicit cancellation/partial failures, durable originals, SHA-256 deduplication, and transactional reference accounting.
- Library list/grid, collections, favorites, Trash, Saved/Source dates, custom ranges, grouping, and retained browse position.
- Room schema 5 with non-destructive migrations from existing installations, FTS4 live prefix search, bundled Latin OCR, bounded text/PDF extraction, page references, local snippets, and media previews with external Open/Share.
- Collection editing, portable Random Links identity, filtered multi-select/bulk collection management, item editing, and confirmed Restore All; Trash actions provide Undo and permanent-deletion confirmation.
- WhatsApp exported `.txt` file selection, optional separately selected attachments, preview before confirmation, conservative dates, provenance, and idempotent Random Links import. Extract ZIP packages before selecting files.
- Versioned durable AI jobs, pause/resume, item/collection exclusions, Wi-Fi constraints, bounded retries and explicit Retry analysis. Exclusions preserve local extraction.
- Bounded image/video/audio/PDF analysis, local 768-dimensional vectors, and distinct labels for Gemini semantic results, AI-plan lexical results, and local keyword search.
- Bounded direct AI answers reuse locally indexed evidence and retain tappable item/page sources. Live deployment revision and paired-provider acceptance remain unverified; local and related results remain available when it is unavailable.
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

The first official [Memora v1.0.0 GitHub release](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.0) provides the permanently signed ARM64 and universal APKs. Export an encrypted backup before a signing transition and follow the [handoff guidance](docs/HANDOFF.md) if Android reports a signature conflict. Physical-phone acceptance remains a separate owner check.
