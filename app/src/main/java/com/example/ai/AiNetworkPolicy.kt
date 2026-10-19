package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The same unmetered policy used by queued WorkManager AI requests. */
class AiNetworkPolicy(private val context: Context) {
    fun isAllowed(): Boolean {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ai_wifi_only", true)) return true
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: SecurityException) { false }
    }

    fun requireAllowed() {
        if (!isAllowed()) throw AiGatewayException.NetworkPolicyException()
    }
}
