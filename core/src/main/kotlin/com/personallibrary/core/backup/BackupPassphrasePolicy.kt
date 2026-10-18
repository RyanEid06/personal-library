package com.personallibrary.core.backup

enum class BackupMode {
    EXPORT,
    RESTORE
}

enum class BackupPassphraseError {
    REQUIRED,
    TOO_SHORT,
    MISMATCH
}

object BackupPassphrasePolicy {
    /** Returns null only when the exact passphrase can be submitted for [mode]. */
    fun validate(mode: BackupMode, passphrase: String, confirmation: String): BackupPassphraseError? {
        if (passphrase.isEmpty()) return BackupPassphraseError.REQUIRED
        if (passphrase.length < EncryptedBackupEngine.MIN_PASSPHRASE_LENGTH) {
            return BackupPassphraseError.TOO_SHORT
        }
        if (mode == BackupMode.EXPORT && passphrase != confirmation) {
            return BackupPassphraseError.MISMATCH
        }
        return null
    }
}
