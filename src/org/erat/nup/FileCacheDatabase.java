// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

class FileCacheDatabase {
    private static final String TAG = "FileCacheDatabase";
    private static final String DATABASE_NAME = "NupFileCache";
    private static final int DATABASE_VERSION = 1;

    private final SQLiteOpenHelper mOpener;

    public FileCacheDatabase(Context context) {
        mOpener = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(
                    "CREATE TABLE CacheEntries (" +
                    "  CacheEntryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "  RemotePath VARCHAR(2048) UNIQUE NOT NULL, " +
                    "  LocalFilename VARCHAR(2048) UNIQUE NOT NULL, " +
                    "  ContentLength INTEGER, " +
                    "  ETag VARCHAR(40), " +
                    "  LastAccessTime INTEGER");
                db.execSQL("CREATE INDEX RemotePath on CacheEntries (RemotePath)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                throw new RuntimeException(
                    "Got request to upgrade database from " + oldVersion + " to " + newVersion);
            }
        };
    }

    public FileCacheEntry getEntryForRemotePath(String remotePath) {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT LocalFilename, ContentLength, ETag FROM CacheEntries WHERE RemotePath = ?",
            new String[]{remotePath});
        if (cursor.getCount() == 0)
            return null;
        return new FileCacheEntry(remotePath, cursor.getString(0), cursor.getLong(1), cursor.getString(2));
    }
}
