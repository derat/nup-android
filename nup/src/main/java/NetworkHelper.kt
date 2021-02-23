/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.net.ConnectivityManager

/** Stupid class that only exists because it's hard to mock top-level functions in Mockito. */
open class NetworkHelper(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Is a network connection currently available? */
    val isNetworkAvailable get() = connectivityManager.activeNetwork != null
}
