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

Minimum passphrase: 12 characters. PBKDF2-HMAC-SHA256, 600,000 iterations and the salt derive a 256-bit wrapping key. AES-GCM wraps a fresh serialized Tink streaming keyset with `PLIBBK01` as associated data. Streaming associated data is `PLIBBK01 || salt`. Each export uses fresh salt, IV and keyset. A lost passphrase prevents recovery.

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

Bounds: manifest 10 MiB, each original 500 MiB, aggregate original bytes 2 GiB, 50,000 assets. Inspection rejects unsupported versions, invalid lengths/metadata, duplicate/undeclared/missing frames, size/hash mismatches (including empty assets), truncation, wrong passphrase, tampering and trailing data. Framed assets must equal declared assets exactly. Export fails on a missing or mismatched original.

## Snapshot and restore

Export obtains one Room transaction snapshot of all metadata and protects originals through streaming; the Room transaction ends before encryption/file I/O. Cancellation propagates.

Inspection validates into private staging before live mutation. Restore previews duplicates, safely remaps unrelated collection-ID collisions, verifies/publishes real blobs, and commits metadata/refcounts transactionally. Failed commits reconcile new orphan publications. Shared originals are counted from committed references. Schedule reminders only after successful commit.

Derived text/AI fields are not trusted as a ready index: restore rebuilds indexes and eligible analysis work. Keystore identity, pairing/session credentials and device preferences are outside the portable archive; pair a restored device separately. Platform backup/transfer exclusions are deliberate.

Core integrity and Room failure-injection tests passed. Physical fresh-device restore remains [owner acceptance](HANDOFF.md).
