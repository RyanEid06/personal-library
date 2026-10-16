# Memora V1 owner handoff

V1 finalization code is complete. The owner has now authorized the final Memora stabilization and permanently signed official APK, version 1.0.0 / code 2, application ID `com.personallibrary.app`. [Release procedure and audit](branding/memora/RELEASE.md) describe the existing owner key and fail-closed local build; [STATUS](STATUS.md) separates current verification from historical debug evidence. Official artifact delivery is pending until its exact source, signature, metadata and checksum are verified.

## Installation

Export an encrypted backup of an existing library before changing installs. Keep its passphrase safely. The intended official Desktop artifact is `Memora-v1.0.0-official.apk`, only after successful permanent-signature verification. A debug-signed installation cannot be updated by this different permanent certificate. Stop before uninstalling a valuable existing library and preserve/export its data. Nothing is uninstalled automatically. Future official updates must keep the same package/certificate and increase versionCode.

Report the failing action, device/Android version and visible message. Never send enrollment/API secrets or personal library content in diagnostics.

## Phone acceptance

- [ ] Check the Memora launcher/name and both Library wordmarks. In Settings -> Appearance choose Light, Dark and System; check immediate change, app restart and phone theme changes while System is selected. Verify header actions, item counts and status/navigation bar contrast. [Exact RGB palette and original artwork](branding/memora/README.md).
- [ ] Share actual WhatsApp text, link, photo/screenshot, sticker, video, audio/voice note, PDF/document, unknown file and multi-file batch. Check captions, previews, Open/Share, cancel and partial failure. Leave the screen during a large save, then save another batch. Saved must mean durable originals. With disposable content, confirm source deletion does not remove the library copy.
- [ ] Import a disposable WhatsApp `.txt` and separately selected attachments. Verify preview counts, unresolved dates, matched/missing/unmatched files. Repeat: no duplicate messages or inflated storage references. Supply a previously missing attachment and verify search refreshes. Extract ZIP packages first.
- [ ] Browse list/grid, collections, Favorites, Unfiled and Trash; check custom ranges, Saved/Source dates, both source sort directions, grouping and retained position. Test offline local snippets/OCR/PDF page references. Restore Trash and verify permanent purge preserves originals still referenced by other items.
- [ ] Pair privately in Settings with the existing operator enrollment secret. Analyze disposable text/photo/audio/PDF and test meaning-based retrieval. Restart and retry. Confirm outages retain local search and honest labels. No secret needs to be sent to the agent.
- [ ] Pause AI, save disposable items, resume and verify processing. Exclude an item/collection before indexing: local extraction continues, remote analysis stays excluded. Test Retry analysis after a recoverable failure. Originals remain unchanged.
- [ ] Test timed/date-only reminders, notification denial then grant, unavailable exact access, Snooze/Complete, notification navigation, reboot and timezone change. Date-only targets 09:00 local and stays a calendar date. Check OEM background policy on the actual phone.
- [ ] Try English voice, editable transcript, microphone denial, cancel/retry and displayed recognizer fallback. Compare typed and voice searches for the same final text.
- [ ] Test biometric/device-credential lock, background/re-entry and incoming shares while locked; protected content must stay hidden until unlock.
- [ ] Export/inspect/restore an encrypted backup on a safe test/fresh-device setup. Verify originals, collections, duplicates, favorites, Trash and date-only reminders. Wrong passphrase/corruption must leave the live library unchanged. Restored indexes rebuild; pair the device separately.
- [ ] Check dark/light themes, large fonts, TalkBack, touch targets, keyboard/insets, navigation and empty/error states. Evaluate 20-30 realistic supported queries with known targets and record top-five success, semantic/visual misses and latency. Design targets are >=90% top-five retrieval for supported fully indexed cases and <300 ms local-search p95 with 10,000 realistic items/chunks. These measurements remain owner acceptance work.

## Freeze and official signing

The owner manually created the permanent key outside the repository; its public [identity](branding/memora/SIGNING_IDENTITY.md) is preserved. The current pass is authorized to build/sign V1 after exact-source automated gates pass. Physical acceptance and any final acceptance/freeze record remain pending. No release tag or public release has been created. Plan data transfer through the tested encrypted backup before any owner-approved uninstall.

## Maintainer continuation

Owner-authorized branch: `main`. Starting SHA: `aeacb1c2e241122b69a9f781411367ffe3ef779f`. Android/core checkpoint: `4217f95dc42f9f3fc6d7769bbb99e78f168379e4`, [all four jobs green](https://github.com/RyanEid06/personal-library/actions/runs/38876784338). A final server stalled-ingress regression then passed 32 local tests/typecheck/build and read-only review. Final delivery SHA and repeated hosted gates are in the Desktop acceptance record. No implementable finalization item remains. Historical `codex/v1` is preserved.

Fixes cover auth/Keystore, backup/restore/refcounts, SAF WhatsApp import, reminders, dates/browse, AI privacy/queue/version races, media bounds/page provenance, search labels/snippets, video/voice, capture lifecycle/transactions, cancellation, migrations and CI. The [finalization ledger](superpowers/plans/2026-10-15-v1-finalization.md) preserves regression and review history. Fake-provider CI and API 35 media tests do not certify the owner's phone or live Gemini quality.
