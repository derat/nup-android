// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class FileCacheDatabase {
    private static final String TAG = "FileCacheDatabase";
    private static final String DATABASE_NAME = "NupFileCache";
    private static final int DATABASE_VERSION = 2;

    private final SQLiteOpenHelper mOpener;

    public FileCacheDatabase(Context context) {
        mOpener = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(
                    "CREATE TABLE CacheEntries (" +
                    "  CacheEntryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "  RemotePath VARCHAR(2048) UNIQUE NOT NULL, " +
                    "  LocalPath VARCHAR(2048) UNIQUE NOT NULL, " +
                    "  ContentLength INTEGER, " +
                    "  ETag VARCHAR(40), " +
                    "  LastAccessTime INTEGER)");
                db.execSQL("CREATE INDEX RemotePath on CacheEntries (RemotePath)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion == 1 && newVersion == 2) {
                    // Rename "LocalFilename" column to "LocalPath".
                    db.execSQL("DROP INDEX IF EXISTS RemotePath");
                    db.execSQL("ALTER TABLE CacheEntries RENAME TO CacheEntriesTmp");
                    onCreate(db);
                    db.execSQL("INSERT INTO CacheEntries SELECT * FROM CacheEntriesTmp");
                    db.execSQL("DROP TABLE CacheEntriesTmp");
                } else {
                    throw new RuntimeException(
                        "Got request to upgrade database from " + oldVersion + " to " + newVersion);
                }
            }
        };

        // Make sure that we have a writable database when we try to do the upgrade.
        mOpener.getWritableDatabase();
    }

    public synchronized FileCacheEntry getEntryById(int id) {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT " +
            "  RemotePath, " +
            "  LocalPath, " +
            "  IFNULL(ContentLength, 0), " +
            "  IFNULL(ETag, '') " +
            "FROM CacheEntries " +
            "WHERE CacheEntryId = ?",
            new String[]{Integer.toString(id)});
        cursor.moveToFirst();
        FileCacheEntry entry =
            !cursor.isAfterLast() ?
            new FileCacheEntry(id, cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3)) :
            null;
        cursor.close();
        return entry;
    }

    public synchronized FileCacheEntry getEntryForRemotePath(String remotePath) {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT CacheEntryId FROM CacheEntries WHERE RemotePath = ?",
            new String[]{remotePath});
        cursor.moveToFirst();
        FileCacheEntry entry = cursor.isAfterLast() ? null : getEntryById(cursor.getInt(0));
        cursor.close();
        return entry;
    }

    public synchronized int addEntry(String remotePath, String localPath) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.execSQL(
            "INSERT INTO CacheEntries (RemotePath, LocalPath, LastAccessTime) VALUES(?, ?, ?)",
            new Object[]{remotePath, localPath, new Date().getTime() / 1000});
        Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null);
        cursor.moveToFirst();
        int id = cursor.getInt(0);
        cursor.close();
        return id;
    }

    public synchronized void removeEntry(int id) {
        mOpener.getWritableDatabase().execSQL(
            "DELETE FROM CacheEntries WHERE CacheEntryId = ?", new Object[]{id});
    }

    public synchronized void setContentLength(int id, long contentLength) {
        mOpener.getWritableDatabase().execSQL(
            "Update CacheEntries SET ContentLength = ? WHERE CacheEntryId = ?",
            new Object[]{ contentLength, id });
    }

    public synchronized void updateLastAccessTime(int id) {
        mOpener.getWritableDatabase().execSQL(
            "Update CacheEntries SET LastAccessTime = ? WHERE CacheEntryId = ?",
            new Object[]{ new Date().getTime() / 1000, id });
    }

    public synchronized List<Integer> getIdsByAge() {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT CacheEntryId FROM CacheEntries ORDER BY LastAccessTime ASC", null);
        List<Integer> ids = new ArrayList<Integer>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            ids.add(cursor.getInt(0));
            cursor.moveToNext();
        }
        cursor.close();
        return ids;
    }
}
