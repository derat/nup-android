/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.os.AsyncTask
import android.view.ContextMenu

class BrowseAlbumsActivity : BrowseActivityBase() {
    override fun getBrowseTitle() = if (artist != null) {
        getString(
            if (onlyCached) R.string.browse_cached_albums_fmt else R.string.browse_albums_fmt,
            artist
        )
    } else {
        getString(if (onlyCached) R.string.browse_cached_albums else R.string.browse_albums)
    }

    override fun getBrowseDisplay() = if (artist == null) {
        StatsRowArrayAdapter.Display.ALBUM_ARTIST
    } else {
        StatsRowArrayAdapter.Display.ALBUM
    }

    // TODO: Right now, we show the full album by default instead of limiting it to songs by
    // the artist. Decide if this makes sense.
    override fun onRowClick(row: StatsRow, pos: Int) = startBrowseSongsActivity(
        album = row.key.album,
        albumId = row.key.albumId
    )

    override fun fillMenu(menu: ContextMenu, row: StatsRow) {
        menu.setHeaderTitle(row.key.album)
        if (artist != null) {
            val msg = getString(R.string.browse_songs_by_artist_fmt, artist)
            menu.add(0, MENU_ITEM_BROWSE_SONGS_BY_ARTIST, 0, msg)
        }
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars)
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs)
    }

    override fun onMenuClick(itemId: Int, row: StatsRow): Boolean {
        return when (itemId) {
            MENU_ITEM_BROWSE_SONGS_BY_ARTIST -> {
                startBrowseSongsActivity(
                    artist = artist,
                    album = row.key.album,
                    albumId = row.key.albumId
                )
                true
            }
            MENU_ITEM_BROWSE_SONGS_WITH_RATING -> {
                startBrowseSongsActivity(
                    album = row.key.album,
                    albumId = row.key.albumId,
                    minRating = 0.75
                )
                true
            }
            MENU_ITEM_BROWSE_SONGS -> {
                startBrowseSongsActivity(album = row.key.album, albumId = row.key.albumId)
                true
            }
            else -> false
        }
    }

    override fun getRows(db: SongDatabase, update: (rows: List<StatsRow>?) -> Unit) {
        when {
            // If the database isn't ready, display a loading message.
            !db.aggregateDataLoaded -> update(null)
            // If we're displaying all data, then we can return it synchronously.
            !onlyCached -> update(
                if (artist != null) db.getAlbumsByArtist(artist!!)
                else db.getAlbumsSortedAlphabetically()
            )
            // Cached data requires an async database query.
            else -> object : AsyncTask<Void?, Void?, List<StatsRow>>() {
                protected override fun doInBackground(vararg args: Void?): List<StatsRow> {
                    return if (artist != null) {
                        db.getCachedAlbumsByArtist(artist!!)
                    } else {
                        db.cachedAlbumsSortedAlphabetically
                    }
                }
                override fun onPostExecute(rows: List<StatsRow>) = update(rows)
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    companion object {
        private const val MENU_ITEM_BROWSE_SONGS_BY_ARTIST = 1
        private const val MENU_ITEM_BROWSE_SONGS_WITH_RATING = 2
        private const val MENU_ITEM_BROWSE_SONGS = 3
    }
}
