// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    // Next ID to be assigned to a new cache entry.
    private int mNextId = 1;

    // Update the database in a background thread.
    private DatabaseUpdater mUpdater;
    private Thread mUpdaterThread;

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

        mUpdater = new DatabaseUpdater();
        mUpdaterThread = new Thread(mUpdater, "FileCacheDatabase.DatabaseUpdater");
        mUpdaterThread.start();
    }

    private synchronized void loadExistingEntries() {
        Cursor cursor = mOpener.getReadableDatabase().rawQuery(
            "SELECT CacheEntryId, RemotePath, LocalPath, IFNULL(ContentLength, 0), LastAccessTime FROM CacheEntries", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            FileCacheEntry entry = new FileCacheEntry(
                cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4));
            File file = new File(entry.getLocalPath());
            entry.setCachedBytes(file.exists() ? file.length() : 0);
            Log.d(TAG, "loaded " + file.getName() + " for " + entry.getRemotePath() +
                  ": " + entry.getCachedBytes() + "/" + entry.getTotalBytes());

            mEntries.put(entry.getId(), entry);
            mEntriesByRemotePath.put(entry.getRemotePath(), entry);
            mNextId = Math.max(mNextId, entry.getId() + 1);
            cursor.moveToNext();
        }
        cursor.close();
    }

    public synchronized void quit() {
        mUpdater.quit();
        try { mUpdaterThread.join(); } catch (InterruptedException e) {}
    }

    public synchronized FileCacheEntry getEntryById(int id) {
        return mEntries.get(id);
    }

    public synchronized FileCacheEntry getEntryForRemotePath(String remotePath) {
        return mEntriesByRemotePath.get(remotePath);
    }

    public synchronized FileCacheEntry addEntry(String remotePath, String localPath) {
        final int id = mNextId++;
        final int accessTime = (int) new Date().getTime() / 1000;
        FileCacheEntry entry = new FileCacheEntry(id, remotePath, localPath, 0, accessTime);
        mEntries.put(id, entry);
        mEntriesByRemotePath.put(remotePath, entry);
        mUpdater.postUpdate(
            "INSERT INTO CacheEntries " +
            "(CacheEntryId, RemotePath, LocalPath, ContentLength, LastAccessTime) " +
            "VALUES(?, ?, ?, 0, ?)",
            new Object[]{ id, remotePath, localPath, accessTime });
        return entry;
    }

    public synchronized void removeEntry(int id) {
        FileCacheEntry entry = mEntries.get(id);
        if (entry == null)
            return;

        mEntries.remove(id);
        mEntriesByRemotePath.remove(entry.getRemotePath());
        mUpdater.postUpdate(
            "DELETE FROM CacheEntries WHERE CacheEntryId = ?",
            new Object[]{ id });
    }

    public synchronized void setTotalBytes(int id, long totalBytes) {
        FileCacheEntry entry = mEntries.get(id);
        if (entry == null)
            return;

        entry.setTotalBytes(totalBytes);
        mUpdater.postUpdate(
            "UPDATE CacheEntries SET ContentLength = ? WHERE CacheEntryId = ?",
            new Object[]{ totalBytes, id });
    }

    public synchronized void updateLastAccessTime(int id) {
        mUpdater.postUpdate(
            "UPDATE CacheEntries SET LastAccessTime = ? WHERE CacheEntryId = ?",
            new Object[]{ new Date().getTime() / 1000, id });
    }

    public synchronized List<Integer> getIdsByAge() {
        List<Integer> ids = new ArrayList<Integer>();
        ids.addAll(mEntries.keySet());
        Collections.sort(ids, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int aTime = (Integer) getEntryById(a).getLastAccessTime();
                int bTime = (Integer) getEntryById(b).getLastAccessTime();
                return (aTime == bTime) ? 0 : (aTime < bTime) ? -1 : 1;
            }
        });
        return ids;
    }

    public synchronized long getTotalCachedBytes() {
        long bytes = 0;
        for (FileCacheEntry entry : mEntries.values())
            bytes += entry.getCachedBytes();
        return bytes;
    }

    private class DatabaseUpdater implements Runnable {
        private Handler mHandler;

        @Override
        public void run() {
            Looper.prepare();
            mHandler = new Handler();
            Looper.loop();
        }

        public void quit() {
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
                    SQLiteDatabase db = mOpener.getWritableDatabase();
                    db.execSQL(sql, values);
                }
            });
        }
    }
}
