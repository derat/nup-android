/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log

/** Monitors network connectivity. */
open class NetworkHelper(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var available = false // last-seen network state

    interface Listener {
        /** Called when the active network becomes available or unavailable. */
        fun onNetworkAvailabilityChange(available: Boolean)
    }

    private var listeners = mutableSetOf<Listener>()

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /** Is a network connection currently available? */
    val isNetworkAvailable get(): Boolean {
        // For some reason, I'm still seeing an active cellular network when I enable airplane mode.
        if (Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0
        ) {
            return false
        }

        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (caps == null) return false
        return requiredCaps.all { caps.hasCapability(it) }
    }

    private fun updateAvailable() {
        val updated = isNetworkAvailable
        if (available == updated) return
        available = updated
        Log.d(TAG, "Network became ${if (available) "" else "un"}available")
        for (l in listeners) l.onNetworkAvailabilityChange(available)
    }

    companion object {
        private const val TAG = "NetworkHelper"
    }

    private val requiredCaps = listOf(
        NetworkCapabilities.NET_CAPABILITY_FOREGROUND,
        NetworkCapabilities.NET_CAPABILITY_INTERNET,
        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
    )

    init {
        val builder = NetworkRequest.Builder()
        for (cap in requiredCaps) builder.addCapability(cap)

        connectivityManager.registerNetworkCallback(
            builder.build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onLost(network)
                    updateAvailable()
                }
                override fun onLost(network: Network) {
                    super.onLost(network)
                    updateAvailable()
                }
            }
        )

        available = isNetworkAvailable
        Log.d(TAG, "Network initially ${if (available) "" else "un"}available")
    }
}
