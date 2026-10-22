package com.memora.qa.harness

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest

/** Candidate mode is opt-in for offline flow suites; live AI keeps its own strict official guard. */
internal fun requireOfficialOrPinnedCandidate(context: Context, flow: String) {
    val info = context.packageManager.getPackageInfo("com.personallibrary.app", PackageManager.GET_SIGNING_CERTIFICATES)
    check(info.versionName == "1.0.1" && info.longVersionCode == 3L) { "Expected Memora1.0.1/code3" }
    val candidate = InstrumentationRegistry.getArguments().getString("candidateApkSha256")
    val digest = MessageDigest.getInstance("SHA-256")
    if (candidate == null) {
        val signer = checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray()
        check(digest.digest(signer).joinToString("") { "%02x".format(it) } ==
            "98686d31bfe4370cc30024439b56d9e72de71c081951d31f5d4fdd31d4eb0598") { "Expected permanent release signer" }
        Log.i("MemoraQA", "$flow: permanent-signer official APK")
    } else {
        check(candidate.matches(Regex("[0-9a-fA-F]{64}"))) { "Candidate requires an exact APK SHA256" }
        File(checkNotNull(info.applicationInfo).sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(candidate, ignoreCase = true)) { "Installed candidate APK differs from requested artifact" }
        Log.i("MemoraQA", "$flow: hash-pinned local candidate; not official release evidence ($actual)")
    }
}
