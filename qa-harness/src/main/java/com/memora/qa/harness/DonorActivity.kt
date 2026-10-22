package com.memora.qa.harness

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.security.MessageDigest

/** A real share donor and synthetic external viewer, independent of Memora's signing key. */
class DonorActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); show(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); show(intent) }

    private fun show(intent: Intent) {
        pendingShare?.let { share ->
            setContentView(Button(this).apply {
                text = "Send synthetic fixture"
                setOnClickListener { pendingShare = null; startActivity(share) }
            })
            return
        }
        val uri = intent.data
        val expected = getSharedPreferences("qa_expected", MODE_PRIVATE).getString("sha256", null)
        val message = if (intent.action == Intent.ACTION_VIEW && uri?.scheme == "content" &&
            uri.authority == "com.personallibrary.app.fileprovider" && expected != null) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                contentResolver.openInputStream(uri)!!.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= 2 * 1024 * 1024) { "Synthetic fixture budget exceeded" }
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual == expected) "QA original verified $actual" else "QA original HASH MISMATCH"
            } catch (_: Exception) { "QA original OPEN FAILED" }
        } else "Memora QA synthetic share donor"
        setContentView(TextView(this).apply { text = message; textSize = 20f; setPadding(24, 48, 24, 24) })
    }

    companion object { var pendingShare: Intent? = null }
}
