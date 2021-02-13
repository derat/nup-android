// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

// This is a simpler class that uses a passed-in SQLiteOpenHelper to create and/or upgrade a
// database
// but then manages its own separate writable handle instead of using the helper's -- I'm seeing
// strict
// mode violations about leaked database cursors that make me suspect that SQLiteOpenHelper is
// closing
// its writable handle in the background at times (the docs don't mention this).
class DatabaseOpener {
    private static final String TAG = "DatabaseOpener";

    private final Context context;
    private final String databaseName;
    private final SQLiteOpenHelper openHelper;

    private SQLiteDatabase db = null;

    public DatabaseOpener(Context context, String databaseName, SQLiteOpenHelper helper) {
        this.context = context;
        this.databaseName = databaseName;
        this.openHelper = helper;
    }

    // Opens and returns a database.  The caller shouldn't call close() on the object, since it's
    // shared across multiple calls.
    public synchronized SQLiteDatabase getDb() {
        if (db == null) {
            // Just use SQLiteOpenHelper to create and upgrade the database.
            // Trying to use it with multithreaded code seems to be a disaster.
            // We repeatedly try to open the database, since I see weird "database locked"
            // errors when trying to open a database just after startup sometimes -- as
            // far as I know, nobody should be using the database at that point.
            while (true) {
                try {
                    SQLiteDatabase tmpDb = openHelper.getWritableDatabase();
                    tmpDb.close();
                    break;
                } catch (SQLiteDatabaseLockedException e) {
                    Log.e(
                            TAG,
                            "database "
                                    + databaseName
                                    + " was locked while trying to create/upgrade it: "
                                    + e);
                }
            }

            while (true) {
                try {
                    db = context.openOrCreateDatabase(databaseName, 0, null);
                    break;
                } catch (SQLiteDatabaseLockedException e) {
                    Log.e(
                            TAG,
                            "database "
                                    + databaseName
                                    + " was locked while trying to open it: "
                                    + e);
                }
            }
        }

        return db;
    }

    // Closes the internal database.  Any previously-returned copies of it cannot be used
    // afterwards.
    public synchronized void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
