/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.util.Log
import java.net.URLEncoder
import java.util.Date
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

// https://developer.android.com/guide/topics/media-apps/interacting-with-assistant
//
// The 'query' arg passed to onPlayFromSearch seems to match the 'query' bundle item.
//
// android.intent.extra.user_query seems to contain the original unparsed query
// (e.g. including "play").
//
// [play Dark Side of the Moon by Pink Floyd]
//   EXTRA_MEDIA_ARTIST "Pink Floyd"
//   EXTRA_MEDIA_TITLE  "The Dark Side of the Moon"
//   query              "Dark Side of the Moon Pink Floyd"
//
// [play Pink Floyd]
//   EXTRA_MEDIA_TITLE  "Pink Floyd"
//   query              "Pink Floyd"
//
// [play Dark Side of the Moon]
//   EXTRA_MEDIA_ARTIST "Pink Floyd"
//   EXTRA_MEDIA_TITLE  "The Dark Side of the Moon"
//   query              "Dark Side of the Moon"
//
// [play versus by Pearl Jam]
//   EXTRA_MEDIA_ARTIST "Pearl Jam"
//   EXTRA_MEDIA_TITLE  "Vs."
//   query              "versus Pearl Jam"
//
// [Play Take 5 by Dave Brubeck]
//   EXTRA_MEDIA_ARTIST "The Dave Brubeck Quartet"
//   EXTRA_MEDIA_ALBUM  "The Jazz Album"
//   EXTRA_MEDIA_TITLE  "Take Five"
//   query              "Take 5 Dave Brubeck"
//
// [play purple]
//   EXTRA_MEDIA_TITLE  "purple"
//   query              "purple"
//
// [Play Purple by Stone Temple Pilots]
//   EXTRA_MEDIA_ARTIST "Stone Temple Pilots"
//   EXTRA_MEDIA_TITLE  "Purple"
//   query              "Purple Stone Temple Pilots"
//
// [Play Purple by baroness]
//   EXTRA_MEDIA_ARTIST "Baroness"
//   EXTRA_MEDIA_TITLE  "Purple"
//   query              "Purple by baroness"
//
// [play Danny b-sides by Danny baranowsky]
//   EXTRA_MEDIA_ARTIST "Danny Baranowsky"
//   EXTRA_MEDIA_TITLE  "dannyBsides"
//   query              "Danny b-sides Danny baranowsky"
//
// [play some band name that I just made up]
//   query              "name that I just made up"
//
// [play you probably haven't heard of it]
//   query              "you probably haven't heard of it"
//
// [Play My Life by Joe Smith]
//   query              "My Life by Joe Smith"
//
// It looks like Assistant can handle the long tail pretty well; see e.g. "dannyBsides" above. When
// I give it completely made-up information as in the last three examples, it doesn't try to parse
// it beyond e.g. removing "play" -- it doesn't split out the title and artist on "by", for
// instance.
//
// When EXTRA_MEDIA_ARTIST isn't set, EXTRA_MEDIA_TITLE seems to match the original query, so I'm
// treating that case the same as the one where EXTRA_MEDIA_TITLE also isn't set.
//
// I'm ignoring EXTRA_MEDIA_ALBUM entirely: for queries like [play album by artist], the album is
// passed via EXTRA_MEDIA_TITLE instead. In the one place where I received EXTRA_MEDIA_ALBUM after
// passing a song title ("Take 5"), it was the name of some random compilation album instead of
// "Time Out".
//
// When we find an album, return all of its songs.
// When we find an artist, shuffle highly-rated songs.

/**
 * Search for songs matched by the supplied query using [SongDatabase].
 *
 * See [MediaSessionCompat.Callback]'s [onPlayFromSearch] method.
 * If [query] is empty, shuffled songs with [EMPTY_MIN_RATING] are returned.
 * If [online] is false, only already-cached songs are returned for empty queries.
 */
suspend fun searchLocal(
    db: SongDatabase,
    query: String,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    online: Boolean = true,
): List<Song> {
    Log.d(TAG, "Query \"$query\" with title \"$title\", artist \"$artist\", album \"$album\"")

    if (title != null && artist != null) {
        // Try to find an exact or substring album match.
        var albumRows = findAlbumsExact(db.albumsSortedAlphabetically, title)
        if (albumRows.isEmpty()) {
            albumRows = findAlbumsSubstring(db.albumsSortedAlphabetically, title)
        }

        return when (albumRows.size) {
            // If we didn't find any matching albums (e.g. Assistant "helpfully" refines [Last of
            // the Mohicans] to title "The Last Of The Mohicans (Original Motion Picture Score)" and
            // artist "Randy Edelman, Trevor Jones"), then just perform a search using the original
            // query.
            0 -> searchLocal(db, query)
            1 -> db.query(albumId = albumRows[0].key.albumId)
            else -> {
                // When there are multiple matching albums, try to disambiguate by artist.
                val artistAlbumRows = findArtistsSubstring(albumRows, artist)
                val row = if (!artistAlbumRows.isEmpty()) artistAlbumRows[0] else albumRows[0]
                db.query(albumId = row.key.albumId)
            }
        }
    }

    // If no query was supplied, just play good music.
    if (query.isEmpty()) {
        return db.query(minRating = EMPTY_MIN_RATING, shuffle = true, onlyCached = !online)
    }

    // Try to find an artist match. Use a prefix query so that we'll also match songs
    // featuring additional artists (e.g. "Foo feat. Bar").
    var songs = db.query(artistPrefix = query, minRating = ARTIST_MIN_RATING, shuffle = true)
    if (!songs.isEmpty()) return songs

    // Try to find an exact album match.
    val albumRows = findAlbumsExact(db.albumsSortedAlphabetically, query)
    if (!albumRows.isEmpty()) return db.query(albumId = albumRows[0].key.albumId)

    // Try to find a substring artist match.
    songs = db.query(
        artist = query,
        substring = true,
        minRating = ARTIST_MIN_RATING,
        shuffle = true,
    )
    if (!songs.isEmpty()) return songs

    // Fall back to a substring album match.
    return db.query(album = query, substring = true)
}

/** Search the server for songs. */
@Throws(SearchException::class)
suspend fun searchServer(
    db: SongDatabase,
    downloader: Downloader,
    artist: String? = null,
    title: String? = null,
    album: String? = null,
    shuffle: Boolean = false,
    keywords: String? = null,
    tags: String? = null,
    minRating: Double = -1.0,
    unrated: Boolean = false,
    maxPlays: Int = -1,
    firstPlayed: Int = 0,
    lastPlayed: Int = 0,
    orderByLastPlayed: Boolean = false,
    firstTrack: Boolean = false,
    onlyCached: Boolean = false,
): List<Song> {
    var params = ""
    val add = fun(k: String, v: String?) {
        if (v.isNullOrEmpty()) return
        if (!params.isEmpty()) params += "&"
        params += "$k=${URLEncoder.encode(v, "utf-8")}"
    }

    add("artist", artist)
    add("title", title)
    add("album", album)
    add("shuffle", if (shuffle) "1" else "")
    add("keywords", keywords)
    add("tags", tags)
    add("minRating", if (minRating > 0) "%.2f".format(minRating) else "")
    add("unrated", if (unrated) "1" else "")
    add("maxPlays", if (maxPlays >= 0) maxPlays.toString() else "")
    add("orderByLastPlayed", if (orderByLastPlayed) "1" else "")
    add("firstTrack", if (firstTrack) "1" else "")

    // We get numbers of seconds before the current time, but the server
    // wants timestamps as seconds since the epoch.
    val now = Date().getTime() / 1000
    if (firstPlayed > 0) add("minFirstPlayed", (now - firstPlayed).toString())
    if (lastPlayed > 0) add("maxLastPlayed", (now - lastPlayed).toString())

    val (response, error) = downloader.downloadString("/query?$params")
    response ?: throw SearchException(error!!)

    val songIds = try {
        JSONArray(JSONTokener(response)).iterator<JSONObject>().asSequence().toList().map {
            o ->
            o.getLong("songId")
        }
    } catch (e: JSONException) {
        throw SearchException("Couldn't parse response: $e")
    }
    Log.d(TAG, "Server returned ${songIds.size} song(s)")

    // Get the actual songs from SongDatabase.
    // TODO: Using onlyCached doesn't work well in cases where the server returns a subset of all
    // matching songs, since we may not have some of the subset locally. I'm not sure how to fix
    // this without either making the server return all matching songs (yikes) or sending the cached
    // songs to the server (also yikes).
    return db.getSongs(songIds, onlyCached = onlyCached).first
}

/** Search for songs using [preset]. Network access may be needed. */
suspend fun searchUsingPreset(
    db: SongDatabase,
    downloader: Downloader,
    preset: SearchPreset
): List<Song> {
    if (preset.canSearchLocal()) {
        return db.query(minRating = preset.minRating, shuffle = preset.shuffle)
    }

    return searchServer(
        db,
        downloader,
        tags = if (preset.tags != "") preset.tags else null,
        minRating = preset.minRating,
        unrated = preset.unrated,
        firstPlayed = preset.firstPlayed,
        lastPlayed = preset.lastPlayed,
        orderByLastPlayed = preset.orderByLastPlayed,
        firstTrack = preset.firstTrack,
        shuffle = preset.shuffle
    )
}

/** Predefined search specified in the server's configuration. */
data class SearchPreset(
    val name: String, // preset name displayed to user, e.g. 'Favorites' or 'Instrumental'
    val tags: String, // comma-separated list, empty for unset
    val minRating: Double, // [0.0, 1.0] or -1 for no minimum (i.e. includes unrated)
    val unrated: Boolean,
    val firstPlayed: Int, // seconds before now, 0 for unset
    val lastPlayed: Int, // seconds before now, 0 for unset
    val orderByLastPlayed: Boolean,
    val firstTrack: Boolean,
    val shuffle: Boolean,
    val play: Boolean, // automatically play results
) {
    /** Return true if the search can be performed with [searchLocal] rather than [searchServer]. */
    fun canSearchLocal() = tags.isEmpty() &&
        unrated == false &&
        firstPlayed == 0 &&
        lastPlayed == 0 &&
        orderByLastPlayed == false &&
        firstTrack == false
}

/** Thrown if an error is encountered while searching. */
class SearchException(reason: String) : Exception(reason)

private fun findArtistExact(rows: List<StatsRow>, artist: String) =
    rows.find { it.key.artist.equals(artist, ignoreCase = true) }
private fun findArtistsSubstring(rows: List<StatsRow>, artist: String) =
    rows.filter { it.key.artist.contains(artist, ignoreCase = true) }

private fun findAlbumsExact(rows: List<StatsRow>, album: String) =
    rows.filter { it.key.album.equals(album, ignoreCase = true) }
private fun findAlbumsSubstring(rows: List<StatsRow>, album: String) =
    rows.filter { it.key.album.contains(album, ignoreCase = true) }

private const val TAG = "Search"

// Minimum ratings to use for different types of queries.
private const val EMPTY_MIN_RATING = 1.0
private const val ARTIST_MIN_RATING = 0.75
