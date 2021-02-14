// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup.test

import org.erat.nup.TaskRunner

/** Fake implemenation of TaskRunner that runs tasks immediately.  */
class FakeTaskRunner : TaskRunner() {
    override fun runInBackground(runnable: Runnable) {
        // Use a new thread to avoid triggering running-on-non-UI-thread asserts.
        val thread = Thread(runnable)
        thread.start()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        }
    }
}