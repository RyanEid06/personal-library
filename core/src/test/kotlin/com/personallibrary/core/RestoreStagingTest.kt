package com.personallibrary.core

import com.personallibrary.core.backup.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class RestoreStagingTest {
    private fun archive(): ByteArray = ByteArrayOutputStream().also { output ->
        EncryptedBackupEngine().exportArchive(output, "synthetic-stage-pass".toCharArray(),
            BackupManifest(items = emptyList(), collections = emptyList(), memberships = emptyList(),
                reminders = emptyList(), assets = emptyList())) { null }
    }.toByteArray()

    @Test fun cancelledPreviewDeletesPrivateStageButProtectsOtherActivePreviews() {
        val root = Files.createTempDirectory("restore-test").toFile()
        try {
            val store = RestoreStaging(root)
            val first = store.inspect(archive().inputStream(), "synthetic-stage-pass".toCharArray(), emptySet())
            val second = store.inspect(archive().inputStream(), "synthetic-stage-pass".toCharArray(), emptySet())
            store.discard(first)
            assertFalse(first.stagingDir.exists())
            RestoreStaging(root).cleanAbandoned()
            assertTrue(second.stagingDir.exists())
            store.discard(second)
            assertFalse(second.stagingDir.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun abandonedStagesRemovedAndUnrelatedDirectoriesPreserved() {
        val root = Files.createTempDirectory("restore-test").toFile()
        try {
            val abandoned = java.io.File(root, "restore_stage_abandoned").apply { mkdirs() }
            java.io.File(abandoned, "decrypted").writeText("synthetic private content")
            val unrelated = java.io.File(root, "other-cache").apply { mkdirs() }
            RestoreStaging(root).cleanAbandoned()
            assertFalse(abandoned.exists())
            assertTrue(unrelated.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun discardRejectsPathsOutsideOwnedStagingRoot() {
        val root = Files.createTempDirectory("restore-test").toFile()
        val outside = Files.createTempDirectory("unrelated").toFile()
        try {
            val preview = RestorePreview(BackupManifest(items=emptyList(), collections=emptyList(), memberships=emptyList(),
                reminders=emptyList(), assets=emptyList()), outside, 0,0,0,0)
            try { RestoreStaging(root).discard(preview); fail("Unowned stage accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(outside.exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
