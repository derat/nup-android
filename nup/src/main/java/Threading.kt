/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.os.Looper

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
