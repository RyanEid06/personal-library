# Encrypted backup format V1

Authoritative implementation: `core/src/main/kotlin/com/personallibrary/core/backup/EncryptedBackupEngine.kt`. Android repository restore adds database/blob integrity and reminder scheduling. This is a passphrase-protected binary streaming archive, not plain JSON or ZIP. Restore does not depend on the original device's Keystore.

## Envelope and encryption

Integer fields use Java DataOutputStream big-endian encoding.

| Field | Encoding |
| --- | --- |
| Envelope magic | 8 ASCII bytes `PLIBBK01` |
| Fresh salt | 16 random bytes |
| Fresh wrapping IV | 12 random bytes |
| Wrapped keyset length | Signed 32-bit integer, validated before allocation |
| Wrapped keyset | AES-256-GCM ciphertext, 128-bit tag |
| Stream | Tink `AES256_GCM_HKDF_1MB` streaming AEAD |

Minimum passphrase in the owner-feedback candidate: 8 characters (older apps require 12). PBKDF2-HMAC-SHA256, 600,000 iterations and the salt derive a 256-bit wrapping key. AES-GCM wraps a fresh serialized Tink streaming keyset with `PLIBBK01` as associated data. Streaming associated data is `PLIBBK01 || salt`. Each export uses fresh salt, IV and keyset. A lost passphrase prevents recovery.

## Decrypted framing

| Field | Encoding |
| --- | --- |
| Archive magic | 8 ASCII bytes `PLIBAR01` |
| Version | Signed 32-bit integer, 1 |
| Manifest length | Signed 32-bit integer |
| Manifest | UTF-8 JSON |
| Asset count | Signed 32-bit integer |
| Each original | 32 raw SHA-256 bytes, signed 64-bit byte length, exactly that many bytes |
| End | ASCII `PLIBAR01_END`, then end of stream |

The manifest includes items, collections, memberships, reminders and assets: owner content, favorites, saved/source dates, Trash and original metadata. Compatible date-only fields default to false/null in older manifests: item `isReminderDateOnly`/`reminderLocalDate`, reminder `isDateOnly`/`localDate`.

V1.0.1 keeps envelope/archive version 1 and adds optional collection `applicationRole` (default null when reading older V1 backups). `RANDOM_LINKS` preserves routing through display-name changes and fresh/merged restores. Non-destructive Room migration 4→5 adds the corresponding unique optional role and default-false analysis-job `metadataOnly` flag; derived jobs remain local rather than portable backup state. Restore preserves compatible live collection display metadata, deterministically remaps genuine identity collisions, and retains memberships when repeated.

The owner-feedback candidate uses Room 6 and one concrete folder per item. Legacy multiple memberships retain the oldest source folder by `(createdAt, id)` before collision remapping. Random Links roles are ignored; imported folders remain ordinary editable/deletable folders. Backups snapshot pending folder deletions as finalized: affected items stay in Trash without the hidden folder or its membership. GIF is a separate item kind. Old backups remain readable; new GIF or short-passphrase backups may require the new app. Archive framing and encryption are unchanged.

Bounds: manifest 10 MiB, each original 500 MiB, aggregate original bytes 2 GiB, 50,000 assets. Inspection rejects unsupported versions, invalid lengths/metadata, duplicate/undeclared/missing frames, size/hash mismatches (including empty assets), truncation, wrong passphrase, tampering and trailing data. Framed assets must equal declared assets exactly. Export fails on a missing or mismatched original.

## Snapshot and restore

Export obtains one Room transaction snapshot of all metadata and protects originals through streaming; the Room transaction ends before encryption/file I/O. Cancellation propagates.

Inspection validates into private staging before live mutation. Restore previews duplicates, safely remaps unrelated collection-ID collisions, verifies/publishes real blobs, and commits metadata/refcounts transactionally. Failed commits reconcile new orphan publications. Shared originals are counted from committed references. Schedule reminders only after successful commit.

Owned staging is removed on cancellation, dismissal, failure and completion; initialization cleans abandoned stages while preserving active previews. This removes private staged files and references, without claiming physical flash-block secure erasure.

Derived text/AI fields are not trusted as a ready index: restore rebuilds indexes and eligible analysis work. Keystore identity, pairing/session credentials and device preferences are outside the portable archive; the owner-feedback candidate registers the new device automatically when eligible AI is requested. Platform backup/transfer exclusions are deliberate.

Core integrity and Room failure-injection tests passed. Physical fresh-device restore remains [owner acceptance](HANDOFF.md).
