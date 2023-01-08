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
import java.time.Instant
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
    private val initDispatcher: CoroutineDispatcher = Dispatchers.IO, // for tests
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
    // Each artist's albums are sorted by ascending date.
    fun albumsByArtist(artist: String): List<StatsRow> =
        _artistAlbums[artist.lowercase()] ?: listOf()

    private var _searchPresets = mutableListOf<SearchPreset>()
    val searchPresets: List<SearchPreset> get() = _searchPresets
    val searchPresetsAutoplay get() = searchPresets.filter { it.play }

    enum class SyncState {
        IDLE,
        STARTING,
        FETCHING_USER_INFO,
        UPDATING_PRESETS,
        UPDATING_SONGS,
        DELETING_SONGS,
        UPDATING_STATS,
    }

    /** Notified about changes to the database. */
    interface Listener {
        /** Called when [syncState] changes. */
        fun onSyncChange(state: SyncState, updatedSongs: Int)
        /** Called when [userInfo] has been fetched from the server. */
        fun onUserInfoFetch(userInfo: UserInfo)
        /** Called synchronization finishes (either successfully or not). */
        fun onSyncDone(success: Boolean, message: String)
        /** Called when aggregate stats have been updated. */
        fun onAggregateDataUpdate()
    }

    /** Get the current database version for testing. */
    suspend fun getVersion(): Int? {
        // TODO: What's the right way to do this in a single line in Kotlin?
        var version: Int? = 0
        opener.getDb() { version = it?.getVersion() }
        return version
    }

    suspend fun cachedArtistsSortedAlphabetically(): List<StatsRow> = getSortedRows(
        """
        SELECT MIN(s.Artist), '' AS Album, '' AS AlbumId, COUNT(*), '' AS CoverFilename, '' AS Date
          FROM Songs s
          JOIN CachedSongs cs ON(s.SongId = cs.SongId)
          GROUP BY s.ArtistNorm
        """.trimIndent(),
        null,
        false /* aggregateAlbums */,
        StatsOrder.ARTIST,
        unsetAlbum = "",
    )

    suspend fun cachedAlbumsSortedAlphabetically(): List<StatsRow> = getSortedRows(
        """
        SELECT MIN(s.Artist), MIN(s.Album), s.AlbumId, COUNT(*), MIN(s.CoverFilename), MIN(s.Date)
          FROM Songs s
          JOIN CachedSongs cs ON(s.SongId = cs.SongId)
          GROUP BY s.ArtistNorm, s.AlbumNorm, s.AlbumId
          ORDER BY s.AlbumNorm ASC, s.AlbumId ASC, 4 DESC
        """.trimIndent(),
        null,
        true /* aggregateAlbums */,
        StatsOrder.ALBUM,
    )

    suspend fun cachedAlbumsByArtist(artist: String): List<StatsRow> = getSortedRows(
        """
        SELECT ? AS Artist, MIN(s.Album), s.AlbumId, COUNT(*), MIN(s.CoverFilename), MIN(s.Date)
          FROM Songs s
          JOIN CachedSongs cs ON(s.SongId = cs.SongId)
          WHERE s.ArtistNorm = ?
          GROUP BY s.AlbumNorm, s.AlbumId
        """.trimIndent(),
        arrayOf(artist, normalizeForSearch(artist)),
        false /* aggregateAlbums */,
        StatsOrder.DATE,
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
        minDate: String? = null, // ISO-8601, i.e. Instant.toString()
        maxDate: String? = null, // ISO-8601, i.e. Instant.toString()
        minRating: Int = 0,
        tags: String? = null, // e.g. "drums electronic -vocals"
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
        builder.add("Date >= ? ", minDate)
        builder.add("Date <= ?", maxDate)
        if (!minDate.isNullOrEmpty() || !maxDate.isNullOrEmpty()) builder.addLiteral("Date != ''")
        builder.add("s.SongId = ?", if (songId >= 0) songId.toString() else null)
        builder.add("Rating >= ?", if (minRating > 0) minRating.toString() else null)
        if (songIds != null && songIds.size > 0) {
            builder.addLiteral("s.SongId IN (" + songIds.joinToString(",") + ")")
        }
        if (artistPrefix != null) {
            builder.addMulti(
                "(Artist = ? OR Artist LIKE ?)",
                listOf(artistPrefix, "$artistPrefix %")
            )
        }
        tags?.trim()?.split("\\s+".toRegex())?.forEach {
            // Escape '%' characters in tags. Use space as the escape character since
            // we know it won't appear in tags (since we split on whitespace above).
            val tag = it.replace("%", " %")
            if (tag.startsWith("-")) builder.add("Tags NOT LIKE ? ESCAPE ' '", "%|${tag.drop(1)}|%")
            else if (!tag.isEmpty()) builder.add("Tags LIKE ? ESCAPE ' '", "%|$tag|%")
        }

        val cachedJoin = if (onlyCached) "JOIN CachedSongs cs ON(s.SongId = cs.SongId)" else ""
        val order = if (shuffle) "RANDOM()" else "Album ASC, AlbumId ASC, Disc ASC, Track ASC"
        val query =
            """
            SELECT s.SongId, Artist, Title, Album, AlbumId, Filename, CoverFilename, Length,
              Track, Disc, Date, TrackGain, AlbumGain, PeakAmp, Rating, Tags
              FROM Songs s $cachedJoin
              ${builder.whereClause()}
              ORDER BY $order
              LIMIT $MAX_QUERY_RESULTS
            """.trimIndent()

        val songs = mutableListOf<Song>()
        opener.getDb() task@{ db ->
            if (db == null) return@task // quit() already called

            val args = builder.selectionArgs
            Log.d(TAG, "Running \"$query\" with args $args")
            db.rawQuery(query, args.toArray<String>(arrayOf<String>())).use {
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
                                date = getString(10).let {
                                    if (it.isEmpty()) null else Instant.parse(it)
                                },
                                trackGain = getFloat(11).toDouble(),
                                albumGain = getFloat(12).toDouble(),
                                peakAmp = getFloat(13).toDouble(),
                                rating = getInt(14),
                                tags = getString(15).trim('|').let {
                                    if (it.isEmpty()) listOf<String>() else it.split("|")
                                },
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
            db.delete("CachedSongs", "SongId = ?", arrayOf(songId.toString()))
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
                "PendingPlaybackReports", "SongId = ? AND StartTime = ?",
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

            setSyncState(SyncState.FETCHING_USER_INFO, 0)
            val userInfo = fetchUserInfo()
            listenerExecutor.execute { listener.onUserInfoFetch(userInfo) }

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

                val songsRes = syncSongs(db, userInfo.excludedTags)
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
    private fun syncSongs(db: SQLiteDatabase, excludedTags: Set<String>): SyncResult {
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
                updatedSongs += syncSongUpdates(db, prevStartNs, false /* deleted */, excludedTags)
                updatedSongs += syncSongUpdates(db, prevStartNs, true /* deleted */, excludedTags)
            } catch (e: ServerException) {
                Log.e(TAG, e.message!!)
                return SyncResult(false, updatedSongs, e.message)
            }

            // Delete any existing songs that have now-excluded tags.
            updatedSongs += deleteSongsWithTags(db, excludedTags)

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

    /** User information from the server's /user endpoint. */
    data class UserInfo(
        val guest: Boolean = false,
        val excludedTags: Set<String> = setOf<String>(),
    )

    @Throws(ServerException::class)
    private fun fetchUserInfo(): UserInfo {
        val (response, error) = downloader.downloadString("/user")
        response ?: throw ServerException(error!!)
        try {
            val obj = JSONObject(JSONTokener(response))
            return UserInfo(
                guest = obj.optBoolean("guest"),
                excludedTags = jsonStringArrayToList(obj.optJSONArray("excludedTags")).toSet(),
            )
        } catch (e: JSONException) {
            throw ServerException("Couldn't parse response: $e")
        }
    }

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
        excludedTags: Set<String>,
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
                "&max=$SYNC_SONG_BATCH_SIZE"
            if (!serverCursor.isEmpty()) path += "&cursor=$serverCursor"
            if (deleted) path += "&deleted=1"

            // Retry to make it easier to do full syncs on flaky connections.
            var response: String? = null
            var error: String? = null
            for (i in 0 until SYNC_RETRIES + 1) {
                val res = downloader.downloadString(path)
                response = res.data
                error = res.error
                if (response != null) break
                Log.w(TAG, "Got error while syncing songs: $error")
            }
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

                                // Java's time-related APIs are awful. Make sure that the date is in
                                // a format that query() will be able to parse using Instant.parse,
                                // which only supports ISO-8601 UTC, e.g. // '2011-12-03T10:15:30Z'.
                                val date = item.optString("date")
                                if (date.isNotEmpty()) {
                                    try { Instant.parse(date) } catch (
                                        e: java.time.format.DateTimeParseException
                                    ) {
                                        throw ServerException("Invalid date \"$date\" for $songId")
                                    }
                                }

                                var tags = ""
                                val tagsArray = item.optJSONArray("tags")
                                if (tagsArray != null && tagsArray.length() > 0) {
                                    val list = jsonStringArrayToList(tagsArray)
                                    if (list.any { excludedTags.contains(it) }) {
                                        Log.d(TAG, "Skipping song $songId due to excluded tag")
                                        continue
                                    }
                                    tags = "|" + list.joinToString("|") + "|"
                                }

                                val values = ContentValues(18)
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
                                values.put("Date", date)
                                values.put("Length", item.getDouble("length"))
                                values.put("TrackGain", item.optDouble("trackGain", 0.0))
                                values.put("AlbumGain", item.optDouble("albumGain", 0.0))
                                values.put("PeakAmp", item.optDouble("peakAmp", 0.0))
                                values.put("Rating", item.getInt("rating"))
                                values.put("Tags", tags)
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

    /** Delete rows from the Songs table with one or more of [tags]. */
    private fun deleteSongsWithTags(db: SQLiteDatabase, tags: Set<String>): Int {
        if (tags.isEmpty()) return 0

        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()
        tags.forEach {
            val tag = it.replace("%", " %") // escape '%' characters; see [query]
            clauses.add("Tags LIKE ? ESCAPE ' '")
            params.add("%|$tag|%")
        }
        val deleted = db.delete("Songs", clauses.joinToString(" OR "), params.toTypedArray())
        Log.d(TAG, "Deleted $deleted existing song(s) due to excluded tags")
        return deleted
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
                val values = ContentValues(11)
                values.put("SortKey", numPresets)
                values.put("Name", o.getString("name"))
                values.put("Tags", o.optString("tags"))
                values.put("MinRating", o.optInt("minRating"))
                values.put("Unrated", o.optBoolean("unrated"))
                values.put("FirstPlayed", timeEnumToSec(o.optInt("firstPlayed")))
                values.put("LastPlayed", timeEnumToSec(o.optInt("lastPlayed")))
                values.put("OrderByLastPlayed", o.optBoolean("orderByLastPlayed"))
                values.put(
                    "MaxPlays",
                    if (o.has("maxPlays")) o.getInt("maxPlays")
                    else -1
                )
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
            SELECT Artist, Album, AlbumId, COUNT(*), MIN(CoverFilename), MIN(Date)
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
                    val date = getString(5).let { if (it.isEmpty()) null else Instant.parse(it) }
                    val row = StatsRow(origArtist, album, albumId, numSongs, coverFilename, date)
                    artistAlbums.getOrPut(canonArtist, { mutableListOf() }).add(row)
                }
            }
        }

        db.delete("ArtistAlbumStats", null, null)
        for ((artist, rows) in artistAlbums) {
            val artistSortKey = getSongSortKey(artist)
            for (row in rows) {
                val values = ContentValues(8)
                values.put("Artist", artist) // canonical
                values.put("Album", row.key.album)
                values.put("AlbumId", row.key.albumId)
                values.put("NumSongs", row.count)
                values.put("ArtistSortKey", artistSortKey)
                values.put("AlbumSortKey", getSongSortKey(row.key.album))
                values.put("CoverFilename", row.coverFilename)
                values.put("Date", row.date?.toString() ?: "")
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
            SELECT Artist, Album, AlbumId, SUM(NumSongs), MIN(CoverFilename), MIN(Date)
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
        newArtistAlbums.values.forEach { sortStatsRows(it, StatsOrder.DATE) }

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
              OrderByLastPlayed, MaxPlays, FirstTrack, Shuffle, Play
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
                            minRating = getInt(2),
                            unrated = getInt(3) != 0,
                            firstPlayed = getInt(4),
                            lastPlayed = getInt(5),
                            orderByLastPlayed = getInt(6) != 0,
                            maxPlays = getInt(7),
                            firstTrack = getInt(8) != 0,
                            shuffle = getInt(9) != 0,
                            play = getInt(10) != 0,
                        )
                    )
                }
            }
        }

        _searchPresets = presets
    }

    /** Get sorted results from the supplied query.
     *
     * @param query SQL query returning (artist, album, album ID, num songs, cover, date)
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
        order: StatsOrder,
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
     * @param cursor cursor for reading (artist, album, album ID, count, cover, date)
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
            val cover = cursor.getString(4)
            val date = cursor.getString(5).let { if (it.isEmpty()) null else Instant.parse(it) }

            // TODO: Decide if we should special-case songs with missing albums here. Right now they
            // all get lumped together, but that's arguably better than creating a separate row for
            // each artist.
            if (aggregateAlbums &&
                lastAlbum != null &&
                lastAlbum.key.albumId == albumId &&
                lastAlbum.key.album.trim().lowercase() == album.trim().lowercase()
            ) {
                lastAlbum.count += count
                lastAlbum.key.artist += ", " + artist
            } else {
                albums.add(StatsRow(artist, album, albumId, count, cover, date))
                lastAlbum = albums.last()
            }

            // TODO: Consider aggregating by album ID so that we have the full count from
            // each album rather than just songs exactly matching the artist. This will
            // affect the count displayed when navigating from BrowseArtistsActivity to
            // BrowseAlbumsActivity.
            artistAlbums
                ?.getOrPut(artist.lowercase(), { mutableListOf() })
                ?.add(StatsRow(artist, album, albumId, count, cover, date))

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
        private const val SYNC_SONG_BATCH_SIZE = 100
        private const val SYNC_RETRIES = 2
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
                        try {
                            upgradeSongDatabase(db, nextVersion)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed upgrading to $nextVersion: $e")
                            throw e
                        }
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
        .lowercase()
}

/** Convert an optional array of strings into a list. */
fun jsonStringArrayToList(array: JSONArray?): List<String> {
    var list = arrayListOf<String>()
    if (array != null) {
        // JSONArray unfortunately doesn't expose an iterator.
        for (i in 0 until array.length()) {
            list.add(array.optString(i))
        }
    }
    return list
}
