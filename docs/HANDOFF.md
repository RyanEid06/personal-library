# Memora V1 owner handoff

## Official V1.0.1 handoff

The Desktop delivery is **ARM64 only**: `C:\Users\Administrator\Desktop\Memora-v1.0.1-arm64-official.apk` (18,319,892 bytes), SHA-256 `8471E6B03CD6502E28000C21E043BBF83B82F1062F885647252E6B8FDD72F246`. After verified publication, both supported variants are downloadable from the [V1.0.1 release channel](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.1); the universal fallback is retained in repository artifacts and designated for the release assets. The previous Desktop candidate is superseded; its repository artifacts remain preserved.

Permanent-signed1.0.1/code3 APK source `5ac62b4ccbcdacdf6144827c8f09a3cfdeca5125` passed all5 jobs in [CI 39389151689](https://github.com/RyanEid06/personal-library/actions/runs/39389151689), including55/55 debug and55/55 optimized instrumentation. The final tag may add documentation only and requires its own green five-job workflow before publication. Official code3 provenance is separate from the historical candidate. Exact pair hashes, authorization, full A–M report and known limits are in [the same worklog](CODEX_V1_0_1_WORKLOG.md).

Install as an update over permanent-signed V1.0.0/code2 using the same package/certificate. Preserve an encrypted backup. All100 physical acceptance checks are still unexecuted; follow [the synthetic-only physical checklist](CODEX_V1_0_1_PHYSICAL_QA.md). Live Render deployment/provider acceptance remains unverified after a Cloudflare challenge. Historical V1.0.0 instructions follow.

## Published V1.0.0 handoff


Memora V1.0.0 (versionCode 2, application ID `com.personallibrary.app`) is the first official release. The ARM64 and universal APKs were built and signed from `1c42f958f3e663d2e547a266670bae4fb54e5214`, certified by [run 38959696966](https://github.com/RyanEid06/personal-library/actions/runs/38959696966). The release-record documentation commits changed no Android, build, or signing inputs; their exact tagged source also passed all five jobs in [run 38965995019](https://github.com/RyanEid06/personal-library/actions/runs/38965995019). The APK hashes and permanent certificate were independently verified before publication. See the [official GitHub release](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.0), [release procedure and audit](branding/memora/RELEASE.md), and [current status](STATUS.md). Physical-phone acceptance remains with the owner.

## Installation

The release includes `Memora-v1.0.1-arm64-official.apk` for arm64-v8a phones and `Memora-v1.0.1-universal.apk` containing arm64-v8a/armeabi-v7a/x86/x86_64. Both require Android 10 or newer and share the same tagged source, version and permanent certificate. Download from the [official release](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.1). Debug-signed `optimizedCheck` artifacts are verification-only.

Export an encrypted backup of an existing library before changing installs. Keep its passphrase safely. The intended primary Desktop artifact is `Memora-v1.0.1-arm64-official.apk`, only after successful permanent-signature verification. A debug-signed installation cannot be updated by this different permanent certificate. Stop before uninstalling a valuable existing library and preserve/export its data. Nothing is uninstalled automatically. Future official updates must keep the same package/certificate and increase versionCode.

Report the failing action, device/Android version and visible message. Never send enrollment/API secrets or personal library content in diagnostics.

## Phone acceptance

- [ ] Check the Memora launcher/name and compact Library header: item count and Saved/Source Date basis share the top row with the grid/list and Settings controls, with no logo/name above them. In Settings -> Appearance choose Light, Dark and System; check immediate change, app restart and phone theme changes while System is selected. Verify header readability and status/navigation bar contrast. [Exact RGB palette and original artwork](branding/memora/README.md).
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

The owner-created permanent key remains outside the repository; its public [identity](branding/memora/SIGNING_IDENTITY.md) is preserved. The first official `v1.0.0` tag and GitHub release are published after exact-source gates passed. Automated release verification is complete. Physical-device acceptance and the owner's final freeze decision remain pending. Plan data transfer through the tested encrypted backup before any owner-approved uninstall.

## Maintainer continuation

Owner-authorized branch: `main`. The first release is `v1.0.0`; its annotated tag identifies the final release-documentation commit, which has a green exact-source five-job run. The signed APKs were built from `1c42f958f3e663d2e547a266670bae4fb54e5214`; comparison confirms only documentation changed between that build source and the tagged source. The immutable [GitHub release](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.0) is the download and checksum record. Detailed CI, signing, runtime, and artifact evidence is in the ignored `artifacts/release-v1/FINAL_VERIFICATION.md`. Earlier finalization started at `aeacb1c2e241122b69a9f781411367ffe3ef779f`, with Android/core checkpoint `4217f95dc42f9f3fc6d7769bbb99e78f168379e4` [green](https://github.com/RyanEid06/personal-library/actions/runs/38876784338), followed by the final 32-test server ingress correction. Historical `codex/v1` was deleted at the owner's request after its complete history was preserved and verified in `artifacts/release-v1/codex-v1-221b877-archive.bundle`; it was an older alternative implementation, not an ancestor of main.

Fixes cover auth/Keystore, backup/restore/refcounts, SAF WhatsApp import, reminders, dates/browse, AI privacy/queue/version races, media bounds/page provenance, search labels/snippets, video/voice, capture lifecycle/transactions, cancellation, migrations and CI. The [finalization ledger](superpowers/plans/2026-10-15-v1-finalization.md) preserves regression and review history. Fake-provider CI and API 35 media tests do not certify the owner's phone or live Gemini quality.
