/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.util.Date

class FileCacheDatabase(context: Context, private val musicDir: String) {
    private val opener: DatabaseOpener

    // Map from an entry's song ID to the entry itself.
    private val entries = HashMap<Long, FileCacheEntry>()

    // Update the database in a background thread.
    private val updater: DatabaseUpdater
    private val updaterThread: Thread
    @Synchronized
    private fun loadExistingEntries() {
        Log.d(TAG, "loading cache entries")
        val cursor = opener.getDb()
            .rawQuery(
                "SELECT SongId, TotalBytes, LastAccessTime FROM CacheEntries",
                null
            )
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            val entry = FileCacheEntry(
                musicDir, cursor.getLong(0), cursor.getLong(1), cursor.getInt(2)
            )
            val file = entry.localFile
            entry.cachedBytes = if (file.exists()) file.length() else 0
            entries[entry.songId] = entry
            cursor.moveToNext()
        }
        Log.d(TAG, "finished loading ${entries.size} cache entries")
        cursor.close()
    }

    @Synchronized
    fun quit() {
        updater.quit()
        try {
            updaterThread.join()
        } catch (e: InterruptedException) {
        }
        opener.close()
    }

    @Synchronized
    fun getEntry(songId: Long): FileCacheEntry? {
        return entries[songId]
    }

    @Synchronized
    fun addEntry(songId: Long): FileCacheEntry {
        val accessTime = (Date().time / 1000).toInt()
        val entry = FileCacheEntry(musicDir, songId, 0, accessTime)
        entries[songId] = entry
        updater.postUpdate(
            "REPLACE INTO CacheEntries (SongId, TotalBytes, LastAccessTime) VALUES(?, 0, ?)",
            arrayOf<Any>(songId, accessTime)
        )
        return entry
    }

    @Synchronized
    fun removeEntry(songId: Long) {
        entries.remove(songId)
        updater.postUpdate("DELETE FROM CacheEntries WHERE SongId = ?", arrayOf<Any>(songId))
    }

    @Synchronized
    fun setTotalBytes(songId: Long, totalBytes: Long) {
        val entry = entries[songId] ?: return
        entry.totalBytes = totalBytes
        updater.postUpdate(
            "UPDATE CacheEntries SET TotalBytes = ? WHERE SongId = ?",
            arrayOf<Any>(totalBytes, songId)
        )
    }

    @Synchronized
    fun updateLastAccessTime(songId: Long) {
        val entry = entries[songId] ?: return
        val now = (Date().time / 1000).toInt()
        entry.lastAccessTime = now
        updater.postUpdate(
            "UPDATE CacheEntries SET LastAccessTime = ? WHERE SongId = ?", arrayOf<Any>(now, songId)
        )
    }

    @get:Synchronized
    val songIdsByAge: List<Long>
        get() {
            val ids: MutableList<Long> = ArrayList()
            ids.addAll(entries.keys)
            ids.sortWith(
                Comparator { a, b ->
                    val aTime = getEntry(a)!!.lastAccessTime
                    val bTime = getEntry(b)!!.lastAccessTime
                    if (aTime == bTime) 0 else if (aTime < bTime) -1 else 1
                }
            )
            return ids
        }

    @get:Synchronized
    val totalCachedBytes: Long
        get() {
            var bytes: Long = 0
            for (entry in entries.values) bytes += entry.cachedBytes
            return bytes
        }

    @get:Synchronized
    val allFullyCachedEntries: List<FileCacheEntry>
        get() {
            val fullyCachedEntries: MutableList<FileCacheEntry> = ArrayList()
            for (entry in entries.values) if (entry.isFullyCached) fullyCachedEntries.add(entry)
            return fullyCachedEntries
        }

    companion object {
        private const val TAG = "FileCacheDatabase"
        private const val DATABASE_NAME = "NupFileCache"
        private const val DATABASE_VERSION = 3

        // IMPORTANT NOTE: When updating any of these, you must replace all previous references in
        // upgradeFromPreviousVersion() with the hardcoded older version of the string.
        private const val CREATE_CACHE_ENTRIES_SQL = (
            "CREATE TABLE CacheEntries (" +
                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                "TotalBytes INTEGER NOT NULL DEFAULT 0, " +
                "ETag VARCHAR(40) NOT NULL DEFAULT '', " +
                "LastAccessTime INTEGER NOT NULL)"
            )
    }

    init {
        val helper: SQLiteOpenHelper =
            object : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(CREATE_CACHE_ENTRIES_SQL)
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    Log.d(TAG, "onUpgrade: $oldVersion -> $newVersion")
                    db.beginTransaction()
                    try {
                        for (nextVersion in (oldVersion + 1)..newVersion) {
                            upgradeFromPreviousVersion(db, nextVersion)
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }

                // Upgrade the database from the version before |newVersion| to |newVersion|.
                private fun upgradeFromPreviousVersion(db: SQLiteDatabase, newVersion: Int) {
                    if (newVersion == 2) {
                        // Rename "LocalFilename" column to "LocalPath".
                        db.execSQL("DROP INDEX IF EXISTS RemotePath")
                        db.execSQL("ALTER TABLE CacheEntries RENAME TO CacheEntriesTmp")
                        db.execSQL(
                            "CREATE TABLE CacheEntries ( " +
                                "CacheEntryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "RemotePath VARCHAR(2048) UNIQUE NOT NULL, " +
                                "LocalPath VARCHAR(2048) UNIQUE NOT NULL, " +
                                "ContentLength INTEGER, " +
                                "ETag VARCHAR(40), " +
                                "LastAccessTime INTEGER)"
                        )
                        db.execSQL("CREATE INDEX RemotePath on CacheEntries (RemotePath)")
                        db.execSQL("INSERT INTO CacheEntries SELECT * FROM CacheEntriesTmp")
                        db.execSQL("DROP TABLE CacheEntriesTmp")
                    } else if (newVersion == 3) {
                        // Redo CacheEntries to be keyed by SongId.
                        db.execSQL("DROP INDEX IF EXISTS RemotePath")
                        db.execSQL("ALTER TABLE CacheEntries RENAME TO CacheEntriesTmp")
                        db.execSQL(CREATE_CACHE_ENTRIES_SQL)
                        val cursor = db.rawQuery(
                            "SELECT LocalPath, " +
                                "IFNULL(ContentLength, 0), " +
                                "IFNULL(LastAccessTime, 0) " +
                                "FROM CacheEntriesTmp",
                            null
                        )
                        cursor.moveToFirst()
                        while (!cursor.isAfterLast) {
                            val file = File(cursor.getString(0))
                            val songId = Integer
                                .valueOf(file.name.split("\\.").toTypedArray()[0])
                                .toLong()
                            db.execSQL(
                                "REPLACE INTO CacheEntries " +
                                    "(SongId, TotalBytes, LastAccessTime) " +
                                    "VALUES(?, ?, ?)",
                                arrayOf<Any>(songId, cursor.getInt(1), cursor.getInt(2))
                            )
                            cursor.moveToNext()
                        }
                        cursor.close()
                        db.execSQL("DROP TABLE CacheEntriesTmp")
                    } else {
                        throw RuntimeException(
                            "Got request to upgrade database to unknown version $newVersion"
                        )
                    }
                }
            }
        opener = DatabaseOpener(context, DATABASE_NAME, helper)

        // Block until we've loaded everything into memory.
        loadExistingEntries()
        updater = DatabaseUpdater(opener)
        updaterThread = Thread(updater, "FileCacheDatabase.DatabaseUpdater")
        updaterThread.start()
    }
}
