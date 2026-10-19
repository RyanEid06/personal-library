package com.example.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileProviderScopeUiTest {
    @Test fun managedOriginalOpensAndPrivateBoundariesReject() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val allowed = File(context.filesDir, "storage_root/blobs/ab/synthetic-provider-test")
        val outside = listOf(File(context.filesDir, "synthetic-private-test.txt"),
            File(context.filesDir, "storage_root/blobs-private/synthetic-test.txt"),
            File(context.cacheDir, "restore_stage_synthetic_test/private.txt"))
        val files = listOf(allowed) + outside
        try {
            files.forEach { it.parentFile!!.mkdirs(); it.writeText("synthetic provider content") }
            val uri = FileProvider.getUriForFile(context, authority, allowed)
            assertEquals("synthetic provider content", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            outside.forEach { file ->
                try { FileProvider.getUriForFile(context, authority, file); fail("Private path must be rejected") }
                catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("Failed to find configured root")) }
            }
        } finally { files.forEach { it.delete() } }
    }
}
