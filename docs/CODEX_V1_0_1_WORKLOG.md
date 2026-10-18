# Memora V1.0.1 worklog

## Current checkpoint

- Checkpoint housekeeping: Git whitespace check correctly detected the owner's intentional Markdown hard-break spaces in the verbatim master. Do not alter the master. Added a narrowly scoped `.gitattributes` rule to preserve its bytes/line endings and intentional whitespace in Git; trimmed only generated QA checklist EOF whitespace. Restage and verify master blob hash matches the attachment before commit.

- **Phase 1 GREEN for locally executable verification; ready for checkpoint commit.** Latest full relevant run `docs/v1.0.1-phase1-cache-green.log` ended BUILD SUCCESSFUL in 2m19s. Core **53/53**, Android JVM **71/71**, zero failures/errors/skips. Debug lint has **0 errors / 80 warnings**. Debug and AndroidTest APKs assembled; **6 new Compose safety tests compile but have not run**. Backend baseline **32/32**, typecheck/build passed.
- Phase 1 reviewer findings addressed: Search Undo and atomic stale-confirmation guard; restore cancellation covers return-dispatch cancellation; Clear Cache protects active staging. Master SHA remains unchanged. No source schema/signing/packaging changes in Phase 1.
- Deferred to available hosted emulator/final Phase 7: actual instrumentation runtime; regenerate exact conditional optimized instrumentation keep rules after all new test references are final. No physical-device verification claimed.
- Next: commit Phase 1, record hash, then begin Phase 2 only. Phase 2 will extend existing collection/membership/item APIs, introduce a stable optional portable Random Links role via non-destructive migration 4→5, and complete selection/edit/Restore All UI. AI Q&A remains gated behind Phases 1–4.

- Second full Phase 1 run `docs/v1.0.1-phase1-final.log` succeeded (3 minutes), including stale-confirmation regression and compilation of 6 new Compose tests. Final counts and checkpoint follow after the small cache-cleanup safeguard: Clear Cache now preserves the restore owner's directory and asks that owner to clean abandoned stages, so it cannot erase an active preview.
- Final manual QA checklist initialized at `docs/CODEX_V1_0_1_PHYSICAL_QA.md`, all 78 checks explicitly unexecuted; candidate SHA/artifact hashes remain pending Phase 7.

- Phase 1 verification run `docs/v1.0.1-phase1-checks.log`: **BUILD SUCCESSFUL**, core 53/53, Android JVM 70/70, zero failures/errors/skips; debug lint (80 warnings, zero errors), debug APK and instrumentation APK assembled. These results predate the final reviewer fixes/new UI tests; current `docs/v1.0.1-phase1-final.log` is verifying them before checkpoint.
- Backend baseline `docs/v1.0.1-phase1-server.log`: 32/32, zero failed/skipped, TypeScript typecheck/build passed. Earlier sandbox socket failures were environmental; no backend implementation was changed to mask them.
- Reviewer fixes: Search now consumes Trash events and shows immediate Undo; UI/Empty Trash/expiry paths require the item still be trashed at permanent deletion. New stale-confirmation test RED captured missing `requireTrash` parameter in `docs/v1.0.1-stale-delete-red.log`, then guard implemented. Direct internal repository deletion remains available for existing transactional tests/maintenance; UI paths pass the atomic guard.
- New `BackupAndTrashSafetyTest` provides 6 Compose instrumentation cases; compilation pending current final run. Runtime deferred locally: SDK has no emulator executable/system image/AVD, and `adb devices -l` returned no attached device. This is not an executed instrumentation pass; hosted emulator verification remains a final-phase option.
- Added live requirement ledger `docs/CODEX_V1_0_1_AUDIT.md` with all 65 numbered master sections; statuses remain PARTIAL until evidence/final audit.

- Owner steering saved in repository `AGENTS.md` before resuming: read/debug/check/review subagents use **gpt-6-luna / high** only; substantial coding subagents use **gpt-6.1-sol / high** only. This supersedes the initial general reviewer/model preferences below. Do not reuse earlier Astra or Luna agents for substantial coding.
- Environment recovery: historical local release instructions confirmed `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<repo>\.tools`, SDK `.tools/android-sdk`, `--no-daemon --no-configuration-cache`; Gradle now executes. Core RED captured unresolved `RestoreStaging` before helper implementation (`docs/v1.0.1-baseline-core-socket.log`). Android build/session `21208` compiled production and tests and reached JVM tests; retrieve terminal result before claims.
- Phase 1 work in progress: explicit backup policy/dialog, lifecycle cleanup, contained staging ownership, repository batch Trash/restore, Undo snackbar, reminder eligibility/identity/receiver safeguards and destructive confirmation. New core/Robolectric tests exist; full verified checkpoint not yet committed.

- Active phase: repository audit complete; Phase 1 implementation and baseline environment diagnosis.
- Active branch: `codex/v1.0.1-hardening`, created from initial HEAD below; no production merge authorized.
- Master specification saved byte-for-byte from attachment; SHA-256 `E1E0B7E8328464CEC98C32463795BBD39B086F0475BCB70DC620F255FD811670`; 2,314 lines. This overrides the earlier awaiting-spec preparation status.
- Existing architecture: Room schema 4, explicit migrations 1→2→3→4, item/asset/membership/reminder/search-document/analysis-job tables, content-addressed originals, FTS4, local extraction + versioned AI publication, existing authenticated TypeScript backend. Preserve these systems.
- Audit agents independently confirmed export mode text inference, cancelled preview leak, missing restored reminder scheduling, single-tap permanent deletion, missing Undo; also reboot rearming Trash and stale alarm delivery. Engine validation, transactional staged restore, dedup/refcounts, and local index foundations already exist.
- Baseline core Gradle command failed before tests: cache write denied in sandbox; authorized elevated retry reached `java.io.IOException: Unable to establish loopback connection`. Evidence: ignored `docs/v1.0.1-baseline-core.log`. Environment diagnosis continues; no test success claimed.
- Local SDK located in ignored `.tools/android-sdk`; no SDK environment variable configured. Existing `.tools/Test-Core.ps1` may provide proven fallback.
- Initial sandbox GitHub check failed (keyring/network unavailable); retry outside sandbox before treating it as invalid credentials.
- Ruling: execute the owner's complete spec directly, using this worklog and an implementation plan; do not insert repeated design/plan approval gates because the owner explicitly directed routine decisions and continuous phased execution. Use the existing checkout on a dedicated branch, preserving untracked QA evidence.

- Date: 2026-10-18 (Asia/Beirut).
- Phase: preparation; awaiting the complete V1.0.1 master specification.
- Initial branch: `main`.
- Initial HEAD: `18d21b436aea3cda781267a45b3fe025ee29c9b6` (`docs(release): record APK build provenance`).
- Initial working tree: existing untracked `docs/qa/`; preserve its contents. This worklog is the only preparation change.
- No product implementation, builds, tests, CI actions, commits, or releases performed during preparation.
- No applicable `AGENTS.md` was found at the repository root, its ancestor directories, or `docs/` during the preparation check. Check nested instructions when auditing implementation paths.

## Standing working rules from the owner

1. On receipt of the next message containing the full specification, save its COMPLETE text verbatim to `docs/CODEX_V1_0_1_MASTER_PROMPT.md` before repository audit or implementation. Do not summarize, shorten, or discard requirements. The master file has deliberately not been created with placeholder text.
2. Keep this worklog separate from the master specification. The master records what was requested; this file records discoveries, decisions, progress, and evidence.
3. Update this file continuously, before major phase changes and whenever an important decision or unexpected bug occurs. Record requirements in progress, architecture discoveries, decisions and reasons, changed files, tests and results, bugs, unresolved problems, deferred verification, next steps, and commit/checkpoint hashes.
4. Use subagents when useful. Prefer available Luna-class agents for fast checks, GPT-6-class reviewers for deeper independent review, and the strongest available Sol coding agent for implementation. Give reviewers explicit instructions to challenge the work. Missing exact profiles must not block progress.
5. Work incrementally and commit logical green checkpoints. A later failing check does not invalidate unrelated completed work. Inspect the failed CI job, preserve the actual error, fix its cause, and rerun only failed jobs when supported. Do not restart from Phase 1 without evidence that prior implementation is invalid.
6. Other accessible repositories may be inspected for compatible troubleshooting references involving Actions, Gradle, signing, APK splitting, JDKs, caching, emulators, artifacts, and release workflows. Understand and adapt a fix before reuse; never copy secrets or signing material.
7. Preserve useful failure logs/output or precise CI run/job references before making changes. Do not erase evidence and guess at causes.
8. After context compression/reset, reopen the complete master specification and this worklog, inspect Git history, and resume from the latest verified checkpoint.
9. Before declaring completion, reread the entire saved master specification and audit the implementation requirement by requirement. Report outstanding and deferred verification explicitly.
10. Do not begin major implementation until the complete specification arrives. Then save it first, update this log, audit the repository, and execute its required phases in order.

## Preparation evidence

- Read-only checks: `git status --short`, `git log -1 --format='%H %s'`, `git branch --show-current`, targeted instruction-file discovery, and lookup for existing V1.0.1 handoff files.
- No existing V1.0.1 master/worklog file was found before creating this worklog.
- Historical memory was consulted only for orientation; historical CI/release claims are not current verification.
- Architecture decisions, implementation files, test results, discovered product bugs, and release verification: none yet; pending specification and repository audit.

## Next steps

1. Receive and persist the full master specification verbatim; verify the saved text is complete.
2. Record its required phases and requirement audit checklist without replacing the master text.
3. Audit current source, applicable instructions, existing QA evidence, Git history, and relevant release constraints.
4. Execute the specified phases, preserving evidence and updating this file at every major checkpoint.
