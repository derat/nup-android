// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.os.Handler;
import android.os.Looper;

class DatabaseUpdater implements Runnable {
    private final DatabaseOpener opener;
    private Handler handler = null;
    private boolean shouldQuit = false;

    DatabaseUpdater(DatabaseOpener opener) {
        this.opener = opener;
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (this) {
            if (shouldQuit) return;
            handler = new Handler();
        }
        Looper.loop();
    }

    public void quit() {
        synchronized (this) {
            // The thread hasn't started looping yet; tell it to exit before starting.
            if (handler == null) {
                shouldQuit = true;
                return;
            }
        }
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Looper.myLooper().quit();
                    }
                });
    }

    public void postUpdate(final String sql, final Object[] values) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        opener.getDb().execSQL(sql, values);
                    }
                });
    }
}
