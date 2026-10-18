You are working on the existing **Memora** Android project.

Repository: `personal-library`  
App: **Memora**  
Package: `com.personallibrary.app`

This task is the final **V1.0.1 hardening and product-completeness pass before physical-device QA**.

The app already has substantial architecture and working systems.

This is NOT a rewrite.

Your goal is to:

1. fix all currently known correctness, privacy, data-safety and UX bugs,
2. complete missing everyday library-management workflows,
3. make local search polished, fast and reliable,
4. harden AI privacy/index consistency,
5. extend the EXISTING retrieval architecture with bounded, source-grounded AI question answering,
6. polish navigation/theme/accessibility,
7. finish with complete verification and an honest readiness report.

The next physical-device QA session should mainly be hunting for **unknown bugs**, not rediscovering problems already known today.

---

# 0. NON-NEGOTIABLE RULES

Before modifying anything, inspect the current repository thoroughly enough to understand the existing implementation.

Specifically inspect:

- Room entities
- DAOs
- migrations
- item/storage/blob architecture
- dedup/reference counting
- collections and item memberships
- system/computed collections
- Trash behavior
- reminders/alarms
- backup/export/restore format
- restore staging
- search/FTS
- search snippets
- `search_documents`
- extracted text
- AI descriptions/concepts
- embeddings
- semantic retrieval
- analysis jobs
- WorkManager
- interactive Ask AI
- backend search/AI routes
- AI exclusion behavior
- network constraints
- navigation
- list/grid implementations
- light/dark themes
- launch theme
- release/signing/build setup
- APK split strategy
- existing tests
- CI/GitHub Actions

Do NOT blindly create new classes, tables or subsystems merely because terminology in this prompt suggests them.

The repository's CURRENT architecture is the starting point.

General rules:

- Do not rewrite working systems without a concrete need.
- Do not introduce unnecessary dependencies.
- Do not weaken current security.
- Do not remove working functionality to make tests pass.
- Do not use destructive Room migration.
- Preserve compatibility with existing installations.
- Preserve valid existing V1 backups where possible.
- Preserve permanent APK signing identity.
- Preserve update compatibility.
- Preserve ARM64 + universal release strategy.
- Preserve offline local-library behavior.
- Never put Gemini/provider credentials into the APK.
- Never log sensitive library content, passphrases or API secrets unnecessarily.
- Use synthetic/fake data in tests.

---

# 1. DEFINITION OF GREEN

A phase is considered **GREEN** when all relevant tests, builds, static checks and validations that the current environment can actually execute have passed.

Hardware-only, emulator-only, signing-environment-only, or other genuinely unavailable verification may be deferred ONLY when the current environment truly cannot perform it.

Deferred verification must:

- be explicitly documented,
- be listed in the final manual QA checklist,
- never be falsely reported as executed,
- never be falsely reported as passed.

A phase must NOT proceed while an executable relevant test/build/check is failing because of that phase's changes.

Therefore:

**GREEN does not mean hallucinating unavailable hardware/emulator testing.**

It means:

> Everything the current environment can legitimately verify is passing, and anything genuinely unavailable is clearly deferred and documented.

---

# 2. CRITICAL AI ARCHITECTURE RULE

Memora already appears to contain most of the intended local AI retrieval foundation, including concepts such as:

- items / `ItemEntity`
- extracted text
- `aiDescription`
- `aiConcepts`
- `search_documents`
- local chunks
- embeddings
- semantic similarity
- analysis jobs
- item/page provenance

Therefore:

# DO NOT CREATE A PARALLEL "AI CATALOG" SYSTEM MERELY TO SATISFY TERMINOLOGY IN THIS PROMPT.

Prefer extending the existing:

- item entities
- search-document/chunk entities
- analysis jobs
- FTS
- local extraction
- embeddings
- semantic retrieval

Only introduce a new schema/table if inspection proves there is a concrete requirement that cannot be cleanly represented in the existing architecture.

If adding schema:

- add a proper Room migration,
- preserve existing data,
- add migration tests,
- preserve restore compatibility,
- do not use destructive migration.

The desired V1 architecture is:

`Memora item → existing extracted/indexed/chunked representation → local retrieval → bounded relevant evidence → Gemini/backend → answer + source items`

NOT:

`entire library → Gemini`

NOT:

`giant Markdown/JSON memory file`

NOT:

`second duplicate AI index beside the existing one`

---

# 3. IMPLEMENTATION ORDER IS MANDATORY

Do not work randomly through this specification.

Use this dependency order.

## PHASE 1 — P0 DATA / PRIVACY / DESTRUCTIVE SAFETY

Complete:

- backup export-mode bug
- backup passphrase validation
- restore staging cleanup
- stale abandoned restore-stage cleanup
- reminder restoration
- permanent-deletion safety
- Trash/Undo foundation

Run all currently executable relevant verification.

Reach GREEN status as defined above.

Commit.

Suggested commit:

`fix: harden backup restore trash and reminder safety`

---

## PHASE 2 — LIBRARY MANAGEMENT UX

Complete:

- collection management
- stable Random Links identity
- backup-safe Random Links identity
- collection editing
- real multi-select
- Select All Visible
- bulk collection management
- Edit Item
- Restore All
- Trash management

Run all currently executable relevant verification.

Reach GREEN.

Commit.

Suggested commit:

`feat: complete v1 library management workflows`

---

## PHASE 3 — LOCAL SEARCH CORRECTNESS

Complete:

- WhatsApp-style live prefix search
- stale-query race fix
- filter visibility
- search/filter consistency
- correct empty states
- snippets preservation
- list/grid consistency

Reach GREEN.

Commit.

Suggested commit:

`fix: harden live local search and filtering`

---

## PHASE 4 — AI PRIVACY / INDEX CONSISTENCY

Complete:

- AI exclusion enforcement
- excluded collection metadata privacy
- Wi-Fi-only behavior
- edit/delete/index consistency
- bounded local candidate retrieval
- provenance preservation

Reach GREEN.

Commit.

Suggested commit:

`fix: harden ai privacy and retrieval consistency`

---

## PHASE 5 — BOUNDED AI QUESTION ANSWERING

Only start this phase after Phases 1–4 are GREEN.

Extend the EXISTING retrieval architecture into:

`question → local candidate retrieval → bounded evidence → server-side answer generation → answer + sources`

Do not rebuild semantic search.

Reach GREEN.

Commit.

Suggested commit:

`feat: add evidence-bounded ai question answering`

---

## PHASE 6 — UI / UX POLISH

Complete:

- navigation
- dark cold-start behavior
- contrast
- accessibility
- icons
- filter layout
- low-risk polish

Reach GREEN.

Commit.

---

## PHASE 7 — COMPLETE RELEASE VERIFICATION

Run all available verification.

Do not declare readiness before this phase.

---

# 4. BACKUP EXPORT MODE BUG — P0

Audit the backup passphrase UI.

There has been logic conceptually equivalent to:

`actionButtonText.contains("Export")`

while the export action text can actually be:

`Choose Destination`

This causes export to accidentally behave as restore mode.

Fix this structurally.

Never determine business logic from display text.

Use explicit state, for example:

- `EXPORT`
- `RESTORE`

via enum/sealed type/state object.

## Export requirements

Encrypted backup export must:

- enforce the actual encryption engine's minimum passphrase length, currently expected to be 12 characters,
- require confirmation,
- require both entries to match,
- block continuation when invalid,
- clearly state the passphrase cannot be recovered,
- never truncate or silently change the password.

## Restore requirements

Restore should:

- request the passphrase,
- handle incorrect password safely,
- handle corrupt backup safely,
- never partially overwrite live data.

Add regression tests proving Export and Restore modes cannot be confused.

---

# 5. RESTORE STAGING PRIVACY — P0

Decrypted restore staging must not remain unnecessarily after restore inspection/cancellation.

Promptly delete/best-effort remove staged decrypted files and references on:

- Cancel
- Back
- dialog dismissal where relevant
- restore failure
- exception
- successful restore completion

Use lifecycle-safe cleanup patterns such as `try/finally` where appropriate.

IMPORTANT:

Do NOT claim guaranteed physical "secure erase" of Android flash-storage blocks.

The product guarantee should be:

- Memora deletes its private staged files,
- removes references,
- cleans abandoned staging promptly.

Also handle stale staging from previous crashes/process death.

On appropriate startup/restore initialization:

- identify abandoned `restore_stage_*` directories or equivalent,
- remove stale abandoned staging safely,
- never delete an active legitimate restore.

Add tests where practical.

---

# 6. REMINDER + TRASH RESTORE BUG — P0/P1

Known bad flow:

1. item has future reminder,
2. item moves to Trash,
3. Android alarm is cancelled,
4. item restores,
5. reminder database state returns,
6. Android alarm is not rescheduled.

Fix this.

On item restoration:

- reschedule active future reminders,
- prevent duplicate schedules,
- ensure batch restore behaves identically,
- ensure Restore All behaves identically,
- handle already-passed reminder time according to current app semantics.

Add regression coverage.

---

# 7. TRASH UX AND DESTRUCTIVE SAFETY

## Move to Trash

Trash is reversible.

Do NOT ask for confirmation every time.

Use Snackbar Undo.

Single:

`Moved to Trash    UNDO`

Bulk:

`12 items moved to Trash    UNDO`

Undo must genuinely restore affected items.

Correct reminder behavior must also return.

## Delete Forever

Permanent deletion is irreversible.

It MUST NOT happen from one accidental tap.

Require confirmation.

Clearly explain that deletion is permanent.

## Empty Trash

Keep confirmation.

## Restore All

Add Restore All.

Use confirmation because this may restore many items.

Ensure restoration correctly handles:

- content state
- membership
- reminders
- search visibility
- AI retrieval eligibility

---

# 8. COLLECTION MANAGEMENT

Collections need a complete lifecycle.

## Visible collection management

For user-created collections, expose a visible overflow action:

`⋮`

Relevant actions should include things such as:

- Edit Collection
- Open / Select Items
- AI Privacy
- Delete Collection

Do not depend on an undiscoverable long-press for critical management.

System/computed collections such as:

- Favorites
- Unfiled
- Reminders

must not expose inappropriate rename/delete actions.

## Edit Collection

Allow:

- name
- icon
- color

Validate names.

At minimum:

- trim surrounding whitespace,
- case-insensitive duplicate detection,
- prevent collisions with reserved/system names where necessary.

## Delete Collection semantics

Deleting a collection must remove the collection/memberships.

It must NOT automatically delete actual saved content.

If an item still belongs to another collection, preserve that membership.

If no collection membership remains, the item may become Unfiled according to existing semantics.

Fix inaccurate confirmation wording.

Use wording equivalent to:

`Delete this collection? Saved items will remain in Memora. Their membership in this collection will be removed.`

---

# 9. RANDOM LINKS MUST HAVE STABLE IDENTITY

The app currently may locate `"Random Links"` by display name.

That becomes unsafe once collections can be renamed.

Use a stable internal application role/key/identifier for behavior that depends on Random Links.

Renaming its visible display name must NOT break:

- WhatsApp import routing
- share/default routing
- migration behavior
- existing membership

## Backup requirement

The stable identity must survive:

- app upgrade,
- backup export,
- restore to another/fresh installation.

If the portable backup format needs a new field:

- make it backward-compatible,
- make the field optional/defaultable when reading older V1 backups,
- preserve valid older backups.

Do not make V1.0.0 backups unreadable just because they lack a new internal role field.

---

# 10. REAL MULTI-SELECT

Implement a mature selection mode.

## Entry

Long-press an item.

Must work in:

- list,
- grid,
- collection views,
- filtered Library,
- search results where appropriate.

## Active selection state

When active:

- normal tap toggles selected state,
- top bar/header shows `N selected`,
- provide explicit close/cancel,
- Back exits selection mode before leaving the screen.

## Required bulk actions

At minimum:

- Select All Visible
- Move selected to Trash
- Add selected to collection(s)
- Remove selected from collection(s)

Favorite may be added only if low-risk and sensible.

## Select All Visible

This means the current logical result set after active:

- search query,
- collection filter,
- type filter,
- date filter,
- other active filters.

Do NOT select the entire database when the user is viewing a filtered subset.

Support large collections without UI freezing.

---

# 11. EDIT ITEM — REQUIRED V1 FEATURE

Users must be able to correct saved content metadata.

## Notes/text

Allow editing:

- display title,
- user-authored body/note where appropriate.

## Links

Allow editing:

- display title,
- URL,
- note/description where model supports it.

Reject clearly malformed input while preserving valid supported URI schemes.

## Files/media

Allow editing:

- display title,
- user note/metadata if model supports it.

Do NOT mutate content-addressed storage or original binary merely because display title changes.

Preserve original filename/provenance.

## WhatsApp-imported content

Preserve original provenance.

Do not silently mutate authoritative:

- original timestamp,
- sender,
- original source,

unless explicitly designed.

---

# 12. EDITS MUST UPDATE DERIVED DATA CORRECTLY

After relevant item edits, update/invalidate derived data.

Depending on what changed:

- FTS
- snippets
- searchable text
- AI description
- AI concepts
- embeddings
- semantic index
- retrieval caches

Do not leave stale search results.

Do not unnecessarily reprocess expensive media if only metadata changed.

For example:

Changing the display title of a 40 MB video should not require complete video re-analysis if metadata/index can be updated independently.

---

# 13. WHATSAPP-STYLE LIVE SEARCH — CORE V1

Normal keyword search should behave like WhatsApp chat search.

Typing:

`b`

then:

`bi`

then:

`bir`

then:

`birth`

then:

`birthday`

should progressively update results automatically.

Do not require Enter/Search.

The current repository already appears to implement FTS prefix behavior.

Preserve and harden it.

Do NOT rewrite a working search engine unnecessarily.

---

# 14. SEARCHABLE CONTENT

Local search should use available indexed content such as:

- title
- note/body
- original filename
- extracted document text
- PDF text
- OCR
- imported WhatsApp text
- AI description
- concepts/searchable metadata

Keyword search must remain local/offline.

Do NOT invoke Gemini merely for ordinary text search.

---

# 15. PREFIX MATCHING

Preserve existing prefix semantics.

Example:

`bi`

can match:

- birthday
- bike
- biology

As more letters are typed, results narrow.

The user should not need to finish a word.

---

# 16. FIX LIVE-SEARCH RACE

There is a stale asynchronous-result risk.

Example:

User types:

`bi`

then quickly:

`birth`

The older `bi` operation must NEVER finish afterward and overwrite results for `birth`.

Use correct latest-query semantics, such as:

- job cancellation,
- `mapLatest`,
- `flatMapLatest`,
- query-generation guard,
- equivalent mechanism suitable for current architecture.

Do not simply increase debounce duration.

Rendered results must always correspond to the current:

- query,
- filters,
- collection state.

---

# 17. SEARCH SNIPPETS

Search snippets already exist.

Preserve them.

Only improve where needed.

Show concise evidence of why an item matched, such as:

- relevant title,
- filename,
- note fragment,
- extracted-text fragment.

Do not create another duplicate snippet system.

---

# 18. SEARCH FILTER VISIBILITY

Library/Search state currently risks invisible constraints.

Fix this.

If results are filtered by:

- collection,
- type,
- date,

the user must see the active filter.

Provide:

`Clear Filters`

No result should mysteriously disappear because a Library filter remained active invisibly.

---

# 19. EMPTY STATES

Differentiate clearly.

## Truly empty library

`Your library is empty`

## Search has no matches

`No results found`

## Active filters produce no matches

`No items match these filters`

Include:

`Clear Filters`

## AI unavailable

Do not present AI failure as ordinary zero-result search.

---

# 20. LOCAL SEARCH + AI SEARCH UX

Local search is the primary fast/offline mechanism.

AI is additive.

Do not throw away useful local results simply because AI returns something.

Use a coherent result hierarchy, such as:

- local results,
- AI answer,
- semantic related matches,

or another simple structure fitting the existing UI.

Do NOT completely redesign Search.

---

# 21. AI EXCLUSION MUST BE STRICT

For AI-excluded items/collections:

- do not send content to Gemini,
- do not include content in network-bound AI evidence,
- do not include excluded candidates in interactive Ask AI,
- do not include excluded items in semantic AI retrieval where exclusion semantics require that,
- re-check exclusion immediately before every network-bound AI request.

## Critical additional rule

If a collection is AI-excluded:

Do NOT include that collection's NAME in:

- `knownCollections`
- search-plan metadata
- equivalent backend metadata sent to AI.

AI exclusion means exclusion.

Do not leak the existence/name of excluded collections unnecessarily.

---

# 22. DO NOT BREAK LOCAL SEARCH WHEN AI IS EXCLUDED

Local extracted-text chunks and AI-derived embeddings may coexist.

Do NOT blindly delete all indexed/search documents when an item becomes AI-excluded.

AI exclusion must NOT make the item disappear from ordinary local/offline keyword search unless that is explicitly existing product behavior.

Preserve:

- extracted text,
- FTS,
- filename,
- local keyword retrieval,

while making:

- embeddings,
- semantic AI use,
- network-bound evidence,

properly exclusion-aware.

Use minimal schema/state extension if required.

---

# 23. AI INDEX LIFECYCLE

Correctly manage existing derived data.

## Add

Use existing extraction/index pipeline.

## Edit

Update only invalidated derived fields.

## Trash

Prefer excluding trashed content from ordinary Library/AI search while preserving enough recoverable state for restore.

## Restore

Restore normal search/AI eligibility appropriately.

## Permanent delete

Remove derived data that no longer has a source:

- embeddings,
- semantic chunks,
- AI descriptions where appropriate,
- stale analysis jobs,
- caches,
- orphaned index data.

No ghost search/AI result may survive permanent deletion.

## AI exclusion

Disable AI retrieval/network usage while preserving ordinary local search.

## Re-enable AI

Rebuild missing AI-derived state if necessary.

---

# 24. WI-FI-ONLY AI

Audit both:

- background AI jobs,
- interactive Ask AI.

When Wi-Fi-only is enabled, behavior must be consistent and understandable.

Requirements:

- background AI should respect appropriate Wi-Fi/unmetered constraint,
- interactive Ask AI must not silently ignore the setting,
- old queued jobs should not remain permanently configured using obsolete network constraints.

Possible safe approaches include:

- re-enqueue pending work,
- runtime network guard,
- combination.

Do not duplicate jobs.

Do not lose work.

Document exact final behavior.

---

# 25. REUSE EXISTING AI INGESTION

Memora already appears to have significant ingestion infrastructure:

- bounded video frame/audio analysis
- extraction
- chunking
- embeddings
- semantic search
- durable jobs

Reuse it.

Do NOT replace this with:

- giant Markdown,
- giant JSON,
- full-library context,
- repeated original-file re-analysis,
- duplicate AI catalog.

Core rule:

# ANALYZE ONCE → RETRIEVE MANY TIMES

Ordinary future questions should use derived:

- transcript/text,
- metadata,
- description,
- concepts,
- embeddings,
- chunks,

instead of repeatedly uploading original large media.

---

# 26. PHASE 5 — BOUNDED AI QUESTION ANSWERING

The current Ask AI flow appears to retrieve matching items rather than generate a grounded direct answer.

Adding direct answers is new functionality.

Only build this after Phases 1–4 are GREEN.

Example target:

User asks:

`When is Sarah's birthday?`

Desired result:

`Sarah's birthday appears to be August 19.`

with source items.

---

# 27. AI Q&A FLOW

Use this architecture:

1. Receive user question.
2. Perform LOCAL candidate retrieval using existing:
   - FTS,
   - search documents,
   - embeddings,
   - semantic retrieval,
   - active user filters.
3. Apply AI exclusions locally.
4. Rank candidates.
5. Select a bounded evidence set.
6. Construct compact evidence.
7. Send question + bounded evidence to server-side answer generation.
8. Receive grounded answer.
9. Return answer with structured source references.
10. Allow source item opening in Memora.

Never send:

- entire library,
- every saved file,
- all videos,
- giant JSON dump.

---

# 28. BACKEND IMPLEMENTATION RULE

If the existing backend does NOT already provide a suitable bounded answer-generation endpoint, you are explicitly allowed to add the **smallest server-side endpoint necessary** for AI Q&A.

Reuse the project's existing backend patterns for:

- authentication
- authorization
- device/session validation
- rate limiting
- input validation
- request size limits
- timeouts
- retry/error behavior
- logging policy
- provider integration

Do NOT invent a second backend architecture.

The Android application should send only:

- the user question,
- bounded selected evidence,
- minimal source metadata needed for provenance.

Gemini/provider credentials must remain **server-side**.

They must NEVER be:

- embedded in Android,
- persisted in APK resources,
- generated in the client,
- logged by Android,
- shipped in build config,
- otherwise exposed to the APK.

Do not move provider-key handling into Android just to avoid modifying the backend.

## Defense-in-depth requirement

The server-side Q&A endpoint must enforce reasonable bounds itself.

It must NOT blindly accept an unrestricted library dump.

Enforce appropriate limits on:

- total request size,
- evidence count,
- evidence text size,
- per-item/chunk contribution,
- generated answer size where appropriate.

The backend must remain safe even if a future buggy client attempts to send too much data.

---

# 29. BOUNDED EVIDENCE / TOP-K

Candidate evidence must have hard limits.

Use a reasonable bounded strategy based on:

- maximum candidate count,
- maximum chunks,
- maximum characters/tokens,
- per-item contribution,
- total request budget.

A small top range such as 5–20 items/chunks may be reasonable depending on chunk size.

Do not use an absurdly large limit.

Prevent one huge document from monopolizing the entire context.

Prefer the most relevant evidence and reasonable source diversity.

---

# 30. AI ANSWERS REQUIRE PROVENANCE

Every library-grounded answer must remain traceable.

Generated response state should preserve source item IDs.

UI should show tappable/accessible source Memora items.

Example:

`Sarah's birthday appears to be August 19.`

Sources:

- Friends WhatsApp Export
- Sarah Birthday Note

Preserve existing page provenance for PDFs/documents when available.

Do not discard existing source metadata.

---

# 31. AI ANSWER GROUNDING

The answer-generation endpoint/model must be instructed to:

- answer only from supplied Memora evidence,
- avoid inventing unsupported facts,
- report insufficient evidence when necessary,
- acknowledge conflicting evidence rather than pretending certainty.

Example conflicting evidence result:

`Memora contains two different birthday dates for Sarah.`

Then expose both sources.

Do NOT build a full automatic conflict-resolution system now.

---

# 32. DO NOT BUILD V2 KNOWLEDGE GRAPH

Explicitly OUT OF SCOPE:

- permanent people profiles
- relationship graph
- automatic birthday database
- autonomous memories
- global fact table
- entity merging
- inference graph
- confidence propagation
- complex temporal reasoning
- full cross-library knowledge graph

Design V1 retrieval so these could be added later.

Do not implement them now.

---

# 33. VOICE SEARCH RECOVERY

Current error UX may tell the user:

`Tap mic or type query above`

while the visible mic cannot actually be tapped.

Fix this.

Either:

- make the microphone/retry control genuinely interactive,
- add an explicit Retry action,
- or change the message to match available actions.

Never instruct impossible behavior.

---

# 34. REMINDERS COLLECTION NAME

The computed collection includes active/future reminders.

Rename:

`Due Reminders`

to:

`Reminders`

unless you intentionally change its query semantics to only show actually-due reminders.

For V1, keeping all active reminders grouped is useful.

Name must match behavior.

---

# 35. DARK MODE / LIGHT MODE

Keep the existing deliberate Memora palette.

Do NOT introduce wallpaper-driven dynamic Material You colors unless already an intentional product feature.

## Paired-state contrast

Fix the low-contrast paired status text in dark mode.

Use themed accessible colors.

## Cold launch

The Android launch theme has been light-only.

Fix obvious white/light cold-start flashing when app target appearance is dark.

Support existing theme behavior:

- Light
- Dark
- Follow System if currently supported.

---

# 36. NAVIGATION

Fix hierarchy.

Expected:

`Library → Settings → Trash`

Back:

`Trash → Settings`

Back:

`Settings → Library`

Add proper visible Back/Up controls.

Back behavior should prioritize:

1. dismiss transient modal/dialog where appropriate,
2. exit selection mode,
3. navigate previous destination,
4. normal system/app back.

Do not globally redirect every secondary screen straight to Library.

---

# 37. LIBRARY FILTER / CONTROL LAYOUT

Library controls are becoming cramped.

Do not redesign everything.

Use a minimal scalable solution such as:

- horizontally scrollable chips,
- compact Sort/Filter controls,
- active-filter chips,
- filter bottom sheet.

Ensure:

- readable labels,
- no clipping,
- active filters obvious,
- Clear Filters accessible,
- usable large-text behavior.

---

# 38. LIST / GRID FUNCTIONAL PARITY

Grid mode must support important management behavior.

Ensure both list and grid support:

- open item,
- long-press selection,
- selected-state feedback,
- bulk actions,
- Trash,
- favorite indication where appropriate,
- reminder indication where appropriate,
- item actions.

Visual density can differ.

Core capabilities should not.

---

# 39. ACCESSIBILITY

Audit custom controls.

Aim for Android-recommended interactive target sizing, approximately 48dp where practical.

Fix obviously undersized 24–36dp targets.

Add meaningful semantics/content descriptions for:

- overflow actions
- edit
- delete
- restore
- favorite
- microphone
- selected state
- collection icons
- collection colors

Examples:

`Blue collection color`

`Work collection icon`

`Remove from Favorites`

Avoid meaningless labels like:

`button`

Ensure critical UI remains usable around 130–150% font scaling.

---

# 40. ICON SEMANTICS

Replace misleading type icons.

Prefer recognizable:

- Audio → music/headphones/waveform/audio
- Document → description/document
- Link → link

Do not use:

- notification as Audio,
- Info as Document,
- Share as primary Link icon.

Maintain Memora's visual language.

---

# 41. FILTERED EMPTY STATE

If filters return nothing while the library contains content:

Do NOT show:

`Your library is empty`

Use:

`No items match these filters`

Provide:

`Clear Filters`

---

# 42. COLLECTION AI PRIVACY UI

Avoid cluttering every collection card with a permanent advanced AI toggle.

Move collection-specific AI privacy into an appropriate place such as:

- overflow menu,
- Edit Collection,
- dedicated Privacy action.

Keep it discoverable without cluttering browsing UI.

---

# 43. SHARE PREVIEW — OPTIONAL

Only after required work is complete and GREEN:

Improve share preview if low-risk.

Instead of only:

`3 files attached`

show a concise list of filenames/titles where practical.

Do not build a full attachment-management system.

---

# 44. FILEPROVIDER — AUDIT, DON'T BREAK

Existing FileProvider may expose broad `"."` file/cache roots.

Audit this.

If they can be safely narrowed without breaking:

- open
- preview
- share
- backup
- import

then tighten them.

If this is risky immediately before QA:

document it as post-V1 defense-in-depth.

Do not break fundamental file handling for theoretical cleanliness.

---

# 45. IMPORT STORAGE PREFLIGHT

Improve import safety where reasonable.

Before large copy/import operations, where information is available:

- verify source access,
- determine size,
- check reasonable free-space headroom,
- fail cleanly if copy cannot complete.

Never leave a half-created database item if file import fails.

Preserve current transactional behavior.

---

# 46. STORAGE SIZE DISPLAY

Trash size may currently sum logical item sizes while underlying content is deduplicated.

Do not present logical total as exact physically reclaimable storage when it is not.

Either:

- calculate reclaimable physical storage correctly,
- or use wording such as logical content size.

Do not perform a giant storage refactor for this statistic.

---

# 47. FTS DATABASE OPEN BEHAVIOR

Audit whether FTS triggers/index are:

- dropped,
- recreated,
- rebuilt

on every database open.

If confirmed and unnecessary:

optimize safely.

Do not change working FTS only because the implementation looks inelegant.

Correctness first.

Add/update tests if behavior changes.

---

# 48. ROOM SCHEMA EXPORT

Current Room setup may use:

`exportSchema = false`

Evaluate enabling schema export for future migration reliability.

If straightforward:

- enable it,
- configure schema location properly,
- retain generated schema history as appropriate.

If this unexpectedly destabilizes build tooling:

document it rather than derailing V1.0.1.

---

# 49. NO CASUAL DELETE-ALL FEATURE

Do NOT add a standard:

`Delete All Library`

button.

V1 already has a safe model:

- Select All Visible
- Trash
- Empty Trash

A future full wipe would have to coordinate:

- Room
- blobs
- search
- AI
- alarms
- WorkManager
- preferences
- pairing
- caches

Out of scope.

---

# 50. NO "DELETE COLLECTION + CONTENT" MAGIC

Do not introduce an ambiguous command that deletes a collection and every attached item.

Items may belong to multiple collections.

Correct explicit workflow:

`Open collection → Select All Visible → Trash`

Deleting a collection itself removes membership only.

---

# 51. SECURITY POSITIONING

Preserve existing:

- Android Keystore
- device auth
- encrypted backup
- server-side provider credentials
- HTTPS
- challenge/session security
- cloud-backup restrictions
- non-exported components
- AI privacy controls

Do not describe the app lock as full encrypted-vault storage unless actual at-rest vault encryption exists.

Optional screenshot/Recents protection may be evaluated only if very low-risk.

---

# 52. PRIVATE DATA LOGGING

Memora may contain:

- private chats
- API keys
- credentials
- personal media
- confidential documents

Do NOT log:

- backup passwords
- provider keys
- full extracted content
- full AI request context
- private files
- personally sensitive payloads

especially in release builds.

Use synthetic fixtures in testing.

---

# 53. PERFORMANCE

Do not introduce UI jank.

Avoid:

- full-library scans on every keypress,
- recomputing embeddings during search,
- giant AI-context construction,
- loading full videos on UI thread,
- unbounded database lists in Compose state,
- duplicate WorkManager jobs,
- expensive main-thread operations.

Local search should feel nearly instantaneous.

AI can take longer, but progress/error state must be explicit.

---

# 54. VERSION / BRANCH

Create a dedicated hardening branch from the correct current base.

Suggested:

`v1.0.1-hardening`

Use existing branch convention if repository clearly establishes one.

Do not rewrite history.

Use logical commits aligned with phases.

Do not merge to production until verification is complete.

---

# 55. RELEASE SIGNING

Preserve permanent release signing.

DO NOT:

- create a new keystore,
- rotate signing identity accidentally,
- commit signing secrets,
- revert production builds to debug signing.

Existing installed copies must remain update-compatible.

---

# 56. APK PACKAGING

Preserve intended release strategy:

- ARM64 primary APK
- universal fallback APK

Preserve:

- R8
- minification
- shrinking
- signing verification
- existing GitHub Actions/release pipeline

Do not regress to giant debug/unoptimized packaging.

---

# 57. TESTING — REQUIRED

After each phase run all relevant verification the current environment can actually execute.

At final verification run all available:

- core JVM tests
- Android unit tests
- backend tests
- lint
- assemble
- release build
- signing verification
- instrumentation/emulator tests if available

Remember the GREEN definition.

Do not halt merely because unavailable hardware testing cannot run.

Do not falsely claim unavailable verification was performed.

Report exact results.

---

# 58. REQUIRED REGRESSION COVERAGE

Add focused tests where technically reasonable.

## Backup

Test:

- explicit Export state
- explicit Restore state
- short passphrase blocked
- mismatch blocked
- valid passphrase accepted
- cancelled restore staging cleanup
- stale stage cleanup
- compatible older backup restore

## Trash/reminders

Test:

- Trash cancels reminder/alarm
- Undo Trash
- bulk Trash Undo
- restore reschedules reminder
- Restore All
- batch restore

## Permanent deletion

Verify irreversible delete cannot execute through an accidental single-tap path.

## Collections

Test:

- create
- rename
- duplicate rejection
- delete membership safely
- preserve multi-collection membership
- stable Random Links identity survives rename
- Random Links identity survives backup/restore

## Multi-select

Test:

- long press entry
- list
- grid
- selection count
- Select All Visible
- filters respected
- bulk Add Collection
- bulk Remove Collection
- bulk Trash

## Edit

Test:

- title edit
- note edit
- link edit
- FTS refresh
- stale old content no longer incorrectly returned
- new content searchable
- AI-derived/index state invalidated/updated correctly

## Search

Test:

- `b`
- `bi`
- `bir`
- `birth`
- `birthday`
- rapid query changes
- stale result cannot overwrite latest
- collection filter
- type filter
- date filter
- Clear Filters
- offline local search
- correct empty states

## AI privacy

Test:

- excluded item absent from AI candidates
- excluded collection absent from AI candidates
- excluded collection name absent from `knownCollections`
- locally searchable content remains available where intended
- no excluded evidence sent to backend

## AI Q&A

Test:

- local retrieval happens first
- evidence is bounded
- source IDs preserved
- answer mapped to sources
- original large media not resent during ordinary question flow
- insufficient evidence response
- conflicting evidence handling
- endpoint input limits

## Navigation

Test:

`Settings → Trash → Back → Settings`

and selection-mode Back behavior.

---

# 59. MANUAL PHYSICAL QA CHECKLIST

Produce a final real-device checklist even if automated tests are green.

Include:

## Library

- add note
- add link
- add file
- add image
- add video
- list
- grid
- favorite
- sort
- filter
- collection behavior

## Search

Type sequentially:

`b`

`bi`

`bir`

`birth`

`birthday`

Also test:

- rapid typing
- rapid deletion
- collection filter
- type filter
- date filter
- no result
- Clear Filters
- offline search

## Selection

- long press
- list
- grid
- Select All Visible
- add collection
- remove collection
- Trash
- Undo

## Edit

- note
- URL
- title
- verify search updates

## Collections

- create
- duplicate rejection
- rename
- icon
- color
- AI privacy
- delete
- confirm content survives
- rename Random Links
- test WhatsApp import/routing afterward

## Trash

- single restore
- Restore All
- Delete Forever
- confirmation
- Empty Trash
- reminder restore
- Undo

## Backup

- short password
- mismatched confirmation
- successful export
- wrong restore password
- correct restore
- cancel restore preview
- stale stage cleanup
- older backup compatibility

## AI

- semantic retrieval
- direct Q&A
- source links
- AI-excluded item
- AI-excluded collection
- Wi-Fi only
- offline
- previously indexed large video queried without uploading full original again

## Theme

- cold light launch
- cold dark launch
- system mode if supported
- paired-state contrast

## Accessibility

- larger font
- larger display scaling
- touch targets
- TalkBack-sensitive controls where practical

## Navigation

- Library
- Settings
- Trash
- Back
- dialogs
- selection mode

---

# 60. DEFINITION OF DONE FOR AI

At completion explicitly confirm each statement:

1. Existing indexing infrastructure was reused rather than replaced unnecessarily.
2. No giant global Gemini Markdown/JSON memory file was added.
3. No duplicate parallel AI catalog was added without concrete justification.
4. Local keyword search remains local/offline.
5. AI exclusion applies to:
   - item evidence,
   - candidate retrieval,
   - semantic AI use,
   - network requests,
   - collection metadata sent to backend.
6. AI-excluded content remains available to ordinary local search where product semantics permit.
7. Ask AI retrieves candidates locally before calling server/Gemini.
8. AI Q&A sends bounded evidence.
9. Backend enforces evidence/request limits.
10. Gemini/provider credentials remain server-side.
11. Ordinary repeated questions do not repeatedly upload original large media.
12. AI answers retain source-item provenance.

---

# 61. DO NOT OVERBUILD

Do NOT add:

- knowledge graph
- people database
- birthday database
- relationship engine
- global AI memory
- autonomous agent
- advanced entity merging
- full temporal-reasoning system
- cloud synchronization
- telemetry
- analytics
- full app wipe
- massive Settings redesign
- major onboarding system
- typo-correction engine unless already trivial in current architecture

This is still **V1.0.1**.

---

# 62. FINAL REPORT FORMAT

When implementation is complete, provide:

## A. VERDICT

Exactly one:

- `READY FOR PHYSICAL QA`
- `READY WITH KNOWN LIMITATIONS`
- `NOT READY`

## B. PHASE REPORT

For every phase list:

- work completed
- tests/checks run
- result
- deferred unavailable verification
- commit hash

## C. REQUIREMENT AUDIT

For every major requirement use:

- COMPLETE
- PARTIAL
- NOT NEEDED
- BLOCKED

Do not silently skip unfinished requirements.

## D. IMPORTANT FILES CHANGED

List key files/modules and purpose.

## E. DATABASE / MIGRATION REPORT

State:

- original schema version
- resulting schema version
- migration path
- migration test result
- destructive migration: YES/NO

Expected: NO.

## F. BACKUP COMPATIBILITY

Confirm behavior for:

- existing V1 backups
- new backup format if changed
- stable collection role
- Random Links restore
- restore staging cleanup

## G. SEARCH ARCHITECTURE

Explain:

- prefix matching
- debounce
- stale-query prevention
- filters
- snippets
- offline behavior

## H. AI ARCHITECTURE

Explain the actual post-change architecture.

Include:

- existing entities reused
- any schema additions and justification
- ingestion
- indexing
- local retrieval
- candidate ranking
- evidence limits
- backend answer endpoint
- server-side security
- provenance
- exclusion handling
- edit/delete lifecycle

Explicitly answer:

`Ordinary AI retrieval repeatedly uploads full original videos/files: YES or NO`

Expected:

`NO`

## I. PRIVACY / SECURITY

Report:

- restore-stage cleanup
- stale-stage cleanup
- AI exclusions
- excluded metadata behavior
- logging
- provider credentials
- FileProvider decision
- remaining defense-in-depth issues

## J. AUTOMATED VERIFICATION

Give exact results.

Examples:

- core: `X/X`
- Android JVM: `X/X`
- backend: `X/X`
- instrumentation: `X/X` or unavailable + reason
- lint
- debug assemble
- release assemble
- signing checks

Never say only:

`All tests passed`

without specifics.

## K. KNOWN LIMITATIONS

Be brutally accurate.

Distinguish:

- confirmed bug
- deferred hardware verification
- optional future improvement

## L. MANUAL QA CHECKLIST

Return the finalized physical-device checklist.

## M. GIT STATE

Report:

- branch
- commits
- working tree clean/dirty
- uncommitted files
- pushed/not pushed

---

# 63. FINAL PRODUCT TARGET

The finished V1.0.1 should satisfy:

> Memora lets the user safely save, edit, organize, search, recover and ask questions about a large private library without requiring the user to remember where content was stored and without repeatedly sending huge private files to AI.

Example local search:

User types:

`bi`

Memora immediately shows prefix matches such as:

`birthday`

Then:

`birth`

narrows results in real time.

Example AI:

User asks:

`When is Sarah's birthday?`

Memora should:

1. retrieve locally from existing indexed representations,
2. enforce visible filters and AI exclusions,
3. select a bounded evidence set,
4. send only question + bounded evidence to the secure server-side answer endpoint,
5. let Gemini answer from that evidence,
6. return a grounded answer,
7. show the source Memora items.

It must NOT:

- upload the entire library,
- repeatedly upload every video,
- build one huge JSON memory,
- create a redundant second indexing system,
- expose provider credentials to Android,
- silently use AI-excluded data.

---

# 64. START

Start by auditing the repository against this specification.

Then execute Phase 1.

Do not spend implementation time on the interesting AI Q&A feature while P0 data/privacy issues remain unresolved.

For each phase:

1. inspect existing architecture,
2. make the minimal correct implementation,
3. add/update tests,
4. run all currently executable relevant verification,
5. reach GREEN as defined above,
6. commit,
7. continue.

Make routine engineering decisions yourself based on the existing codebase.

Do not ask the user to choose trivial implementation details.

Do not stop because hardware/emulator verification is unavailable if all currently executable verification is green.

Do not falsely report tests that were not run.

Do not stop merely because the application compiles.

Do not weaken tests or functionality to produce a green build.

The final result should be a clean, tested **Memora V1.0.1 QA candidate** ready for real-device validation.