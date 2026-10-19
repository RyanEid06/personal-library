# Memora V1.0.1 physical-device QA

Candidate source SHA and signed APK hashes: pending final release verification.
All items below are unexecuted physical-device checks. Automated and emulator evidence must be reported separately.

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



Produce a final real-device checklist even if automated tests are green.

Include:

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

Type sequentially:

`b`

`bi`

`bir`

`birth`

`birthday`

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
