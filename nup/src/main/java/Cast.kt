/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.os.StrictMode
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider

/** Supplies [CastOptions] for initializing the [CastContext] singleton. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context) =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context) = null
}

/** Get the shared [CastContext]. */
fun getCastContext(context: Context): CastContext {
    // Of course initializing Cast hits the disk.
    // TODO: Maybe make this be a suspend function that uses the IO thread.
    val origPolicy = StrictMode.allowThreadDiskReads()
    try {
        return CastContext.getSharedInstance(context)
    } finally {
        StrictMode.setThreadPolicy(origPolicy)
    }
}
