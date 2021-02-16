/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Intent
import android.os.AsyncTask
import android.view.ContextMenu

class BrowseArtistsActivity : BrowseActivityBase() {
    override fun getBrowseTitle() = getString(
        if (onlyCached) R.string.browse_cached_artists else R.string.browse_artists
    )

    override fun getBrowseDisplay() = StatsRowArrayAdapter.Display.ARTIST

    override fun onRowClick(row: StatsRow, pos: Int) = startBrowseAlbumsActivity(row.key.artist)

    override fun fillMenu(menu: ContextMenu, row: StatsRow) {
        menu.setHeaderTitle(row.key.artist)
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars)
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs)
        menu.add(0, MENU_ITEM_BROWSE_ALBUMS, 0, R.string.browse_albums)
    }

    override fun onMenuClick(itemId: Int, row: StatsRow): Boolean {
        return when (itemId) {
            MENU_ITEM_BROWSE_SONGS_WITH_RATING -> {
                startBrowseSongsActivity(artist = row.key.artist, minRating = 0.75)
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

    override fun getRows(db: SongDatabase, update: (rows: List<StatsRow>?) -> Unit) {
        when {
            // If the database isn't ready, display a loading message.
            !db.aggregateDataLoaded -> update(null)
            // If we're displaying all data, then we can return it synchronously.
            !onlyCached -> update(db.getArtistsSortedAlphabetically())
            // Cached data requires an async database query.
            else -> object : AsyncTask<Void?, Void?, List<StatsRow>>() {
                protected override fun doInBackground(vararg args: Void?): List<StatsRow> {
                    return db.cachedArtistsSortedAlphabetically
                }
                override fun onPostExecute(rows: List<StatsRow>) = update(rows)
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
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
