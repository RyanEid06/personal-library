package com.personallibrary.core.backup

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Owns decrypted previews for this process. Deletion removes private files, not flash blocks. */
class RestoreStaging(private val root: File) {
    companion object {
        private val guard = Any()
        private val active = mutableSetOf<String>()
    }

    fun inspect(input: InputStream, passphrase: CharArray, existingIds: Set<String>): RestorePreview = synchronized(guard) {
        root.mkdirs()
        cleanAbandoned()
        // Inspection is synchronous. The lock protects stages under construction from another cleanup.
        EncryptedBackupEngine().inspectAndStage(input, passphrase, root.toPath(), existingIds).also {
            active.add(it.stagingDir.canonicalPath)
        }
    }

    fun discard(preview: RestorePreview) = synchronized(guard) {
        val stage = preview.stagingDir
        require(stage.name.startsWith("restore_stage_") && stage.parentFile.canonicalFile == root.canonicalFile &&
            !Files.isSymbolicLink(stage.toPath()) && stage.canonicalFile.parentFile == root.canonicalFile) { "Unowned restore stage" }
        stage.deleteRecursively()
        active.remove(stage.canonicalPath)
        Unit
    }

    fun cleanAbandoned() = synchronized(guard) {
        root.listFiles()?.forEach { stage ->
            if (stage.name.startsWith("restore_stage_") && !Files.isSymbolicLink(stage.toPath()) &&
                stage.canonicalFile.parentFile == root.canonicalFile && stage.canonicalPath !in active) {
                stage.deleteRecursively()
            }
        }
    }
}
