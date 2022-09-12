/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Intent
import android.os.Bundle
import android.view.ContextMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Displays a list of artists. */
class BrowseArtistsActivity : BrowseActivityBase() {
    override val display = StatsRowArrayAdapter.Display.ARTIST

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(if (onlyCached) R.string.cached_artists else R.string.artists)
    }

    override fun onRowClick(row: StatsRow, pos: Int, fragment: BrowseListFragment) =
        startBrowseAlbumsActivity(row.key.artist)

    override fun fillMenu(menu: ContextMenu, row: StatsRow) {
        menu.setHeaderTitle(row.key.artist)
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.four_star_songs)
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.songs)
        menu.add(0, MENU_ITEM_BROWSE_ALBUMS, 0, R.string.albums)
    }

    override fun onMenuClick(itemId: Int, row: StatsRow): Boolean {
        return when (itemId) {
            MENU_ITEM_BROWSE_SONGS_WITH_RATING -> {
                startBrowseSongsActivity(artist = row.key.artist, minRating = 4)
                true
            }
            MENU_ITEM_BROWSE_SONGS -> {
                startBrowseSongsActivity(artist = row.key.artist)
                true
            }
            MENU_ITEM_BROWSE_ALBUMS -> {
                startBrowseAlbumsActivity(row.key.artist)
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
            !onlyCached -> update(db.artistsSortedAlphabetically)
            // Cached data requires an async database query.
            else -> scope.async(Dispatchers.Main) {
                update(async(Dispatchers.IO) { db.cachedArtistsSortedAlphabetically() }.await())
            }
        }
    }

    /** Launch BrowseAlbumsActivity for a given artist. */
    private fun startBrowseAlbumsActivity(artist: String) {
        val intent = Intent(this, BrowseAlbumsActivity::class.java)
        intent.putExtra(BUNDLE_ARTIST, artist)
        intent.putExtra(BUNDLE_CACHED, onlyCached)
        startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE)
    }

    companion object {
        private const val MENU_ITEM_BROWSE_SONGS_WITH_RATING = 1
        private const val MENU_ITEM_BROWSE_SONGS = 2
        private const val MENU_ITEM_BROWSE_ALBUMS = 3
    }
}
