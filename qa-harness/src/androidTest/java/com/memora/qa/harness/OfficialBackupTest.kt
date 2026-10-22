package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.personallibrary.core.backup.BackupManifest
import com.personallibrary.core.backup.BackupItemRecord
import com.personallibrary.core.backup.RestorePreview
import com.personallibrary.core.backup.RestoreStaging
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** Real SAF backup/restore; optional cleanup check reads only synthetic restore-stage paths. */
@RunWith(AndroidJUnit4::class)
class OfficialBackupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var token: String
    private var guarded = false
    private var testMethod = ""
    private var publicSource: Uri? = null
    private lateinit var archiveStaging: RestoreStaging
    private val inspectedArchives = mutableListOf<RestorePreview>()
    private val notices = Collections.synchronizedList(mutableListOf<String>())
    // This generated fixture password is artificial test data, never the owner's pairing/signing key.
    private lateinit var fixturePassphrase: String

    @get:Rule val evidence = object : TestWatcher() {
        override fun starting(description: Description) { testMethod = description.methodName }
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching { ui.checkpoint("FAILED") }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) {
            Log.i("MemoraQA", "${description.methodName}: PASS")
        }
    }

    @Before fun guardOfficialSyntheticEmulator() {
        token = "QA-BK-${SystemClock.elapsedRealtime()}"
        fixturePassphrase = java.util.UUID.randomUUID().toString()
        ui = ReleaseUi(instrumentation.uiAutomation, File(context.filesDir, "qa-evidence/$token"))
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the synthetic emulator is allowed" }
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        if (testMethod == UPGRADE_METHOD) {
            val arguments = InstrumentationRegistry.getArguments()
            check(arguments.getString("class") == "${javaClass.name}#$UPGRADE_METHOD" &&
                arguments.getString("hostUpgrade") == "true") { "Only the explicitly selected host upgrade method may run on 1.0.0" }
            check(arguments.getString("rollbackSnapshot").orEmpty().matches(ROLLBACK_SNAPSHOT)) {
                "Host must first verify the preserved official rollback snapshot"
            }
            assertPublishedApk("1.0.0", 2L, OLD_APK_SHA256)
        } else {
            val info = context.packageManager.getPackageInfo(PRODUCT, PackageManager.GET_SIGNING_CERTIFICATES)
            check(info.versionName == "1.0.1" && info.longVersionCode == 3L) { "Expected official 1.0.1/code3" }
            check(hash(checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray()) == CERTIFICATE) {
                "Expected permanent release signer"
            }
        }
        guarded = true
        archiveStaging = RestoreStaging(File(context.cacheDir, "qa-backup-stage/$token"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = eventTypes or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        }
        Log.i("MemoraQA", "Backup observer event mask=${instrumentation.uiAutomation.serviceInfo.eventTypes}")
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                if (event.packageName?.toString() == PRODUCT) {
                    val entries = event.text.map { it.toString() }
                    notices += entries
                    entries.filter { it.startsWith("Encrypted backup exported successfully") ||
                        it.startsWith("Could not inspect the backup.") || it.startsWith("Restored ") }
                        .forEach { Log.i("MemoraQA", "Backup result: $it") }
                }
            }
        }
        launchProduct()
        library()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
    }

    @After fun detachListener() {
        instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().remove("sha256").commit()
        if (::archiveStaging.isInitialized) {
            var failure: Throwable? = null
            inspectedArchives.forEach { preview ->
                runCatching {
                    archiveStaging.discard(preview)
                    check(!preview.stagingDir.exists()) { "Owned decrypted archive stage must be removed" }
                }.exceptionOrNull()?.let { error ->
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    @Test fun T38_T42_encryptedSafExportValidationCancellationAndRealRestoreRoundTrip() {
        val baseline = libraryCount()
        check(baseline in 211..500) { "Expected the existing bounded synthetic import/share library" }
        val original = File(context.filesDir, "synthetic-shares/$token.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(tinyPdf(token))
        }
        saveSyntheticPdf(original)
        library()
        awaitCount(baseline + 1)
        openMarker(original.name)
        ui.waitText("Page 1 of 1", 30000)
        assertMembershipChecked("Random Links")
        ui.checkpoint("T38-source-pdf-rendered")
        library()
        settingsBackup()

        ui.click("Export Backup")
        ui.waitText("Export Encrypted Backup")
        ui.waitText("Enter a passphrase.")
        assertActionEnabled("Choose Destination", false)
        setPassword(0, "short")
        setPassword(1, "short")
        ui.waitText("Use at least 12 characters.")
        assertActionEnabled("Choose Destination", false)
        setPassword(0, fixturePassphrase)
        setPassword(1, "Different-synthetic-passphrase")
        ui.waitText("The passphrases do not match.")
        assertActionEnabled("Choose Destination", false)
        ui.checkpoint("T39-export-invalid-passphrases")
        setPassword(1, fixturePassphrase)
        assertActionEnabled("Choose Destination", true)
        val backupName = "$token.plb"
        val backupPath = "/sdcard/Download/$backupName"
        assertPublicBackupAbsent(backupName)
        ui.click("Choose Destination")
        awaitPicker()
        downloads()
        setFilename(backupName)
        ui.checkpoint("T42-create-document-before-cancel")
        ui.back()
        ui.await("Cancelled destination must return to Memora") { foreground() == PRODUCT }
        library()
        awaitCount(baseline + 1)

        assertPublicBackupAbsent(backupName)
        settingsBackup()
        ui.click("Export Backup")
        ui.waitText("Export Encrypted Backup")
        setPassword(0, fixturePassphrase)
        setPassword(1, fixturePassphrase)
        ui.click("Choose Destination")
        awaitPicker()
        downloads()
        setFilename(backupName)
        ui.checkpoint("T42-create-document-downloads")
        notices.clear()
        pickerClick("Save", "SAVE")
        awaitNotice("Encrypted backup exported successfully (.plb)")
        ui.await("Export must return to Memora") { foreground() == PRODUCT }
        val encrypted = readOwnPublicBackup(backupPath)
        check(encrypted.take(8).toByteArray().contentEquals("PLIBBK01".toByteArray())) { "Expected encrypted PLB envelope" }
        check(!encrypted.toString(Charsets.ISO_8859_1).contains(token)) { "Synthetic content leaked in cleartext" }
        Log.i("MemoraQA", "T38/T42 public synthetic backup bytes=${encrypted.size} sha256=${hash(encrypted)}")
        val beforeArchive = inspectPublicArchive(encrypted)
        check(beforeArchive.manifest.items.size >= baseline + 1)
        check(beforeArchive.manifest.items.single { it.originalFileName == original.name }.contentHash == hash(original.readBytes()))
        ui.checkpoint("T38-export-success")

        selectBackup(backupName)
        ui.waitText("Decrypt & Inspect Backup")
        assertActionEnabled("Inspect Archive", false)
        setPassword(0, "Wrong-synthetic-$token")
        notices.clear()
        ui.click("Inspect Archive")
        awaitNotice("Could not inspect the backup. Check the passphrase and archive, then try again.")
        check(ui.exact("Restore Archive Preview") == null) { "Wrong password must never reach commit preview" }
        library()
        awaitCount(baseline + 1)
        openMarker(original.name)
        ui.waitText("Page 1 of 1", 30000)
        assertMembershipChecked("Random Links")
        verifyRestoredOriginal(original)
        launchProduct()
        library()
        awaitCount(baseline + 1)
        ui.checkpoint("T39-wrong-restore-password-count-and-original-preserved")

        inspectBackup(backupName)
        val total = previewNumber("Total Items")
        check(total >= baseline + 1)
        check(previewNumber("Existing Duplicates") == total)
        check(previewNumber("New Items to Restore") == 0)
        check(previewNumber("Attached Blobs") > 0)
        ui.checkpoint("T40-correct-inspect-before-cancel")
        ui.click("Cancel")
        ui.await("Restore preview must close on Cancel") { ui.exact("Restore Archive Preview") == null }
        library()
        awaitCount(baseline + 1)
        openMarker(original.name)
        ui.waitText("Page 1 of 1", 30000)
        ui.checkpoint("T40-cancel-retains-source")

        // Delete only the just-created fixture via its own UI row, so restore must really import a missing ID/blob.
        ui.click("Trash")
        ui.await("Synthetic item sheet must close") { ui.exact("Edit Item") == null }
        library()
        awaitCount(baseline)
        ui.click("Settings")
        ui.scrollTo("View Trash")
        ui.click("View Trash")
        ui.scrollTo(original.name)
        clickInMarkerRow(original.name, "Delete Forever")
        ui.waitText("Delete Forever?")
        ui.click("Delete Forever")
        ui.await("Exact synthetic item must disappear from Trash") { ui.exact(original.name) == null && ui.exact("Delete Forever?") == null }
        ui.click("Back to Settings")
        ui.await("Trash must close") { ui.exact("View Trash") != null || ui.exact("Settings") != null }
        library()
        awaitCount(baseline)

        inspectBackup(backupName)
        check(previewNumber("Total Items") == total)
        check(previewNumber("Existing Duplicates") == total - 1)
        check(previewNumber("New Items to Restore") == 1)
        ui.checkpoint("T41-one-actual-missing-item-preview")
        notices.clear()
        ui.click("Commit Restore")
        awaitNotice("Restored 1 items successfully", 120000)
        ui.await("Successful restore preview must close", 30000) { ui.exact("Restore Archive Preview") == null }
        library()
        awaitCount(baseline + 1)
        // A process restart is bounded to Memora on the same guarded emulator; no data reset.
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
        awaitCount(baseline + 1)
        openMarker(original.name)
        ui.waitText("Page 1 of 1", 30000)
        assertMembershipChecked("Random Links")
        verifyRestoredOriginal(original)
        check(hash(readOwnPublicBackup(backupPath)) == hash(encrypted)) { "Restore must not modify exported archive" }
        Log.i("MemoraQA", "T38-T42 actual SAF round trip: baseline=$baseline restored=1 persisted=${baseline + 1}; restored PDF hash matched")
        ui.checkpoint("T41-restored-original-hash-after-process-restart")
        launchProduct()
        library()
        val afterName = "$token-after.plb"
        val afterArchive = exportAndInspectCurrentLibrary(afterName)
        assertArchiveGraphEqual(beforeArchive.manifest, afterArchive.manifest)
        Log.i("MemoraQA", "T41 full public archive graph identical: items=${afterArchive.totalItems} collections=${afterArchive.totalCollections} assets=${afterArchive.totalAssets}; every asset hash and size verified")
        ui.checkpoint("T41-full-archive-fields-relationships-and-all-assets-verified")
        ui.shell("rm -- /sdcard/Download/$afterName")
        assertPublicBackupAbsent(afterName)
        // Remove only this run's verified public export after the compatibility/hash checks.
        check(Regex("QA-BK-\\d+\\.plb").matches(backupName))
        ui.shell("rm -- $backupPath")
        assertPublicBackupAbsent(backupName)
        check(context.contentResolver.delete(checkNotNull(publicSource), null, null) == 1) { "Owned public source must be removed" }
        check(original.delete()) { "Owned source fixture must be removed after successful round trip" }
    }

    @Test fun T40_cancelRemovesPrivateStageAndPreservesCompleteGraph() {
        check(InstrumentationRegistry.getArguments().getString("inspectSyntheticProductStage") == "true") {
            "Explicit synthetic-only, read-only restore staging inspection required"
        }
        val baseline = libraryCount()
        check(baseline in 211..500) { "Expected the known bounded synthetic library" }
        check(productStageEntryCount() == 0) { "Pre-test product staging must be empty" }
        val bodyMarker = "QACANCEL${System.currentTimeMillis()}BODY"
        val original = File(context.filesDir, "synthetic-shares/$token.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(tinyPdf("$token $bodyMarker"))
        }
        saveSyntheticPdf(original)
        library()
        awaitCount(baseline + 1)
        // Finish the fixture's independent local indexing before taking the cancellation baseline.
        awaitBodyOnlySearch(bodyMarker)
        ui.click(original.name)
        ui.waitText("Page 1 of 1", 30000)
        library()
        val backupName = "$token.plb"
        val before = exportAndInspectCurrentLibrary(backupName)
        val baselinePdf = before.manifest.items.single { it.originalFileName == original.name }
        check(baselinePdf.extractedText?.contains(bodyMarker) == true) {
            "The fixture's local extraction must be settled before the strict cancellation baseline"
        }
        check(baselinePdf.aiStatus in setOf("QUEUED", "EXCLUDED")) {
            "The fixture's local analysis queue publication must also be settled before the baseline"
        }
        inspectBackup(backupName)
        check(previewNumber("Total Items") == before.totalItems)
        check(previewNumber("Existing Duplicates") == before.totalItems)
        check(previewNumber("New Items to Restore") == 0)
        val stagedEntries = productStageEntryCount()
        check(stagedEntries > 1) { "Positive control must observe the product's populated private restore stage" }
        val stageRoot = "/data/user/0/$PRODUCT/cache/staging"
        val stagedFiles = ui.shell("su 0 find $stageRoot -type f").lineSequence().filter { it.isNotBlank() }.toList()
        check(stagedFiles.isNotEmpty() && stagedFiles.all {
            it.matches(Regex("$stageRoot/restore_stage_[^/]+/assets/[0-9a-f]{64}"))
        }) { "Positive control must contain actual staged asset files, not only empty directories" }
        check(stagedFiles.map { it.substringAfterLast('/') }.sorted() == before.manifest.assets.map { it.hash }.sorted()) {
            "Private stage must contain exactly the authenticated archive's asset files"
        }
        ui.checkpoint("T40-private-stage-positive-control")
        ui.click("Cancel")
        ui.await("Cancelled restore preview must close") { ui.exact("Restore Archive Preview") == null }
        ui.await("Cancel must remove every private product staging entry", 30000) { productStageEntryCount() == 0 }
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
        awaitCount(baseline + 1)
        openMarker(original.name)
        ui.waitText("Page 1 of 1", 30000)
        assertMembershipChecked("Random Links")
        verifyRestoredOriginal(original)
        launchProduct()
        library()
        val afterName = "$token-after.plb"
        val after = exportAndInspectCurrentLibrary(afterName)
        assertArchiveGraphEqual(before.manifest, after.manifest)
        check(productStageEntryCount() == 0)
        File(context.filesDir, "qa-evidence/$token/T40-private-stage-counts.txt").writeText(
            "before=0\npreview=$stagedEntries\npreview_asset_files=${stagedFiles.size}\nafter_cancel=0\nafter_restart_and_reexport=0\n"
        )
        ui.checkpoint("T40-cancel-private-cleanup-and-exact-graph-verified")
        listOf(backupName, afterName).forEach { name ->
            ui.shell("rm -- /sdcard/Download/$name")
            assertPublicBackupAbsent(name)
        }
        check(context.contentResolver.delete(checkNotNull(publicSource), null, null) == 1)
        check(original.delete())
    }

    private fun productStageEntryCount(): Int {
        check(guarded && InstrumentationRegistry.getArguments().getString("inspectSyntheticProductStage") == "true")
        // Dedicated rootable synthetic AVD only. Read names beneath this fixed staging root;
        // never read file contents, databases, preferences, keys, or any other private directory.
        val root = "/data/user/0/$PRODUCT/cache/staging"
        val listing = ui.shell("su 0 find $root").lineSequence().filter { it.isNotBlank() }.toList()
        check(listing.firstOrNull() == root) { "The fixed private staging root must be positively readable" }
        val entries = listing.drop(1)
        check(entries.all { it.startsWith("$root/restore_stage_") }) { "Staging inspection failed or found an unexpected path" }
        return entries.size
    }

    @Test fun T47_publishedUpgradePreservesCompleteSyntheticGraph() {
        val rollback = checkNotNull(InstrumentationRegistry.getArguments().getString("rollbackSnapshot"))
        val evidenceDir = File(context.filesDir, "qa-evidence/$token").apply { mkdirs() }
        val barrier = File(evidenceDir, "HOST_UPGRADED_PRODUCT")
        check(!File(evidenceDir, "READY_FOR_HOST_UPGRADE.txt").exists() && !barrier.exists()) { "Fresh upgrade barrier required" }
        check(libraryCount() == 0) { "Upgrade requires the host-installed fresh disposable 1.0.0 library" }
        ui.click("Settings")
        ui.scrollTo("Not Paired")
        ui.waitText("Not Paired")
        library()
        // The old release has no default collection on a fresh install; create its legacy row through UI.
        ui.click("Collections")
        ui.click("Add Collection")
        ui.waitText("New Collection")
        ui.edit("Collection Name", "Random Links")
        ui.click("Create")
        ui.await("Legacy collection must be created") { ui.exact("New Collection") == null && ui.exact("Random Links") != null }
        val bodyMarker = "QAUPGRADE${System.currentTimeMillis()}BODY"
        val original = File(context.filesDir, "synthetic-shares/$token.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(tinyPdf("$token $bodyMarker"))
        }
        saveSyntheticPdf(original)
        awaitCount(1)
        ui.edit(SEARCH, token)
        awaitCount(1)
        ui.click(original.name)
        ui.waitText("Page 1 of 1", 30000)
        ui.click("Favorite")
        assertMembershipChecked("Random Links")
        library()
        val noteTitle = "$token upgrade note"
        val noteBody = "$token synthetic owner note preserved across the published upgrade."
        ui.click("Add")
        ui.waitText("Add to Library")
        ui.edit("Title (Optional)", noteTitle)
        ui.edit("Note Content / Text / Grades", noteBody)
        ui.scrollTo("Random Links")
        ui.click("Random Links")
        ui.scrollTo("Save Note")
        ui.click("Save Note")
        ui.await("Synthetic note must be saved") { ui.exact("Add to Library") == null }
        library()
        awaitCount(2)
        // Finish local extraction before the baseline: an asynchronous first index is not upgrade drift.
        awaitBodyOnlySearch(bodyMarker)
        ui.waitText(original.name)
        library()
        val backupName = "$token.plb"
        val before = exportAndInspectCurrentLibrary(backupName)
        check(before.totalItems == 2 && before.totalCollections == 1 && before.totalAssets == 1)
        val legacy = before.manifest.collections.single()
        check(legacy.name == "Random Links" && legacy.appRole == null)
        val pdf = before.manifest.items.single { it.originalFileName == original.name }
        check(pdf.isFavorite && pdf.contentHash == hash(original.readBytes()) && pdf.extractedText?.contains(bodyMarker) == true)
        check(before.manifest.items.all { it.aiStatus == "QUEUED" }) { "Paused old-release local indexing must finish before export" }
        check(before.manifest.items.single { it.title == noteTitle }.content == noteBody)
        check(before.manifest.memberships.size == 2 && before.manifest.memberships.all { it.collectionId == legacy.id })
        ui.checkpoint("T47-old-published-archive-authenticated")
        File(evidenceDir, "READY_FOR_HOST_UPGRADE.txt").writeText(
            "token=$token\nrollback=$rollback\nitems=${before.totalItems}\nactive=2\ncollections=${before.totalCollections}\nassets=${before.totalAssets}\noldApkSha256=$OLD_APK_SHA256\nnewApkSha256=$NEW_APK_SHA256\n"
        )
        Log.i("MemoraQA", "T47 authenticated old export ready for host install -r; token=$token rollback=$rollback")
        // The host alone installs the verified new APK with -r, never -d or a second uninstall.
        ui.await("Waiting for verified host upgrade of the synthetic product only", 180000) { barrier.isFile }
        assertPublishedApk("1.0.1", 3L, NEW_APK_SHA256)
        launchProduct()
        library()
        awaitCount(2)
        ui.click("Settings")
        ui.scrollTo("Pause Background AI")
        assertMembershipChecked("Pause Background AI")
        ui.scrollTo("Not Paired")
        ui.waitText("Not Paired")
        library()
        ui.edit(SEARCH, bodyMarker)
        awaitCount(1)
        ui.click(original.name)
        ui.waitText("Page 1 of 1", 30000)
        assertMembershipChecked("Random Links")
        verifyRestoredOriginal(original)
        ui.checkpoint("T47-upgraded-body-search-membership-original")
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
        awaitCount(2)
        val afterName = "$token-after.plb"
        val after = exportAndInspectCurrentLibrary(afterName)
        // Schema 4→5 adopts exactly this legacy ID. Every other portable field, including all
        // derived item fields, remains strict: this is an upgrade, not a fresh archive restore.
        check(after.manifest.collections.single { it.id == legacy.id }.appRole == "RANDOM_LINKS")
        check(after.manifest.collections.count { it.appRole == "RANDOM_LINKS" } == 1)
        val expected = before.manifest.copy(collections = before.manifest.collections.map {
            if (it.id == legacy.id) it.copy(appRole = "RANDOM_LINKS") else it
        })
        assertArchiveGraphEqual(expected, after.manifest)
        Log.i("MemoraQA", "T47 published 1.0.0/code2 to 1.0.1/code3: exact stable graph, one legacy role adoption and every asset hash/size verified after restart")
        ui.checkpoint("T47-published-upgrade-full-graph-verified")
        listOf(backupName, afterName).forEach { name ->
            ui.shell("rm -- /sdcard/Download/$name")
            assertPublicBackupAbsent(name)
        }
        check(context.contentResolver.delete(checkNotNull(publicSource), null, null) == 1)
        check(original.delete())
    }

    @Test fun T41_fullArchiveReconstructsIntoHostClearedSyntheticApp() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("hostClearedRestore") == "true") { "Explicit host-coordinated synthetic restore required" }
        val rollback = arguments.getString("rollbackSnapshot").orEmpty()
        check(rollback.matches(Regex("memora-official-before-t41-empty-restore-[0-9-]+"))) {
            "Host must first save and verify a unique rollback snapshot"
        }
        val evidenceDir = File(context.filesDir, "qa-evidence/$token")
        check(!File(evidenceDir, "READY_FOR_HOST_CLEAR.txt").exists() && !File(evidenceDir, "HOST_CLEARED_PRODUCT").exists()) {
            "A fresh run token with no previous host barrier is required"
        }
        val baseline = libraryCount()
        check(baseline in 211..500) { "Expected the known bounded synthetic library" }
        val bodyMarker = "QAREINDEX${System.currentTimeMillis()}BODY"
        val original = File(context.filesDir, "synthetic-shares/$token.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(tinyPdf("$token $bodyMarker"))
        }
        saveSyntheticPdf(original)
        library()
        awaitCount(baseline + 1)
        val backupName = "$token.plb"
        val before = exportAndInspectCurrentLibrary(backupName)
        check(before.totalItems >= baseline + 1 && before.totalCollections > 1 && before.totalAssets > 1)
        check(before.manifest.items.single { it.originalFileName == original.name }.contentHash == hash(original.readBytes()))
        ui.checkpoint("T41-full-archive-authenticated-before-host-clear")
        File(evidenceDir, "READY_FOR_HOST_CLEAR.txt").writeText(
            "rollback=$rollback\nitems=${before.totalItems}\nactive=${baseline + 1}\ncollections=${before.totalCollections}\nassets=${before.totalAssets}\n"
        )
        Log.i("MemoraQA", "T41 verified export ready for explicit host clear; token=$token rollback=$rollback")
        // The harness never resets data. The host verifies its rollback snapshot, clears only this
        // disposable product package, then creates this owned barrier file. The password stays in memory.
        ui.await("Waiting for verified host clear of the synthetic product only", 180000) {
            File(evidenceDir, "HOST_CLEARED_PRODUCT").isFile
        }
        launchProduct()
        library()
        awaitCount(0)
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
        ui.checkpoint("T41-empty-destination-before-restore")
        inspectBackup(backupName)
        check(previewNumber("Total Items") == before.totalItems)
        check(previewNumber("Existing Duplicates") == 0)
        check(previewNumber("New Items to Restore") == before.totalItems)
        ui.checkpoint("T41-all-archive-records-are-new")
        notices.clear()
        ui.click("Commit Restore")
        awaitNotice("Restored ${before.totalItems} items successfully", 120000)
        ui.await("Full restore preview must close", 30000) { ui.exact("Restore Archive Preview") == null }
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
        awaitCount(baseline + 1)
        // This marker exists only in the PDF body, so a filename match cannot prove reindexing.
        ui.edit(SEARCH, bodyMarker)
        awaitCount(1)
        ui.click(original.name)
        ui.waitText("Page 1 of 1", 30000)
        ui.checkpoint("T41-restored-body-only-local-search")
        assertMembershipChecked("Random Links")
        verifyRestoredOriginal(original)
        launchProduct()
        library()
        val afterName = "$token-after.plb"
        val after = exportAndInspectCurrentLibrary(afterName)
        assertArchiveGraphEqual(before.manifest, after.manifest, derivedIndexWasRebuilt = true)
        val restoredPdf = after.manifest.items.single { it.originalFileName == original.name }
        check(restoredPdf.extractedText?.contains(bodyMarker) == true) {
            "The restored PDF must have a freshly rebuilt local text index"
        }
        Log.i("MemoraQA", "T41 empty-destination reconstruction PASS: items=${after.totalItems} collections=${after.totalCollections} assets=${after.totalAssets}; exact graph and all asset hashes/sizes after process restart")
        ui.checkpoint("T41-empty-destination-full-graph-and-all-originals-verified")
        listOf(backupName, afterName).forEach { name ->
            ui.shell("rm -- /sdcard/Download/$name")
            assertPublicBackupAbsent(name)
        }
        check(context.contentResolver.delete(checkNotNull(publicSource), null, null) == 1)
        check(original.delete())
    }

    private fun exportAndInspectCurrentLibrary(name: String): RestorePreview {
        assertPublicBackupAbsent(name)
        settingsBackup()
        ui.click("Export Backup")
        ui.waitText("Export Encrypted Backup")
        setPassword(0, fixturePassphrase)
        // 1.0.0 infers export mode from "Choose Destination" and hides confirmation; 1.0.1 fixes that mode.
        val oldUpgradeExport = testMethod == UPGRADE_METHOD && context.packageManager.getPackageInfo(PRODUCT, 0).longVersionCode == 2L
        if (!oldUpgradeExport) setPassword(1, fixturePassphrase)
        ui.click("Choose Destination")
        awaitPicker()
        downloads()
        setFilename(name)
        notices.clear()
        pickerClick("Save", "SAVE")
        awaitNotice("Encrypted backup exported successfully (.plb)")
        return inspectPublicArchive(readOwnPublicBackup("/sdcard/Download/$name"))
    }

    private fun launchProduct() {
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.await("Memora library must be ready") { foreground() == PRODUCT && ui.exact("Library") != null }
    }

    private fun library() {
        repeat(7) {
            if (ui.exact("Edit Item") != null || ui.exact("Save from WhatsApp / Share") != null || ui.exact("Back to Settings") != null) {
                ui.back()
                SystemClock.sleep(200)
            } else if (ui.exact("Library") == null) {
                ui.back()
                SystemClock.sleep(200)
            }
        }
        ui.click("Library")
        // A populated search field shows its query instead of the hint; edit() also finds hintText.
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
        ui.await("Library count must settle") { countOrNull() != null }
    }

    private fun libraryCount(): Int { ui.await("Visible library count required") { countOrNull() != null }; return checkNotNull(countOrNull()) }
    private fun countOrNull(): Int? = ui.nodes().asSequence().filter { it.isVisibleToUser }.mapNotNull { node ->
        Regex("^(\\d+) items • Saved Date$").matchEntire(node.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
    }.firstOrNull()
    private fun awaitCount(expected: Int) = ui.await("Expected $expected saved synthetic items", 45000) { countOrNull() == expected }

    private fun awaitBodyOnlySearch(bodyMarker: String) {
        ui.edit(SEARCH, bodyMarker)
        var nextRefresh = 0L
        ui.await("Body-only local search must find exactly one indexed fixture", 120000) {
            if (countOrNull() == 1) true else {
                // QA003 also hides worker index updates from an unchanged query in published APKs.
                if (SystemClock.uptimeMillis() >= nextRefresh) {
                    ui.edit(SEARCH, "")
                    ui.edit(SEARCH, bodyMarker)
                    nextRefresh = SystemClock.uptimeMillis() + 1500
                }
                false
            }
        }
    }

    private fun settingsBackup() {
        if (ui.exact("Export Backup") == null) ui.click("Settings")
        ui.await("Settings scroll must be ready") { ui.exact("Export Backup") != null || ui.nodes().any { it.isVisibleToUser && it.isScrollable } }
        // Returning from Trash can retain a scroll position below the backup card.
        repeat(14) {
            if (ui.exact("Export Backup") != null) return
            if (!ui.scroll(forward = false)) return@repeat
            SystemClock.sleep(150)
        }
        ui.scrollTo("Export Backup")
    }

    private fun saveSyntheticPdf(file: File) {
        check(guarded && file.name == "$token.pdf" && token.matches(Regex("QA-BK-\\d+")))
        // QA006 blocks incoming Share with many collections; use the independently tested Add/SAF path.
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        publicSource = uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(file.readBytes()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        check(hash(checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }) == hash(file.readBytes()))
        library()
        ui.click("Add")
        ui.waitText("Add to Library")
        ui.scrollTo("Random Links", 24)
        ui.click("Random Links")
        for (attempt in 0 until 24) {
            if (ui.exact("Import Files") != null) break
            if (!ui.scroll(forward = false)) break
        }
        ui.click("Import Files")
        ui.click("Choose Files from Device")
        awaitPicker()
        downloads()
        ui.scrollTo(file.name, 30)
        val target = checkNotNull(ui.exact(file.name))
        val bounds = Rect().also(target::getBoundsInScreen)
        check(target.isVisibleToUser && !bounds.isEmpty && target.packageName?.toString() in PICKERS)
        ui.shell("input touchscreen swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 800")
        ui.waitText("1 selected")
        pickerClick("Select", "Open", "OPEN")
        ui.await("SAF fixture must return to Memora") { foreground() == PRODUCT && ui.exact("Add to Library") == null }
        ui.waitText("Saved 1 item(s) to your library.", 60000)
        Log.w("MemoraQA", "QA003/006 workaround: Add/SAF fixture then restart after confirmed capture; original defects remain")
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
    }

    private fun openMarker(name: String) {
        library()
        ui.edit(SEARCH, token)
        awaitCount(1)
        ui.click(name)
        ui.waitText("Edit Item")
    }

    private fun setPassword(index: Int, value: String) {
        fun fields() = ui.nodes().filter { it.isVisibleToUser && it.isEditable }
        ui.await("Expected backup passphrase field $index") { fields().size > index }
        check(fields()[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Backup field rejected input" }
        // PasswordVisualTransformation deliberately masks accessibility text; never require a plaintext echo.
        ui.await("Backup field must accept the generated input") { fields().getOrNull(index)?.text?.length == value.length }
    }

    private fun assertActionEnabled(label: String, enabled: Boolean) {
        ui.await("Unexpected enabled state for $label") {
            var node = ui.exact(label)
            repeat(7) {
                val candidate = node ?: return@repeat
                if (candidate.isClickable || candidate.className?.toString()?.contains("Button") == true) return@await candidate.isEnabled == enabled
                node = candidate.parent
            }
            false
        }
    }

    private fun awaitPicker() = ui.await("Android document picker must be foreground") { foreground() in PICKERS }
    private fun foreground() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
    private fun pickerClick(vararg labels: String) {
        check(foreground() in PICKERS) { "SAF action refused outside document picker" }
        ui.await("Picker action missing: ${labels.joinToString()}") { labels.any { ui.exact(it) != null } }
        ui.click(labels.first { ui.exact(it) != null })
    }

    private fun downloads() {
        awaitPicker()
        pickerClick("Show roots", "Show navigation roots")
        ui.await("Picker roots drawer must open") {
            ui.nodes().any { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
        fun root() = ui.nodes().filter {
            it.isVisibleToUser && it.viewIdResourceName == "android:id/title" &&
                it.text?.toString() in setOf("Downloads", "Download")
        }.singleOrNull()
        ui.await("Downloads root must be unique") { foreground() in PICKERS && root() != null }
        ui.clickNode(checkNotNull(root()))
        ui.await("Downloads drawer must close") {
            foreground() in PICKERS && ui.nodes().none { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
    }

    private fun setFilename(name: String) {
        ui.await("CreateDocument filename field required") { ui.nodes().count { it.isVisibleToUser && it.isEditable } == 1 }
        val field = ui.nodes().single { it.isVisibleToUser && it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        ui.await("CreateDocument must accept unique filename") { ui.nodes().any { it.isEditable && it.text?.toString() == name } }
    }

    private fun selectBackup(name: String) {
        settingsBackup()
        ui.click("Restore")
        awaitPicker()
        downloads()
        ui.scrollTo(name, 30)
        ui.click(name)
        ui.waitText("Decrypt & Inspect Backup")
    }

    private fun inspectBackup(name: String) {
        selectBackup(name)
        setPassword(0, fixturePassphrase)
        ui.click("Inspect Archive")
        ui.waitText("Restore Archive Preview", 120000)
    }

    private fun previewNumber(label: String): Int {
        val pattern = Regex("• ${Regex.escape(label)}: (\\d+).*")
        ui.await("Missing restore preview value: $label") { ui.nodes().any { pattern.matches(it.text?.toString().orEmpty()) } }
        return ui.nodes().firstNotNullOf { pattern.matchEntire(it.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt() }
    }

    private fun awaitNotice(expected: String, timeout: Long = 60000) {
        ui.await("Expected result notice: $expected", timeout) { synchronized(notices) { notices.any { it == expected } } || ui.exact(expected) != null }
    }

    private fun clickInMarkerRow(marker: String, action: String) {
        var ancestor = checkNotNull(ui.exact(marker))
        repeat(8) {
            val matches = descendants(ancestor).filter { it.isVisibleToUser && (it.text?.toString() == action || it.contentDescription?.toString() == action) }
            if (matches.size == 1) { ui.clickNode(matches.single()); return }
            ancestor = ancestor.parent ?: throw AssertionError("No scoped synthetic Trash row")
        }
        throw AssertionError("No unambiguous action in synthetic Trash row")
    }

    private fun assertMembershipChecked(label: String) {
        ui.scrollTo(label)
        ui.await("Saved collection membership must survive: $label") {
            val text = ui.exact(label) ?: return@await false
            val bounds = Rect().also(text::getBoundsInScreen)
            val aligned = ui.nodes().filter {
                val other = Rect().also(it::getBoundsInScreen)
                it.isVisibleToUser && it.isCheckable && (other.right <= bounds.left || other.left >= bounds.right) &&
                    other.top < bounds.bottom && other.bottom > bounds.top
            }
            if (aligned.size == 1) return@await aligned.single().isChecked
            var ancestor: AccessibilityNodeInfo? = text
            repeat(6) {
                val checkboxes = ancestor?.let(::descendants).orEmpty().filter { it.isVisibleToUser && it.isCheckable }
                if (checkboxes.size == 1) return@await checkboxes.single().isChecked
                ancestor = ancestor?.parent
            }
            false
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { result += descendants(it) }
        return result
    }

    private fun inspectPublicArchive(bytes: ByteArray): RestorePreview {
        val passphrase = fixturePassphrase.toCharArray()
        val preview = try {
            archiveStaging.inspect(bytes.inputStream(), passphrase, emptySet()).also { inspectedArchives += it }
        } finally { passphrase.fill('\u0000') }
        preview.manifest.assets.forEach { asset ->
            val staged = File(preview.stagingDir, "assets/${asset.hash}")
            check(staged.length() == asset.sizeBytes && hash(staged.readBytes()) == asset.hash) {
                "Authenticated public backup original failed exact size/hash verification"
            }
        }
        return preview
    }

    private fun assertArchiveGraphEqual(before: BackupManifest, after: BackupManifest, derivedIndexWasRebuilt: Boolean = false) {
        val beforeItems = before.items.associateBy { it.id }
        val afterItems = after.items.associateBy { it.id }
        val changedFields = mutableMapOf<String, Int>()
        val fields = BackupItemRecord::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        for (id in beforeItems.keys.intersect(afterItems.keys)) {
            if (beforeItems[id] == afterItems[id]) continue
            fields.forEach { field ->
                field.isAccessible = true
                if (field.get(beforeItems.getValue(id)) != field.get(afterItems.getValue(id))) {
                    changedFields[field.name] = changedFields.getOrDefault(field.name, 0) + 1
                }
            }
        }
        // Diagnostics expose counts and field names only, never record IDs, contents or passphrases.
        val diagnostic = "missing=${(beforeItems.keys - afterItems.keys).size} added=${(afterItems.keys - beforeItems.keys).size} changedFields=${changedFields.toSortedMap()}"
        File(context.filesDir, "qa-evidence/$token/T41-item-field-change-counts.txt").apply {
            parentFile!!.mkdirs()
            writeText(diagnostic)
        }
        Log.i("MemoraQA", "T41 item field comparison: $diagnostic")
        fun portableItem(item: BackupItemRecord) = if (derivedIndexWasRebuilt) {
            // BACKUP_FORMAT.md:46 requires rebuilding derived indexes instead of trusting archived AI state.
            item.copy(extractedText = null, aiDescription = null, aiConcepts = null, aiStatus = "PENDING")
        } else item
        check(beforeItems.mapValues { portableItem(it.value) } == afterItems.mapValues { portableItem(it.value) }) {
            "Backup portable item fields changed across restore: $diagnostic"
        }
        if (derivedIndexWasRebuilt) {
            check(after.items.all { it.aiDescription == null && it.aiConcepts == null && it.aiStatus !in setOf("READY", "PARTIAL", "PROCESSING") }) {
                "Fresh unpaired/paused restore must not trust archived ready AI analysis"
            }
        }
        check(before.collections.associateBy { it.id } == after.collections.associateBy { it.id }) { "Backup collection fields changed across restore" }
        check(before.memberships.toSet() == after.memberships.toSet()) { "Backup membership edges changed across restore" }
        check(before.reminders.associateBy { it.id } == after.reminders.associateBy { it.id }) { "Backup reminder fields changed across restore" }
        check(before.assets.associateBy { it.hash } == after.assets.associateBy { it.hash }) { "Backup original metadata changed across restore" }
        check(before.version == after.version && before.app == after.app) { "Backup format identity changed" }
        // Only exportTimestamp and, for fresh restore, the four documented derived fields may change.
    }

    private fun assertPublicBackupAbsent(name: String) {
        check(guarded && name in setOf("$token.plb", "$token-after.plb") && token.matches(Regex("QA-BK-\\d+")))
        // UiAutomation does not parse shell quoting or operators; these names contain only a fixed prefix and digits.
        val names = ui.shell("ls -1 /sdcard/Download").lineSequence().map { it.trim() }.toSet()
        check("$token.pdf" in names) { "Downloads must be readable and contain this run's published source" }
        check(name !in names) { "Named synthetic backup must not exist" }
    }

    private fun readOwnPublicBackup(path: String): ByteArray {
        check(guarded && path in setOf("/sdcard/Download/$token.plb", "/sdcard/Download/$token-after.plb") && token.matches(Regex("QA-BK-\\d+")))
        val size = ui.shell("stat -c %s $path").trim().toLongOrNull() ?: throw AssertionError("Saved public backup is missing")
        check(size in 100..32L * 1024 * 1024) { "Synthetic backup outside bounded byte budget" }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path"))
            .use { it.readNBytes(size.toInt() + 1) }
        check(bytes.size.toLong() == size) { "Public backup read was incomplete" }
        return bytes
    }

    private fun verifyRestoredOriginal(file: File) {
        val expected = hash(file.readBytes())
        check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().putString("sha256", expected).commit())
        repeat(12) {
            if (ui.exact("Open File") == null) {
                ui.scroll(forward = false)
                SystemClock.sleep(150)
            }
        }
        ui.scrollTo("Open File")
        ui.click("Open File")
        ui.await("Restored original hash must match the generated PDF", 20000) {
            when {
                ui.exact("QA original verified $expected") != null -> true
                ui.clickIfPresent("Memora QA Harness") -> false
                ui.clickIfPresent("Just once") -> false
                else -> false
            }
        }
    }

    private fun assertPublishedApk(version: String, code: Long, expectedHash: String) {
        val info = context.packageManager.getPackageInfo(PRODUCT, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.versionName == version && info.longVersionCode == code) { "Expected published $version/code$code" }
        check(hash(checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray()) == CERTIFICATE) { "Expected permanent release signer" }
        val application = checkNotNull(info.applicationInfo)
        check(application.splitSourceDirs.isNullOrEmpty()) { "Expected the published universal APK" }
        val path = application.sourceDir
        check(path.matches(Regex("/data/app/[A-Za-z0-9_./=+~-]+/base\\.apk"))) { "Unexpected installed APK path" }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path")).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                check(size <= 128L * 1024 * 1024) { "Installed APK exceeds published byte budget" }
                digest.update(buffer, 0, read)
            }
        }
        check(size > 0 && digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Installed APK differs from exact published $version artifact" }
        Log.i("MemoraQA", "T47 installed published version=$version code=$code bytes=$size sha256=$expectedHash certificate=$CERTIFICATE")
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tinyPdf(marker: String): ByteArray {
        val output = ByteArrayOutputStream()
        fun write(text: String) { output.write(text.toByteArray(Charsets.US_ASCII)) }
        val stream = "BT /F1 14 Tf 24 100 Td ($marker SYNTHETIC BACKUP ROUND TRIP) Tj ET\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 180] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length ${stream.length} >>\nstream\n${stream}endstream")
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> output.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = output.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(Locale.US, it)) }
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
        private const val CERTIFICATE = "98686d31bfe4370cc30024439b56d9e72de71c081951d31f5d4fdd31d4eb0598"
        private const val UPGRADE_METHOD = "T47_publishedUpgradePreservesCompleteSyntheticGraph"
        private const val OLD_APK_SHA256 = "99e54e5882601b53d5e35f59d356cbaaf1a947608496b54ae581180b97818906"
        private const val NEW_APK_SHA256 = "b53d05b83e45dfb667effab1d1117aac9025701723e4f13b843d044d1fe2143d"
        private val ROLLBACK_SNAPSHOT = Regex("memora-official-before-t41-empty-restore-[0-9-]+")
        private val PICKERS = setOf("com.google.android.documentsui", "com.android.documentsui")
    }
}
