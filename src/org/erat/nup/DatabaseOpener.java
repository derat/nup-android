// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

// This is a simpler wrapper around a SQLiteOpenHelper that returns a writable[1] database, repeatedly trying to open it
// on error[2].
//
// 1. Using SQLiteOpenHelper.getReadableDatabase() seems like a disaster for a multithreaded program; the database
//    object is invalidated if getWritableDatabase() is called, per the docs.
// 2. I'm seeing occasional "database locked" errors when trying open a database at startup.  I have no idea why; it
//    hasn't already been opened somewhere else, as far as I can see.
class DatabaseOpener {
    private static final String TAG = "DatabaseOpener";

    private final SQLiteOpenHelper mOpenHelper;

    public DatabaseOpener(SQLiteOpenHelper helper) {
        mOpenHelper = helper;
    }

    // Returns a writable handle.  Do not call close on it(); it will be shared with subsequent callers.
    public synchronized SQLiteDatabase getDb() {
        while (true) {
            try {
                return mOpenHelper.getWritableDatabase();
            } catch (SQLiteException e) {
                Log.e(TAG, "got exception while trying to open database: " + e);
            }
        }
    }
}
