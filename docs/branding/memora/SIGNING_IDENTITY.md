# Memora Permanent Signing Identity

Created: 2026-10-16

## Release key

- Alias: `memora-release`
- Key type: RSA 4096-bit
- Signature algorithm: SHA256withRSA
- Certificate validity: 2026-10-16 through 2126-09-22
- Package/application ID: `com.personallibrary.app`

## Certificate SHA-256 fingerprint

`98:68:6D:31:BF:E4:37:0C:C3:00:24:43:9B:56:D9:E7:2D:E7:1C:08:19:51:D3:1F:5D:4F:DD:31:D4:EB:05:98`

## Permanent keystore

Local owner path:

`C:\Users\Administrator\Documents\Memora-Signing\memora-release.p12`

Keystore file SHA-256:

`908E801EE64FAD19978A8D30E3E5951C4A3955BF59154AEA32182F0EF1C2FAD8`

## Critical

The permanent keystore and its password are NOT stored in this repository.

Every official Memora APK must use this same signing identity. Losing the keystore or its password prevents future APKs from updating existing official Memora installations.

Keep at least one secure backup of the `.p12` file outside this computer.

Never commit:
- `*.p12`
- `*.jks`
- `*.keystore`
- signing passwords
