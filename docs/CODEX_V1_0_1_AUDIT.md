# Memora V1.0.1 requirement audit

Authority: `CODEX_V1_0_1_MASTER_PROMPT.md` (verbatim). This is a live audit, not a completion claim.

Status vocabulary at final audit: COMPLETE / PARTIAL / NOT NEEDED / BLOCKED. Pending work remains PARTIAL until evidence is recorded.

| Section | Requirement | Status | Evidence / remaining work |
| --- | --- | --- | --- |
| 0 | NON-NEGOTIABLE RULES | PARTIAL | Pending phase verification and final audit. |
| 1 | DEFINITION OF GREEN | PARTIAL | Pending phase verification and final audit. |
| 2 | CRITICAL AI ARCHITECTURE RULE | PARTIAL | Pending phase verification and final audit. |
| 3 | IMPLEMENTATION ORDER IS MANDATORY | PARTIAL | Pending phase verification and final audit. |
| 4 | BACKUP EXPORT MODE BUG — P0 | COMPLETE | Explicit BackupMode and engine-minimum/exact-confirmation policy; core tests passed; 6 Compose safety tests compile, runtime deferred to hosted/final verification. |
| 5 | RESTORE STAGING PRIVACY — P0 | COMPLETE | Owned staging, cancel/dismiss/disposal/exception/finally cleanup, abandoned-stage sweep and active-stage cache protection; 3 core staging tests passed. |
| 6 | REMINDER + TRASH RESTORE BUG — P0/P1 | COMPLETE | Single/batch/Undo future scheduling, past/done behavior, timezone persistence, boot/stale alarm/collision protections; focused Robolectric suites passed. Restore All UI follows Phase 2. |
| 7 | TRASH UX AND DESTRUCTIVE SAFETY | COMPLETE | Snackbar single/bulk Undo, confirmed Delete Forever/Empty/Restore All, common reminder-aware batch restore. JVM safety tests passed; Compose tests compile, runtime deferred. |
| 8 | COLLECTION MANAGEMENT | COMPLETE | Visible overflow, computed protections, validated name/icon/color editing, privacy dialog, atomic membership-only deletion; management/integrity tests passed. |
| 9 | RANDOM LINKS MUST HAVE STABLE IDENTITY | COMPLETE | Schema 4→5 portable optional application role, legacy adoption, renamed routing/export/fresh/merge restore; repeated restore and identity collision regressions passed. |
| 10 | REAL MULTI-SELECT | COMPLETE | List/grid/search/collection selection, count/close/Back, off-main Select All Visible guarded by results/selection generations, union/subtract and Trash; 7 Compose regressions compile, runtime deferred. |
| 11 | EDIT ITEM — REQUIRED V1 FEATURE | COMPLETE | Native text/link editing and media title editing, safe URI validation, original bytes/filename/timestamps/import provenance preserved; domain tests passed and editor Compose tests compile. |
| 12 | EDITS MUST UPDATE DERIVED DATA CORRECTLY | COMPLETE | Content version invalidation/FTS/local extraction plus cached metadata-only text refresh; no cached-media full re-analysis, stale publications rejected, partial coverage preserved. JVM fake-HTTP regressions passed; cross-cutting privacy audit follows Phase 4. |
| 13 | WHATSAPP-STYLE LIVE SEARCH — CORE V1 | PARTIAL | Pending phase verification and final audit. |
| 14 | SEARCHABLE CONTENT | PARTIAL | Pending phase verification and final audit. |
| 15 | PREFIX MATCHING | PARTIAL | Pending phase verification and final audit. |
| 16 | FIX LIVE-SEARCH RACE | PARTIAL | Pending phase verification and final audit. |
| 17 | SEARCH SNIPPETS | PARTIAL | Pending phase verification and final audit. |
| 18 | SEARCH FILTER VISIBILITY | PARTIAL | Pending phase verification and final audit. |
| 19 | EMPTY STATES | PARTIAL | Pending phase verification and final audit. |
| 20 | LOCAL SEARCH + AI SEARCH UX | PARTIAL | Pending phase verification and final audit. |
| 21 | AI EXCLUSION MUST BE STRICT | PARTIAL | Pending phase verification and final audit. |
| 22 | DO NOT BREAK LOCAL SEARCH WHEN AI IS EXCLUDED | PARTIAL | Pending phase verification and final audit. |
| 23 | AI INDEX LIFECYCLE | PARTIAL | Pending phase verification and final audit. |
| 24 | WI-FI-ONLY AI | PARTIAL | Pending phase verification and final audit. |
| 25 | REUSE EXISTING AI INGESTION | PARTIAL | Pending phase verification and final audit. |
| 26 | PHASE 5 — BOUNDED AI QUESTION ANSWERING | PARTIAL | Pending phase verification and final audit. |
| 27 | AI Q&A FLOW | PARTIAL | Pending phase verification and final audit. |
| 28 | BACKEND IMPLEMENTATION RULE | PARTIAL | Pending phase verification and final audit. |
| 29 | BOUNDED EVIDENCE / TOP-K | PARTIAL | Pending phase verification and final audit. |
| 30 | AI ANSWERS REQUIRE PROVENANCE | PARTIAL | Pending phase verification and final audit. |
| 31 | AI ANSWER GROUNDING | PARTIAL | Pending phase verification and final audit. |
| 32 | DO NOT BUILD V2 KNOWLEDGE GRAPH | PARTIAL | Pending phase verification and final audit. |
| 33 | VOICE SEARCH RECOVERY | PARTIAL | Pending phase verification and final audit. |
| 34 | REMINDERS COLLECTION NAME | PARTIAL | Pending phase verification and final audit. |
| 35 | DARK MODE / LIGHT MODE | PARTIAL | Pending phase verification and final audit. |
| 36 | NAVIGATION | PARTIAL | Pending phase verification and final audit. |
| 37 | LIBRARY FILTER / CONTROL LAYOUT | PARTIAL | Pending phase verification and final audit. |
| 38 | LIST / GRID FUNCTIONAL PARITY | PARTIAL | Pending phase verification and final audit. |
| 39 | ACCESSIBILITY | PARTIAL | Pending phase verification and final audit. |
| 40 | ICON SEMANTICS | PARTIAL | Pending phase verification and final audit. |
| 41 | FILTERED EMPTY STATE | PARTIAL | Pending phase verification and final audit. |
| 42 | COLLECTION AI PRIVACY UI | PARTIAL | Pending phase verification and final audit. |
| 43 | SHARE PREVIEW — OPTIONAL | PARTIAL | Pending phase verification and final audit. |
| 44 | FILEPROVIDER — AUDIT, DON'T BREAK | PARTIAL | Pending phase verification and final audit. |
| 45 | IMPORT STORAGE PREFLIGHT | PARTIAL | Pending phase verification and final audit. |
| 46 | STORAGE SIZE DISPLAY | PARTIAL | Pending phase verification and final audit. |
| 47 | FTS DATABASE OPEN BEHAVIOR | PARTIAL | Pending phase verification and final audit. |
| 48 | ROOM SCHEMA EXPORT | COMPLETE | Room schema export enabled with KSP location; original schema 4 and resulting schema 5 retained. Actual Room migration-open regression passed, no destructive migration. |
| 49 | NO CASUAL DELETE-ALL FEATURE | PARTIAL | Pending phase verification and final audit. |
| 50 | NO "DELETE COLLECTION + CONTENT" MAGIC | PARTIAL | Pending phase verification and final audit. |
| 51 | SECURITY POSITIONING | PARTIAL | Pending phase verification and final audit. |
| 52 | PRIVATE DATA LOGGING | PARTIAL | Pending phase verification and final audit. |
| 53 | PERFORMANCE | PARTIAL | Pending phase verification and final audit. |
| 54 | VERSION / BRANCH | PARTIAL | Pending phase verification and final audit. |
| 55 | RELEASE SIGNING | PARTIAL | Pending phase verification and final audit. |
| 56 | APK PACKAGING | PARTIAL | Pending phase verification and final audit. |
| 57 | TESTING — REQUIRED | PARTIAL | Pending phase verification and final audit. |
| 58 | REQUIRED REGRESSION COVERAGE | PARTIAL | Pending phase verification and final audit. |
| 59 | MANUAL PHYSICAL QA CHECKLIST | PARTIAL | Pending phase verification and final audit. |
| 60 | DEFINITION OF DONE FOR AI | PARTIAL | Pending phase verification and final audit. |
| 61 | DO NOT OVERBUILD | PARTIAL | Pending phase verification and final audit. |
| 62 | FINAL REPORT FORMAT | PARTIAL | Pending phase verification and final audit. |
| 63 | FINAL PRODUCT TARGET | PARTIAL | Pending phase verification and final audit. |
| 64 | START | PARTIAL | Pending phase verification and final audit. |
