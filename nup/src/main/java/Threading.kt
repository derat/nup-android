/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.os.Looper
import java.util.concurrent.ExecutorService

/** Crash if not running on the given Looper. */
fun assertOnLooper(looper: Looper) {
    check(looper.thread === Thread.currentThread()) {
        "Running on ${Thread.currentThread()} instead of ${looper.thread}"
    }
}

/** Crash if called from a thread besides the main/UI thread. */
fun assertOnMainThread() {
    assertOnLooper(Looper.getMainLooper())
}

/** Crash if called from the main thread. */
fun assertNotOnMainThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "Running on main thread; shouldn't be"
    }
}

/**
 * Assert that code is being run by a single-threaded executor.
 *
 * @param executor created using [Executors.newSingleThreadScheduledExecutor]
 */
class ThreadChecker(executor: ExecutorService) {
    private val thread: Thread // [executor]'s thread

    /** Crash if not called from [executor]'s thread. */
    fun assertThread() {
        val cur = Thread.currentThread()
        check(cur == thread) { "Running on $cur instead of $thread" }
    }

    init {
        // TODO: Find some way to support larger pool sizes.
        // Right now, [assertThread] will crash if the executor has multiple threads.
        thread = executor.submit<Thread> { Thread.currentThread() }.get()
    }
}
