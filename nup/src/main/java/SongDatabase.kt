/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.text.TextUtils
import android.util.Log
import java.util.Collections
import java.util.Date
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Wraps a SQLite database containing information about all known songs. */
class SongDatabase(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val listenerExecutor: Executor,
    private val cache: FileCache,
    private val downloader: Downloader,
    private val networkHelper: NetworkHelper,
    initDispatcher: CoroutineDispatcher = Dispatchers.IO, // for tests
) {
    private val opener: DatabaseOpener

    var aggregateDataLoaded = false
        private set
    var numSongs = 0
        private set
    var syncState = SyncState.IDLE
        private set
    var lastSyncDate: Date? = null
        private set

    // https://discuss.kotlinlang.org/t/exposing-a-mutable-member-as-immutable/6359
    private var _artistsSortedAlphabetically = mutableListOf<StatsRow>()
    private var _artistsSortedByNumSongs = mutableListOf<StatsRow>()
    private var _albumsSortedAlphabetically = mutableListOf<StatsRow>()
    private var _artistAlbums = mutableMapOf<String, MutableList<StatsRow>>()

    val artistsSortedAlphabetically: List<StatsRow> get() = _artistsSortedAlphabetically
    val artistsSortedByNumSongs: List<StatsRow> get() = _artistsSortedByNumSongs
    val albumsSortedAlphabetically: List<StatsRow> get() = _albumsSortedAlphabetically
    fun albumsByArtist(artist: String): List<StatsRow> =
        _artistAlbums[artist.toLowerCase()] ?: listOf()

    private var _searchPresets = mutableListOf<SearchPreset>()
    val searchPresets: List<SearchPreset> get() = _searchPresets
    val searchPresetsAutoplay get() = searchPresets.filter { it.play }

    enum class SyncState {
        IDLE,
        STARTING,
        UPDATING_PRESETS,
        UPDATING_SONGS,
        DELETING_SONGS,
        UPDATING_STATS,
    }

    /** Notified about changes to the database. */
    interface Listener {
        /** Called when [syncState] changes. */
        fun onSyncChange(state: SyncState, updatedSongs: Int)
        /** Called synchronization finishes (either successfully or not). */
        fun onSyncDone(success: Boolean, message: String)
        /** Called when aggregate stats have been updated. */
        fun onAggregateDataUpdate()
    }

    suspend fun cachedArtistsSortedAlphabetically(): List<StatsRow> = getSortedRows(
        "SELECT s.Artist, '' AS Album, '' AS AlbumId, COUNT(*) " +
            "FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "GROUP BY LOWER(TRIM(s.Artist))",
        null,
        false /* aggregateAlbums */,
        SongOrder.ARTIST,
        unsetAlbum = "",
    )

    suspend fun cachedAlbumsSortedAlphabetically(): List<StatsRow> = getSortedRows(
        "SELECT s.Artist, s.Album, s.AlbumId, COUNT(*) " +
            "FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "GROUP BY LOWER(TRIM(s.Artist)), LOWER(TRIM(s.Album)), s.AlbumId " +
            "ORDER BY LOWER(TRIM(s.Album)) ASC, s.AlbumId ASC, 4 DESC",
        null,
        true /* aggregateAlbums */,
        SongOrder.ALBUM,
    )

    suspend fun cachedAlbumsByArtist(artist: String): List<StatsRow> = getSortedRows(
        "SELECT ? AS Artist, s.Album, s.AlbumId, COUNT(*) " +
            "FROM Songs s " +
            "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " +
            "WHERE LOWER(s.Artist) = LOWER(?) " +
            "GROUP BY LOWER(TRIM(s.Album)), s.AlbumId",
        arrayOf(artist, artist),
        false /* aggregateAlbums */,
        SongOrder.ALBUM,
    )

    fun quit() {
        opener.close()
    }

    /**
     * Get songs matching the supplied criteria.
     *
     * @return matched songs, either grouped by album or shuffled
     */
    suspend fun query(
        artist: String? = null,
        artistPrefix: String? = null,
        title: String? = null,
        album: String? = null,
        albumId: String? = null,
        songId: Long = -1,
        songIds: List<Long>? = null,
        minRating: Double = -1.0,
        shuffle: Boolean = false,
        substring: Boolean = false,
        onlyCached: Boolean = false,
    ): List<Song> {
        class QueryBuilder {
            var selections = ArrayList<String>()
            var selectionArgs = ArrayList<String>()

            fun add(clause: String, selectionArg: String?, substring: Boolean) {
                if (selectionArg.isNullOrEmpty()) return

                selections.add(clause)
                if (selectionArg == UNSET_STRING) {
                    selectionArgs.add("")
                } else {
                    selectionArgs.add(if (substring) "%$selectionArg%" else selectionArg)
                }
            }

            fun addMulti(clause: String, args: List<String>) {
                selections.add(clause)
                selectionArgs.addAll(args)
            }

            fun addLiteral(clause: String) = selections.add(clause)

            // Get a WHERE clause (plus trailing space) if |selections| is non-empty, or just an
            // empty string otherwise.
            val whereClause: String
                get() = if (selections.isEmpty()) {
                    ""
                } else {
                    "WHERE " + TextUtils.join(" AND ", selections) + " "
                }
        }

        val builder = QueryBuilder()
        builder.add("Artist LIKE ?", artist, substring)
        builder.add("Title LIKE ?", title, substring)
        builder.add("Album LIKE ?", album, substring)
        builder.add("AlbumId = ?", albumId, false)
        builder.add("s.SongId = ?", if (songId >= 0) songId.toString() else null, false)
        builder.add("Rating >= ?", if (minRating >= 0.0) minRating.toString() else null, false)
        if (songIds != null && songIds.size > 0) {
            builder.addLiteral("s.SongId IN (" + songIds.joinToString(",") + ")")
        }
        if (artistPrefix != null) {
            builder.addMulti(
                "(Artist = ? OR Artist LIKE ?)",
                listOf(artistPrefix, "$artistPrefix %")
            )
        }
        val query = (
            "SELECT s.SongId, Artist, Title, Album, AlbumId, Filename, CoverFilename, Length, " +
                "TrackNumber, DiscNumber, TrackGain, AlbumGain, PeakAmp, Rating " +
                "FROM Songs s " +
                (if (onlyCached) "JOIN CachedSongs cs ON(s.SongId = cs.SongId) " else "") +
                builder.whereClause +
                "ORDER BY " +
                (
                    if (shuffle) "RANDOM() "
                    else "Album ASC, AlbumId ASC, DiscNumber ASC, TrackNumber ASC "
                    ) +
                "LIMIT " + MAX_QUERY_RESULTS
            )

        val songs = mutableListOf<Song>()
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called

            Log.d(TAG, "Running \"$query\" with ${TextUtils.join(", ", builder.selectionArgs)}")
            val cursor = db.rawQuery(
                query, builder.selectionArgs.toArray<String>(arrayOf<String>())
            )
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                val song = Song(
                    id = cursor.getLong(0),
                    artist = cursor.getString(1),
                    title = cursor.getString(2),
                    album = cursor.getString(3),
                    albumId = cursor.getString(4),
                    filename = cursor.getString(5),
                    coverFilename = cursor.getString(6),
                    lengthSec = cursor.getInt(7),
                    track = cursor.getInt(8),
                    disc = cursor.getInt(9),
                    trackGain = cursor.getFloat(10).toDouble(),
                    albumGain = cursor.getFloat(11).toDouble(),
                    peakAmp = cursor.getFloat(12).toDouble(),
                    rating = cursor.getFloat(13).toDouble(),
                )
                songs.add(song)
                cursor.moveToNext()
            }
            cursor.close()
        }
        return songs
    }

    /** Return songs corresponding to the supplied IDs.
     *
     * The order of the requested songs is preserved. If an index is supplied, it is adjusted
     * to point at the same song (if possible) if one or more songs were missing from the database.
     *
     * @return requested songs and a possibly-updated index
     */
    suspend fun getSongs(
        songIds: List<Long>,
        origIndex: Int = -1,
        onlyCached: Boolean = false
    ): Pair<List<Song>, Int> {
        val songMap = query(songIds = songIds, onlyCached = onlyCached).map { it.id to it }.toMap()
        val songs = mutableListOf<Song>()
        var index = origIndex // sigh
        songIds.forEachIndexed { idx, id ->
            val song = songMap.get(id)
            if (song != null) songs.add(song)
            else if (index > 0 && idx < index) index--
        }
        return Pair(songs, index)
    }

    /** Record the fact that a song has been successfully cached. */
    suspend fun handleSongCached(songId: Long) {
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called
            val values = ContentValues(1)
            values.put("SongId", songId)
            db.replace("CachedSongs", "", values)
        }
    }

    /** Record the fact that a song has been evicted from the cache. */
    suspend fun handleSongEvicted(songId: Long) {
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called
            db.delete("CachedSongs ", "SongId = ?", arrayOf(songId.toString()))
        }
    }

    /** Add an entry to the PendingPlaybackReports table. */
    suspend fun addPendingPlaybackReport(songId: Long, startDate: Date) {
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called
            val values = ContentValues(2)
            values.put("SongId", songId)
            values.put("StartTime", startDate.time)
            db.replace("PendingPlaybackReports", "", values)
        }
    }

    /** Remove an entry from the PendingPlaybackReports table. */
    suspend fun removePendingPlaybackReport(songId: Long, startDate: Date) {
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called
            db.delete(
                "PendingPlaybackReports ", "SongId = ? AND StartTime = ?",
                arrayOf(songId.toString(), startDate.time.toString())
            )
        }
    }

    /** Queued report of a song being played. */
    data class PendingPlaybackReport(var songId: Long, var startDate: Date)

    /** Get all pending playback reports from the PendingPlaybackReports table. */
    suspend fun allPendingPlaybackReports(): List<PendingPlaybackReport> {
        val reports = mutableListOf<PendingPlaybackReport>()
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called

            val cursor = db.rawQuery("SELECT SongId, StartTime FROM PendingPlaybackReports", null)
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                reports.add(
                    PendingPlaybackReport(cursor.getLong(0), Date(cursor.getLong(1)))
                )
                cursor.moveToNext()
            }
            cursor.close()
        }
        return reports
    }

    /** Synchronize the song list with the server. */
    suspend fun syncWithServer() {
        synchronized(this) {
            if (syncState != SyncState.IDLE) {
                notifySyncDone(false, context.getString(R.string.sync_already))
                return
            }
            setSyncState(SyncState.STARTING, 0)
        }

        try {
            if (!networkHelper.isNetworkAvailable) {
                notifySyncDone(false, context.getString(R.string.network_is_unavailable))
                return
            }
            opener.getDb() task@{ db ->
                if (db == null) {
                    notifySyncDone(false, "Database already closed")
                    return@task
                }

                val presetsRes = syncSearchPresets(db)
                if (!presetsRes.success) {
                    notifySyncDone(false, presetsRes.error!!)
                    return@task
                }
                loadSearchPresets(db)

                val songsRes = syncSongs(db)
                if (!songsRes.success) {
                    notifySyncDone(false, songsRes.error!!)
                    return@task
                }
                loadAggregateData(db, songsRes.updatedItems > 0)
                notifySyncDone(true, context.getString(R.string.sync_complete))
            }
        } finally {
            setSyncState(SyncState.IDLE, 0)
        }
    }

    /** Result of a call to [syncSongs] or [syncSearchPresets]. */
    data class SyncResult(val success: Boolean, val updatedItems: Int, val error: String?)

    /** Update local list of songs on behalf of [syncWithServer]. */
    private fun syncSongs(db: SQLiteDatabase): SyncResult {
        var updatedSongs = 0

        if (db.inTransaction()) throw RuntimeException("Already in transaction")
        db.beginTransaction()
        try {
            // Ask the server for the current time before we fetch anything.  We'll use this as the
            // starting point for the next sync, to handle the case where some songs in the server
            // are updated while we're doing this sync.
            val (startStr, error) = downloader.downloadString("/now")
            startStr ?: return SyncResult(false, 0, error!!)
            var startNs = startStr.toLong()

            // Start where we left off last time.
            val dbCursor = db.rawQuery("SELECT ServerTimeNsec FROM LastUpdateTime", null)
            dbCursor.moveToFirst()
            val prevStartNs = dbCursor.getLong(0)
            dbCursor.close()

            try {
                updatedSongs += syncSongUpdates(db, prevStartNs, false)
                updatedSongs += syncSongUpdates(db, prevStartNs, true)
            } catch (e: ServerException) {
                Log.e(TAG, e.message!!)
                return SyncResult(false, updatedSongs, e.message)
            }

            val values = ContentValues(2)
            values.put("LocalTimeNsec", Date().time * 1000 * 1000)
            values.put("ServerTimeNsec", startNs)
            db.update("LastUpdateTime", values, null, null)

            if (updatedSongs > 0) {
                setSyncState(SyncState.UPDATING_STATS, 0)
                updateArtistAlbumStats(db)
                updateCachedSongs(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return SyncResult(true, updatedSongs, null)
    }

    /** Update [syncState] and notify [listener]. */
    private fun setSyncState(state: SyncState, updatedSongs: Int) {
        syncState = state
        listenerExecutor.execute { listener.onSyncChange(state, updatedSongs) }
    }

    /** Notify [listener] that a sync attempt has finished. */
    private fun notifySyncDone(success: Boolean, message: String) {
        listenerExecutor.execute { listener.onSyncDone(success, message) }
    }

    /** Thrown by [syncSongUpdates] and [syncSearchPresets] if an error is encountered. */
    class ServerException(reason: String) : Exception(reason)

    /**
     * Get updated songs from the server on behalf of [syncSongs].
     *
     * @return number of updated songs
     */
    @Throws(ServerException::class)
    private fun syncSongUpdates(
        db: SQLiteDatabase,
        prevStartTimeNsec: Long,
        deleted: Boolean,
    ): Int {
        // The server breaks its results up into batches instead of sending us a bunch of songs
        // at once, so use the cursor that it returns to start in the correct place in the next
        // request.
        var serverCursor = ""
        var numUpdates = 0
        fetch@ while (true) {
            var path = "/export?type=song" +
                "&omit=plays,sha1" +
                "&minLastModifiedNsec=$prevStartTimeNsec" +
                "&max=$SERVER_SONG_BATCH_SIZE"
            if (!serverCursor.isEmpty()) path += "&cursor=$serverCursor"
            if (deleted) path += "&deleted=1"

            val (response, error) = downloader.downloadString(path)
            response ?: throw ServerException(error!!)

            try {
                // The trim() is necessary here; more() returns true for trailing whitespace.
                val tokener = JSONTokener(response.trim())
                while (true) {
                    // If we reached the end of the response without getting a cursor, we're done.
                    if (!tokener.more()) break@fetch

                    val item = tokener.nextValue()
                    when {
                        item is JSONObject -> {
                            val songId = item.getLong("songId")
                            if (deleted) {
                                Log.d(TAG, "Deleting song $songId")
                                db.delete("Songs", "SongId = ?", arrayOf(songId.toString()))
                            } else {
                                val values = ContentValues(14)
                                values.put("SongId", songId)
                                values.put("Filename", item.getString("filename"))
                                values.put("CoverFilename", item.optString("coverFilename"))
                                values.put("Artist", item.getString("artist"))
                                values.put("Title", item.getString("title"))
                                values.put("Album", item.getString("album"))
                                values.put("AlbumId", item.optString("albumId"))
                                values.put("TrackNumber", item.getInt("track"))
                                values.put("DiscNumber", item.getInt("disc"))
                                values.put("Length", item.getDouble("length"))
                                values.put("TrackGain", item.optDouble("trackGain", 0.0))
                                values.put("AlbumGain", item.optDouble("albumGain", 0.0))
                                values.put("PeakAmp", item.optDouble("peakAmp", 0.0))
                                values.put("Rating", item.getDouble("rating"))
                                db.replace("Songs", "", values)
                            }
                            numUpdates++
                        }
                        item is String -> {
                            serverCursor = item
                            if (tokener.more()) throw ServerException("Trailing data after cursor")
                            break
                        }
                        else -> throw ServerException("Got unexpected ${item.javaClass.name}")
                    }
                }
            } catch (e: JSONException) {
                throw ServerException("Couldn't parse response: $e")
            }

            setSyncState(
                if (deleted) SyncState.DELETING_SONGS else SyncState.UPDATING_SONGS,
                numUpdates,
            )
        }

        setSyncState(
            if (deleted) SyncState.DELETING_SONGS else SyncState.UPDATING_SONGS,
            numUpdates,
        )
        return numUpdates
    }

    /** Fetch and save all search presets from the server. */
    private fun syncSearchPresets(db: SQLiteDatabase): SyncResult {
        setSyncState(SyncState.UPDATING_PRESETS, 0)
        val (response, error) = downloader.downloadString("/presets")
        response ?: return SyncResult(false, 0, error!!)

        if (db.inTransaction()) throw RuntimeException("Already in transaction")
        db.beginTransaction()
        try {
            db.delete("SearchPresets", null, null)

            val timeEnumToSec = { v: Int ->
                when (v) {
                    0 -> 0 // unset
                    1 -> 86400 // one day
                    2 -> 604800 // one week
                    3 -> 2592000 // one month
                    4 -> 7776000 // three months
                    5 -> 15552000 // six months
                    6 -> 31536000 // one year
                    7 -> 94608000 // three years
                    8 -> 157680000 // five years
                    else -> throw ServerException("Invalid time enum value $v")
                }
            }

            var numPresets = 0
            for (
                o in JSONArray(JSONTokener(response)).iterator<JSONObject>()
                    .asSequence().toList()
            ) {
                val values = ContentValues(10)
                values.put("SortKey", numPresets)
                values.put("Name", o.getString("name"))
                values.put("Tags", o.optString("tags"))
                values.put(
                    "MinRating",
                    // Convert number of stars in [1, 5] to rating in [0.0, 1.0].
                    if (o.has("minRating")) (o.getInt("minRating").coerceIn(1, 5) - 1) / 4.0
                    else -1.0
                )
                values.put("Unrated", o.optBoolean("unrated"))
                values.put("FirstPlayed", timeEnumToSec(o.optInt("firstPlayed")))
                values.put("LastPlayed", timeEnumToSec(o.optInt("lastPlayed")))
                values.put("FirstTrack", o.optBoolean("firstTrack"))
                values.put("Shuffle", o.optBoolean("shuffle"))
                values.put("Play", o.optBoolean("play"))
                db.replace("SearchPresets", "", values)
                numPresets++
            }
            db.setTransactionSuccessful()
            return SyncResult(true, numPresets, null)
        } catch (e: JSONException) {
            return SyncResult(false, 0, "Couldn't parse response: $e")
        } finally {
            db.endTransaction()
        }
    }

    /** Rebuild the artistAlbumStats table from the Songs table. */
    private fun updateArtistAlbumStats(db: SQLiteDatabase) {
        // Lowercased artist name to the first row that we saw in its original case.
        val artistCaseMap = mutableMapOf<String, String>()

        // Artist name to map from album to number of songs.
        val artistMap = mutableMapOf<String, MutableMap<StatsKey, Int>>()

        val cursor = db.rawQuery(
            "SELECT Artist, Album, AlbumId, COUNT(*) " +
                "FROM Songs " +
                "GROUP BY Artist, Album, AlbumId",
            null
        )
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            // Normalize the artist case so that we'll still group all their songs together
            // even if some use different capitalization.
            var origArtist = cursor.getString(0)
            if (origArtist.isEmpty()) origArtist = UNSET_STRING
            val artist = artistCaseMap.getOrPut(origArtist.toLowerCase(), { origArtist })

            var album = cursor.getString(1)
            if (album.isEmpty()) album = UNSET_STRING

            val albumId = cursor.getString(2)
            val numSongs = cursor.getInt(3)

            val key = StatsKey(origArtist, album, albumId)
            artistMap.getOrPut(artist, { mutableMapOf() })[key] = numSongs
            cursor.moveToNext()
        }
        cursor.close()

        db.delete("ArtistAlbumStats", null, null)
        for ((artist, albums) in artistMap) {
            val artistSortKey = getSongOrderKey(artist, SongOrder.ARTIST)
            for (key in albums.keys) {
                val values = ContentValues(6)
                values.put("Artist", artist)
                values.put("Album", key.album)
                values.put("AlbumId", key.albumId)
                values.put("NumSongs", albums[key])
                values.put("ArtistSortKey", artistSortKey)
                values.put("AlbumSortKey", getSongOrderKey(key.album, SongOrder.ALBUM))
                db.insert("ArtistAlbumStats", "", values)
            }
        }
    }

    /** Repopulate the CachedSongs table with fully-downloaded songs from the cache. */
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
        Log.d(TAG, "Learned about $numSongs cached song(s)")
    }

    /** Update members containing aggregate data from the database. */
    private fun loadAggregateData(db: SQLiteDatabase, songsUpdated: Boolean) {
        var cursor = db.rawQuery("SELECT LocalTimeNsec FROM LastUpdateTime", null)
        cursor.moveToFirst()
        val newSyncDate =
            if (cursor.getLong(0) > 0) Date(cursor.getLong(0) / (1000 * 1000)) else null
        cursor.close()

        // If the song data didn't change and we've already loaded it, bail out early.
        if (!songsUpdated && aggregateDataLoaded) {
            listenerExecutor.execute { listener.onAggregateDataUpdate() }
            return
        }

        cursor = db.rawQuery("SELECT COUNT(*) FROM Songs", null)
        cursor.moveToFirst()
        val newNumSongs = cursor.getInt(0)
        cursor.close()

        // Avoid modifying the live members.
        val newArtistsAlpha = mutableListOf<StatsRow>()
        val newArtistsNumSongs = mutableListOf<StatsRow>()
        val newAlbumsAlpha = mutableListOf<StatsRow>()
        val newArtistAlbums = mutableMapOf<String, MutableList<StatsRow>>()

        cursor = db.rawQuery(
            "SELECT Artist, SUM(NumSongs) " +
                "FROM ArtistAlbumStats " +
                "GROUP BY LOWER(TRIM(Artist)) " +
                "ORDER BY ArtistSortKey ASC",
            null
        )
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            newArtistsAlpha.add(StatsRow(cursor.getString(0), "", "", cursor.getInt(1)))
            cursor.moveToNext()
        }
        cursor.close()

        cursor = db.rawQuery(
            "SELECT Artist, Album, AlbumId, SUM(NumSongs) " +
                "FROM ArtistAlbumStats " +
                "GROUP BY LOWER(TRIM(Artist)), LOWER(TRIM(Album)), AlbumId " +
                "ORDER BY AlbumSortKey ASC, AlbumId ASC, 4 DESC",
            null
        )
        cursor.moveToFirst()
        readAlbumRows(cursor, newAlbumsAlpha, true /* aggregateAlbums */, newArtistAlbums)
        cursor.close()

        newArtistsNumSongs.addAll(newArtistsAlpha)
        Collections.sort(newArtistsNumSongs) { a, b -> b.count - a.count }

        lastSyncDate = newSyncDate
        numSongs = newNumSongs
        _artistsSortedAlphabetically = newArtistsAlpha
        _albumsSortedAlphabetically = newAlbumsAlpha
        _artistsSortedByNumSongs = newArtistsNumSongs
        _artistAlbums = newArtistAlbums
        aggregateDataLoaded = true

        listenerExecutor.execute { listener.onAggregateDataUpdate() }
    }

    /* Loads search presets that were previously synced from the server. */
    private fun loadSearchPresets(db: SQLiteDatabase) {
        val presets = mutableListOf<SearchPreset>()

        val cursor = db.rawQuery(
            "SELECT Name, Tags, MinRating, Unrated, FirstPlayed, LastPlayed, " +
                "FirstTrack, Shuffle, Play FROM SearchPresets ORDER By SortKey ASC",
            null
        )
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            presets.add(
                SearchPreset(
                    name = cursor.getString(0),
                    tags = cursor.getString(1),
                    minRating = cursor.getFloat(2).toDouble(),
                    unrated = cursor.getInt(3) != 0,
                    firstPlayed = cursor.getInt(4),
                    lastPlayed = cursor.getInt(5),
                    firstTrack = cursor.getInt(6) != 0,
                    shuffle = cursor.getInt(7) != 0,
                    play = cursor.getInt(8) != 0,
                )
            )
            cursor.moveToNext()
        }
        cursor.close()

        _searchPresets = presets
    }

    /** Get sorted results from the supplied query.
     *
     * @param query SQL query returning rows of (artist, album, album ID, num songs)
     * @param selectionArgs positional parameters for [query]
     * @param aggregateAlbums group rows by (album, album ID)
     * @param order sort order for rows
     * @param unsetArtist string to use in place of empty artist values
     * @param unsetAlbum string to use in place of empty album values
     */
    private suspend fun getSortedRows(
        query: String,
        selectionArgs: Array<String>?,
        aggregateAlbums: Boolean,
        order: SongOrder,
        unsetArtist: String = UNSET_STRING,
        unsetAlbum: String = UNSET_STRING,
    ): List<StatsRow> {
        val rows = mutableListOf<StatsRow>()
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called
            val cursor = db.rawQuery(query, selectionArgs)
            cursor.moveToFirst()
            readAlbumRows(cursor, rows, aggregateAlbums, null, unsetArtist, unsetAlbum)
            cursor.close()
        }
        sortStatsRows(rows, order)
        return rows
    }

    /** Iterate over [cursor] and get per-album stats.
     *
     * The caller is responsible for performing the query and positioning and closing [cursor].
     *
     * If [aggregateAlbums] is true, rows should ordered by ascending album and descending count
     * (so that the most-represented artist can be identified for each album).
     *
     * @param cursor cursor for reading rows of (artist, album, album ID, count)
     * @param albums destination for per-album stats
     * @param aggregateAlbums group rows by (album, album ID)
     * @param artistAlbums optional destination for per-artist, per-album stats
     * @param unsetArtist string to use in place of empty artist values
     * @param unsetAlbum string to use in place of empty album values
     */
    private fun readAlbumRows(
        cursor: Cursor,
        albums: MutableList<StatsRow>,
        aggregateAlbums: Boolean,
        artistAlbums: MutableMap<String, MutableList<StatsRow>>?,
        unsetArtist: String = UNSET_STRING,
        unsetAlbum: String = UNSET_STRING,
    ) {
        var lastAlbum: StatsRow? = null // last row added to |albums|

        while (!cursor.isAfterLast) {
            var artist = cursor.getString(0)
            if (artist.isEmpty()) artist = unsetArtist
            var album = cursor.getString(1)
            if (album.isEmpty()) album = unsetAlbum
            val albumId = cursor.getString(2)
            val count = cursor.getInt(3)

            // TODO: Decide if we should special-case songs with missing albums here. Right now they
            // all get lumped together, but that's arguably better than creating a separate row for
            // each artist.
            if (aggregateAlbums &&
                lastAlbum != null &&
                lastAlbum.key.albumId == albumId &&
                lastAlbum.key.album.trim().toLowerCase() == album.trim().toLowerCase()
            ) {
                lastAlbum.count += count
                lastAlbum.key.artist += ", " + artist
            } else {
                albums.add(StatsRow(artist, album, albumId, count))
                lastAlbum = albums.last()
            }

            // TODO: Consider aggregating by album ID so that we have the full count from
            // each album rather than just songs exactly matching the artist. This will
            // affect the count displayed when navigating from BrowseArtistsActivity to
            // BrowseAlbumsActivity.
            artistAlbums?.getOrPut(artist.toLowerCase(), { mutableListOf() })
                ?.add(StatsRow(artist, album, albumId, count))

            cursor.moveToNext()
        }
    }

    companion object {
        private const val TAG = "SongDatabase"

        // Special user-visible string that we use to represent a blank field.
        // It should be something that doesn't legitimately appear in any fields,
        // so "[unknown]" is out (MusicBrainz uses it for unknown artists).
        public const val UNSET_STRING = "[unset]"

        private const val DATABASE_NAME = "NupSongs"
        private const val MAX_QUERY_RESULTS = 250
        private const val DATABASE_VERSION = 17
        private const val SERVER_SONG_BATCH_SIZE = 100

        // IMPORTANT NOTE: When updating any of these, you must replace all previous references in
        // upgradeFromPreviousVersion() with the hardcoded older version of the string.
        private const val CREATE_SONGS_SQL =
            "CREATE TABLE Songs (" +
                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                "Filename VARCHAR(256) NOT NULL, " +
                "CoverFilename VARCHAR(256) NOT NULL, " +
                "Artist VARCHAR(256) NOT NULL, " +
                "Title VARCHAR(256) NOT NULL, " +
                "Album VARCHAR(256) NOT NULL, " +
                "AlbumId VARCHAR(256) NOT NULL, " +
                "TrackNumber INTEGER NOT NULL, " +
                "DiscNumber INTEGER NOT NULL, " +
                "Length INTEGER NOT NULL, " + // TODO: Why is this an int?
                "TrackGain FLOAT NOT NULL, " +
                "AlbumGain FLOAT NOT NULL, " +
                "PeakAmp FLOAT NOT NULL, " +
                "Rating FLOAT NOT NULL)"
        private const val CREATE_SONGS_ARTIST_INDEX_SQL = "CREATE INDEX Artist ON Songs (Artist)"
        private const val CREATE_SONGS_ALBUM_INDEX_SQL = "CREATE INDEX Album ON Songs (Album)"
        private const val CREATE_SONGS_ALBUM_ID_INDEX_SQL =
            "CREATE INDEX AlbumId ON Songs (AlbumId)"

        private const val CREATE_ARTIST_ALBUM_STATS_SQL =
            "CREATE TABLE ArtistAlbumStats (" +
                "Artist VARCHAR(256) NOT NULL, " +
                "Album VARCHAR(256) NOT NULL, " +
                "AlbumId VARCHAR(256) NOT NULL, " +
                "NumSongs INTEGER NOT NULL, " +
                "ArtistSortKey VARCHAR(256) NOT NULL, " +
                "AlbumSortKey VARCHAR(256) NOT NULL)"
        private const val CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL =
            "CREATE INDEX ArtistSortKey ON ArtistAlbumStats (ArtistSortKey)"
        private const val CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL =
            "CREATE INDEX AlbumSortKey ON ArtistAlbumStats (AlbumSortKey)"

        private const val CREATE_LAST_UPDATE_TIME_SQL = (
            "CREATE TABLE LastUpdateTime (" +
                "LocalTimeNsec INTEGER NOT NULL, " +
                "ServerTimeNsec INTEGER NOT NULL)"
            )
        private const val INSERT_LAST_UPDATE_TIME_SQL =
            "INSERT INTO LastUpdateTime (LocalTimeNsec, ServerTimeNsec) VALUES(0, 0)"

        private const val CREATE_CACHED_SONGS_SQL =
            "CREATE TABLE CachedSongs (SongId INTEGER PRIMARY KEY NOT NULL)"

        private const val CREATE_PENDING_PLAYBACK_REPORTS_SQL =
            "CREATE TABLE PendingPlaybackReports (" +
                "SongId INTEGER NOT NULL, " +
                "StartTime INTEGER NOT NULL, " +
                "PRIMARY KEY (SongId, StartTime))"

        private const val CREATE_SEARCH_PRESETS_SQL =
            "CREATE TABLE SearchPresets (" +
                "SortKey INTEGER NOT NULL, " + // 0-based index in array from server
                "Name VARCHAR(256) NOT NULL, " +
                "Tags VARCHAR(256) NOT NULL, " +
                "MinRating FLOAT NOT NULL, " + // [0.0, 1.0], -1 for unset
                "Unrated BOOLEAN NOT NULL, " +
                "FirstPlayed INTEGER NOT NULL, " + // seconds before now, 0 for unset
                "LastPlayed INTEGER NOT NULL, " + // seconds before now, 0 for unset
                "FirstTrack BOOLEAN NOT NULL, " +
                "Shuffle BOOLEAN NOT NULL, " +
                "Play BOOLEAN NOT NULL)"
    }

    init {
        val helper: SQLiteOpenHelper =
            object : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
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
                    db.execSQL(CREATE_SEARCH_PRESETS_SQL)
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    Log.d(TAG, "Upgrading from $oldVersion to $newVersion")
                    // Per https://developer.android.com/reference/kotlin/android/database/sqlite/SQLiteOpenHelper,
                    // "[onUpgrade] executes within a transaction. If an exception is thrown, all
                    // changes will automatically be rolled back." This function previously started
                    // its own transaction and threw if db.inTransaction() was initially true, so
                    // presumably something must've changed at some point.
                    for (nextVersion in (oldVersion + 1)..newVersion) {
                        upgradeFromPreviousVersion(db, nextVersion)
                    }
                }

                // Upgrade the database from the version before [newVersion] to [newVersion].
                // The only reason I'm keeping the old upgrade steps is for reference when
                // writing new ones.
                private fun upgradeFromPreviousVersion(db: SQLiteDatabase, newVersion: Int) {
                    if (newVersion == 2) {
                        // Version 2: Create LastUpdateTime table.
                        db.execSQL(
                            "CREATE TABLE LastUpdateTime (" +
                                "Timestamp INTEGER NOT NULL, " +
                                "MaxLastModified INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO LastUpdateTime " +
                                "(Timestamp, MaxLastModified) " +
                                "VALUES(0, 0)"
                        )
                    } else if (newVersion == 3) {
                        // Version 3: Add Songs.Deleted column.
                        db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                        db.execSQL(
                            "CREATE TABLE Songs (" +
                                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                                "Sha1 CHAR(40) NOT NULL, " +
                                "Filename VARCHAR(256) NOT NULL, " +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Title VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "TrackNumber INTEGER NOT NULL, " +
                                "Length INTEGER NOT NULL, " +
                                "Rating FLOAT NOT NULL, " +
                                "Deleted BOOLEAN NOT NULL, " +
                                "LastModifiedUsec INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO Songs " +
                                "SELECT SongId, Sha1, Filename, Artist, Title, Album, " +
                                "TrackNumber, Length, Rating, 0, LastModified " +
                                "FROM SongsTmp"
                        )
                        db.execSQL("DROP TABLE SongsTmp")
                    } else if (newVersion == 4) {
                        // Version 4: Create ArtistAlbumStats table and indexes on Songs.Artist
                        // and Songs.Album.
                        db.execSQL(
                            "CREATE TABLE ArtistAlbumStats (" +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "NumSongs INTEGER NOT NULL)"
                        )
                        db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL)
                        db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL)
                    } else if (newVersion == 5) {
                        // Version 5: LastModified -> LastModifiedUsec (seconds to
                        // microseconds).
                        db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                        db.execSQL("UPDATE SongsTmp SET LastModified = LastModified * 1000000")
                        db.execSQL(
                            "CREATE TABLE Songs (" +
                                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                                "Sha1 CHAR(40) NOT NULL, " +
                                "Filename VARCHAR(256) NOT NULL, " +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Title VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "TrackNumber INTEGER NOT NULL, " +
                                "Length INTEGER NOT NULL, " +
                                "Rating FLOAT NOT NULL, " +
                                "Deleted BOOLEAN NOT NULL, " +
                                "LastModifiedUsec INTEGER NOT NULL)"
                        )
                        db.execSQL("INSERT INTO Songs SELECT * FROM SongsTmp")
                        db.execSQL("DROP TABLE SongsTmp")
                        db.execSQL("ALTER TABLE LastUpdateTime RENAME TO LastUpdateTimeTmp")
                        db.execSQL(
                            "UPDATE LastUpdateTimeTmp SET MaxLastModified = " +
                                "MaxLastModified * 1000000 WHERE MaxLastModified > 0"
                        )
                        db.execSQL(CREATE_LAST_UPDATE_TIME_SQL)
                        db.execSQL(
                            "INSERT INTO LastUpdateTime SELECT * FROM LastUpdateTimeTmp"
                        )
                        db.execSQL("DROP TABLE LastUpdateTimeTmp")
                    } else if (newVersion == 6) {
                        // Version 6: Drop Sha1, Deleted, and LastModifiedUsec columns from
                        // Songs table.
                        db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                        db.execSQL(
                            "CREATE TABLE Songs (" +
                                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                                "Filename VARCHAR(256) NOT NULL, " +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Title VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "TrackNumber INTEGER NOT NULL, " +
                                "Length INTEGER NOT NULL, " +
                                "Rating FLOAT NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO Songs SELECT SongId, Filename, Artist, Title, " +
                                " Album, TrackNumber, Length, Rating FROM SongsTmp WHERE" +
                                " Deleted = 0"
                        )
                        db.execSQL("DROP TABLE SongsTmp")
                    } else if (newVersion == 7) {
                        // Version 7: Add ArtistSortKey and AlbumSortKey columns to
                        // ArtistAlbumStats.
                        db.execSQL(
                            "ALTER TABLE ArtistAlbumStats RENAME TO ArtistAlbumStatsTmp"
                        )
                        db.execSQL(CREATE_ARTIST_ALBUM_STATS_SQL)
                        db.execSQL(
                            "INSERT INTO ArtistAlbumStats SELECT Artist, Album, NumSongs, " +
                                "Artist, Album FROM ArtistAlbumStatsTmp"
                        )
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
                            "Sorry, you need to delete everything and start over. :-("
                        )
                    } else if (newVersion == 12 || newVersion == 13) {
                        // Versions 12 and 13: Update sort ordering.
                        // It isn't actually safe to call updateArtistAlbumStats() like this, since
                        // it could be now be assuming a schema different than the one used in
                        // these old versions.
                        updateArtistAlbumStats(db)
                    } else if (newVersion == 14) {
                        // Version 14: Add AlbumId, TrackGain, AlbumGain, and PeakAmp to Songs.
                        db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                        db.execSQL(
                            "CREATE TABLE Songs (" +
                                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                                "Url VARCHAR(256) NOT NULL, " +
                                "CoverUrl VARCHAR(256) NOT NULL, " +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Title VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "AlbumId VARCHAR(256) NOT NULL, " +
                                "TrackNumber INTEGER NOT NULL, " +
                                "DiscNumber INTEGER NOT NULL, " +
                                "Length INTEGER NOT NULL, " +
                                "TrackGain FLOAT NOT NULL, " +
                                "AlbumGain FLOAT NOT NULL, " +
                                "PeakAmp FLOAT NOT NULL, " +
                                "Rating FLOAT NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO Songs " +
                                "SELECT SongId, Url, CoverUrl, Artist, Title, Album, '', " +
                                "TrackNumber, DiscNumber, Length, 0, 0, 0, Rating " +
                                "FROM SongsTmp"
                        )
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
                            "CREATE TABLE ArtistAlbumStats (" +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "AlbumId VARCHAR(256) NOT NULL, " +
                                "NumSongs INTEGER NOT NULL, " +
                                "ArtistSortKey VARCHAR(256) NOT NULL, " +
                                "AlbumSortKey VARCHAR(256) NOT NULL)"
                        )
                        db.execSQL(CREATE_ARTIST_ALBUM_STATS_ARTIST_SORT_KEY_INDEX_SQL)
                        db.execSQL(CREATE_ARTIST_ALBUM_STATS_ALBUM_SORT_KEY_INDEX_SQL)
                        updateArtistAlbumStats(db)
                    } else if (newVersion == 16) {
                        // Version 16: Replace Url and CoverUrl with Filename and CoverFilename.
                        db.execSQL("ALTER TABLE Songs RENAME TO SongsTmp")
                        db.execSQL(
                            "CREATE TABLE Songs (" +
                                "SongId INTEGER PRIMARY KEY NOT NULL, " +
                                "Filename VARCHAR(256) NOT NULL, " +
                                "CoverFilename VARCHAR(256) NOT NULL, " +
                                "Artist VARCHAR(256) NOT NULL, " +
                                "Title VARCHAR(256) NOT NULL, " +
                                "Album VARCHAR(256) NOT NULL, " +
                                "AlbumId VARCHAR(256) NOT NULL, " +
                                "TrackNumber INTEGER NOT NULL, " +
                                "DiscNumber INTEGER NOT NULL, " +
                                "Length INTEGER NOT NULL, " +
                                "TrackGain FLOAT NOT NULL, " +
                                "AlbumGain FLOAT NOT NULL, " +
                                "PeakAmp FLOAT NOT NULL, " +
                                "Rating FLOAT NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO Songs " +
                                "SELECT SongId, '', '', Artist, Title, Album, AlbumId, " +
                                "TrackNumber, DiscNumber, Length, TrackGain, AlbumGain, " +
                                "PeakAmp, Rating FROM SongsTmp"
                        )
                        db.execSQL("DROP TABLE SongsTmp")
                        db.execSQL(CREATE_SONGS_ARTIST_INDEX_SQL)
                        db.execSQL(CREATE_SONGS_ALBUM_INDEX_SQL)
                        db.execSQL(CREATE_SONGS_ALBUM_ID_INDEX_SQL)
                        // I'm deeming it too hard to convert URLs to filenames,
                        // so force a full sync.
                        db.execSQL(
                            "UPDATE LastUpdateTime SET LocalTimeNsec = 0, ServerTimeNsec = 0"
                        )
                    } else if (newVersion == 17) {
                        // Version 17: Add SearchPresets table.
                        db.execSQL(CREATE_SEARCH_PRESETS_SQL)
                    } else {
                        throw RuntimeException(
                            "Got request to upgrade database to unknown version $newVersion"
                        )
                    }
                }
            }
        opener = DatabaseOpener(context, DATABASE_NAME, helper)

        // Get some info from the database in a background thread.
        scope.launch(initDispatcher) {
            opener.getDb() task@{ db ->
                if (db == null) return@task // quit() already called

                loadSearchPresets(db)
                loadAggregateData(db, false)

                if (db.inTransaction()) throw RuntimeException("Already in transaction")
                db.beginTransaction()
                try {
                    updateCachedSongs(db)
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
}
