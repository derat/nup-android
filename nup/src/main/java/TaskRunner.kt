// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.os.AsyncTask

/**
 * Wraps simple invocations of AsyncTask so that code that uses it can be called from unit tests
 * (where AsyncTask is unimplemented).
 */
open class TaskRunner {
    open fun runInBackground(runnable: Runnable) {
        object : AsyncTask<Void?, Void?, Void?>() {
            override fun doInBackground(vararg args: Void?): Void? {
                runnable.run()
                return null
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }
}
