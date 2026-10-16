# Personal Library V1 implementation and acceptance plan

> Execution is inline on owner-authorized main. A fresh whole-change reviewer is required before completion. This plan was reconciled with verified implementation on 16 October 2026; the approved design remains the product acceptance reference.

**Goal:** Durable Android capture, organization, offline/assisted retrieval, encrypted backup, WhatsApp migration, reminders, and English voice search. The owner explicitly split final delivery into agent verification, Desktop test APK, owner acceptance, then V1 freeze and official signing.

**Architecture:** Native Compose app, Room schema 4 and FTS4, SHA-256 managed originals, local extraction and versioned WorkManager analysis, a Kotlin/JVM integrity core, and a stateless Node Gemini proxy. No web app, new provider or replacement storage architecture.

**Pinned stack:** Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, KSP 2.3.5, Compose BOM 2024.09.00, Room 2.7.0, JDK 21 / Java 17 bytecode; minimum API 29, compile 36.1, target 36. Core package is `com.personallibrary.core`; Android sources use `com.example`, application ID `com.personallibrary.app`.

**References:** [Design](../specs/2026-10-13-personal-library-design.md), [status and exact evidence](../../STATUS.md), [finalization red/green ledger](2026-10-15-v1-finalization.md), [phone handoff](../../HANDOFF.md), [backup format](../../BACKUP_FORMAT.md).

## Constraints

- Authoritative data/originals stay on the phone; save is durable before success and never waits for Gemini.
- Preserve independent Saved, Source and Reminder dates; ambiguous import dates are not guessed.
- Automatic AI was selected by the owner. Exclusions/pause control future uploads while local search remains available.
- Keep provider/enrollment/signing secrets outside Git and Android. Test providers use synthetic data.
- Preserve Room migrations, many-to-many collections, WorkManager, Tink streaming backup and existing Material presentation.
- Main is explicitly authorized for this finalization. Preserve historical codex/v1; do not blindly merge/delete it.
- Automated/fake-provider/emulator evidence does not replace real WhatsApp, phone, live Gemini or visual/quality acceptance.

## 1. Reproducible project and integrity core

- [x] Pin build/runtime dependencies and implement core-only configuration without Android SDK configuration.
- [x] Verify real managed-blob stream/dedup/failure/path/bounds behavior; safe FTS tokens, Unicode and calendar boundaries.
- [x] Restore exact-SHA core, Android JVM/lint/build, backend and emulator workflow with retained APK/checksum/reports.

## 2. Durable capture and Library

- [x] Decode SEND/SEND_MULTIPLE, ClipData, streams and missing MIME; retain Share Preview and originals.
- [x] Own confirmed transient-URI copy in a foreground service; journal interrupted batches; surface cancel/partial failures; prevent stale replay/false Saved.
- [x] Transactionally commit item/membership/refcounts and clean new orphan publications on failure; test consecutive service batches and lifecycle cleanup.
- [x] Provide note/file Add, Library filters/thumbnails/details/Open/Share and external fallback for unknown content.
- [ ] Owner: actual WhatsApp/sticker/batch/source-deletion and large-file/background behavior.

## 3. Retrieval and organization

- [x] Room schema 4, explicit 2->3 and 3->4 migrations, real FTS operator/Unicode tests and transactional memberships.
- [x] Collections, favorites, computed views, batch operations, separate Saved/Source date basis, custom calendar ranges, grouping, source sort both directions and retained list/grid state.
- [x] Bounded text/PDF and bundled Latin OCR; separate owner notes from derived text; local snippets and existing media previews.
- [x] Preserve PDF page chunks and reindex legacy flattened originals before remote analysis; API 35 local-only instrumentation proof.
- [ ] Owner: real OCR/native previews, browse continuity, accessibility and realistic search quality/latency.

## 4. Trash and encrypted portability

- [x] Tink AES256_GCM_HKDF_1MB stream, PBKDF2-HMAC-SHA256/600,000, wrapped fresh keyset, strict framing and passphrase minimum; no JSON/ZIP fallback.
- [x] Fail missing-original export; inspect exact asset set, sizes/hashes, corruption/truncation/password/bounds including empty files.
- [x] Atomic metadata snapshot, staged restore, real blob checks, collision remap, duplicate policy, shared references, rollback orphan reconciliation, portable date-only fields, derived reindex and alarms after commit.
- [x] Trash/restore/purge/storage/backup UI and deliberate platform backup/transfer exclusions.
- [ ] Owner: fresh-device restore and inspection of actual originals/reminders/collections.

## 5. WhatsApp migration

- [x] Conservative multiline/date-order parser and deterministic imports retaining raw export, sender/source/saved provenance.
- [x] SAF text and optional separate attachment selection, preview before confirmation, matched/missing/unmatched reporting and Random Links destination.
- [x] Idempotent repeated attachment refcounts; replacement invalidates old derived state/jobs transactionally and preserves owner metadata.
- [ ] Owner: real exported text and attachments; extract ZIP packages before selection.

## 6. Reminders and lock

- [x] Contextual notification permission, honest blocked/exact/inexact state, date-only calendar UI/09:00 local persistence, timezone/time-change recomputation, obsolete alarm cancellation and tests.
- [x] Snooze/Complete/navigation, boot rescheduling and biometric/device-credential lock.
- [ ] Owner: actual notifications, reboot/OEM behavior, timezone, lock and incoming-share acceptance.

## 7. Authenticated backend

- [x] Require enrollment/renewal proof of possession, canonical challenges and credential key fingerprint; negative and restart-safe signed renewal tests.
- [x] Production Android Keystore fails closed; test signers explicitly injected; secrets stay server-side.
- [x] Safe typed AI routes, body size bounds, response/provider deadlines and cancellation, schema/output validation, rate limits and environment-configured revocations. Timeout aborts body parsing and closes incomplete ingress after flushing 504.
- [x] Render configuration/setup guide; health and anonymous rejection live checks. Stateless credentials survive restart with stable secrets; replay/rate tracking is process-memory state.
- [ ] Owner: paired-phone authenticated live Gemini analysis, embedding and search. Health/fake-provider proof does not close this gate.

## 8. Automatic AI and semantic retrieval

- [x] Versioned persisted jobs/WorkManager, Wi-Fi/battery controls, pause/resume, exclusions retaining local extraction, bounded attempts, explicit retry and stale-publication guards.
- [x] Images bounded, four video frames, audio <=first five minutes, PDF batches <=10 pages within initial 100 pages, bounded text; originals unchanged and partial/unsupported state explicit.
- [x] Local normalized 768-D compatible embeddings and page/chunk provenance; typed query plans, strict filters, semantic scan and distinct semantic/AI-plan/local labels.
- [x] API 35 audio extraction and PDF page/local-only/legacy tests; deterministic core/backend/Android regressions.
- [ ] Owner: live media usefulness, network recovery and retrieval quality on realistic content.

## 9. Voice and delivery acceptance

- [x] Prefer available on-device English recognition before checking default-service availability; declare speech visibility; label fallback; editable transcript uses typed pipeline. Availability/error/transcript tests pass.
- [x] Fresh whole-change review, reproduce/fix Important findings, full final code gates and prohibited-pattern audit.
- [x] Update docs only after code verification; preserve honest evidence boundaries and official-signing deferral.
Delivery gate: exact documentation SHA CI/Render revision and checksum-verified Desktop APK. The accompanying acceptance record supplies final values; no permanent key yet.
- [ ] Owner: voice/microphone, TalkBack, large fonts, themes, targets, keyboard/insets, navigation and error states.
- [ ] Owner: 20-30 realistic retrieval queries (target >=90% top-five for supported fully indexed content) and 10,000-item/chunk local search p95 (target <300 ms). No measured pass is claimed.
- [ ] After owner acceptance: record accepted revision, freeze V1, create official signed version using owner-managed permanent signing material and a safe data transition.

The original design's physical/provider/accessibility/performance acceptance remains open explicitly. The owner's latest instruction authorizes completing the agent delivery goal at the green Desktop handoff, while these acceptance gates remain their part. A debug APK is not an official release.
