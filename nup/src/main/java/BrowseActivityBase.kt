/*
 * Copyright 2016 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.ListActivity
import android.content.Intent
import android.os.Bundle
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ListView
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.erat.nup.NupActivity.Companion.service

abstract class BrowseActivityBase :
    ListActivity(),
    CoroutineScope,
    NupService.SongDatabaseUpdateListener {
    lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    private val rows: MutableList<StatsRow> = ArrayList()
    private lateinit var adapter: StatsRowArrayAdapter

    protected lateinit var onlyArtist: String // artist passed in intent (may be null)
    protected var onlyCached: Boolean = false // displaying only cached songs

    abstract val display: StatsRowArrayAdapter.Display

    abstract fun onRowClick(row: StatsRow, pos: Int)
    abstract fun fillMenu(menu: ContextMenu, row: StatsRow)
    abstract fun onMenuClick(itemId: Int, row: StatsRow): Boolean
    abstract fun getRows(db: SongDatabase, update: (rows: List<StatsRow>?) -> Unit)

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        job = Job()
        onlyArtist = intent.getStringExtra(BUNDLE_ARTIST) ?: ""
        onlyCached = intent.getBooleanExtra(BUNDLE_CACHED, false)

        adapter = StatsRowArrayAdapter(
            this,
            R.layout.browse_row,
            rows,
            display,
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browse_menu, menu)
        return true
    }

    override fun onListItemClick(listView: ListView, view: View, position: Int, id: Long) {
        onRowClick(rows[position], position)
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenuInfo) {
        fillMenu(menu, rows[(menuInfo as AdapterContextMenuInfo).position])
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return onMenuClick(item.itemId, rows[(item.menuInfo as AdapterContextMenuInfo).position])
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.browse_pause_menu_item -> {
                service!!.pause()
                true
            }
            R.id.browse_return_menu_item -> {
                setResult(RESULT_OK)
                finish()
                true
            }
            else -> false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onSongDatabaseUpdate() {
        getRows(
            service!!.songDb!!,
            { newRows: List<StatsRow>? ->
                rows.clear()
                if (newRows == null) {
                    val msg = getString(R.string.loading)
                    rows.add(StatsRow(msg, msg, "", -1))
                    listView.isFastScrollEnabled = false
                    adapter.enabled = false
                    adapter.notifyDataSetChanged()
                } else {
                    rows.addAll(newRows)
                    listView.isFastScrollEnabled = false
                    adapter.enabled = true
                    adapter.notifyDataSetChanged()
                    listView.isFastScrollEnabled = true
                }
            }
        )
    }

    /** Launch BrowseSongsActivity for the given criteria. */
    protected fun startBrowseSongsActivity(
        artist: String? = null,
        album: String? = null,
        albumId: String? = null,
        minRating: Double = -1.0,
    ) {
        val intent = Intent(this, BrowseSongsActivity::class.java)
        if (artist != null) intent.putExtra(BUNDLE_ARTIST, artist)
        if (album != null) intent.putExtra(BUNDLE_ALBUM, album)
        if (albumId != null) intent.putExtra(BUNDLE_ALBUM_ID, albumId)
        if (minRating >= 0) intent.putExtra(BUNDLE_MIN_RATING, minRating)
        intent.putExtra(BUNDLE_CACHED, onlyCached)
        startActivityForResult(intent, BROWSE_SONGS_REQUEST_CODE)
    }

    companion object {
        // Identifiers for activities that we start.
        const val BROWSE_ARTISTS_REQUEST_CODE = 1
        const val BROWSE_ALBUMS_REQUEST_CODE = 2
        const val BROWSE_SONGS_REQUEST_CODE = 3

        const val BUNDLE_ARTIST = "artist"
        const val BUNDLE_ALBUM = "album"
        const val BUNDLE_ALBUM_ID = "album_id"
        const val BUNDLE_MIN_RATING = "min_rating"
        const val BUNDLE_CACHED = "cached"
    }
}
