// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

class DatabaseUpdater implements Runnable {
    private final SQLiteDatabase mDb;
    private Handler mHandler = null;
    private boolean mShouldQuit = false;

    DatabaseUpdater(SQLiteDatabase db) {
        mDb = db;
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (this) {
            if (mShouldQuit)
                return;
            mHandler = new Handler();
        }
        Looper.loop();
    }

    public void quit() {
        synchronized (this) {
            // The thread hasn't started looping yet; tell it to exit before starting.
            if (mHandler == null) {
                mShouldQuit = true;
                return;
            }
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
    }

    public void postUpdate(final String sql, final Object[] values) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mDb.execSQL(sql, values);
            }
        });
    }
}
