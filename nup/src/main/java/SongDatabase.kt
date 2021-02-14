// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.AsyncTask
import android.text.TextUtils
import android.util.Log
import org.erat.nup.Util.getSortingKey
import org.erat.nup.Util.sortStatsRowList
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import java.util.*

internal class SongDatabase(
        private val context: Context,
        private val listener: Listener,
        private val cache: FileCache,
        private val downloader: Downloader,
        private val networkHelper: NetworkHelper) {
    private val opener: DatabaseOpener

    // Update the database in a background thread.
    private val updater: DatabaseUpdater
    private val updaterThread: Thread
    var aggregateDataLoaded = false
        private set
    var numSongs = 0
        private set
    var lastSyncDate: Date? = null
        private set

    // Int values in these maps are numbers of songs.
    private val artistsSortedAlphabetically: MutableList<StatsRow> = ArrayList()
    private val artistsSortedByNumSongs: MutableList<StatsRow> = ArrayList()
    private val albumsSortedAlphabetically: MutableList<StatsRow> = ArrayList()
    private val artistAlbums = HashMap<String, MutableList<StatsRow>>()

    interface Listener {
        fun onAggregateDataUpdate()
    }

    enum class SyncState {
        UPDATING_SONGS, DELETING_SONGS, UPDATING_STATS
    }

    interface SyncProgressListener {
        fun onSyncProgress(state: SyncState?, numSongs: Int)
    }

    fun getAlbumsSortedAlphabetically(): List<StatsRow> {
        return albumsSortedAlphabetically
    }

    fun getArtistsSortedAlphabetically(): List<StatsRow> {
        return artistsSortedAlphabetically
    }

    fun getArtistsSortedByNumSongs(): List<StatsRow> {
        return artistsSortedByNumSongs
    }

    fun getAlbumsByArtist(artist: String): List<StatsRow> {
        val lowerArtist = artist.toLowerCase()
        return if (artistAlbums.containsKey(lowerArtist)) artistAlbums[lowerArtist]!! else ArrayList()
    }

    val cachedArtistsSortedAlphabetically: List<StatsRow>
        get() = getSortedRows(
                "SELECT s.Artist, '' AS Album, '' AS AlbumId, COUNT(*) "
                        + "FROM Songs s "
                        + "JOIN CachedSongs cs ON(s.SongId = cs.SongId) "
                        + "GROUP BY LOWER(TRIM(s.Artist))",
                null,
                Util.SORT_ARTIST)

    // TODO: It'd be better to use most-frequent-artist logic here similar
    // to what loadAggregateData() does for |mAlbumsSortedAlphabetically|.
    // I haven't figured out how to do that solely with SQL, though, and
    // I'd rather not write one-off code here.
    val cachedAlbumsSortedAlphabetically: List<StatsRow>
        get() =// TODO: It'd be better to use most-frequent-artist logic here similar
        // to what loadAggregateData() does for |mAlbumsSortedAlphabetically|.
        // I haven't figured out how to do that solely with SQL, though, and
                // I'd rather not write one-off code here.
            getSortedRows(
                    "SELECT MIN(s.Artist), s.Album, s.AlbumId, COUNT(*) "
                            + "FROM Songs s "
                            + "JOIN CachedSongs cs ON(s.SongId = cs.SongId) "
                            + "GROUP BY LOWER(TRIM(s.Album)), s.AlbumId",
                    null,
                    Util.SORT_ALBUM)

    fun getCachedAlbumsByArtist(artist: String): List<StatsRow> {
        return getSortedRows(
                "SELECT '' AS Artist, s.Album, s.AlbumId, COUNT(*) "
                        + "FROM Songs s "
                        + "JOIN CachedSongs cs ON(s.SongId = cs.SongId) "
                        + "WHERE LOWER(s.Artist) = LOWER(?) "
                        + "GROUP BY LOWER(TRIM(s.Album)), s.AlbumId", arrayOf(artist),
                Util.SORT_ALBUM)
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

    fun query(
            artist: String?,
            title: String?,
            album: String?,
            albumId: String?,
            minRating: Double,
            shuffle: Boolean,
            substring: Boolean,
            onlyCached: Boolean): List<Song> {
        class QueryBuilder {
            var selections = ArrayList<String>()
            var selectionArgs = ArrayList<String>()
            fun add(clause: String, selectionArg: String?, substring: Boolean) {
                if (selectionArg == null || selectionArg.isEmpty()) return
                selections.add(clause)
                if (selectionArg == UNSET_STRING) selectionArgs.add("") else selectionArgs.add(if (substring) "%$selectionArg%" else selectionArg)
            }

            // Get a WHERE clause (plus trailing space) if |selections| is non-empty, or just an
            // empty string otherwise.
            val whereClause: String
                get() = if (selections.isEmpty()) "" else "WHERE " + TextUtils.join(" AND ", selections) + " "
        }

        val builder = QueryBuilder()
        builder.add("Artist LIKE ?", artist, substring)
        builder.add("Title LIKE ?", title, substring)
        builder.add("Album LIKE ?", album, substring)
        builder.add("AlbumId = ?", albumId, false)
        builder.add("Rating >= ?", if (minRating >= 0.0) java.lang.Double.toString(minRating) else null, false)
        val query = ("SELECT s.SongId, Artist, Title, Album, AlbumId, Url, CoverUrl, Length, "
                + "TrackNumber, DiscNumber, TrackGain, AlbumGain, PeakAmp, Rating "
                + "FROM Songs s "
                + (if (onlyCached) "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " else "")
                + builder.whereClause
                + "ORDER BY "
                + (if (shuffle) "RANDOM()" else "Album ASC, AlbumId ASC, DiscNumber ASC, TrackNumber ASC")
                + " "
                + "LIMIT "
                + MAX_QUERY_RESULTS)
        Log.d(
                TAG,
                "running query \""
                        + query
                        + "\" with args "
                        + TextUtils.join(", ", builder.selectionArgs))
        val db = opener.getDb()
        val cursor = db.rawQuery(query, builder.selectionArgs.toArray<String>(arrayOf<String>()))
        val songs: MutableList<Song> = ArrayList()
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            val song = Song(
                    cursor.getLong(0),  // songId
                    cursor.getString(1),  // artist
                    cursor.getString(2),  // title
                    cursor.getString(3),  // album
                    cursor.getString(4),  // albumId
                    cursor.getString(5),  // url
                    cursor.getString(6),  // coverUrl
                    cursor.getInt(7),  // lengthSec
                    cursor.getInt(8),  // track
                    cursor.getInt(9),  // disc
                    cursor.getFloat(10).toDouble(),  // trackGain
                    cursor.getFloat(11).toDouble(),  // albumGain
                    cursor.getFloat(12).toDouble(),  // peakAmp
                    cursor.getFloat(13).toDouble()) // rating
            songs.add(song)
            cursor.moveToNext()
        }
        cursor.close()
        return songs
    }

    // Record the fact that a song has been successfully cached.
    fun handleSongCached(songId: Long) {
        updater.postUpdate("REPLACE INTO CachedSongs (SongId) VALUES(?)", arrayOf(songId))
    }

    // Record the fact that a song has been evicted from the cache.
    fun handleSongEvicted(songId: Long) {
        updater.postUpdate("DELETE FROM CachedSongs WHERE SongId = ?", arrayOf(songId))
    }

    // Add an entry to the PendingPlaybackReports table.
    fun addPendingPlaybackReport(songId: Long, startDate: Date) {
        updater.postUpdate(
                "REPLACE INTO PendingPlaybackReports (SongId, StartTime) VALUES(?, ?)", arrayOf(songId, startDate.time / 1000))
    }

    // Remove an entry from the PendingPlaybackReports table.
    fun removePendingPlaybackReport(songId: Long, startDate: Date) {
        updater.postUpdate(
                "DELETE FROM PendingPlaybackReports WHERE SongId = ? AND StartTime = ?", arrayOf(songId, startDate.time / 1000))
    }

    // Simple struct representing a queued report of a song being played.
    class PendingPlaybackReport(var songId: Long, var startDate: Date)

    // Get all pending playback reports from the PendingPlaybackReports table.
    val allPendingPlaybackReports: List<PendingPlaybackReport>
        get() {
            val query = "SELECT SongId, StartTime FROM PendingPlaybackReports"
            val db = opener.getDb()
            val cursor = db.rawQuery(query, null)
            cursor.moveToFirst()
            val reports: MutableList<PendingPlaybackReport> = ArrayList()
            while (!cursor.isAfterLast) {
                reports.add(
                        PendingPlaybackReport(
                                cursor.getLong(0), Date(cursor.getLong(1) * 1000)))
                cursor.moveToNext()
            }
            cursor.close()
            return reports
        }

    fun syncWithServer(listener: SyncProgressListener, message: Array<String?>): Boolean {
        if (!networkHelper.isNetworkAvailable) {
            message[0] = context.getString(R.string.network_is_unavailable)
            return false
        }
        var numSongsUpdated = 0
        val db = opener.getDb()
        db.beginTransaction()
        try {
            // Ask the server for the current time before we fetch anything.  We'll use this as the
            // starting point for the next sync, to handle the case where some songs in the server
            // are updated while we're doing this sync.
            val startTimeStr = downloader.downloadString("/now_nsec", message) ?: return false
            var startTimeNsec: Long
            try {
                startTimeNsec = java.lang.Long.valueOf(startTimeStr)
            } catch (e: NumberFormatException) {
                message[0] = "Unable to parse time: $startTimeStr"
                return false
            }

            // Start where we left off last time.
            val dbCursor = db.rawQuery("SELECT ServerTimeNsec FROM LastUpdateTime", null)
            dbCursor.moveToFirst()
            val prevStartTimeNsec = dbCursor.getLong(0)
            dbCursor.close()
            try {
                numSongsUpdated += queryServer(db, prevStartTimeNsec, false, listener, message)
                numSongsUpdated += queryServer(db, prevStartTimeNsec, true, listener, message)
            } catch (e: ServerException) {
                return false
            }
            val values = ContentValues(2)
            values.put("LocalTimeNsec", Date().time * 1000 * 1000)
            values.put("ServerTimeNsec", startTimeNsec)
            db.update("LastUpdateTime", values, null, null)
            if (numSongsUpdated > 0) {
                listener.onSyncProgress(SyncState.UPDATING_STATS, numSongsUpdated)
                updateStatsTables(db)
                updateCachedSongs(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loadAggregateData(numSongsUpdated > 0)
        message[0] = "Synchronization complete."
        return true
    }

    class ServerException(reason: String?) : Exception(reason)

    // Helper method for SyncWithServer. Returns number of updated songs.
    @Throws(ServerException::class)
    private fun queryServer(
            db: SQLiteDatabase,
            prevStartTimeNsec: Long,
            deleted: Boolean,
            listener: SyncProgressListener,
            message: Array<String?>): Int {
        var numUpdates = 0

        // The server breaks its results up into batches instead of sending us a bunch of songs
        // at once, so use the cursor that it returns to start in the correct place in the next
        // request.
        var serverCursor = ""
        while (numUpdates == 0 || !serverCursor.isEmpty()) {
            val path = String.format(
                    "/songs?minLastModifiedNsec=%d&deleted=%d&max=%d&cursor=%s",
                    prevStartTimeNsec,
                    if (deleted) 1 else 0,
                    SERVER_SONG_BATCH_SIZE,
                    serverCursor)
            val response = downloader.downloadString(path, message)
                    ?: throw ServerException("download failed")
            serverCursor = ""
            try {
                val objects = JSONTokener(response).nextValue() as JSONArray
                if (objects.length() == 0) break
                for (i in 0 until objects.length()) {
                    val jsonSong = objects.optJSONObject(i)
                            ?: if (i == objects.length() - 1) {
                                serverCursor = objects.getString(i)
                                break
                            } else {
                                message[0] = "Item $i from server isn't a JSON object"
                                throw ServerException("list item not object")
                            }
                    val songId = jsonSong.getLong("songId")
                    if (deleted) {
                        Log.d(TAG, "deleting song $songId")
                        db.delete("Songs", "SongId = ?", arrayOf(java.lang.Long.toString(songId)))
                    } else {
                        val values = ContentValues(14)
                        values.put("SongId", songId)
                        values.put("Url", jsonSong.getString("url"))
                        values.put(
                                "CoverUrl",
                                if (jsonSong.has("coverUrl")) jsonSong.getString("coverUrl") else "")
                        values.put("Artist", jsonSong.getString("artist"))
                        values.put("Title", jsonSong.getString("title"))
                        values.put("Album", jsonSong.getString("album"))
                        values.put(
                                "AlbumId",
                                if (jsonSong.has("albumId")) jsonSong.getString("albumId") else "")
                        values.put("TrackNumber", jsonSong.getInt("track"))
                        values.put("DiscNumber", jsonSong.getInt("disc"))
                        values.put("Length", jsonSong.getDouble("length"))
                        values.put(
                                "TrackGain",
                                if (jsonSong.has("trackGain")) jsonSong.getDouble("trackGain") else 0.0)
                        values.put(
                                "AlbumGain",
                                if (jsonSong.has("albumGain")) jsonSong.getDouble("albumGain") else 0.0)
                        values.put(
                                "PeakAmp",
                                if (jsonSong.has("peakAmp")) jsonSong.getDouble("peakAmp") else 0.0)
                        values.put("Rating", jsonSong.getDouble("rating"))
                        db.replace("Songs", "", values)
                    }
                    numUpdates++
                }
            } catch (e: JSONException) {
                message[0] = "Couldn't parse response: $e"
                throw ServerException("bad data")
            }
            listener.onSyncProgress(
                    if (deleted) SyncState.DELETING_SONGS else SyncState.UPDATING_SONGS, numUpdates)
        }
        return numUpdates
    }

    private fun updateStatsTables(db: SQLiteDatabase) {
        // Map from lowercased artist name to the first row that we saw in its original case.
        val artistCaseMap = HashMap<String, String>()

        // Map from artist name to map from album to number of songs.
        val artistAlbums = HashMap<String?, HashMap<StatsKey, Int>>()
        val cursor = db.rawQuery(
                "SELECT Artist, Album, AlbumId, COUNT(*) "
                        + "  FROM Songs "
                        + "  GROUP BY Artist, Album, AlbumId",
                null)
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            var origArtist = cursor.getString(0)
            if (origArtist.isEmpty()) origArtist = UNSET_STRING

            // Normalize the artist case so that we'll still group all their songs together
            // even if some use different capitalization.
            val lowerArtist = origArtist.toLowerCase()
            if (!artistCaseMap.containsKey(lowerArtist)) artistCaseMap[lowerArtist] = origArtist
            val artist = artistCaseMap[lowerArtist]
            var album = cursor.getString(1)
            if (album.isEmpty()) album = UNSET_STRING
            val albumId = cursor.getString(2)
            val numSongs = cursor.getInt(3)
            var albumMap: HashMap<StatsKey, Int>
            if (artistAlbums.containsKey(artist)) {
                albumMap = artistAlbums[artist]!!
            } else {
                albumMap = HashMap()
                artistAlbums[artist] = albumMap
            }
            albumMap[StatsKey(origArtist, album, albumId)] = numSongs
            cursor.moveToNext()
        }
        cursor.close()
        db.delete("ArtistAlbumStats", null, null)
        for (artist in artistAlbums.keys) {
            val artistSortKey = getSortingKey(artist!!, Util.SORT_ARTIST)
            val albumMap = artistAlbums[artist]!!
            for (key in albumMap.keys) {
                val values = ContentValues(6)
                values.put("Artist", artist)
                values.put("Album", key.album)
                values.put("AlbumId", key.albumId)
                values.put("NumSongs", albumMap[key])
                values.put("ArtistSortKey", artistSortKey)
                values.put("AlbumSortKey", getSortingKey(key.album, Util.SORT_ALBUM))
                db.insert("ArtistAlbumStats", "", values)
            }
        }
    }

    // Clear the CachedSongs table and repopulate it with all of the fully-downloaded songs in the
    // cache.
    private fun updateCachedSongs(db: SQLiteDatabase) {
        db.delete("CachedSongs", null, null)
        var numSongs = 0
        for (entry in cache.allFullyCachedEntries) {
            if (!entry.isFullyCached) continue
            val values = ContentValues(1)
            values.put("SongId", entry.songId)
            db.replace("CachedSongs", null, values)
            numSongs++
        }
        Log.d(TAG, "learned about $numSongs cached song(s)")
    }

    private fun loadAggregateData(songsUpdated: Boolean) {
        val db = opener.getDb()
        var cursor = db.rawQuery("SELECT LocalTimeNsec FROM LastUpdateTime", null)
        cursor.moveToFirst()
        lastSyncDate = if (cursor.getLong(0) > 0) Date(cursor.getLong(0) / (1000 * 1000)) else null
        cursor.close()

        // If the song data didn't change and we've already loaded it, bail out early.
        if (!songsUpdated && aggregateDataLoaded) {
            listener.onAggregateDataUpdate()
            return
        }
        numSongs = 0
        artistsSortedAlphabetically.clear()
        albumsSortedAlphabetically.clear()
        artistsSortedByNumSongs.clear()
        artistAlbums.clear()
        cursor = db.rawQuery("SELECT COUNT(*) FROM Songs", null)
        cursor.moveToFirst()
        numSongs = cursor.getInt(0)
        cursor.close()
        cursor = db.rawQuery(
                "SELECT Artist, SUM(NumSongs) "
                        + "FROM ArtistAlbumStats "
                        + "GROUP BY LOWER(TRIM(Artist)) "
                        + "ORDER BY ArtistSortKey ASC",
                null)
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            artistsSortedAlphabetically.add(
                    StatsRow(cursor.getString(0), "", "", cursor.getInt(1)))
            cursor.moveToNext()
        }
        cursor.close()

        // Aggregate by (album, albumId) while storing the most-frequent artist for each album.
        var lastRow: StatsRow? = null // last row added to |albumsSortedAlphabetically|
        cursor = db.rawQuery(
                "SELECT Artist, Album, AlbumId, SUM(NumSongs) "
                        + "FROM ArtistAlbumStats "
                        + "GROUP BY LOWER(TRIM(Artist)), LOWER(TRIM(Album)), AlbumId "
                        + "ORDER BY AlbumSortKey ASC, AlbumId ASC, 4 DESC",
                null)
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            val artist = cursor.getString(0)
            val album = cursor.getString(1)
            val albumId = cursor.getString(2)
            val count = cursor.getInt(3)
            cursor.moveToNext()
            if (lastRow != null && lastRow.key.albumId == albumId && lastRow.key.album.trim { it <= ' ' }.toLowerCase() == album.trim { it <= ' ' }.toLowerCase()) {
                lastRow.count += count
            } else {
                val row = StatsRow(artist, album, albumId, count)
                albumsSortedAlphabetically.add(row)
                lastRow = row
            }

            // TODO: Consider aggregating by album ID so that we have the full count from
            // each album rather than just songs exactly matching the artist. This will
            // affect the count displayed when navigating from BrowseArtistsActivity to
            // BrowseAlbumsActivity.
            val lowerArtist = artist.toLowerCase()
            var albums = artistAlbums[lowerArtist]
            if (albums == null) {
                albums = ArrayList()
                artistAlbums[lowerArtist] = albums
            }
            albums.add(StatsRow(artist, album, albumId, count))
        }
        cursor.close()
        artistsSortedByNumSongs.addAll(artistsSortedAlphabetically)
        Collections.sort(
                artistsSortedByNumSongs
        ) { a, b -> b.count - a.count }
        aggregateDataLoaded = true
        listener.onAggregateDataUpdate()
    }

    // Given a query that returns artist, album, album ID, and num songs,
    // returns its results in sorted order.
    private fun getSortedRows(query: String, selectionArgs: Array<String>?, sortType: Int): List<StatsRow> {
        val db = opener.getDb()
        val cursor = db.rawQuery(query, selectionArgs)
        val rows: MutableList<StatsRow> = ArrayList()
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            var artist = cursor.getString(0)
            if (artist.isEmpty()) artist = UNSET_STRING
            var album = cursor.getString(1)
            if (album.isEmpty()) album = UNSET_STRING
            val albumId = cursor.getString(2)
            val count = cursor.getInt(3)
            rows.add(StatsRow(artist, album, albumId, count))
            cursor.moveToNext()
        }
        cursor.close()
        sortStatsRowList(rows, sortType)
        return rows
    }

    companion object {
        private const val TAG = "SongDatabase"

        // Special user-visible string that we use to represent a blank field.
        // It should be something that doesn't legitimately appear in any fields,
        // so "[unknown]" is out (MusicBrainz uses it for unknown artists).
        const val UNSET_STRING = "[unset]"
        private const val MAX_QUERY_RESULTS = "250"
        private const val DATABASE_NAME = "NupSongs"
        private const val DATABASE_VERSION = 15
        private const val SERVER_SONG_BATCH_SIZE = 100

        // IMPORTANT NOTE: When updating any of these, you must replace all previous references in
        // upgradeFromPreviousVersion() with the hardcoded older version of the string.
        private const val CREATE_SONGS_SQL = ("CREATE TABLE Songs ("
                + "  SongId INTEGER PRIMARY KEY NOT NULL, "
                + "  Url VARCHAR(256) NOT NULL, "
                + "  CoverUrl VARCHAR(256) NOT NULL, "
                + "  Artist VARCHAR(256) NOT NULL, "
                + "  Title VARCHAR(256) NOT NULL, "
                + "  Album VARCHAR(256) NOT NULL, "
                + "  AlbumId VARCHAR(256) NOT NULL, "
                + "  TrackNumber INTEGER NOT NULL, "
                + "  DiscNumber INTEGER NOT NULL, "
                + "  Length INTEGER NOT NULL, "
                + "  TrackGain FLOAT NOT NULL, "
                + "  AlbumGain FLOAT NOT NULL, "
                + "  PeakAmp FLOAT NOT NULL, "
                + "  Rating FLOAT NOT NULL)")
        private const val CREATE_SONGS_ARTIST_INDEX_SQL = "CREATE INDEX Artist ON Songs (Artist)"
        private const val CREATE_SONGS_ALBUM_INDEX_SQL = "CREATE INDEX Album ON Songs (Album)"
        private const val CREATE_SONGS_ALBUM_ID_INDEX_SQL = "CREATE INDEX AlbumId ON Songs (AlbumId)"
        private const val CREATE_ARTIST_ALBUM_STATS_SQL = ("CREATE TABLE ArtistAlbumStats ("
                + "  Artist VARCHAR(256) NOT NULL, "
                + "  Album VARCHAR(256) NOT NULL, "
                + "  AlbumId VARCHAR(256) NOT NULL, "
                + "  NumSongs INTEGER NOT NULL, "
                + "  ArtistSortKey VARCHAR(256) NOT NULL, "
                + "  AlbumSortKey VARCHAR(256) NOT NULL)")
        private const val CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL = "CREATE INDEX ArtistSortKey ON ArtistAlbumStats (ArtistSortKey)"
        private const val CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL = "CREATE INDEX AlbumSortKey ON ArtistAlbumStats (AlbumSortKey)"
        private const val CREATE_LAST_UPDATE_TIME_SQL = ("CREATE TABLE LastUpdateTime ("
                + "  LocalTimeNsec INTEGER NOT NULL, "
                + "  ServerTimeNsec INTEGER NOT NULL)")
        private const val INSERT_LAST_UPDATE_TIME_SQL = "INSERT INTO LastUpdateTime (LocalTimeNsec, ServerTimeNsec) VALUES(0, 0)"
        private const val CREATE_CACHED_SONGS_SQL = "CREATE TABLE CachedSongs (" + "  SongId INTEGER PRIMARY KEY NOT NULL)"
        private const val CREATE_PENDING_PLAYBACK_REPORTS_SQL = ("CREATE TABLE PendingPlaybackReports ("
                + "  SongId INTEGER NOT NULL, "
                + "  StartTime INTEGER NOT NULL, "
                + "  PRIMARY KEY (SongId, StartTime))")
    }

    init {
        val helper: SQLiteOpenHelper = object : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(CREATE_SONGS_SQL)
                db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL)
                db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL)
                db.execSQL(CREATE_SONGS_ALBUM_ID_INDEX_SQL)
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL)
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL)
                db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL)
                db.execSQL(CREATE_LAST_UPDATE_TIME_SQL)
                db.execSQL(INSERT_LAST_UPDATE_TIME_SQL)
                db.execSQL(CREATE_CACHED_SONGS_SQL)
                db.execSQL(CREATE_PENDING_PLAYBACK_REPORTS_SQL)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                Log.d(TAG, "onUpgrade: $oldVersion -> $newVersion")
                db.beginTransaction()
                try {
                    for (nextVersion in oldVersion + 1..newVersion) upgradeFromPreviousVersion(db, nextVersion)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            // Upgrade the database from the version before |newVersion| to |newVersion|.
            // The only reason I'm keeping the old upgrade steps is for reference when
            // writing new ones.
            private fun upgradeFromPreviousVersion(db: SQLiteDatabase, newVersion: Int) {
                if (newVersion == 2) {
                    // Version 2: Create LastUpdateTime table.
                    db.execSQL(
                            "CREATE TABLE LastUpdateTime ("
                                    + "  Timestamp INTEGER NOT NULL, "
                                    + "  MaxLastModified INTEGER NOT NULL)")
                    db.execSQL(
                            "INSERT INTO LastUpdateTime "
                                    + "  (Timestamp, MaxLastModified) "
                                    + "  VALUES(0, 0)")
                } else if (newVersion == 3) {
                    // Version 3: Add Songs.Deleted column.
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                    db.execSQL(
                            "CREATE TABLE Songs ("
                                    + "  SongId INTEGER PRIMARY KEY NOT NULL, "
                                    + "  Sha1 CHAR(40) NOT NULL, "
                                    + "  Filename VARCHAR(256) NOT NULL, "
                                    + "  Artist VARCHAR(256) NOT NULL, "
                                    + "  Title VARCHAR(256) NOT NULL, "
                                    + "  Album VARCHAR(256) NOT NULL, "
                                    + "  TrackNumber INTEGER NOT NULL, "
                                    + "  Length INTEGER NOT NULL, "
                                    + "  Rating FLOAT NOT NULL, "
                                    + "  Deleted BOOLEAN NOT NULL, "
                                    + "  LastModifiedUsec INTEGER NOT NULL)")
                    db.execSQL(
                            "INSERT INTO Songs SELECT SongId, Sha1, Filename, Artist,"
                                    + " Title, Album,     TrackNumber, Length, Rating, 0,"
                                    + " LastModified FROM SongsTmp")
                    db.execSQL("DROP TABLE SongsTmp")
                } else if (newVersion == 4) {
                    // Version 4: Create ArtistAlbumStats table and indexes on Songs.Artist
                    // and Songs.Album.
                    db.execSQL(
                            "CREATE TABLE ArtistAlbumStats ("
                                    + "  Artist VARCHAR(256) NOT NULL, "
                                    + "  Album VARCHAR(256) NOT NULL, "
                                    + "  NumSongs INTEGER NOT NULL)")
                    db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL)
                    db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL)
                } else if (newVersion == 5) {
                    // Version 5: LastModified -> LastModifiedUsec (seconds to
                    // microseconds).
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                    db.execSQL("UPDATE SongsTmp SET LastModified = LastModified * 1000000")
                    db.execSQL(
                            "CREATE TABLE Songs ("
                                    + "  SongId INTEGER PRIMARY KEY NOT NULL, "
                                    + "  Sha1 CHAR(40) NOT NULL, "
                                    + "  Filename VARCHAR(256) NOT NULL, "
                                    + "  Artist VARCHAR(256) NOT NULL, "
                                    + "  Title VARCHAR(256) NOT NULL, "
                                    + "  Album VARCHAR(256) NOT NULL, "
                                    + "  TrackNumber INTEGER NOT NULL, "
                                    + "  Length INTEGER NOT NULL, "
                                    + "  Rating FLOAT NOT NULL, "
                                    + "  Deleted BOOLEAN NOT NULL, "
                                    + "  LastModifiedUsec INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO Songs SELECT * FROM SongsTmp")
                    db.execSQL("DROP TABLE SongsTmp")
                    db.execSQL("ALTER TABLE LastUpdateTime RENAME TO LastUpdateTimeTmp")
                    db.execSQL("UPDATE LastUpdateTimeTmp SET MaxLastModified ="
                            + " MaxLastModified * 1000000 WHERE MaxLastModified > 0")
                    db.execSQL(CREATE_LAST_UPDATE_TIME_SQL)
                    db.execSQL(
                            "INSERT INTO LastUpdateTime SELECT * FROM LastUpdateTimeTmp")
                    db.execSQL("DROP TABLE LastUpdateTimeTmp")
                } else if (newVersion == 6) {
                    // Version 6: Drop Sha1, Deleted, and LastModifiedUsec columns from
                    // Songs table.
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                    db.execSQL(
                            "CREATE TABLE Songs ("
                                    + "  SongId INTEGER PRIMARY KEY NOT NULL, "
                                    + "  Filename VARCHAR(256) NOT NULL, "
                                    + "  Artist VARCHAR(256) NOT NULL, "
                                    + "  Title VARCHAR(256) NOT NULL, "
                                    + "  Album VARCHAR(256) NOT NULL, "
                                    + "  TrackNumber INTEGER NOT NULL, "
                                    + "  Length INTEGER NOT NULL, "
                                    + "  Rating FLOAT NOT NULL)")
                    db.execSQL(
                            "INSERT INTO Songs SELECT SongId, Filename, Artist, Title,"
                                    + " Album, TrackNumber, Length, Rating FROM SongsTmp WHERE"
                                    + " Deleted = 0")
                    db.execSQL("DROP TABLE SongsTmp")
                } else if (newVersion == 7) {
                    // Version 7: Add ArtistSortKey and AlbumSortKey columns to
                    // ArtistAlbumStats.
                    db.execSQL(
                            "ALTER TABLE ArtistAlbumStats RENAME TO ArtistAlbumStatsTmp")
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL)
                    db.execSQL("INSERT INTO ArtistAlbumStats SELECT Artist, Album, NumSongs,"
                            + " Artist, Album FROM ArtistAlbumStatsTmp")
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL)
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL)
                    db.execSQL("DROP TABLE ArtistAlbumStatsTmp")
                } else if (newVersion == 8) {
                    // Version 8: Add CachedSongs table.
                    db.execSQL(CREATE_CACHED_SONGS_SQL)
                } else if (newVersion == 9) {
                    // Version 9: Add index on Songs.Filename.
                    db.execSQL("CREATE INDEX Filename ON Songs (Filename)")
                } else if (newVersion == 10) {
                    // Version 10: Add PendingPlaybackReports table.
                    db.execSQL(CREATE_PENDING_PLAYBACK_REPORTS_SQL)
                } else if (newVersion == 11) {
                    // Version 11: Change way too much stuff for AppEngine backend.
                    throw RuntimeException(
                            "Sorry, you need to delete everything and start over. :-(")
                } else if (newVersion == 12 || newVersion == 13) {
                    // Versions 12 and 13: Update sort ordering.
                    // It isn't actually safe to call updateStatsTables() like this, since
                    // it could be now be assuming a schema different than the one used in
                    // these old versions.
                    updateStatsTables(db)
                } else if (newVersion == 14) {
                    // Version 14: Add AlbumId, TrackGain, AlbumGain, and PeakAmp to Songs.
                    db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                    db.execSQL(
                            "CREATE TABLE Songs ("
                                    + "  SongId INTEGER PRIMARY KEY NOT NULL, "
                                    + "  Url VARCHAR(256) NOT NULL, "
                                    + "  CoverUrl VARCHAR(256) NOT NULL, "
                                    + "  Artist VARCHAR(256) NOT NULL, "
                                    + "  Title VARCHAR(256) NOT NULL, "
                                    + "  Album VARCHAR(256) NOT NULL, "
                                    + "  AlbumId VARCHAR(256) NOT NULL, "
                                    + "  TrackNumber INTEGER NOT NULL, "
                                    + "  DiscNumber INTEGER NOT NULL, "
                                    + "  Length INTEGER NOT NULL, "
                                    + "  TrackGain FLOAT NOT NULL, "
                                    + "  AlbumGain FLOAT NOT NULL, "
                                    + "  PeakAmp FLOAT NOT NULL, "
                                    + "  Rating FLOAT NOT NULL)")
                    db.execSQL(
                            "INSERT INTO Songs SELECT SongId, Url, CoverUrl, Artist,"
                                    + " Title, Album, '',     TrackNumber, DiscNumber, Length,"
                                    + " 0, 0, 0, Rating FROM SongsTmp")
                    db.execSQL("DROP TABLE SongsTmp")
                    // Sigh, I think I should've been recreating indexes after previous
                    // upgrades. From testing with the sqlite3 command, it looks like the
                    // old indexes will probably be updated to point at SongsTmp and then
                    // dropped.
                    db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL)
                    db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL)
                    db.execSQL(CREATE_SONGS_ALBUM_ID_INDEX_SQL)
                } else if (newVersion == 15) {
                    // Version 15: Add AlbumId to ArtistAlbumStats.
                    db.execSQL("DROP TABLE ArtistAlbumStats")
                    db.execSQL(
                            "CREATE TABLE ArtistAlbumStats ("
                                    + "  Artist VARCHAR(256) NOT NULL, "
                                    + "  Album VARCHAR(256) NOT NULL, "
                                    + "  AlbumId VARCHAR(256) NOT NULL, "
                                    + "  NumSongs INTEGER NOT NULL, "
                                    + "  ArtistSortKey VARCHAR(256) NOT NULL, "
                                    + "  AlbumSortKey VARCHAR(256) NOT NULL)")
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL)
                    db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL)
                    updateStatsTables(db)
                } else {
                    throw RuntimeException("Got request to upgrade database to unknown version "
                            + newVersion)
                }
            }
        }
        opener = DatabaseOpener(context, DATABASE_NAME, helper)

        // Get some info from the database in a background thread.
        object : AsyncTask<Void?, Void?, Void?>() {
            protected override fun doInBackground(vararg args: Void?): Void? {
                loadAggregateData(false)
                val db = opener.getDb()
                db.beginTransaction()
                try {
                    updateCachedSongs(db)
                } finally {
                    db.endTransaction()
                }
                return null
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        updater = DatabaseUpdater(opener)
        updaterThread = Thread(updater, "SongDatabase.DatabaseUpdater")
        updaterThread.start()
    }
}