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
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Monitors network connectivity. */
open class NetworkHelper(private val context: Context, private val scope: CoroutineScope) {
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
    val isNetworkAvailable get() =
        if (Build.VERSION.SDK_INT >= 26) connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        ).let { caps -> requiredCaps.all { caps?.hasCapability(it) ?: false } }
        else connectivityManager.activeNetworkInfo?.isConnected() ?: false

    /** Schedule a task to check the state and notify listeners about changes. */
    private fun scheduleUpdate() =
        scope.launch(Dispatchers.Main) task@{
            // Android doesn't seem to provide an easy way to figure out when general network
            // connectivity (in the sense of "can I talk to a random server on the Internet?")
            // comes or goes.
            //
            // ConnectivityManager.NetworkCallback appears to focus on monitoring individual network
            // connections, and the documentation for its methods are full of (accurate) warnings
            // like "Do NOT call ConnectivityManager.getNetworkCapabilities(android.net.Network) or
            // ConnectivityManager.getLinkProperties(android.net.Network) or other synchronous
            // ConnectivityManager methods in this callback as this is prone to race conditions ;
            // calling these methods while in a callback may return an outdated or even a null
            // object."
            //
            // isNetworkAvailable's approach of just checking the active network's capabilities
            // seems to mostly work, so just post delayed tasks from the callback in the hope that
            // things will have settled down by the time that we look at the state.
            delay(UPDATE_DELAY_MS)
            val updated = isNetworkAvailable
            if (available == updated) return@task
            available = updated
            Log.d(TAG, "Network became ${if (available) "" else "un"}available")
            for (l in listeners) l.onNetworkAvailabilityChange(available)
        }

    companion object {
        private const val TAG = "NetworkHelper"
        private const val UPDATE_DELAY_MS = 500L
    }

    // Sigh, what a mess.
    private val requiredCaps = mapOf(
        NetworkCapabilities.NET_CAPABILITY_FOREGROUND to 28,
        NetworkCapabilities.NET_CAPABILITY_INTERNET to 21,
        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED to 21,
        NetworkCapabilities.NET_CAPABILITY_VALIDATED to 23,
    ).filter { Build.VERSION.SDK_INT >= it.value }.keys

    init {
        val builder = NetworkRequest.Builder()
        for (cap in requiredCaps) builder.addCapability(cap)

        connectivityManager.registerNetworkCallback(
            builder.build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, caps)
                    scheduleUpdate()
                }
                override fun onLost(network: Network) {
                    super.onLost(network)
                    scheduleUpdate()
                }
            }
        )

        available = isNetworkAvailable
        Log.d(TAG, "Network initially ${if (available) "" else "un"}available")
    }
}
