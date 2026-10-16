package com.example.release

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ai.AiGateway
import com.example.ai.SearchPlanRequest
import com.example.data.DATABASE_CALLBACK
import com.example.data.LibraryRepository
import com.example.data.LibraryRoomDatabase
import com.example.indexing.AndroidTextExtractor
import com.example.reminder.ReminderScheduler
import com.example.security.AppLockManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs against debug AND the nondebuggable, R8/resource-shrunk verification build. */
@RunWith(AndroidJUnit4::class)
class ReleaseRuntimeTest {
    @Test fun roomManagedOriginalAndTinkBackupRoundTripAfterShrinking() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = context.getSharedPreferences("personal_library_settings", 0)
        val paused = settings.getBoolean("ai_paused", false)
        settings.edit().putBoolean("ai_paused", true).commit()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
            .addCallback(DATABASE_CALLBACK).build()
        val repository = LibraryRepository(context, db)
        val backup = File.createTempFile("release-roundtrip-", ".plb", context.cacheDir)
        val bytes = "Memora optimized original integrity".toByteArray()
        val passphrase = "synthetic-release-test-passphrase".toCharArray()
        try {
            val item = repository.captureStream(bytes.inputStream(), "release-proof.txt", "text/plain")
            assertNotNull(repository.dao.getItemById(item.id))
            assertTrue(repository.exportEncryptedBackup(Uri.fromFile(backup), passphrase))
            val preview = checkNotNull(repository.inspectBackup(Uri.fromFile(backup), passphrase))
            assertEquals(item.contentHash, preview.manifest.items.single().contentHash)
            repository.deletePermanently(item.id)
            assertEquals(1, repository.commitRestore(preview))
            val restored = checkNotNull(repository.dao.getItemById(item.id))
            assertArrayEquals(bytes, checkNotNull(repository.getBlobFile(checkNotNull(restored.contentHash))).readBytes())
            assertTrue(repository.dao.searchFts("integrity").any { it.id == item.id })
            repository.deletePermanently(item.id)
        } finally {
            db.close(); backup.delete(); passphrase.fill('\u0000')
            settings.edit().putBoolean("ai_paused", paused).commit()
        }
    }

    @Test fun bundledOcrRecognizesTextAfterShrinking() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = File.createTempFile("release-ocr-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText("MEMORA LIBRARY", 40f, 150f, Paint().apply {
                color = Color.BLACK; textSize = 80f; isAntiAlias = true
            })
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val extracted = AndroidTextExtractor.image(image, "image/png")
            assertTrue("Bundled OCR must recognize synthetic text", extracted.text.contains("MEMORA", true))
        } finally { bitmap.recycle(); image.delete() }
    }

    @Test fun serializationGatewayBiometricAndReminderInitializationAfterShrinking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = SearchPlanRequest("chemistry", listOf("Study"), "2026-10-16T12:00:00Z")
        assertEquals(request, Json.decodeFromString<SearchPlanRequest>(Json.encodeToString(request)))
        assertTrue(AiGateway(context).backendBaseUrl.startsWith("https://"))
        val lock = AppLockManager(context)
        assertFalse(lock.isLockEnabled())
        lock.canAuthenticate()
        val reminders = ReminderScheduler(context)
        assertFalse(reminders.schedule("release-expired", "Expired", 1L))
        reminders.cancel("release-expired")
    }
}
