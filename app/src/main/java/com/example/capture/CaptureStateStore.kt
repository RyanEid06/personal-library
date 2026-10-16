package com.example.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureState(val saving: Boolean = false, val batchId: String? = null, val total: Int = 0,
    val saved: Int = 0, val failed: Int = 0, val message: String? = null)

/** Counts survive process death; transient source URIs are deliberately never replayed. */
class CaptureStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("capture_state", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CaptureState(
        total = prefs.getInt("total", 0), saved = prefs.getInt("saved", 0), failed = prefs.getInt("failed", 0),
        message = if (prefs.getBoolean("saving", false)) "Saving was interrupted. Items already committed are kept; share unfinished files again." else prefs.getString("message", null)
    ))
    val state = mutable.asStateFlow()
    init { persist(mutable.value) }

    @Synchronized fun begin(id: String, total: Int): Boolean {
        if (mutable.value.saving) return false
        val next = CaptureState(saving = true, batchId = id, total = total)
        if (!persist(next)) {
            mutable.value = CaptureState(message = "Cannot record save progress. Free phone storage and try again.")
            return false
        }
        mutable.value = next
        return true
    }
    @Synchronized fun progress(saved: Int, failed: Int, batchId: String? = null) {
        if (batchId == null || mutable.value.batchId == batchId) update(mutable.value.copy(saved = saved, failed = failed))
    }
    @Synchronized fun finish(message: String, batchId: String? = null) {
        if (batchId == null || mutable.value.batchId == batchId) update(mutable.value.copy(saving = false, batchId = null, message = message))
    }
    @Synchronized fun dismiss() { if (!mutable.value.saving) update(CaptureState()) }
    private fun update(value: CaptureState) { persist(value); mutable.value = value }
    private fun persist(value: CaptureState): Boolean = prefs.edit().putBoolean("saving", value.saving).putInt("total", value.total).putInt("saved", value.saved)
            .putInt("failed", value.failed).putString("message", value.message).commit()
}
