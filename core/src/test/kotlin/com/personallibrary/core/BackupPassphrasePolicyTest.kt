package com.personallibrary.core

import com.personallibrary.core.backup.BackupMode
import com.personallibrary.core.backup.BackupPassphraseError
import com.personallibrary.core.backup.BackupPassphrasePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPassphrasePolicyTest {
    @Test fun exportBlocksShortPassphrase() {
        assertEquals(
            BackupPassphraseError.TOO_SHORT,
            BackupPassphrasePolicy.validate(BackupMode.EXPORT, "short", "short")
        )
    }

    @Test fun exportRequiresExactConfirmationMatch() {
        assertEquals(
            BackupPassphraseError.MISMATCH,
            BackupPassphrasePolicy.validate(BackupMode.EXPORT, "twelve chars", "twelve chars ")
        )
    }

    @Test fun validExportPassphraseIsAcceptedWithoutTrimming() {
        assertNull(BackupPassphrasePolicy.validate(BackupMode.EXPORT, "12345678901 ", "12345678901 "))
    }

    @Test fun restoreRequiresAUsableEngineLengthButNoConfirmation() {
        assertEquals(
            BackupPassphraseError.TOO_SHORT,
            BackupPassphrasePolicy.validate(BackupMode.RESTORE, "short", "")
        )
        assertNull(BackupPassphrasePolicy.validate(BackupMode.RESTORE, "123456789012", ""))
    }
}
