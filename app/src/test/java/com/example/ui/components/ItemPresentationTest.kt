package com.example.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import com.example.data.ItemEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemPresentationTest {
    @Test fun kindLabelsMeetTextContrastOnTheirCardAndTintedBadgeInBothThemes() {
        for (dark in listOf(false, true)) {
            for (kind in listOf("PHOTO", "STICKER", "VIDEO", "AUDIO", "TEXT", "LINK", "DOCUMENT", "UNKNOWN")) {
                val foreground = kindColorForTheme(kind, dark)
                val surfaces = if (dark) listOf(Color(0xFF1E293B)) else listOf(Color.White, Color(0xFFF1F5F9))
                for (surface in surfaces) {
                    for (background in listOf(surface, foreground.copy(alpha = 0.15f).compositeOver(surface))) {
                        val first = foreground.luminance()
                        val second = background.luminance()
                        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
                        assertTrue("$kind contrast $ratio in ${if (dark) "dark" else "light"} theme", ratio >= 4.5f)
                    }
                }
            }
        }
    }

    @Test fun audioUsesHeadphonesInsteadOfReminderIcon() {
        assertEquals(Icons.Filled.Headphones, getKindIcon("AUDIO"))
    }

    @Test fun linkUsesLinkInsteadOfSharingActionIcon() {
        assertEquals(Icons.Filled.Link, getKindIcon("LINK"))
    }

    @Test fun documentUsesDocumentInsteadOfInformationIcon() {
        assertEquals(Icons.Filled.Description, getKindIcon("DOCUMENT"))
    }

    @Test fun textAndLinkSharingKeepTheOriginalPayloadInASystemChooser() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            var launched: Intent? = null
            override fun startActivity(intent: Intent) { launched = intent }
        }
        for (kind in listOf("TEXT", "LINK")) {
            val content = if (kind == "LINK") "https://example.test/synthetic" else "Synthetic body"
            val item = ItemEntity("synthetic-share", "Synthetic title", content, kind)
            shareItemEntity(context, item, null)
            val chooser = requireNotNull(context.launched)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(item.title, send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(if (kind == "LINK") content else "${item.title}\n\n$content", send.getStringExtra(Intent.EXTRA_TEXT))
        }
    }
}
