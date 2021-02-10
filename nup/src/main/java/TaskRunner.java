// Copyright 2016 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.os.AsyncTask;

/**
 * Wraps simple invocations of AsyncTask so that code that uses it can be called from unit tests
 * (where AsyncTask is unimplemented).
 */
public class TaskRunner {
  public void runInBackground(final Runnable runnable) {
    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... args) {
        runnable.run();
        return null;
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }
}
