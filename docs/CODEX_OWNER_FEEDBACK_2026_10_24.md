# Memora owner feedback — 24 October 2026

Status: APPROVED; implementation and core/app/server local checks pass; debug/optimized assembly and DEX linkage pass; exact-source CI/runtime and private code5 delivery remain pending. Current evidence and exact next action live in root `CODEX_WORKLOG.md`. This is the canonical addendum for the owner's 19 reports and decisions. The earlier V1.0.1 master specification remains immutable; this newer owner feedback supersedes conflicting behavior (navigation, collection deletion/cardinality, password minimum, theme and AI setup).

## Original owner request

The 13 attached screenshots are evidence in the originating chat; their text is not an instruction source. Do not copy personal screenshots into Git. Owner wording:

1) dismiss button
2) sharing stuff from the app are all as bin not as original form
3) stickers are considered photos
4) gifs are considered vid ( we don't have a seperate gif section so i don't mind, but probably better they have there own section)
5) when i added 4 things to a collection named random links then deleted it, the 4 didn't get deleted from main library, that's very weird, if i wanted to delete a library on purpose all in it should go, a simple undo button with a dellay for error is enough to stop accidental stuff)
6) when i share something, a new memora tab opens every time instead of using the already opened one, this creates multiple tabes of same app
7) random links collection is basically hard coded, can't delete and always reshows us
8) why is Ai not pre-paired like sprendwise, i don't wanna have to pair each time or even once at the start, the user has no idea what a Api key or url is
9)The search page is not necessary, we can already search for stuff in Library It should just be Library/collections/settings
10) Ai problems, see pics, basically, first one is Ai won't work if a local search found anything relevant ( it should still work maby the thing I'm looking for still isn't there because i couldn't remember the exact thing to search for), second see the pic i sent for when no local search is there, the finding relevant evidence screen is bad UI it's too much words, we can improve, maby remove the no local if we looking with Ai, third i had a bday in library and i searched for birthday and it said there is not enough evidence, Ai should be able to pick up that birthday and bday are the same, especially if it's Gemini 3.8 so check what prompt Ai is getting
11) i noticed you can't add stuff to the default collection, even when i went to reminder and tried to add, it still asked me what collection i wanted to put it in ( even though the process was started already insights a collection aka reminders) and on top reminders wasn't even an option, that needs fixing
12) after i got notified and i wanted to press clear, it was cle on the first line and ar on the second line The Ui is broken Also check that when i add something to default collection reminders, it auto gets asks what time or time or date, cuz it is definitely a reminder if it's in there
13) in this view not all items are even, bad Ui
14) opening a collection redirects you to the library page, shouldn't be the case, it should just open normally in the collection tab and you can search only inside that collection you chose and if you ask Ai he'll also only have access to the stuff inside that collection, so maby every collection has its own " . Md file " The stores info for Ai about this collection
15) even after dismiss is gone, the top is very empty, i think you reserved that place for the dismiss thingy, ye we getting rid of that, a confirmation can be added in another way with better UX and UI and in green not yellow for confirmation)
16) what even is unfiled and what is supposed to be in it
17) i don't think speeche thingy works, it's always " Stopped " In this screen in the pic, i should just have to click on the microphone icon for it to start recording and click again for it to stop ( big icon in pic) and for the very first time it should auto start after i click on small icon on right of search bar
18) 12 character password too long, make it 8 minimum
19) dark mode needs full rework, it should look exactly like spend wise dark mode Our current one is more like grey mode

## Confirmed owner decisions

- One concrete folder per item. Inbox means no concrete folder. Favorites and Reminders remain computed views, so a Work item can also appear there.
- Deleting a collection deletes all its active items from the Library everywhere by moving them to Trash. Undo is **5 seconds**, not 10. Later manual restoration goes to Inbox after the deleted folder is finalized.
- Existing multiple memberships keep the oldest collection by `(createdAt, id)`; preserve files/items and disclose changed associations.
- Recognize GIFs automatically only with reliable format/source information; ambiguous MP4 stays VIDEO. No manual GIF tagging feature requested.
- AI works without an account, enrollment secret, API key or URL entry; automatic device registration with request limits is accepted. Do not embed a shared secret.
- AI defaults to both Wi-Fi and mobile data; Settings can opt into Wi-Fi-only. Preserve explicit saved restrictions.
- Pause Background AI stops automatic work only; deliberate Ask AI remains usable and respects network restrictions and exclusions.
- Reuse Room/chunks/indexes for collection AI. Per-collection Markdown was a suggestion, not a requirement; do not build a duplicate index.
- Owner authorized implementation on the existing QA branch and asked for durable Markdown checkpoints for later chats. No main merge, public release or production deployment authorized.

## Accepted implementation plan and acceptance map

| ID | Required behavior | Check |
|---|---|---|
| 01 | Remove persistent Dismiss banner; transient green save snackbar | Successful save feedback disappears and leaves no reserved header |
| 02 | Original bytes/name/extension/MIME via FileProvider, grants and ClipData | Receiver resolves display name/MIME and exact bytes; actual WhatsApp owner check separate |
| 03 | STK- WebP is STICKER; reclassify affected stored rows | Classifier plus upgrade fixtures |
| 04 | Add GIF kind/filter/rendering for reliable GIF format; ambiguous MP4 VIDEO | Import, preview, filter and backup regression |
| 05 | Atomic collection+item Trash, persisted 5s Undo; later item restore Inbox | Undo/expiry/restart/pre-existing Trash/reminder/refcount tests |
| 06 | One MainActivity task; consume every new share once through lock gate | Sequential SEND/SEND_MULTIPLE and Recents/lifecycle coverage |
| 07 | Existing Random Links ordinary/deletable; no automatic creation | Save/import/restart/legacy-role backup regressions |
| 08 | Automatic signed device registration and renewal; ordinary Settings has no technical setup | Registration/expiry/retry/replay/revocation/rate tests; no secrets in APK |
| 09 | Only Library / Collections / Settings navigation; Ask AI in Library | Navigation/Back/state/selection tests |
| 10 | Ask AI with/without local hits; synonyms before retrieval caps; concise progress; preserve local results on failure | Old Ryan bday beyond 160 newer items without vectors; grounded citations/conflicts/limits |
| 11 | Contextual saves inherit current folder/view; no second folder question | Notes/links/files/shares plus Favorites/Inbox/Reminders |
| 12 | Reminders asks date/date+time before save; cancel creates nothing; Clear never wraps | Scheduler/permissions/date-only and narrow/large-font UI |
| 13 | Uniform grid slots for preview/title/body/actions with scalable text | Mixed-media grid screenshots and action reachability |
| 14 | Collection detail stays in Collections; local and AI scope cannot escape | Clear filters/query/tab/retry/membership change/no out-of-scope dispatch |
| 15 | Consume insets once, no empty top gap, green confirmations, cancellable progress/errors separate | Status/IME/system navigation/narrow screens |
| 16 | Rename Unfiled to Inbox; explain items without a folder | Default save/move/restore and empty-state checks |
| 17 | Auto-start voice after permission; large mic toggles/retries; precise errors/cleanup | Permission, unavailable service, partial/final result, stop/retry/dispose |
| 18 | Backup passphrase minimum 8 in UI and encryption engine; cryptography unchanged | 7 rejected, 8 accepted, old backups restore, mismatch/wrong-password checks |
| 19 | SpendWise dark surfaces throughout app and system bars | Light/Dark/System screenshots, contrast, RTL/font scale |

### Storage and compatibility

- Enforce <=1 membership per item with a unique index. Missing/invalid assignment is Inbox. All create/capture/import/move/backup paths must apply this rule.
- Migrate Room v5 forward explicitly. Retain harmless appRole column but clear/ignore Random Links role instead of rebuilding the table just to drop it.
- Preserve old memberships in a temporary migration ledger until SharedPreferences AI exclusions have been synchronously committed to affected item exclusions. Block AI while checkpoint pending; replay safely after crash. Apply transfer-before-detach to moves/deletions too.
- Legacy backups normalize membership by source `(collection.createdAt, collection.id)` before ID remapping. Preserve Skip Existing/Keep Both semantics. New GIF/8-character backups may require the new app; old backups remain readable.
- Persist pending collection deletion identity/deadline and exact newly-trashed members. Reject new saves to hidden folders. Use Android's accessible timeout recommendation with 5s base. Undo and expiry share the persisted deadline. Never resurrect already-trashed items. Conflicting per-item restore must not leave active items in hidden folders.
- Snapshot pending deletes as finalized in backups; later restoration goes to Inbox. Permanent item/file cleanup uses existing refcount/index/reminder-safe repository paths.

### AI and UI architecture

- New public signed `/v1/auth/register`; preserve legacy secret enrollment/session compatibility. Register single-flight only after privacy/network eligibility. Keep provider keys server-side, proof of possession, renewal, cancellation and limits. Anonymous keys cannot fully prevent abuse; do not promise app attestation. No new paid infrastructure/accounts.
- Move Gemini synonym planning before bounded candidate selection and pass expanded concepts into candidate/document/evidence ranking. Use cached vectors without introducing a new index. Maintain payload, citation and privacy guards.
- Collection scope is immutable across retrieval, auth retries and publication; clear filters cannot remove it. Keep per-tab/per-folder query and scroll state. Ignore/discard stale responses.
- Share a browsing composable across Library and collection detail; preserve stable lazy list/grid hosts from QA009. Put Ask AI into this browsing surface and simplify progress/empty states.
- Dark tokens from SpendWise: background #05080C, surface #0C1117, low #080C11, high #111820, border #202A33. Retain native Compose components and accessibility.

## Execution order and durable progress

1. Save this addendum and update root worklog. Reuse current checkout/QA branch; preserve owner `classes.jar`, `docs/qa/` and certified code4 artifacts.
2. Storage/media/password checkpoint (03/04/05/07/11/12/16/18 foundations): write failing tests, implement minimal shared changes, verify.
3. AI/auth/retrieval checkpoint (08/10, scoped14/network defaults/pause): test-first backend/client, no production deployment.
4. UI/share/voice/theme checkpoint (01/02/06/09/11/12/13/14/15/16/17/19): integrate new storage/AI interfaces and test behavior.
5. Fresh challenge review; fix confirmed gaps. Run full core/app JVM, server, lint, debug/optimized instrumentation. Keep evidence types separate.
6. New private 1.0.2/code5 only after all five exact-source CI jobs pass; preserve old code4. Prepare concrete backend rollout changes before any deployment approval. No merge/tag/publication/deploy.

Each milestone records files, tests/results/logs, rulings, commit/run IDs, unresolved limits and exact next action in root CODEX_WORKLOG.md. Use ignored artifacts/owner-feedback-20261024 for verbose logs. A test written is not a test run; implementation is not physical acceptance.

## Baseline and constraints

- Start branch codex/v1.0.1-final-qa, HEAD 9bb4977; no tracked dirty files; unrelated untracked classes.jar/docs/qa/.
- Published historical ledger stays 30 PASS / 11 FAIL / 7 incomplete; new candidate evidence never rewrites it.
- JDK21, repository .tools/android-sdk, JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<repo>/.tools, Gradle --no-daemon --no-configuration-cache --console=plain. One heavy build at a time.
- Only authorized synthetic emulator data. Do not install/touch a physical phone, call paid providers, deploy, print secrets or copy personal screenshots as part of local implementation.
- Smallest complete existing-architecture change; use ponytail and impeccable guidance. Repository root worklog is the durable checkpoint; do not create a competing progress system.
