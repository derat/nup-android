// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.os.Handler
import android.os.Looper

internal class DatabaseUpdater(private val opener: DatabaseOpener) : Runnable {
    private var handler: Handler? = null
    private var shouldQuit = false
    override fun run() {
        Looper.prepare()
        synchronized(this) {
            if (shouldQuit) return
            handler = Handler()
        }
        Looper.loop()
    }

    fun quit() {
        synchronized(this) {
            // The thread hasn't started looping yet; tell it to exit before starting.
            if (handler == null) {
                shouldQuit = true
                return
            }
        }
        handler!!.post(
                Runnable { Looper.myLooper()!!.quit() })
    }

    fun postUpdate(sql: String?, values: Array<Any>?) {
        handler!!.post { opener.getDb()!!.execSQL(sql, values) }
    }
}