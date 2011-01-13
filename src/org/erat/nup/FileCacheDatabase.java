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
                    "  LastAccessTime INTEGER)");
                db.execSQL("CREATE INDEX RemotePath on CacheEntries (RemotePath)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                throw new RuntimeException(
                    "Got request to upgrade database from " + oldVersion + " to " + newVersion);
            }
        };
    }

    public synchronized FileCacheEntry getEntryForRemotePath(String remotePath) {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT " +
            "  CacheEntryId, " +
            "  LocalFilename, " +
            "  IFNULL(ContentLength, 0), " +
            "  IFNULL(ETag, '') " +
            "FROM CacheEntries " +
            "WHERE RemotePath = ?",
            new String[]{remotePath});
        if (cursor.getCount() == 0)
            return null;
        cursor.moveToFirst();
        return new FileCacheEntry(cursor.getInt(0), remotePath, cursor.getString(1), cursor.getLong(2), cursor.getString(3));
    }

    public synchronized int addEntry(String remotePath, String localFilename) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.execSQL(
            "INSERT INTO CacheEntries (RemotePath, LocalFilename) VALUES(?, ?)",
            new Object[]{remotePath, localFilename});
        Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null);
        cursor.moveToFirst();
        return cursor.getInt(0);
    }

    public synchronized void setContentLength(int id, long contentLength) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.execSQL(
            "Update CacheEntries SET ContentLength = ? WHERE CacheEntryId = ?",
            new Object[]{contentLength, id});
    }

    public synchronized void clear() {
        mOpener.getWritableDatabase().execSQL("DELETE FROM CacheEntries");
    }
}
