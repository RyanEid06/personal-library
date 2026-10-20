# Memora V1.0.1 physical-device QA

Candidate source `d5b506e69066d1d60dfa2890bf7a083b845765ac`; [five-job CI39370480374](https://github.com/RyanEid06/personal-library/actions/runs/39370480374) passed. Version1.0.1/code3; permanent signing certificate unchanged.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| [Memora-v1.0.1-arm64-candidate.apk](../artifacts/release-v1.0.1-candidate/Memora-v1.0.1-arm64-candidate.apk) | 18,319,892 | `785FAE813EB16A99C9006446394CC6EDE32A5C1243A1C50FC93C3BEB2BEE3AED` |
| [Memora-v1.0.1-universal-candidate.apk](../artifacts/release-v1.0.1-candidate/Memora-v1.0.1-universal-candidate.apk) | 48,356,815 | `656678B2A338A92324AD6F075DED66FB89520D9EE6F2F3A258248CBB191234EB` |

All items below are unexecuted physical-device checks. Automated and emulator evidence must be reported separately.

Update compatibility and performance follow-up:

- [ ] On a disposable synthetic-only installation of permanent-signed V1.0.0/code 2, add representative library/collection/reminder records, install this same-certificate candidate as an update, and verify schema 4→5 retains them and routes renamed Random Links correctly.
- [ ] On a fresh synthetic-only installation, restore an older valid V1 backup and a new role-bearing V1 backup; verify items, originals, memberships, stable Random Links identity and future reminders.
- [ ] Measure rapid typing/deletion, first-result latency and scroll responsiveness in both layouts with a large synthetic library; record device, Android version, row/asset counts, timings and peak memory rather than treating bounded unit tests as performance proof.
- [ ] Test genuine notification PendingIntent delivery with the app stopped, running and recreated; verify the requested synthetic item opens once, dismissal stays closed, and a fresh notification for the same item opens again.

Phase 6 UI and file-safety follow-up:

- [ ] Navigate Library → Settings → Trash; verify visible Up and system Back return through Settings, and dialogs/selection close before navigation.
- [ ] Open a synthetic item from a reminder, dismiss/edit it, then change another item; verify its detail does not reopen. Recreate, then open a new notification for the same item.
- [ ] Cold-launch Light, Dark, and System preferences with matching and opposite OS appearance. API 31+ persists app splash appearance; API 29–30 may show the OS-selected starting window before the saved override can be read.
- [ ] Check paired status, type labels, favorites and selected cards in both themes; inspect list/grid/collection actions at 130–150% font and larger display scaling.
- [ ] Verify voice-error guidance names an available action, and type/edit a query after recognition fails.
- [ ] Open/share synthetic image, video, audio and document originals; verify receiving apps can read granted URIs. Private app files, cache and decrypted restore staging must never receive share URIs.
- [ ] Import a known large synthetic file with insufficient free space, then an unknown-size source that fails during copying; confirm clear failure/counts, no half-created item and no staged leftovers.
- [ ] Confirm Trash labels its deduplicated item sum as logical content size rather than exact reclaimable space.

Phase 4 privacy and lifecycle follow-up:

- [ ] Exclude a collection with a distinctive synthetic name; confirm ordinary offline keyword results remain visible while Ask AI omits that collection and its items.
- [ ] Move an item into and out of an excluded collection using both its editor and bulk collection actions; confirm AI eligibility resumes without losing local text or cached analysis.
- [ ] Delete an excluded collection; confirm saved content survives and eligible former members resume AI processing.
- [ ] Change Wi-Fi-only while work is queued and while Ask AI is running; confirm unmetered-policy feedback and no duplicate jobs.
- [ ] Change exclusions, Trash, or edit an item while an AI request is running; confirm obsolete results and source links do not appear.
- [ ] Ask a natural-language question whose relevant synthetic note is older than many unrelated records; verify source selection.
- [ ] Query a relevant later PDF page and verify that its page number is retained.
- [ ] Check a grounded direct answer, conflicting sources, and insufficient evidence; open every displayed source item/page.
- [ ] Against an older backend without the answer endpoint, verify the explicit unavailable message and retained local/related results.

Known bounded-retrieval limitation: a fact on a later PDF page can be missed when that page has no matching question text or selected semantic signal. Narrow the source/collection or use additional keywords; do not interpret an insufficient-evidence response as proof that the library contains no such fact. Automated citation validation checks supplied references, not factual accuracy of every model claim. The new backend endpoint has not been deployed or exercised with a live provider during implementation.



Use synthetic notes, links, exports, attachments and media for these checks. Record the tested device, Android version, candidate SHA and each actual result.


## Library

- [ ] add note
- [ ] add link
- [ ] add file
- [ ] add image
- [ ] add video
- [ ] list
- [ ] grid
- [ ] favorite
- [ ] sort
- [ ] filter
- [ ] collection behavior

## Search

- [ ] Type `b` → `bi` → `bir` → `birth` → `birthday`; verify results update and narrow without pressing Search.

Also test:

- [ ] rapid typing
- [ ] rapid deletion
- [ ] collection filter
- [ ] type filter
- [ ] date filter
- [ ] no result
- [ ] Clear Filters
- [ ] offline search

## Selection

- [ ] long press
- [ ] list
- [ ] grid
- [ ] Select All Visible
- [ ] add collection
- [ ] remove collection
- [ ] Trash
- [ ] Undo

## Edit

- [ ] note
- [ ] URL
- [ ] title
- [ ] verify search updates

## Collections

- [ ] create
- [ ] duplicate rejection
- [ ] rename
- [ ] icon
- [ ] color
- [ ] AI privacy
- [ ] delete
- [ ] confirm content survives
- [ ] rename Random Links
- [ ] test WhatsApp import/routing afterward

## Trash

- [ ] single restore
- [ ] Restore All
- [ ] Delete Forever
- [ ] confirmation
- [ ] Empty Trash
- [ ] reminder restore
- [ ] Undo

## Backup

- [ ] short password
- [ ] mismatched confirmation
- [ ] successful export
- [ ] wrong restore password
- [ ] correct restore
- [ ] cancel restore preview
- [ ] stale stage cleanup
- [ ] older backup compatibility

## AI

- [ ] semantic retrieval
- [ ] direct Q&A
- [ ] source links
- [ ] AI-excluded item
- [ ] AI-excluded collection
- [ ] Wi-Fi only
- [ ] offline
- [ ] previously indexed large video queried without uploading full original again

## Theme

- [ ] cold light launch
- [ ] cold dark launch
- [ ] system mode if supported
- [ ] paired-state contrast

## Accessibility

- [ ] larger font
- [ ] larger display scaling
- [ ] touch targets
- [ ] TalkBack-sensitive controls where practical

## Navigation

- [ ] Library
- [ ] Settings
- [ ] Trash
- [ ] Back
- [ ] dialogs
- [ ] selection mode

---
