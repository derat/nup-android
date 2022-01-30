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
import java.text.Normalizer
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
        """
        SELECT MIN(s.Artist), '' AS Album, '' AS AlbumId, COUNT(*), '' AS CoverFilename
          FROM Songs s
          JOIN CachedSongs cs ON(s.SongId = cs.SongId)
          GROUP BY s.ArtistNorm
        """.trimIndent(),
        null,
        false /* aggregateAlbums */,
        SongOrder.ARTIST,
        unsetAlbum = "",
    )

    suspend fun cachedAlbumsSortedAlphabetically(): List<StatsRow> = getSortedRows(
        """
        SELECT MIN(s.Artist), MIN(s.Album), s.AlbumId, COUNT(*), MIN(s.CoverFilename)
          FROM Songs s
          JOIN CachedSongs cs ON(s.SongId = cs.SongId)
          GROUP BY s.ArtistNorm, s.AlbumNorm, s.AlbumId
          ORDER BY s.AlbumNorm ASC, s.AlbumId ASC, 4 DESC
        """.trimIndent(),
        null,
        true /* aggregateAlbums */,
        SongOrder.ALBUM,
    )

    suspend fun cachedAlbumsByArtist(artist: String): List<StatsRow> = getSortedRows(
        """
        SELECT ? AS Artist, MIN(s.Album), s.AlbumId, COUNT(*), MIN(s.CoverFilename)
          FROM Songs s
          JOIN CachedSongs cs ON(s.SongId = cs.SongId)
          WHERE s.ArtistNorm = ?
          GROUP BY s.AlbumNorm, s.AlbumId
        """.trimIndent(),
        arrayOf(artist, normalizeForSearch(artist)),
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

            fun add(
                clause: String,
                arg: String?,
                substring: Boolean = false,
                normalize: Boolean = false
            ) {
                if (arg.isNullOrEmpty()) return

                selections.add(clause)
                if (arg == UNSET_STRING) {
                    selectionArgs.add("")
                } else {
                    val s = if (normalize) normalizeForSearch(arg) else arg
                    selectionArgs.add(if (substring) "%$s%" else s)
                }
            }

            fun addMulti(clause: String, args: List<String>) {
                selections.add(clause)
                selectionArgs.addAll(args)
            }

            fun addLiteral(clause: String) = selections.add(clause)

            fun whereClause() =
                if (selections.isEmpty()) ""
                else ("WHERE " + TextUtils.join(" AND ", selections))
        }

        val builder = QueryBuilder()
        builder.add("ArtistNorm LIKE ?", artist, substring, true)
        builder.add("TitleNorm LIKE ?", title, substring, true)
        builder.add("AlbumNorm LIKE ?", album, substring, true)
        builder.add("AlbumId = ?", albumId)
        builder.add("s.SongId = ?", if (songId >= 0) songId.toString() else null)
        builder.add("Rating >= ?", if (minRating >= 0.0) minRating.toString() else null)
        if (songIds != null && songIds.size > 0) {
            builder.addLiteral("s.SongId IN (" + songIds.joinToString(",") + ")")
        }
        if (artistPrefix != null) {
            builder.addMulti(
                "(Artist = ? OR Artist LIKE ?)",
                listOf(artistPrefix, "$artistPrefix %")
            )
        }
        val cachedJoin = if (onlyCached) "JOIN CachedSongs cs ON(s.SongId = cs.SongId)" else ""
        val order = if (shuffle) "RANDOM()" else "Album ASC, AlbumId ASC, Disc ASC, Track ASC"
        val query =
            """
            SELECT s.SongId, Artist, Title, Album, AlbumId, Filename, CoverFilename, Length,
              Track, Disc, TrackGain, AlbumGain, PeakAmp, Rating
              FROM Songs s $cachedJoin
              ${builder.whereClause()}
              ORDER BY $order
              LIMIT $MAX_QUERY_RESULTS
            """.trimIndent()
        val songs = mutableListOf<Song>()
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called

            Log.d(TAG, "Running \"$query\" with ${TextUtils.join(", ", builder.selectionArgs)}")
            val args = builder.selectionArgs.toArray<String>(arrayOf<String>())
            db.rawQuery(query, args).use {
                with(it) {
                    while (moveToNext()) {
                        songs.add(
                            Song(
                                id = getLong(0),
                                artist = getString(1),
                                title = getString(2),
                                album = getString(3),
                                albumId = getString(4),
                                filename = getString(5),
                                coverFilename = getString(6),
                                lengthSec = getFloat(7).toDouble(),
                                track = getInt(8),
                                disc = getInt(9),
                                trackGain = getFloat(10).toDouble(),
                                albumGain = getFloat(11).toDouble(),
                                peakAmp = getFloat(12).toDouble(),
                                rating = getFloat(13).toDouble(),
                            )
                        )
                    }
                }
            }
        }

        if (shuffle) spreadSongs(songs)

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

            val query = "SELECT SongId, StartTime FROM PendingPlaybackReports"
            db.rawQuery(query, null).use {
                while (it.moveToNext()) {
                    reports.add(PendingPlaybackReport(it.getLong(0), Date(it.getLong(1))))
                }
            }
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
            val prevStartNs = db.rawQuery("SELECT ServerTimeNsec FROM LastUpdateTime", null).use {
                it.moveToFirst()
                it.getLong(0)
            }

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
                                val artist = item.getString("artist")
                                val title = item.getString("title")
                                val album = item.getString("album")

                                val values = ContentValues(17)
                                values.put("SongId", songId)
                                values.put("Filename", item.getString("filename"))
                                values.put("CoverFilename", item.optString("coverFilename"))
                                values.put("Artist", artist)
                                values.put("Title", title)
                                values.put("Album", album)
                                values.put("AlbumId", item.optString("albumId"))
                                values.put("ArtistNorm", normalizeForSearch(artist))
                                values.put("TitleNorm", normalizeForSearch(title))
                                values.put("AlbumNorm", normalizeForSearch(album))
                                values.put("Track", item.getInt("track"))
                                values.put("Disc", item.getInt("disc"))
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
        // Normalized artist name to most-common original artist name.
        val canonArtists = mutableMapOf<String, String>()
        // Canonical artist name to album stats.
        val artistAlbums = mutableMapOf<String, MutableList<StatsRow>>()

        db.rawQuery(
            """
            SELECT Artist, Album, AlbumId, COUNT(*), MIN(CoverFilename)
              FROM Songs
              GROUP BY Artist, Album, AlbumId
              ORDER BY 4 DESC
            """.trimIndent(),
            null
        ).use {
            with(it) {
                while (moveToNext()) {
                    var origArtist = getString(0).ifEmpty { UNSET_STRING }
                    val canonArtist = canonArtists
                        .getOrPut(normalizeForSearch(origArtist), { origArtist })
                    var album = getString(1).ifEmpty { UNSET_STRING }
                    val albumId = getString(2)
                    val numSongs = getInt(3)
                    val coverFilename = getString(4)
                    val row = StatsRow(origArtist, album, albumId, numSongs, coverFilename)
                    artistAlbums.getOrPut(canonArtist, { mutableListOf() }).add(row)
                }
            }
        }

        db.delete("ArtistAlbumStats", null, null)
        for ((artist, rows) in artistAlbums) {
            val artistSortKey = getSongOrderKey(artist, SongOrder.ARTIST)
            for (row in rows) {
                val values = ContentValues(7)
                values.put("Artist", artist) // canonical
                values.put("Album", row.key.album)
                values.put("AlbumId", row.key.albumId)
                values.put("NumSongs", row.count)
                values.put("ArtistSortKey", artistSortKey)
                values.put("AlbumSortKey", getSongOrderKey(row.key.album, SongOrder.ALBUM))
                values.put("CoverFilename", row.coverFilename)
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
        val newSyncDate = db.rawQuery("SELECT LocalTimeNsec FROM LastUpdateTime", null).use {
            it.moveToFirst()
            val ns = it.getLong(0)
            if (ns > 0) Date(ns / (1000 * 1000)) else null
        }

        // If the song data didn't change and we've already loaded it, bail out early.
        if (!songsUpdated && aggregateDataLoaded) {
            listenerExecutor.execute { listener.onAggregateDataUpdate() }
            return
        }

        val newNumSongs = db.rawQuery("SELECT COUNT(*) FROM Songs", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

        // Avoid modifying the live members.
        val newArtistsAlpha = mutableListOf<StatsRow>()
        val newArtistsNumSongs = mutableListOf<StatsRow>()
        val newAlbumsAlpha = mutableListOf<StatsRow>()
        val newArtistAlbums = mutableMapOf<String, MutableList<StatsRow>>()

        db.rawQuery(
            """
            SELECT Artist, SUM(NumSongs)
              FROM ArtistAlbumStats
              GROUP BY Artist
              ORDER BY ArtistSortKey ASC
            """.trimIndent(),
            null
        ).use {
            while (it.moveToNext()) {
                newArtistsAlpha.add(StatsRow(it.getString(0), "", "", it.getInt(1)))
            }
        }

        db.rawQuery(
            """
            SELECT Artist, Album, AlbumId, SUM(NumSongs), MIN(CoverFilename)
              FROM ArtistAlbumStats
              GROUP BY Artist, Album, AlbumId
              ORDER BY AlbumSortKey ASC, AlbumId ASC, 4 DESC
            """.trimIndent(),
            null
        ).use {
            it.moveToFirst()
            readAlbumRows(it, newAlbumsAlpha, true /* aggregateAlbums */, newArtistAlbums)
        }

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

        db.rawQuery(
            """
            SELECT Name, Tags, MinRating, Unrated, FirstPlayed, LastPlayed,
                FirstTrack, Shuffle, Play
              FROM SearchPresets
              ORDER By SortKey ASC
            """.trimIndent(),
            null
        ).use {
            with(it) {
                while (moveToNext()) {
                    presets.add(
                        SearchPreset(
                            name = getString(0),
                            tags = getString(1),
                            minRating = getFloat(2).toDouble(),
                            unrated = getInt(3) != 0,
                            firstPlayed = getInt(4),
                            lastPlayed = getInt(5),
                            firstTrack = getInt(6) != 0,
                            shuffle = getInt(7) != 0,
                            play = getInt(8) != 0,
                        )
                    )
                }
            }
        }

        _searchPresets = presets
    }

    /** Get sorted results from the supplied query.
     *
     * @param query SQL query returning rows of (artist, album, album ID, num songs, cover filename)
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
            db.rawQuery(query, selectionArgs).use {
                it.moveToFirst()
                readAlbumRows(it, rows, aggregateAlbums, null, unsetArtist, unsetAlbum)
            }
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
     * @param cursor cursor for reading rows of (artist, album, album ID, count, cover filename)
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
            val coverFilename = cursor.getString(4)

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
                albums.add(StatsRow(artist, album, albumId, count, coverFilename))
                lastAlbum = albums.last()
            }

            // TODO: Consider aggregating by album ID so that we have the full count from
            // each album rather than just songs exactly matching the artist. This will
            // affect the count displayed when navigating from BrowseArtistsActivity to
            // BrowseAlbumsActivity.
            artistAlbums
                ?.getOrPut(artist.toLowerCase(), { mutableListOf() })
                ?.add(StatsRow(artist, album, albumId, count, coverFilename))

            cursor.moveToNext()
        }
    }

    companion object {
        private const val TAG = "SongDatabase"

        // Special user-visible string that we use to represent a blank field.
        // It should be something that doesn't legitimately appear in any fields,
        // so "[unknown]" is out (MusicBrainz uses it for unknown artists).
        public const val UNSET_STRING = "[unset]"

        public const val DATABASE_NAME = "NupSongs" // public for tests
        private const val MAX_QUERY_RESULTS = 250
        private const val SERVER_SONG_BATCH_SIZE = 100
    }

    init {
        opener = DatabaseOpener(
            context, DATABASE_NAME,
            object : SQLiteOpenHelper(context, DATABASE_NAME, null, getMaxSongDatabaseVersion()) {
                override fun onCreate(db: SQLiteDatabase) = createSongDatabase(db)
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    Log.d(TAG, "Upgrading from $oldVersion to $newVersion")
                    // Per https://developer.android.com/reference/kotlin/android/database/sqlite/SQLiteOpenHelper,
                    // "[onUpgrade] executes within a transaction. If an exception is thrown, all
                    // changes will automatically be rolled back." This function previously started
                    // its own transaction and threw if db.inTransaction() was initially true, so
                    // presumably something must've changed at some point.
                    for (nextVersion in (oldVersion + 1)..newVersion) {
                        upgradeSongDatabase(db, nextVersion)
                    }

                    // This is a bit of a hack, but if it looks like one or more upgrades dropped
                    // and recreated the ArtistAlbumStats table, regenerate it.
                    val numRows = db.rawQuery("SELECT COUNT(*) FROM ArtistAlbumStats", null).use {
                        it.moveToFirst()
                        it.getInt(0)
                    }
                    if (numRows == 0) updateArtistAlbumStats(db)
                }
            }
        )

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

/**
 * Decompose Unicode characters in [s], strip accents, and trim and lowercase it.
 *
 * This matches the server's query.Normalize() function.
 */
fun normalizeForSearch(s: String): String {
    return Normalizer
        .normalize(s, Normalizer.Form.NFKD)
        .replace("\\p{Mn}".toRegex(), "")
        .trim()
        .toLowerCase()
}
