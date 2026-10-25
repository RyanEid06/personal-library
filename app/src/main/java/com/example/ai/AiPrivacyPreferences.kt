package com.example.ai

import android.content.SharedPreferences

/** Shared mutation boundary for persisted item and collection AI exclusions. */
object AiPrivacyPreferences {
    // Keep both exclusion sets behind one process lock through the durable write.
    @Synchronized
    fun mutate(prefs: SharedPreferences, key: String, transform: (MutableSet<String>) -> Unit): Boolean {
        val values = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        transform(values)
        return prefs.edit().putStringSet(key, values).commit()
    }
}
