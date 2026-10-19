# Memora V1.0.1 requirement audit

Authority: `CODEX_V1_0_1_MASTER_PROMPT.md` (verbatim). This is a live audit, not a completion claim.

Status vocabulary at final audit: COMPLETE / PARTIAL / NOT NEEDED / BLOCKED. Pending work remains PARTIAL until evidence is recorded.

| Section | Requirement | Status | Evidence / remaining work |
| --- | --- | --- | --- |
| 0 | NON-NEGOTIABLE RULES | COMPLETE | Existing architecture audited; scoped phase commits, synthetic fixtures, permanent identity and non-destructive migration preserved. Final runtime/signing gates remain Phase 7. |
| 1 | DEFINITION OF GREEN | PARTIAL | Pending phase verification and final audit. |
| 2 | CRITICAL AI ARCHITECTURE RULE | COMPLETE | Existing items, FTS, search_documents, embeddings and durable analysis jobs reused; no parallel catalog or global memory file. |
| 3 | IMPLEMENTATION ORDER IS MANDATORY | COMPLETE | Phases 1-6 verified and committed in mandatory order; Phase 7 active. |
| 4 | BACKUP EXPORT MODE BUG — P0 | COMPLETE | Explicit BackupMode and engine-minimum/exact-confirmation policy; core tests passed; 6 Compose safety tests compile, runtime deferred to hosted/final verification. |
| 5 | RESTORE STAGING PRIVACY — P0 | COMPLETE | Owned staging, cancel/dismiss/disposal/exception/finally cleanup, abandoned-stage sweep and active-stage cache protection; 3 core staging tests passed. |
| 6 | REMINDER + TRASH RESTORE BUG — P0/P1 | COMPLETE | Single/batch/Undo future scheduling, past/done behavior, timezone persistence, boot/stale alarm/collision protections; focused Robolectric suites passed. Restore All UI follows Phase 2. |
| 7 | TRASH UX AND DESTRUCTIVE SAFETY | COMPLETE | Snackbar single/bulk Undo, confirmed Delete Forever/Empty/Restore All, common reminder-aware batch restore. JVM safety tests passed; Compose tests compile, runtime deferred. |
| 8 | COLLECTION MANAGEMENT | COMPLETE | Visible overflow, computed protections, validated name/icon/color editing, privacy dialog, atomic membership-only deletion; management/integrity tests passed. |
| 9 | RANDOM LINKS MUST HAVE STABLE IDENTITY | COMPLETE | Schema 4→5 portable optional application role, legacy adoption, renamed routing/export/fresh/merge restore; repeated restore and identity collision regressions passed. |
| 10 | REAL MULTI-SELECT | COMPLETE | List/grid/search/collection selection, count/close/Back, off-main Select All Visible guarded by results/selection generations, union/subtract and Trash; 7 Compose regressions compile, runtime deferred. |
| 11 | EDIT ITEM — REQUIRED V1 FEATURE | COMPLETE | Native text/link editing and media title editing, safe URI validation, original bytes/filename/timestamps/import provenance preserved; domain tests passed and editor Compose tests compile. |
| 12 | EDITS MUST UPDATE DERIVED DATA CORRECTLY | COMPLETE | Content version invalidation/FTS/local extraction plus cached metadata-only text refresh; no cached-media full re-analysis, stale publications rejected, partial coverage preserved. JVM fake-HTTP regressions passed; cross-cutting privacy audit follows Phase 4. |
| 13 | WHATSAPP-STYLE LIVE SEARCH — CORE V1 | COMPLETE | Phase 3 commit 14073e9: live debounced prefix updates; narrowing FTS/domain regressions passed; Compose runtime deferred. |
| 14 | SEARCHABLE CONTENT | COMPLETE | Existing local FTS fields preserved: title/body/filename/extracted text/AI metadata; ordinary search makes no provider request. |
| 15 | PREFIX MATCHING | COMPLETE | Correct FTS4 quoted-token prefix syntax; exact-short-word distractor and progressive-prefix regressions passed. |
| 16 | FIX LIVE-SEARCH RACE | COMPLETE | Shared generation/context runner cancels and rejects obsolete query/filter/data publications; focused coroutine tests passed. |
| 17 | SEARCH SNIPPETS | COMPLETE | Existing snippet engine shares sanitized terms, prioritizes token-start evidence and handles Unicode code points; focused regressions passed. |
| 18 | SEARCH FILTER VISIBILITY | COMPLETE | Shared visible collection/type/date chips and Clear Filters on Library/Search; Compose tests compile, runtime deferred. |
| 19 | EMPTY STATES | COMPLETE | Distinct empty Library, query/filter zero results and AI/local errors; additive AI-only wording corrected; runtime deferred. |
| 20 | LOCAL SEARCH + AI SEARCH UX | COMPLETE | Local results preserved alongside additive semantic matches and explicit AI progress/errors; union selection respects filters. Direct Q&A remains Phase 5. |
| 21 | AI EXCLUSION MUST BE STRICT | COMPLETE | Phase 4: fresh filtered SQL candidates; omitted all collection-name plan metadata; per-dispatch guards cover auth/retry and publication. Synthetic payload/privacy tests passed. |
| 22 | DO NOT BREAK LOCAL SEARCH WHEN AI IS EXCLUDED | COMPLETE | Exclusion changes eligibility/job state only; exact local document/embedding preservation and offline FTS regressions passed. |
| 23 | AI INDEX LIFECYCLE | COMPLETE | Existing versioned publication/derived-data deletion retained; re-enable uses compatible cached semantics or metadata-only jobs, preserves attempts, reconciles single/bulk/deleted collection membership even after ordinary caller cancellation. JVM regressions passed; physical lifecycle QA pending. |
| 24 | WI-FI-ONLY AI | COMPLETE | Current unmetered capability checked after caller guard for each AI/auth HTTP dispatch; queued unique work replaces obsolete constraints, offline work retains durable attempt. Synthetic policy/401/cancellation tests passed; physical network-switch QA pending. |
| 25 | REUSE EXISTING AI INGESTION | COMPLETE | Existing LocalIndexer, durable jobs, canonical versioned chunks and embedding model reused. Interactive retrieval bounded to 160 local candidates/480 documents/4 per item, ranks natural-query terms and later page evidence. No parallel catalog/index. |
| 26 | PHASE 5 — BOUNDED AI QUESTION ANSWERING | COMPLETE | Implemented only after Phase 4 GREEN+commit567c85d; bounded direct-answer flow tested with synthetic local HTTP. Live deployment/provider acceptance remains unexecuted. |
| 27 | AI Q&A FLOW | COMPLETE | Existing fresh local snapshot/FTS/chunks/semantic ranks select cached evidence before answer POST; exclusions/filters/version/request guards retained. No-evidence flow avoids answer POST. |
| 28 | BACKEND IMPLEMENTATION RULE | COMPLETE | Small authenticated answer endpoint reuses device sessions, rate limits, body limits, timeout/AbortSignal/provider patterns;104 backend tests/typecheck/build passed. Endpoint not deployed; client404 clearly preserves local/related matches. |
| 29 | BOUNDED EVIDENCE / TOP-K | COMPLETE | Max12 chunks/3 per item/1200 each/12000 total text/32KiB UTF-8 text/64KiB request; question2000/title200; server independently enforces limits; client caps response32KiB. Approximate bounded recall may miss an unsignaled later page. |
| 30 | AI ANSWERS REQUIRE PROVENANCE | COMPLETE | Request-local source refs map to local items/pages; server remaps again before provider and validates citations before mapping back. Executed VM/source tests pass; four source/answer Compose tests compile, runtime deferred. |
| 31 | AI ANSWER GROUNDING | COMPLETE | Higher-priority evidence-only/untrusted-content prompt; strict ANSWERED/CONFLICTING/INSUFFICIENT shape and allowlisted citations; fixed local insufficiency wording. Structural validation cannot prove model factual accuracy; real provider/manual QA remains pending. |
| 32 | DO NOT BUILD V2 KNOWLEDGE GRAPH | COMPLETE | No people/fact/relationship/knowledge graph, global memory file or duplicate AI catalog added. Existing schema5/index pipeline retained. |
| 33 | VOICE SEARCH RECOVERY | COMPLETE | Voice errors name available typed input; executable VoiceSearchTest passed. |
| 34 | REMINDERS COLLECTION NAME | COMPLETE | Computed collection and active-filter chip say Reminders; stable computed_due query retained. |
| 35 | DARK MODE / LIGHT MODE | PARTIAL | Fixed day/night/manual launch resources, API31 app splash preference and paired/type contrast; 7 SDK-expanded launch regressions passed. API29-30 OS starting window may precede saved manual override; physical cold-launch acceptance pending. |
| 36 | NAVIGATION | COMPLETE | Hierarchical Back/visible Up, modal/selection priority, one-time notification consumption and saveable detail ID; navigation instrumentation compiles, hosted runtime pending. |
| 37 | LIBRARY FILTER / CONTROL LAYOUT | COMPLETE | Existing horizontally scrolling filters/sort and visible active filters retained; Clear Filters remains available. Large-font tests compile; device acceptance pending. |
| 38 | LIST / GRID FUNCTIONAL PARITY | COMPLETE | Grid Share reuses common handler; list/grid favorite/reminder/selection actions retained. Presentation tests passed; parity instrumentation compiles. |
| 39 | ACCESSIBILITY | COMPLETE | 48dp controls, meaningful picker/favorite semantics, constrained card action layout and >=4.5 type-label contrast verified locally; large-font/TalkBack device acceptance pending. |
| 40 | ICON SEMANTICS | COMPLETE | Headphones/Link/Description type icons; executable icon regressions passed. |
| 41 | FILTERED EMPTY STATE | COMPLETE | Phase 3 shared filtered-empty wording and Clear Filters; Compose coverage compiled, runtime deferred. |
| 42 | COLLECTION AI PRIVACY UI | COMPLETE | Collection-specific AI Privacy remains in visible overflow dialog; no permanent card toggle. |
| 43 | SHARE PREVIEW — OPTIONAL | NOT NEEDED | Optional filename-list preview deferred; existing confirmed share preview preserved. |
| 44 | FILEPROVIDER — AUDIT, DON'T BREAK | COMPLETE | Only files/storage_root/blobs is exposed; real manifest/resource test passed, all three consumers audited. Real provider URI open/read/private-cache-prefix boundary instrumentation compiles; Windows AndroidX separator mismatch prevents local URI runtime claims. |
| 45 | IMPORT STORAGE PREFLIGHT | COMPLETE | Optional provider size, 16MiB disk headroom and streamed budget reuse atomic storage/Room publication; core and real fake-provider capture regressions passed, including missing/negative/oversize metadata. |
| 46 | STORAGE SIZE DISPLAY | COMPLETE | Settings explicitly labels logical content size; no exact reclaimable-storage claim. |
| 47 | FTS DATABASE OPEN BEHAVIOR | COMPLETE | Diagnostic reproduced eight unnecessary DDL changes; conditional repair preserves unchanged opens, rebuilds missing/stale indexes, and reconciles legacy triggers during 4→5 migration. Actual Room tests passed. |
| 48 | ROOM SCHEMA EXPORT | COMPLETE | Room schema export enabled with KSP location; original schema 4 and resulting schema 5 retained. Actual Room migration-open regression passed, no destructive migration. |
| 49 | NO CASUAL DELETE-ALL FEATURE | COMPLETE | No casual Delete All Library command added; filtered Select All Visible -> Trash -> confirmed Empty Trash retained. |
| 50 | NO "DELETE COLLECTION + CONTENT" MAGIC | COMPLETE | Collection deletion removes membership only; saved items and other memberships preserved by executed regressions. |
| 51 | SECURITY POSITIONING | COMPLETE | Keystore/auth/HTTPS/encrypted backups/platform-backup exclusions/non-exported components retained; app lock is not represented as an encrypted vault. |
| 52 | PRIVATE DATA LOGGING | COMPLETE | No private-content/passphrase/provider-secret logs added; backend logs request metadata and returns sanitized provider errors. Signing diagnostics remain transient. |
| 53 | PERFORMANCE | PARTIAL | Bounded AI candidates/evidence, latest-query cancellation and off-main Select All tested; real large-library latency, scrolling and memory measurements remain device QA. |
| 54 | VERSION / BRANCH | PARTIAL | Separate codex/v1.0.1-hardening branch; version1.0.1/code3 prepared. Final source commit/push and exact-source hosted verification pending. |
| 55 | RELEASE SIGNING | PARTIAL | Same permanent key and isolated QA-candidate mode preserved; 51 synthetic safety checks per PowerShell runtime passed. Actual signing awaits exact-source five-job CI. |
| 56 | APK PACKAGING | PARTIAL | ARM64/universal release strategy and R8/shrinking preserved; exact instrumentation-only keep rules regenerated. Final optimized/release artifact checks in progress. |
| 57 | TESTING — REQUIRED | PARTIAL | Pending phase verification and final audit. |
| 58 | REQUIRED REGRESSION COVERAGE | PARTIAL | Pending phase verification and final audit. |
| 59 | MANUAL PHYSICAL QA CHECKLIST | COMPLETE | Same physical QA checklist finalized with synthetic navigation/provider/large-font/network/backup/reminder/theme checks; all physical checks explicitly unexecuted. |
| 60 | DEFINITION OF DONE FOR AI | COMPLETE | All 12 AI architecture statements supported by existing pipeline, bounded local-first protocol, per-dispatch privacy/network guards, cached-only evidence, server bounds and local source mapping. Live endpoint/provider acceptance remains separate. |
| 61 | DO NOT OVERBUILD | COMPLETE | No knowledge graph, people/fact database, global memory, cloud sync, telemetry, full wipe or major redesign added. |
| 62 | FINAL REPORT FORMAT | PARTIAL | Pending phase verification and final audit. |
| 63 | FINAL PRODUCT TARGET | PARTIAL | Pending phase verification and final audit. |
| 64 | START | PARTIAL | Pending phase verification and final audit. |
