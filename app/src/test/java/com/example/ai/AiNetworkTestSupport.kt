package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo

/** Explicit synthetic connectivity for HTTP fixtures; production continues to fail closed. */
@Suppress("DEPRECATION")
fun setAiTestNetwork(context: Context, unmetered: Boolean, connected: Boolean = true) {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadow = shadowOf(manager)
    if (!connected) {
        shadow.setActiveNetworkInfo(null)
        return
    }
    shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
    val capabilities = NetworkCapabilities()
    shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    if (unmetered) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    else shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    shadow.setNetworkCapabilities(requireNotNull(manager.activeNetwork), capabilities)
}
