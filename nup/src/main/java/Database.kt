/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// This is a simpler class that uses a passed-in SQLiteOpenHelper to create and/or upgrade a
// database but then manages its own separate writable handle instead of using the helper's.
// I'm seeing strict mode violations about leaked database cursors that make me suspect that
// SQLiteOpenHelper is closing its writable handle in the background at times (the docs don't
// mention this).
// TODO: No idea if this is still necessary.
class DatabaseOpener(
    private val context: Context,
    private val name: String,
    private val openHelper: SQLiteOpenHelper
) {
    private var db: SQLiteDatabase? = null

    /** True after close() has been called. */
    var closed = false
        @Synchronized get
        @Synchronized private set

    /**
     * Open and return a database.
     *
     * The caller shouldn't call close() on the object, since it's shared across multiple calls.
     */
    @Synchronized fun getDb(): SQLiteDatabase {
        if (closed) throw RuntimeException("Database already closed")

        if (db == null) {
            // Just use SQLiteOpenHelper to create and upgrade the database.
            // Trying to use it with multithreaded code seems to be a disaster.
            // We repeatedly try to open the database, since I see weird "database locked"
            // errors when trying to open a database just after startup sometimes -- as
            // far as I know, nobody should be using the database at that point.
            while (true) {
                try {
                    openHelper.writableDatabase.close()
                    break
                } catch (e: SQLiteDatabaseLockedException) {
                    Log.e(TAG, "Database $name locked while trying to create/upgrade it: $e")
                }
            }

            while (true) {
                try {
                    db = context.openOrCreateDatabase(name, 0, null)
                    break
                } catch (e: SQLiteDatabaseLockedException) {
                    Log.e(TAG, "Database $name locked while trying to open it: $e")
                }
            }
        }
        return db!!
    }

    /** Close the database, invalidating previously-returned objects. */
    @Synchronized fun close() {
        db?.close()
        db = null
        closed = true
    }

    companion object {
        private const val TAG = "DatabaseOpener"
    }
}

/** Runs queries asynchronously (but in-order) against a database. */
class DatabaseUpdater(private val opener: DatabaseOpener) {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    /** Shut down the updater. */
    fun quit() {
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    /** Run [sql] asynchronously. */
    fun postUpdate(sql: String, values: Array<Any>?) {
        executor.execute { opener.getDb().execSQL(sql, values) }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SEC = 60L
    }
}
