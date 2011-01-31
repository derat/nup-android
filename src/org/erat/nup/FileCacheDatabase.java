// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

class FileCacheDatabase {
    private static final String TAG = "FileCacheDatabase";
    private static final String DATABASE_NAME = "NupFileCache";
    private static final int DATABASE_VERSION = 2;

    private final SQLiteOpenHelper mOpener;

    // Map from an entry's ID to the entry itself.
    private final HashMap<Integer,FileCacheEntry> mEntries = new HashMap<Integer,FileCacheEntry>();

    // Map from an entry's remote path to the entry itself.
    private final HashMap<String,FileCacheEntry> mEntriesByRemotePath = new HashMap<String,FileCacheEntry>();

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

        // Block until we've loaded everything into memory.
        loadExistingEntries();
    }

    private synchronized void loadExistingEntries() {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT CacheEntryId, RemotePath, LocalPath, IFNULL(ContentLength, 0) FROM CacheEntries", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            FileCacheEntry entry = new FileCacheEntry(
                cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3));
            File file = new File(entry.getLocalPath());
            entry.setCachedBytes(file.exists() ? file.length() : 0);
            Log.d(TAG, "loaded " + file.getName() + " for " + entry.getRemotePath() +
                  ": " + entry.getCachedBytes() + "/" + entry.getTotalBytes());

            mEntries.put(entry.getId(), entry);
            mEntriesByRemotePath.put(entry.getRemotePath(), entry);
            cursor.moveToNext();
        }
        cursor.close();
    }

    public synchronized FileCacheEntry getEntryById(int id) {
        return mEntries.get(id);
    }

    public synchronized FileCacheEntry getEntryForRemotePath(String remotePath) {
        return mEntriesByRemotePath.get(remotePath);
    }

    public synchronized FileCacheEntry addEntry(String remotePath, String localPath) {
        SQLiteDatabase db = mOpener.getWritableDatabase();
        db.execSQL(
            "INSERT INTO CacheEntries (RemotePath, LocalPath, LastAccessTime) VALUES(?, ?, ?)",
            new Object[]{remotePath, localPath, new Date().getTime() / 1000});
        Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null);
        cursor.moveToFirst();
        int id = cursor.getInt(0);
        cursor.close();

        FileCacheEntry entry = new FileCacheEntry(id, remotePath, localPath, 0);
        mEntries.put(id, entry);
        mEntriesByRemotePath.put(remotePath, entry);
        return entry;
    }

    public synchronized void removeEntry(int id) {
        FileCacheEntry entry = mEntries.get(id);
        if (entry == null)
            return;

        mEntries.remove(id);
        mEntriesByRemotePath.remove(entry.getRemotePath());
        mOpener.getWritableDatabase().execSQL(
            "DELETE FROM CacheEntries WHERE CacheEntryId = ?", new Object[]{id});
    }

    public synchronized void setCachedBytes(int id, long cachedBytes) {
        FileCacheEntry entry = mEntries.get(id);
        if (entry == null)
            return;
        entry.setCachedBytes(cachedBytes);
    }

    public synchronized void setTotalBytes(int id, long totalBytes) {
        FileCacheEntry entry = mEntries.get(id);
        if (entry == null)
            return;

        entry.setTotalBytes(totalBytes);
        mOpener.getWritableDatabase().execSQL(
            "UPDATE CacheEntries SET ContentLength = ? WHERE CacheEntryId = ?",
            new Object[]{ totalBytes, id });
    }

    public synchronized void updateLastAccessTime(int id) {
        mOpener.getWritableDatabase().execSQL(
            "UPDATE CacheEntries SET LastAccessTime = ? WHERE CacheEntryId = ?",
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
