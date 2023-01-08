/**
 * Copyright 2022 Daniel Erat.
 * All rights reserved.
 */

package org.erat.nup

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log
import java.io.File

class NupApplication : Application() {
    override fun onCreate() {
        Log.d(TAG, "App created")
        super.onCreate()

        CrashLogger.register(File(getExternalFilesDir(null), CRASH_DIR_NAME))

        // The documentation about where strict mode should be enabled isn't great, but setting it
        // here seems to catch both thread and VM policy violations in both NupActivity's and
        // NupService's onCreate methods.
        val threadBuilder =
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
        if (BuildConfig.DEBUG) {
            threadBuilder.penaltyDeathOnNetwork()
            // I've seen weird DiskReadViolation errors when NupActivity.launchBrowser() calls
            // startActivity() on a Samsung S21, so avoid showing the dialog there.
            if (!listOf(Build.BRAND, Build.MANUFACTURER).any { it.equals("samsung", true) }) {
                threadBuilder.penaltyDialog()
            }
        }
        StrictMode.setThreadPolicy(threadBuilder.build())

        // TODO: It'd be nice to set penaltyDeath() here for debug builds, but there is (was?) a
        // frequent LeakedClosableViolation from android.view.SurfaceControl:
        // https://github.com/derat/nup-android/issues/11
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                // TODO: I'm seeing an InstanceCountViolation:
                // "class org.erat.nup.NupActivity; instances=3; limit=2"
                // Maybe it's a bug in the framework?
                .setClassInstanceLimit(NupActivity::class.java, 3)
                .penaltyLog()
                .build()
        )
    }

    companion object {
        private const val TAG = "NupApplication"
        private const val CRASH_DIR_NAME = "crashes" // files subdir where crashes are written
    }
}
