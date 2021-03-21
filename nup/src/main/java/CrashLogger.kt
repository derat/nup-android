/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.os.StrictMode
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/** Writes uncaught exceptions to text files and rethrows them. */
class CrashLogger private constructor(private val dir: File) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss")

    override fun uncaughtException(thread: Thread, error: Throwable) {
        val origPolicy = StrictMode.allowThreadDiskWrites()
        try {
            dir.mkdirs()
            val file = File(dir, dateFormat.format(Date()) + ".txt")
            Log.d(TAG, "Creating crash file ${file.absolutePath}")
            file.createNewFile()

            file.printWriter().use { out ->
                error.printStackTrace(out)
                if (error.cause != null) {
                    out.print("\n\nCaused by:\n")
                    error.cause?.printStackTrace(out)
                }
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: $e")
        } catch (e: IOException) {
            Log.e(TAG, "IO error: $e")
        } finally {
            StrictMode.setThreadPolicy(origPolicy)
        }

        defaultHandler?.uncaughtException(thread, error)
    }

    companion object {
        private const val TAG = "CrashLogger"
        private var singleton: CrashLogger? = null

        /** Register the crash reporter. */
        fun register(dir: File) {
            if (singleton != null) return
            singleton = CrashLogger(dir)
            Thread.setDefaultUncaughtExceptionHandler(singleton)
        }

        /** Unregister the crash reporter. */
        fun unregister() {
            if (singleton == null) return
            Thread.setDefaultUncaughtExceptionHandler(null)
            singleton = null
        }
    }
}
