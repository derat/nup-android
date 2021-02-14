// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashLogger private constructor(private val dir: File) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            dir.mkdirs()
            val format = SimpleDateFormat("yyyyMMdd-HHmmss")
            val file = File(dir, format.format(Date()) + ".txt")
            Log.d(TAG, "creating crash file " + file.absolutePath)
            file.createNewFile()
            val writer = PrintWriter(file)
            error.printStackTrace(writer)
            val cause = error.cause
            if (cause != null) {
                writer.print("\n\nCaused by:\n")
                cause.printStackTrace(writer)
            }
            writer.close()
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "file not found: $e")
        } catch (e: IOException) {
            Log.e(TAG, "IO error: $e")
        }
        defaultHandler.uncaughtException(thread, error)
    }

    companion object {
        private const val TAG = "CrashLogger"
        private var singleton: CrashLogger? = null
        @JvmStatic
        fun register(dir: File) {
            if (singleton != null) return
            singleton = CrashLogger(dir)
            Thread.setDefaultUncaughtExceptionHandler(singleton)
        }

        @JvmStatic
        fun unregister() {
            if (singleton == null) return
            Thread.setDefaultUncaughtExceptionHandler(null)
            singleton = null
        }
    }

}