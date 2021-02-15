// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
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

class BrowseArtistsActivity : BrowseActivityBase(), SongDatabaseUpdateListener {
    // Are we displaying only cached songs?
    private var onlyCached = false

    // Artists that we're displaying.
    private val rows: MutableList<StatsRow> = ArrayList()
    private var adapter: StatsRowArrayAdapter? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onlyCached = intent.getBooleanExtra(BUNDLE_CACHED, false)
        setTitle(if (onlyCached) R.string.browse_cached_artists else R.string.browse_artists)
        adapter = StatsRowArrayAdapter(
            this,
            R.layout.browse_row,
            rows,
            StatsRowArrayAdapter.DISPLAY_ARTIST,
            Util.SORT_ARTIST
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
        startBrowseAlbumsActivity(rows[position].key.artist)
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        view: View,
        menuInfo: ContextMenuInfo
    ) {
        val pos = (menuInfo as AdapterContextMenuInfo).position
        menu.setHeaderTitle(rows[pos].key.artist)
        menu.add(0, MENU_ITEM_BROWSE_SONGS_WITH_RATING, 0, R.string.browse_songs_four_stars)
        menu.add(0, MENU_ITEM_BROWSE_SONGS, 0, R.string.browse_songs)
        menu.add(0, MENU_ITEM_BROWSE_ALBUMS, 0, R.string.browse_albums)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val row = rows[info.position]
        return when (item.itemId) {
            MENU_ITEM_BROWSE_SONGS_WITH_RATING -> {
                startBrowseSongsActivity(row.key.artist, 0.75)
                true
            }
            MENU_ITEM_BROWSE_SONGS -> {
                startBrowseSongsActivity(row.key.artist, -1.0)
                true
            }
            MENU_ITEM_BROWSE_ALBUMS -> {
                startBrowseAlbumsActivity(row.key.artist)
                true
            }
            else -> false
        }
    }

    // Implements NupService.SongDatabaseUpdateListener.
    override fun onSongDatabaseUpdate() {
        if (!service!!.songDb!!.aggregateDataLoaded) {
            rows.add(StatsRow(getString(R.string.loading), "", "", -1))
            adapter!!.setEnabled(false)
            adapter!!.notifyDataSetChanged()
            return
        }
        if (!onlyCached) {
            updateRows(service!!.songDb!!.getArtistsSortedAlphabetically())
        } else {
            object : AsyncTask<Void?, Void?, List<StatsRow>>() {
                protected override fun doInBackground(vararg args: Void?): List<StatsRow> {
                    return service!!
                        .songDb!!
                        .cachedArtistsSortedAlphabetically
                }

                override fun onPostExecute(rows: List<StatsRow>) {
                    updateRows(rows)
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    // Show a new list of artists.
    private fun updateRows(newRows: List<StatsRow>) {
        val listView = listView
        rows.clear()
        rows.addAll(newRows)
        listView.isFastScrollEnabled = false
        adapter!!.setEnabled(true)
        adapter!!.notifyDataSetChanged()
        listView.isFastScrollEnabled = true
        resizeListViewToFixFastScroll(listView)
    }

    // Launch BrowseAlbumsActivity for a given artist.
    private fun startBrowseAlbumsActivity(artist: String) {
        val intent = Intent(this, BrowseAlbumsActivity::class.java)
        intent.putExtra(BUNDLE_ARTIST, artist)
        intent.putExtra(BUNDLE_CACHED, onlyCached)
        startActivityForResult(intent, BROWSE_ALBUMS_REQUEST_CODE)
    }

    // Launch BrowseSongsActivity for a given artist.
    private fun startBrowseSongsActivity(artist: String, minRating: Double) {
        val intent = Intent(this, BrowseSongsActivity::class.java)
        intent.putExtra(BUNDLE_ARTIST, artist)
        intent.putExtra(BUNDLE_CACHED, onlyCached)
        if (minRating >= 0) intent.putExtra(BUNDLE_MIN_RATING, minRating)
        startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE)
    }

    companion object {
        private const val TAG = "BrowseArtistsActivity"

        // Identifiers for activities that we start.
        private const val BROWSE_ALBUMS_REQUEST_CODE = 1
        private const val BROWSE_SONGS_REQUEST_CODE = 2
        private const val MENU_ITEM_BROWSE_SONGS_WITH_RATING = 1
        private const val MENU_ITEM_BROWSE_SONGS = 2
        private const val MENU_ITEM_BROWSE_ALBUMS = 3
    }
}
