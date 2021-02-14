// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.content.Context
import android.net.ConnectivityManager

// TODO: Make this non-open if possible after switching to Robolectric.
open class NetworkHelper(context: Context) {
    private val connectivityManager: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Is a network connection currently available?
    val isNetworkAvailable: Boolean
        get() {
            val info = connectivityManager.activeNetworkInfo
            return info != null && info.isAvailable
        }

}
