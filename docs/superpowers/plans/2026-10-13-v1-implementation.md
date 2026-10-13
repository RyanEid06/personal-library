# Personal Library V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute inline; a fresh whole-change reviewer is required before claiming the full implementation complete.

**Goal:** Build the Android personal library described in the approved product proposal, with durable local capture, organization, retrieval, backup, migration, reminders, Gemini enrichment, and English voice search.

**Architecture:** One Android application module owns Compose UI, Room persistence, Android integrations, and durable workers. A small Kotlin/JVM core module isolates file integrity and search/date algorithms for fast real tests without an SDK. A separate Node service holds provider credentials; originals and the authoritative search index stay on the phone.

**Tech Stack:** Gradle 9.6.0, AGP 9.4.0, Kotlin/Compose compiler 2.4.21, Compose BOM 2026.09.00, Room 2.8.5, KSP 2.3.12, JDK 21 with Java 17 bytecode; Android minimum API 29, compile/target API 37. Versions are pinned, not dynamic. Provider and ancillary Android dependency versions are pinned when their tasks are implemented.

**Spec:** [Personal Library design](../specs/2026-10-13-personal-library-design.md).

## Global Constraints

- Android first; English interface and English voice queries.
- Keep the authoritative collection and original files on the phone.
- Receive content through the Android share menu, especially from WhatsApp.
- Browse by type and filter or group by day, month, year, or all time.
- Search both directly and through Gemini-assisted natural language.
- Automatic AI analysis was explicitly selected by the owner.
- Store unknown files even when their content cannot be previewed or understood.
- Saving succeeds only after durable local file/database persistence; Gemini is never on the save critical path.
- Separate saved date, source date, and reminder due date. Use explicit timezone-aware half-open date ranges.
- Backend keys and enrollment secrets never enter the APK or Git. Use synthetic data for provider tests.
- Use the normal Windows terminal/keyring for every Git/GitHub operation. The owner authorized repository creation, pushes, and builds on 13 October 2026.
- Repository is private under the working name `RyanEid06/personal-library`; the product name remains open.
- Keep the complete V1 objective active. A partial APK or green core suite is not full product acceptance.

## Review Focus

1. A sender may omit or misdeclare MIME, filename, size, caption, or date; capture must retain supplied bytes without inventing metadata (Tasks 1/2).
2. Shared URI access may expire, a stream may fail, and Android may kill capture; no false Saved state or dangling database reference (Tasks 1/2).
3. A reused blob may have multiple items/collections; deleting one association must not destroy another item's original (Tasks 2/3/4).
4. Search input may contain FTS operators, quotes, emoji, or non-Latin content; queries must be safe and useful without raw SQL from AI (Tasks 1/3/7).
5. A backup may be corrupt, oversized, duplicated, or use a wrong password; restore must validate into staging and preserve the current library (Task 4).

## File structure and contracts

- Root Gradle files and `gradle/wrapper/`: reproducible build. `-PcoreOnly=true` intentionally excludes Android configuration for core tests only.
- `core/src/main/kotlin/com/personallibrary/core/`: `ItemKind`, `ContentClassifier`, `ManagedAssetStore`, `SearchQuery`, date ranges, backup/import/semantic algorithms as subsequent tasks require.
- `core/src/test/kotlin/com/personallibrary/core/`: real stream/filesystem/date/search fixtures. No source-text tests.
- `app/src/main/java/com/personallibrary/app/data/`: Room entities, DAO, database, repositories, persisted jobs, relationships.
- `app/src/main/java/com/personallibrary/app/capture/`: exported share entry, intent decoding, foreground import ownership, confirmation UI.
- `app/src/main/java/com/personallibrary/app/ui/`: theme, Library/Collections/Search screens, details, settings, and screen ViewModels.
- `app/src/main/java/com/personallibrary/app/platform/`: document pickers, FileProvider, media previews, voice, app lock, reminders.
- `app/src/main/java/com/personallibrary/app/indexing/`: bundled OCR, extraction, versioned indexing workers.
- `app/src/main/java/com/personallibrary/app/backup/`: snapshot/export/staged restore.
- `app/src/main/java/com/personallibrary/app/ai/`: authenticated gateway, enrollment, exclusions/settings, search orchestration.
- `server/src/`: HTTP routing, auth/quota storage, Gemini adapter, schemas, deadlines, sanitized errors.
- `.github/workflows/android.yml`: core tests, Android unit/lint/build, emulator integration checks, retained private artifacts.
- `docs/STATUS.md`: authoritative implementation ledger, exact checks, known limitations, and remaining release gates.

## Task 1: Reproducible project and integrity core

**Files:** root Gradle/wrapper files; `core/build.gradle.kts`; core classification/storage/query/date files and tests; Android workflow; status ledger.

**Interfaces:**

- `ContentClassifier.classify(mimeType: String?, fileName: String?, explicitSticker: Boolean = false): ItemKind`.
- `ManagedAssetStore(root: Path).import(input: InputStream, originalName: String?, declaredMime: String?, availableBytes: Long = Long.MAX_VALUE): StoredAsset`.
- `StoredAsset(hash: String, relativePath: String, sizeBytes: Long, originalName: String, mimeType: String, kind: ItemKind)`.
- `SearchQuery.fts(raw: String): String?`; each token is quoted/escaped and uses prefix matching; empty/unsearchable input returns null.
- `DateRange.month(year: Int, month: Int, zone: ZoneId): DateRange`, with `startInclusive: Instant` and `endExclusive: Instant`.

- [ ] Write classifier cases: WebP remains PHOTO unless explicitly marked; octet-stream JSON is DOCUMENT; MIME case/parameters normalize; unknown binary is FILE.
- [ ] Write real temporary-directory storage tests: identical bytes reuse one blob; path-like display names cannot escape root; a failing stream leaves no final/staging file; low-space failure preserves existing blobs; byte counts/hash match fixed fixtures.
- [ ] Write search cases containing quotes/operators, emoji and non-Latin tokens, and month boundaries crossing years/timezone transitions.
- [ ] Run `./gradlew -PcoreOnly=true :core:test`; observe missing behavior, implement contracts, rerun the full core suite.
- [ ] Initialize the requested private repository, commit explicit files, create the `codex/v1` work branch, and push using the normal terminal.
- [ ] Record actual test evidence in STATUS. Commit/push Task 1 and let CI run on the pushed SHA.

## Task 2: Durable Android capture and usable Library

**Files:** `app/build.gradle.kts`, manifest/resources, Application/MainActivity, `data/LibraryDatabase.kt`, entities/DAO/repository, `capture/SharePayload.kt`, `capture/CaptureViewModel.kt`, Library and item detail screens, FileProvider paths, Android tests.

**Interfaces:** `LibraryRepository.capture(request: CaptureRequest): CaptureResult`; `observeItems(filter: LibraryFilter): Flow<List<LibraryItem>>`; `createNote(text: String): String`; `LibraryItem` references an optional managed asset, preserved original metadata and an import batch.

- [ ] Write Room/repository tests for file persistence after reopening the DB, content deduplication with independent item metadata, concurrent duplicate capture, Unicode notes, and failed import cleanup. Use real temporary files and a real Room database.
- [ ] Write Android intent decoding tests for SEND/SEND_MULTIPLE, ClipData, mixed attachments, missing MIME, caption+files, and new-intent delivery without repeating an old batch.
- [ ] Implement staging/copy/hash before DB commit; journal import state for process recovery. Copy transient URIs while the share entry owns access. Never enqueue only a transient URI for later copying.
- [ ] Implement the Material 3 three-destination shell, local note/file Add flow, share preview and Save action, type filters, thumbnails, item details, Open/Share actions and explicit errors.
- [ ] Unknown or undecodable files remain stored and open externally. Original bytes are never recompressed by storage.
- [ ] Run core + Android unit tests, `:app:lintDebug :app:assembleDebug`, and emulator persistence/capture smoke tests. Publish only private CI artifacts.
- [ ] Record exact phone-only WhatsApp/sticker tests still required; do not label synthetic share intents as actual WhatsApp acceptance. Commit/push.

## Task 3: Offline retrieval and organization

**Files:** search/date filters, collection/favorite relationships, Room FTS, local extraction/OCR worker, media/PDF/text preview adapters, collection/search UI and integration tests.

**Interfaces:** `LibraryRepository.setFavorite(id: String, value: Boolean)`; `addToCollection(itemIds: Set<String>, collectionId: String)`; `search(query: String, filter: LibraryFilter): Flow<List<LibraryItem>>`; `IndexingRepository.indexLocal(itemId: String)`.

- [ ] Write tests showing a screenshot's recognized text is retrievable after restart, collection deletion retains originals, overlapping memberships create no extra blobs, and date filters include/exclude exact boundary instants.
- [ ] Test safe FTS input at the real DAO boundary; preserve notes, URLs, emoji and filenames. New notes and extracted text update the FTS index in the same logical operation.
- [ ] Implement flat collections, Favorites/Unfiled/Due views, batch selection, Saved/Source date filtering and day/month/year grouping, clear match snippets, and restore scroll position.
- [ ] Add bounded local file text/PDF extraction and bundled Latin OCR. Separate unsupported, partial and complete coverage.
- [ ] Add supported photo/sticker/GIF, media and PDF previews, screenshot Copy text, and bounded JSON/CSV/plain-text viewing. External-view fallback stays available.
- [ ] Run full core/Android suites and emulator create/search/collection/preview flows; commit/push with evidence.

## Task 4: Trash, portability and encrypted backup

**Files:** backup core framing/manifest logic, Android backup service/SAF UI, trash/blob reference management, storage accounting, backup/restore tests.

**Interfaces:** `BackupService.export(destination: Uri, password: CharArray): BackupSummary`; `inspect(source: Uri, password: CharArray): RestorePreview`; `restore(preview: RestorePreview, policy: DuplicatePolicy): RestoreSummary`.

- [ ] Write filesystem round-trip tests for originals and metadata; wrong password, tampered ciphertext, truncated stream, unsafe archive paths, unbounded manifest sizes and duplicate entries must fail before live-library mutation.
- [ ] Implement versioned backup framing with a maintained authenticated-encryption primitive/library and a passphrase KDF; document the exact format and bounds. Never rely on the current device's Keystore as the only restore key.
- [ ] Snapshot/export all source data and relationships; regenerate derived indexes only when explicitly recorded. Validate restore into staging, preview counts and duplicates, then transactionally install metadata and managed assets with crash recovery.
- [ ] Implement 30-day Trash with Undo and safe final deletion of unreferenced blobs; accurate originals/index/cache/trash storage accounting; Last successful backup.
- [ ] Configure platform backup/data-transfer exclusions deliberately. Export using SAF. Clear cache deletes only regenerable previews.
- [ ] Run fresh-install/emulator restore and corruption cases plus the full suite, record evidence, commit/push.

## Task 5: Random Links migration

**Files:** core `WhatsAppExportParser.kt` and fixtures/tests, Android import preview/attachment matching, repository import bookkeeping.

**Interfaces:** `WhatsAppExportParser.parse(text: String, dateOrder: DateOrder?): ExportParseResult`; explicit unresolved date ambiguity and missing-attachment records; idempotent import identifiers retain source provenance.

- [ ] Write multiline, Unicode, year/month boundary, locale/date-order ambiguity, system-message and missing-media fixtures before parsing code.
- [ ] Implement conservative parsing and attachment matching; preserve raw export, original dates and supplied sender data; never guess missing sender/date/attachments.
- [ ] Implement counts/ambiguity preview, matching recent attachments, Random Links collection, missing-media report and resumable batch progress.
- [ ] Verify repeated import reuses bytes and does not silently duplicate messages. Preserve the source export/group. Full suites and emulator sample import; commit/push.

## Task 6: Local reminders and app lock

**Files:** reminder DB/scheduler, receiver/notification actions, boot rescheduling, optional biometric/device-credential lock, related UI and tests.

**Interfaces:** `ReminderScheduler.schedule(reminder: Reminder): ScheduleResult`; `cancel(id: String)`; rescheduling reads persisted records and current platform permissions.

- [ ] Write real scheduling boundary tests for permission denial, cancellation/snooze, reboot restoration, date-only events and timezone changes.
- [ ] Implement contextual notification permission requests, explicit reminder confirmation, supported exact/inexact alarm behavior with honest permission UI, and item navigation from notifications.
- [ ] Implement optional app lock and background/re-entry protection without locking out the incoming capture flow or leaking item previews while locked.
- [ ] Run emulator notification/lock tests and record OEM/physical-phone acceptance still outstanding; full suites; commit/push.

## Task 7: Authenticated Gemini backend

**Files:** `server/package.json`, pinned lockfile, TypeScript sources/tests, `.env.example`, Render service configuration and setup guide, Android gateway/Keystore enrollment.

**Interfaces:** authenticated `/v1/ai/analyze`, `/v1/ai/embed`, `/v1/ai/search-plan` with versioned JSON schemas, request IDs, upload/provider/body deadlines, bounded sizes and stable sanitized error codes. Device pairing precedes privileged API access; the server stores no content library.

- [ ] Write HTTP tests for unenrolled/expired/revoked devices, challenge replay, invalid schemas/size, deadline during stalled upload, invalid provider response, quota vs transient failure, and cancellation. Use a real local server and controlled provider transport.
- [ ] Implement durable auth/revocation/usage limits, Keystore-backed device signing, short-lived sessions, structured Gemini output validation, no content logging, and safe retry ceilings. Never put API/enrollment secrets in the APK.
- [ ] Use configurable tested Flash and embedding models. Provide synthetic-only live smoke command that checks credential presence without printing values.
- [ ] Add deployment configuration and setup instructions; keep actual credentials outside Git. A configured local service and automated fake-provider proof are distinct from live Render/provider proof.
- [ ] Run server typecheck/tests/build and Android client tests; commit/push. Record missing operator configuration without claiming deployment.

## Task 8: Automatic AI enrichment and semantic retrieval

**Files:** persisted analysis jobs, WorkManager orchestration, media sampling/transcription adapters, embedding storage/versioning, local similarity search, assisted Search UI/settings.

**Interfaces:** `AiGateway.analyze(input: AnalysisInput): AnalysisResult`; `embed(text: String, task: EmbeddingTask): Embedding`; `SearchRepository.assisted(query: String, filter: LibraryFilter): SearchResults`.

- [ ] Write tests for excluded items/collections, process restart, network/battery constraint loss, quota/permanent failure, stale result after edit/delete, incompatible embedding dimensions/versions, and strict type/date constraints during ranking.
- [ ] Implement automatic bounded batches after durable local save, Wi-Fi/background controls and Pause AI, coverage states and version-keyed jobs. Retain owner notes separately from machine descriptions.
- [ ] Apply spec limits: 1,600 px analysis images; up to six video frames; automatic audio up to five minutes; PDF batches up to ten pages/initial 100-page ceiling; text extraction initially 1 MiB. Preserve complete originals and expose partial coverage.
- [ ] Store normalized 768-dimensional embeddings and source chunk/page references locally; combine safe full-text search with a semantic scan of the permitted index, not only a keyword shortlist. Query AI returns an allowlisted typed plan, never executable SQL.
- [ ] Return real item cards with match explanations and source links. New AI requests require internet; local search remains usable during outages/wakeup.
- [ ] Run deterministic retrieval fixtures and synthetic live tests when configured, full server/core/Android suites; commit/push with separate quality/latency evidence.

## Task 9: English voice search and release acceptance

**Files:** speech adapter and editable transcript UI, accessibility/performance tests, signing/distribution guidance, final acceptance matrix and private APK artifact.

- [ ] Write recognition availability/permission/error/cancellation tests and ensure voice and typed queries enter the same search pipeline.
- [ ] Prefer an available English on-device recognizer; identify online fallback and obtain the relevant microphone/cloud-recording consent. Do not add always-listening behavior.
- [ ] Verify TalkBack, large fonts, dark/light themes, touch targets, reduced motion, keyboard/insets, navigation and empty/error states.
- [ ] Measure local search p95 against 10,000 realistic items/chunks; target under 300 ms. Evaluate 20-30 owner-like queries with known targets and report exact/semantic/visual coverage separately; target at least 90% top-five success for supported fully indexed cases.
- [ ] Run all build/test/lint checks; fresh whole-change review, fix important findings with reproducing tests, and push final source. Produce a signed private candidate only with secure signing material available.
- [ ] Complete actual WhatsApp text/media/sticker/batch, source-deletion, large file, reboot/OEM reminder, voice, and fresh-device backup restore acceptance. Emulator success cannot satisfy this gate.
- [ ] Mark the persistent goal complete only when current evidence proves the whole requested V1 and repository/push/build deliverables; otherwise keep it active and record the precise outstanding gates.
