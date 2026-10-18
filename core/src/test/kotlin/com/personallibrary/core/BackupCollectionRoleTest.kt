package com.personallibrary.core

import com.personallibrary.core.backup.BackupCollectionRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BackupCollectionRoleTest {
    @Test fun legacyCollectionDefaultsToNoRole() {
        val legacy = """{"id":"legacy","name":"Random Links","iconName":"chat","colorHex":"#ffffff","createdAt":1}"""
        assertNull(Json.decodeFromString<BackupCollectionRecord>(legacy).appRole)
    }
    @Test fun renamedRoleSurvivesPortableSerialization() {
        val collection = BackupCollectionRecord("id", "Owner renamed inbox", "chat", "#ffffff", 1, "RANDOM_LINKS")
        assertEquals(collection, Json.decodeFromString<BackupCollectionRecord>(Json.encodeToString(collection)))
    }
}
