/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.util.Log

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
 * Search for songs matched by the supplied query.
 *
 * See [MediaSessionCompat.Callback]'s [onPlayFromSearch] method.
 */
suspend fun searchForSongs(
    db: SongDatabase,
    query: String,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
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
            0 -> searchForSongs(db, query)
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
    if (query.isEmpty()) return db.query(minRating = EMPTY_MIN_RATING, shuffle = true)

    // Try to find an exact artist match.
    var songs = db.query(artist = query, minRating = ARTIST_MIN_RATING, shuffle = true)
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
