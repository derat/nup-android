// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.app.Activity
import android.app.Dialog
import android.os.AsyncTask
import android.os.Bundle
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.AdapterView.OnItemClickListener
import org.erat.nup.BrowseSongsActivity
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.SongDetailsDialog.createBundle
import org.erat.nup.SongDetailsDialog.createDialog
import org.erat.nup.SongDetailsDialog.prepareDialog
import org.erat.nup.Util.getSortingKey
import java.util.*

// This class doesn't extend BrowseActivityBase since it displays a different menu.
class BrowseSongsActivity : Activity(), OnItemClickListener {
    // Passed-in criteria specifying which songs to display.
    private var artist: String? = null
    private var album: String? = null
    private var albumId: String? = null
    private var onlyCached = false
    private var minRating = -1.0

    // Songs that we're displaying.
    private var songs: List<Song> = ArrayList()
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.browse_songs)
        artist = intent.getStringExtra(BrowseActivityBase.BUNDLE_ARTIST)
        album = intent.getStringExtra(BrowseActivityBase.BUNDLE_ALBUM)
        albumId = intent.getStringExtra(BrowseActivityBase.BUNDLE_ALBUM_ID)
        onlyCached = intent.getBooleanExtra(BrowseActivityBase.BUNDLE_CACHED, false)
        minRating = intent.getDoubleExtra(BrowseActivityBase.BUNDLE_MIN_RATING, -1.0)
        title = if (album != null) {
            getString(
                    if (onlyCached) R.string.browse_cached_songs_from_album_fmt else R.string.browse_songs_from_album_fmt,
                    album)
        } else if (artist != null) {
            getString(
                    if (onlyCached) R.string.browse_cached_songs_by_artist_fmt else R.string.browse_songs_by_artist_fmt,
                    artist)
        } else {
            getString(if (onlyCached) R.string.browse_cached_songs else R.string.browse_songs)
        }

        // Do the query for the songs in the background.
        object : AsyncTask<Void?, Void?, List<Song>>() {
            override fun onPreExecute() {
                // Create a temporary ArrayAdapter that just says "Loading...".
                val items: MutableList<String> = ArrayList()
                items.add(getString(R.string.loading))
                val adapter: ArrayAdapter<String> = object : ArrayAdapter<String>(
                        this@BrowseSongsActivity, R.layout.browse_row, R.id.main, items) {
                    override fun areAllItemsEnabled(): Boolean {
                        return false
                    }

                    override fun isEnabled(position: Int): Boolean {
                        return false
                    }
                }
                val view = findViewById<View>(R.id.songs) as ListView
                view.adapter = adapter
            }

            protected override fun doInBackground(vararg args: Void?): List<Song> {
                return service!!
                        .songDb!!
                        .query(artist, null, album, albumId, minRating, false, false, onlyCached)
            }

            override fun onPostExecute(newSongs: List<Song>) {
                // The results come back in album order. If we're viewing all songs by
                // an artist, sort them alphabetically instead.
                if ((album == null || album!!.isEmpty()) && (albumId == null || albumId!!.isEmpty())) {
                    Collections.sort(
                            newSongs
                    ) { a, b ->
                        getSortingKey(a.title, Util.SORT_TITLE)
                                .compareTo(
                                        getSortingKey(b.title, Util.SORT_TITLE))
                    }
                }
                songs = newSongs
                val titleKey = "title"
                val data: MutableList<HashMap<String, String?>> = ArrayList()
                for (song in songs) {
                    val map = HashMap<String, String?>()
                    map[titleKey] = song.title
                    data.add(map)
                }
                val adapter = SimpleAdapter(
                        this@BrowseSongsActivity,
                        data,
                        R.layout.browse_row, arrayOf(titleKey), intArrayOf(R.id.main))
                val view = findViewById<View>(R.id.songs) as ListView
                view.adapter = adapter
                view.onItemClickListener = this@BrowseSongsActivity
                registerForContextMenu(view)
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browse_menu, menu)
        return true
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

    override fun onCreateContextMenu(
            menu: ContextMenu, view: View, menuInfo: ContextMenuInfo) {
        if (view.id == R.id.songs) {
            val info = menuInfo as AdapterContextMenuInfo
            val song = songs[info.position] ?: return
            menu.setHeaderTitle(song.title)
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play)
            menu.add(0, MENU_ITEM_INSERT, 0, R.string.insert)
            menu.add(0, MENU_ITEM_APPEND, 0, R.string.append)
            menu.add(0, MENU_ITEM_SONG_DETAILS, 0, R.string.song_details_ellipsis)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val song = songs[info.position] ?: return false
        return when (item.itemId) {
            MENU_ITEM_PLAY -> {
                service!!.addSongToPlaylist(song, true)
                true
            }
            MENU_ITEM_INSERT -> {
                service!!.addSongToPlaylist(song, false)
                true
            }
            MENU_ITEM_APPEND -> {
                service!!.appendSongToPlaylist(song)
                true
            }
            MENU_ITEM_SONG_DETAILS -> {
                showDialog(DIALOG_SONG_DETAILS, createBundle(song))
                true
            }
            else -> false
        }
    }

    // Implements AdapterView.OnItemClickListener.
    override fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
        val song = songs[position] ?: return
        service!!.appendSongToPlaylist(song)
        Toast.makeText(this, getString(R.string.appended_song_fmt, song.title), Toast.LENGTH_SHORT)
                .show()
    }

    override fun onCreateDialog(id: Int, args: Bundle): Dialog? {
        return if (id == DIALOG_SONG_DETAILS) createDialog(this) else null
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog, args: Bundle) {
        super.onPrepareDialog(id, dialog, args)
        if (id == DIALOG_SONG_DETAILS) prepareDialog(dialog, args)
    }

    fun onAppendButtonClicked(view: View?) {
        if (songs.isEmpty()) return
        service!!.appendSongsToPlaylist(songs)
        setResult(RESULT_OK)
        finish()
    }

    fun onInsertButtonClicked(view: View?) {
        if (songs.isEmpty()) return
        service!!.addSongsToPlaylist(songs, false)
        setResult(RESULT_OK)
        finish()
    }

    fun onReplaceButtonClicked(view: View?) {
        if (songs.isEmpty()) return
        service!!.clearPlaylist()
        service!!.appendSongsToPlaylist(songs)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "BrowseSongsActivity"

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_INSERT = 2
        private const val MENU_ITEM_APPEND = 3
        private const val MENU_ITEM_SONG_DETAILS = 4
        private const val DIALOG_SONG_DETAILS = 1
    }
}