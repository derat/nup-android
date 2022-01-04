/*
 * Copyright 2021 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.res.Resources
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Provides functionality for implementing [MediaBrowserServiceCompat]. */
class MediaBrowserHelper(
    private val service: MediaBrowserServiceCompat,
    private val db: SongDatabase,
    private val networkHelper: NetworkHelper,
    private val scope: CoroutineScope,
    private val res: Resources,
) : NetworkHelper.Listener {
    override fun onNetworkAvailabilityChange(available: Boolean) {
        Log.d(TAG, "Notifying about root change for network change")
        service.notifyChildrenChanged(ROOT_ID)
    }

    /** Implements [MediaBrowserServiceCompat.onLoadChildren]. */
    fun onLoadChildren(
        parentId: String,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaItem>>
    ) {
        Log.d(TAG, "Got request for children of \"$parentId\"")
        when {
            parentId == ROOT_ID -> {
                // Per https://developer.android.com/training/cars/media#root-menu-structure, it's
                // usually the case that the root can have up to 4 children, all of which can only
                // be browsable.
                val items = mutableListOf<MediaItem>()
                if (networkHelper.isNetworkAvailable) {
                    items.add(makeMenuItem(R.string.artists, ARTISTS_ID))
                    items.add(makeMenuItem(R.string.albums, ALBUMS_ID))
                } else {
                    // The full "Artists (cached)" string is too long for Android Auto's tabs.
                    items.add(makeMenuItem(R.string.artists_star, CACHED_ARTISTS_ID))
                    items.add(makeMenuItem(R.string.albums_star, CACHED_ALBUMS_ID))
                }
                items.add(makeMenuItem(R.string.shuffle, SHUFFLE_ID, playable = true))
                result.sendResult(items)
            }
            parentId == ARTISTS_ID -> {
                val items = mutableListOf<MediaItem>()
                for (row in db.artistsSortedAlphabetically) {
                    items.add(makeArtistItem(row, onlyCached = false))
                }
                result.sendResult(items)
            }
            parentId == ALBUMS_ID -> {
                val items = mutableListOf<MediaItem>()
                for (row in db.albumsSortedAlphabetically) {
                    items.add(makeAlbumItem(row, onlyCached = false, includeArtist = true))
                }
                result.sendResult(items)
            }
            parentId.startsWith(CACHED_ARTISTS_ID) -> {
                result.detach()
                scope.launch(Dispatchers.IO) {
                    val items = mutableListOf<MediaItem>()
                    for (row in db.cachedArtistsSortedAlphabetically()) {
                        items.add(makeArtistItem(row, onlyCached = true))
                    }
                    result.sendResult(items)
                }
            }
            parentId.startsWith(CACHED_ALBUMS_ID) -> {
                result.detach()
                scope.launch(Dispatchers.IO) {
                    val items = mutableListOf<MediaItem>()
                    for (row in db.cachedAlbumsSortedAlphabetically()) {
                        items.add(makeAlbumItem(row, onlyCached = true, includeArtist = true))
                    }
                    result.sendResult(items)
                }
            }
            parentId.startsWith(ARTIST_ID_PREFIX) -> {
                val items = mutableListOf<MediaItem>()
                val artist = parentId.substring(ARTIST_ID_PREFIX.length)
                for (row in db.albumsByArtist(artist)) {
                    items.add(makeAlbumItem(row, onlyCached = false, includeArtist = false))
                }
                result.sendResult(items)
            }
            parentId.startsWith(CACHED_ARTIST_ID_PREFIX) -> {
                result.detach()
                scope.launch(Dispatchers.IO) {
                    val artist = parentId.substring(CACHED_ARTIST_ID_PREFIX.length)
                    val items = mutableListOf<MediaItem>()
                    for (row in db.cachedAlbumsByArtist(artist)) {
                        items.add(makeAlbumItem(row, onlyCached = true, includeArtist = false))
                    }
                    result.sendResult(items)
                }
            }
            parentId.startsWith(ALBUM_ID_PREFIX) -> {
                result.detach()
                scope.launch(Dispatchers.IO) {
                    val items = mutableListOf<MediaItem>()
                    for (song in getSongsForMediaId(parentId)) {
                        items.add(makeSongItem(song))
                    }
                    result.sendResult(items)
                }
            }
            else -> {
                Log.e(TAG, "Don't know how to get children of media ID \"$parentId\"")
                result.sendResult(mutableListOf<MediaItem>())
            }
        }
    }

    /** Create a [MediaItem] with the supplied string resource and media ID. */
    private fun makeMenuItem(resId: Int, id: String, playable: Boolean = false): MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setTitle(res.getText(resId))
            .setMediaId(id)
            .build()
        return MediaItem(desc, if (playable) MediaItem.FLAG_PLAYABLE else MediaItem.FLAG_BROWSABLE)
    }

    /** Create a [MediaItem] for the artist described by [row]. */
    private fun makeArtistItem(row: StatsRow, onlyCached: Boolean): MediaItem {
        val pre = if (onlyCached) CACHED_ARTIST_ID_PREFIX else ARTIST_ID_PREFIX
        val desc = MediaDescriptionCompat.Builder()
            .setTitle(row.key.artist)
            .setMediaId(pre + row.key.artist)
            .build()
        return MediaItem(desc, MediaItem.FLAG_BROWSABLE)
    }

    /** Create a [MediaItem] for the album described by [row]. */
    private fun makeAlbumItem(
        row: StatsRow,
        onlyCached: Boolean,
        includeArtist: Boolean
    ): MediaItem {
        // This seems like it's close to an internal Android limit. When I call setExtras()
        // to attach a Bundle with android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT, I'm unable
        // to list albums and logcat shows a "JavaBinder: !!! FAILED BINDER TRANSACTION !!!  (parcel
        // size = 676544)" error. That still doesn't make much sense to me, since the Android binder
        // size limit is supposedly 1 MB.
        val pre = if (onlyCached) CACHED_ALBUM_ID_PREFIX else ALBUM_ID_PREFIX
        val builder = MediaDescriptionCompat.Builder()
            .setTitle(row.key.album)
            .setMediaId(pre + row.key.albumId)
        if (includeArtist) builder.setSubtitle(row.key.artist)

        // TODO: Why isn't Android Audio displaying UI for browsing into albums?
        // I asked at https://stackoverflow.com/q/66509112/6882947.
        return MediaItem(builder.build(), MediaItem.FLAG_BROWSABLE or MediaItem.FLAG_PLAYABLE)
    }

    /** Create a [MediaItem] for [song]. */
    public fun makeSongItem(song: Song): MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setTitle(song.title)
            .setSubtitle(song.artist)
            .setMediaId(getSongMediaId(song.id))
            .build()
        return MediaItem(desc, MediaItem.FLAG_PLAYABLE)
    }

    /** Query the database for songs identified by [id]. */
    public suspend fun getSongsForMediaId(id: String): List<Song> {
        return when {
            id == SHUFFLE_ID ->
                searchForSongs(db, "", online = networkHelper.isNetworkAvailable)
            id.startsWith(ALBUM_ID_PREFIX) ->
                db.query(albumId = id.substring(ALBUM_ID_PREFIX.length))
            id.startsWith(CACHED_ALBUM_ID_PREFIX) ->
                db.query(albumId = id.substring(CACHED_ALBUM_ID_PREFIX.length), onlyCached = true)
            id.startsWith(SONG_ID_PREFIX) ->
                db.query(songId = id.substring(SONG_ID_PREFIX.length).toLong())
            else -> {
                Log.e(TAG, "Don't know how to query for songs with media ID \"$id\"")
                listOf<Song>()
            }
        }
    }

    companion object {
        private const val TAG = "MediaBrowserHelper"

        public const val ROOT_ID = "root_id"

        private const val ARTISTS_ID = "artists"
        private const val ALBUMS_ID = "albums"
        private const val CACHED_ARTISTS_ID = "cached_artists"
        private const val CACHED_ALBUMS_ID = "cached_albums"
        private const val SHUFFLE_ID = "shuffle"

        private const val ARTIST_ID_PREFIX = "artist_"
        private const val ALBUM_ID_PREFIX = "album_"
        private const val CACHED_ARTIST_ID_PREFIX = "cached_artist_"
        private const val CACHED_ALBUM_ID_PREFIX = "cached_album_"
        private const val SONG_ID_PREFIX = "song_"

        public fun getSongMediaId(songId: Long) = "${SONG_ID_PREFIX}$songId"
    }

    init {
        networkHelper.addListener(this)
    }
}
