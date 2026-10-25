package com.example.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileProviderScopeTest {
    // AndroidX uses literal '/' for containment; Windows paths cannot exercise it.
    // Actual URI boundaries and open/read are covered by Android instrumentation.
    @Test fun realManifestExposesOnlyManagedOriginalsAndNamedExportCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.resolveContentProvider("${BuildConfig.APPLICATION_ID}.fileprovider", PackageManager.GET_META_DATA)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(R.xml.file_paths, info.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        val roots = mutableListOf<Pair<String, String?>>()
        context.resources.getXml(R.xml.file_paths).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name != "paths") {
                    roots += parser.name to parser.getAttributeValue(null, "path")
                }
            }
        }
        assertEquals(listOf("files-path" to "storage_root/blobs/", "cache-path" to "shared_originals/"), roots)
    }
}
