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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

class FileCacheDatabase {
    private static final String TAG = "FileCacheDatabase";

    private static final String DATABASE_NAME = "NupFileCache";
    private static final int DATABASE_VERSION = 3;

    // IMPORTANT NOTE: When updating any of these, you must replace all previous references in
    // upgradeFromPreviousVersion() with the hardcoded older version of the string.
    private static final String CREATE_CACHE_ENTRIES_SQL =
        "CREATE TABLE CacheEntries (" +
        "  SongId INTEGER PRIMARY KEY NOT NULL, " +
        "  TotalBytes INTEGER NOT NULL DEFAULT 0, " +
        "  ETag VARCHAR(40) NOT NULL DEFAULT '', " +
        "  LastAccessTime INTEGER NOT NULL)";

    private final DatabaseOpener mOpener;

    // Map from an entry's song ID to the entry itself.
    private final HashMap<Long,FileCacheEntry> mEntries = new HashMap<Long,FileCacheEntry>();

    // Update the database in a background thread.
    private final DatabaseUpdater mUpdater;
    private final Thread mUpdaterThread;

    public FileCacheDatabase(Context context) {
        SQLiteOpenHelper helper = new SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(CREATE_CACHE_ENTRIES_SQL);
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                Log.d(TAG, "onUpgrade: " + oldVersion + " -> " + newVersion);
                db.beginTransaction();
                try {
                    for (int nextVersion = oldVersion + 1; nextVersion <= newVersion; ++nextVersion)
                        upgradeFromPreviousVersion(db, nextVersion);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            // Upgrade the database from the version before |newVersion| to |newVersion|.
            private void upgradeFromPreviousVersion(SQLiteDatabase db, int newVersion) {
                if (newVersion == 2) {
                    // Rename "LocalFilename" column to "LocalPath".
                    db.execSQL("DROP INDEX IF EXISTS RemotePath");
                    db.execSQL("ALTER TABLE CacheEntries RENAME TO CacheEntriesTmp");
                    db.execSQL("CREATE TABLE CacheEntries (" +
                               "  CacheEntryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                               "  RemotePath VARCHAR(2048) UNIQUE NOT NULL, " +
                               "  LocalPath VARCHAR(2048) UNIQUE NOT NULL, " +
                               "  ContentLength INTEGER, " +
                               "  ETag VARCHAR(40), " +
                               "  LastAccessTime INTEGER)");
                    db.execSQL("CREATE INDEX RemotePath on CacheEntries (RemotePath)");
                    db.execSQL("INSERT INTO CacheEntries SELECT * FROM CacheEntriesTmp");
                    db.execSQL("DROP TABLE CacheEntriesTmp");
                } else if (newVersion == 3) {
                    // Redo CacheEntries to be keyed by SongId.
                    db.execSQL("DROP INDEX IF EXISTS RemotePath");
                    db.execSQL("ALTER TABLE CacheEntries RENAME TO CacheEntriesTmp");
                    db.execSQL(CREATE_CACHE_ENTRIES_SQL);
                    Cursor cursor = db.rawQuery(
                        "SELECT LocalPath, IFNULL(ContentLength, 0), IFNULL(LastAccessTime, 0) " +
                        "FROM CacheEntriesTmp",
                        null);
                    cursor.moveToFirst();
                    while (!cursor.isAfterLast()) {
                        File file = new File(cursor.getString(0));
                        int songId = Integer.valueOf(file.getName().split("\\.")[0]);
                        db.execSQL("REPLACE INTO CacheEntries (SongId, TotalBytes, LastAccessTime) VALUES(?, ?, ?)",
                                   new Object[]{ songId, cursor.getInt(1), cursor.getInt(2) });
                        cursor.moveToNext();
                    }
                    cursor.close();
                    db.execSQL("DROP TABLE CacheEntriesTmp");
                } else {
                    throw new RuntimeException(
                        "Got request to upgrade database to unknown version " + newVersion);
                }
            }
        };
        mOpener = new DatabaseOpener(context, DATABASE_NAME, helper);

        // Block until we've loaded everything into memory.
        loadExistingEntries(context);
        mUpdater = new DatabaseUpdater(mOpener);
        mUpdaterThread = new Thread(mUpdater, "FileCacheDatabase.DatabaseUpdater");
        mUpdaterThread.start();
    }

    private synchronized void loadExistingEntries(Context context) {
        Log.d(TAG, "loading cache entries");
        Cursor cursor = mOpener.getDb().rawQuery("SELECT SongId, TotalBytes, LastAccessTime FROM CacheEntries", null);
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            FileCacheEntry entry =
                new FileCacheEntry(cursor.getInt(0), cursor.getLong(1), cursor.getInt(2));
            File file = entry.getLocalFile(context);
            entry.setCachedBytes(file.exists() ? file.length() : 0);
            mEntries.put(entry.getSongId(), entry);
            cursor.moveToNext();
        }
        Log.d(TAG, "finished loading " + mEntries.size() + " cache entries");
        cursor.close();
    }

    public synchronized void quit() {
        mUpdater.quit();
        try { mUpdaterThread.join(); } catch (InterruptedException e) {}
        mOpener.close();
    }

    public synchronized FileCacheEntry getEntry(long songId) {
        return mEntries.get(songId);
    }

    public synchronized FileCacheEntry addEntry(long songId) {
        final int accessTime = (int) (new Date().getTime() / 1000);
        FileCacheEntry entry = new FileCacheEntry(songId, 0, accessTime);
        mEntries.put(songId, entry);
        mUpdater.postUpdate(
            "REPLACE INTO CacheEntries (SongId, TotalBytes, LastAccessTime) VALUES(?, 0, ?)",
            new Object[]{ songId, accessTime });
        return entry;
    }

    public synchronized void removeEntry(long songId) {
        FileCacheEntry entry = mEntries.get(songId);
        if (entry == null)
            return;

        mEntries.remove(songId);
        mUpdater.postUpdate(
            "DELETE FROM CacheEntries WHERE SongId = ?",
            new Object[]{ songId });
    }

    public synchronized void setTotalBytes(long songId, long totalBytes) {
        FileCacheEntry entry = mEntries.get(songId);
        if (entry == null)
            return;

        entry.setTotalBytes(totalBytes);
        mUpdater.postUpdate(
            "UPDATE CacheEntries SET TotalBytes = ? WHERE SongId = ?",
            new Object[]{ totalBytes, songId });
    }

    public synchronized void updateLastAccessTime(long songId) {
        FileCacheEntry entry = mEntries.get(songId);
        if (entry == null)
            return;

        int now = (int) (new Date().getTime() / 1000);
        entry.setLastAccessTime(now);
        mUpdater.postUpdate(
            "UPDATE CacheEntries SET LastAccessTime = ? WHERE SongId = ?",
            new Object[]{ now, songId });
    }

    public synchronized List<Long> getSongIdsByAge() {
        List<Long> ids = new ArrayList<Long>();
        ids.addAll(mEntries.keySet());
        Collections.sort(ids, new Comparator<Long>() {
            @Override
            public int compare(Long a, Long b) {
                int aTime = (Integer) getEntry(a).getLastAccessTime();
                int bTime = (Integer) getEntry(b).getLastAccessTime();
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

    public synchronized List<FileCacheEntry> getAllFullyCachedEntries() {
        List<FileCacheEntry> fullyCachedEntries = new ArrayList<FileCacheEntry>();
        for (FileCacheEntry entry : mEntries.values())
            if (entry.isFullyCached())
                fullyCachedEntries.add(entry);
        return fullyCachedEntries;
    }
}
