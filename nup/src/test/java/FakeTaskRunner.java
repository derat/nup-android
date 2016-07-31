// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup.test;

import org.erat.nup.TaskRunner;

/** Fake implemenation of TaskRunner that runs tasks immediately. */
public class FakeTaskRunner extends TaskRunner {
    public void runInBackground(final Runnable runnable) {
        runnable.run();
    }
}
