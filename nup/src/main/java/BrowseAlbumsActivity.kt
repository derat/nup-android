/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ListView
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.NupService.SongDatabaseUpdateListener
import org.erat.nup.Util.resizeListViewToFixFastScroll

class BrowseAlbumsActivity : BrowseActivityBase(), SongDatabaseUpdateListener {
    // Are we displaying only cached songs?
    private var onlyCached = false

    // Artist that was passed to us, or null if we were started directly from BrowseTopActivity.
    private var artist: String? = null

    // Albums that we're displaying along with number of tracks.
    // Just the albums featuring |mArtist| if it's non-null, or all albums on the server otherwise.
    private val rows: MutableList<StatsRow> = ArrayList()
    private var adapter: StatsRowArrayAdapter? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onlyCached = intent.getBooleanExtra(BUNDLE_CACHED, false)
        artist = intent.getStringExtra(BUNDLE_ARTIST)
        title = if (artist != null) getString(
            if (onlyCached) R.string.browse_cached_albums_fmt else R.string.browse_albums_fmt,
            artist
        ) else getString(
            if (onlyCached) R.string.browse_cached_albums else R.string.browse_albums
        )
        val display = if (artist == null) {
            StatsRowArrayAdapter.DISPLAY_ALBUM_ARTIST
        } else {
            StatsRowArrayAdapter.DISPLAY_ALBUM
        }
        adapter = StatsRowArrayAdapter(
            this,
            R.layout.browse_row,
            rows,
            display,
            Util.SORT_ALBUM
        )
        listAdapter = adapter
        registerForContextMenu(listView)
        service!!.addSongDatabaseUpdateListener(this)
        onSongDatabaseUpdate()
    }

    override fun onDestroy() {
        service!!.removeSongDatabaseUpdateListener(this)
        super.onDestroy()
    }

    override fun onListItemClick(listView: ListView, view: View, position: Int, id: Long) {
        val row = rows[position]
        // TODO: Right now, we show the full album by default instead of limiting it to songs by
        // |mArtist|. Decide if this makes sense.
        startBrowseSongsActivity(null, row.key.album, row.key.albumId, -1.0)
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        view: View,
        menuInfo: ContextMenuInfo
    ) {
        val pos = (menuInfo as AdapterContextMenuInfo).position
        menu.setHeaderTitle(rows[pos].key.album)
        if (artist != null) {
            val msg = getString(R.string.browse_songs_by_artist_fmt, artist)
            menu.add(0, MENU_ITEM_BROWSE_SONGS_BY_ARTIST, 0, msg)
        }
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars)
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val row = rows[info.position]
        return when (item.itemId) {
            MENU_ITEM_BROWSE_SONGS_BY_ARTIST -> {
                startBrowseSongsActivity(artist, row.key.album, row.key.albumId, -1.0)
                true
            }
            MENU_ITEM_BROWSE_SONGS_WITH_RATING -> {
                startBrowseSongsActivity("", row.key.album, row.key.albumId, 0.75)
                true
            }
            MENU_ITEM_BROWSE_SONGS -> {
                startBrowseSongsActivity("", row.key.album, row.key.albumId, -1.0)
                true
            }
            else -> false
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    override fun onSongDatabaseUpdate() {
        if (!service!!.songDb!!.aggregateDataLoaded) {
            rows.add(StatsRow("", getString(R.string.loading), "", -1))
            adapter!!.setEnabled(false)
            adapter!!.notifyDataSetChanged()
            return
        }
        if (!onlyCached) {
            updateRows(
                if (artist != null) service!!.songDb!!.getAlbumsByArtist(artist!!)
                else service!!.songDb!!.getAlbumsSortedAlphabetically()
            )
        } else {
            object : AsyncTask<Void?, Void?, List<StatsRow>>() {
                protected override fun doInBackground(vararg args: Void?): List<StatsRow> {
                    return if (artist != null) {
                        service!!.songDb!!.getCachedAlbumsByArtist(artist!!)
                    } else {
                        service!!.songDb!!.cachedAlbumsSortedAlphabetically
                    }
                }

                override fun onPostExecute(rows: List<StatsRow>) {
                    updateRows(rows)
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    // Show a new list of albums.
    private fun updateRows(newRows: List<StatsRow>) {
        rows.clear()
        rows.addAll(newRows)
        val listView = listView
        listView.isFastScrollEnabled = false
        adapter!!.setEnabled(true)
        adapter!!.notifyDataSetChanged()
        listView.isFastScrollEnabled = true
        resizeListViewToFixFastScroll(listView)
    }

    // Launch BrowseSongsActivity for a given album.
    private fun startBrowseSongsActivity(
        artist: String?,
        album: String,
        albumId: String,
        minRating: Double
    ) {
        val intent = Intent(this, BrowseSongsActivity::class.java)
        if (artist != null) intent.putExtra(BUNDLE_ARTIST, artist)
        intent.putExtra(BUNDLE_ALBUM, album)
        intent.putExtra(BUNDLE_ALBUM_ID, albumId)
        intent.putExtra(BUNDLE_CACHED, onlyCached)
        if (minRating >= 0.0) intent.putExtra(BUNDLE_MIN_RATING, minRating)
        startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE)
    }

    companion object {
        private const val TAG = "BrowseAlbumsActivity"

        // Identifier for the BrowseSongsActivity that we start.
        private const val BROWSE_SONGS_REQUEST_CODE = 1
        private const val MENU_ITEM_BROWSE_SONGS_BY_ARTIST = 1
        private const val MENU_ITEM_BROWSE_SONGS_WITH_RATING = 2
        private const val MENU_ITEM_BROWSE_SONGS = 3
    }
}
