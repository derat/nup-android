// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup.test;

import org.erat.nup.TaskRunner;

/** Fake implemenation of TaskRunner that runs tasks immediately. */
public class FakeTaskRunner extends TaskRunner {
    public void runInBackground(final Runnable runnable) {
        // Use a new thread to avoid triggering running-on-non-UI-thread asserts.
        Thread thread = new Thread(runnable);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
