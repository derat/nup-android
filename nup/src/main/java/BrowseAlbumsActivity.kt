/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.os.Bundle
import android.view.ContextMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Displays a list of albums. */
class BrowseAlbumsActivity : BrowseActivityBase() {
    override val display
        get() = if (onlyArtist == "") {
            StatsRowArrayAdapter.Display.ALBUM_ARTIST
        } else {
            StatsRowArrayAdapter.Display.ALBUM
        }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = if (onlyArtist != "") {
            getString(
                if (onlyCached) R.string.browse_cached_albums_fmt else R.string.browse_albums_fmt,
                onlyArtist
            )
        } else {
            getString(if (onlyCached) R.string.browse_cached_albums else R.string.browse_albums)
        }
    }

    // TODO: Right now, we show the full album by default instead of limiting it to songs by
    // the artist. Decide if this makes sense.
    override fun onRowClick(row: StatsRow, pos: Int) = startBrowseSongsActivity(
        album = row.key.album,
        albumId = row.key.albumId
    )

    override fun fillMenu(menu: ContextMenu, row: StatsRow) {
        menu.setHeaderTitle(row.key.album)
        if (onlyArtist != "") {
            val msg = getString(R.string.browse_songs_by_artist_fmt, onlyArtist)
            menu.add(0, MENU_ITEM_BROWSE_SONGS_BY_ARTIST, 0, msg)
        }
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars)
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs)
    }

    override fun onMenuClick(itemId: Int, row: StatsRow): Boolean {
        return when (itemId) {
            MENU_ITEM_BROWSE_SONGS_BY_ARTIST -> {
                startBrowseSongsActivity(
                    artist = onlyArtist,
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

    override fun getRows(
        db: SongDatabase,
        scope: CoroutineScope,
        update: (rows: List<StatsRow>?) -> Unit
    ) {
        when {
            // If the database isn't ready, display a loading message.
            !db.aggregateDataLoaded -> update(null)
            // If we're displaying all data, then we can return it synchronously.
            !onlyCached -> update(
                if (onlyArtist != "") db.albumsByArtist(onlyArtist)
                else db.albumsSortedAlphabetically
            )
            // Cached data requires an async database query.
            else -> scope.async(Dispatchers.Main) {
                update(
                    async(Dispatchers.IO) {
                        if (onlyArtist != "") {
                            db.cachedAlbumsByArtist(onlyArtist)
                        } else {
                            db.cachedAlbumsSortedAlphabetically()
                        }
                    }.await()
                )
            }
        }
    }

    companion object {
        private const val MENU_ITEM_BROWSE_SONGS_BY_ARTIST = 1
        private const val MENU_ITEM_BROWSE_SONGS_WITH_RATING = 2
        private const val MENU_ITEM_BROWSE_SONGS = 3
    }
}
