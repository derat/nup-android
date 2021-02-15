/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.Activity
import android.app.Dialog
import android.app.ProgressDialog
import android.app.SearchManager
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.SongDetailsDialog.createBundle
import org.erat.nup.SongDetailsDialog.createDialog
import org.erat.nup.SongDetailsDialog.prepareDialog

class SearchResultsActivity : Activity() {
    // Songs that we're displaying.
    private var songs: List<Song> = ArrayList()
    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "activity created")
        super.onCreate(savedInstanceState)
        setTitle(R.string.search_results)
        setContentView(R.layout.search_results)
        class Query(
            var artist: String?,
            var title: String?,
            var album: String?,
            // TODO: Include albumId somehow.
            var minRating: Double,
            var shuffle: Boolean,
            var substring: Boolean,
            var onlyCached: Boolean
        )

        val queries: MutableList<Query> = ArrayList()
        val intent = intent
        if (Intent.ACTION_SEARCH == intent.action) {
            val queryString = intent.getStringExtra(SearchManager.QUERY)
            queries.add(Query(queryString, null, null, -1.0, false, true, false))
            queries.add(Query(null, null, queryString, -1.0, false, true, false))
            queries.add(Query(null, queryString, null, -1.0, false, true, false))
        } else {
            queries.add(
                Query(
                    intent.getStringExtra(BUNDLE_ARTIST),
                    intent.getStringExtra(BUNDLE_TITLE),
                    intent.getStringExtra(BUNDLE_ALBUM),
                    intent.getDoubleExtra(BUNDLE_MIN_RATING, -1.0),
                    intent.getBooleanExtra(BUNDLE_SHUFFLE, false),
                    intent.getBooleanExtra(BUNDLE_SUBSTRING, false),
                    intent.getBooleanExtra(BUNDLE_CACHED, false)
                )
            )
        }
        object : AsyncTask<Void?, Void?, List<Song>>() {
            private var dialog: ProgressDialog? = null
            override fun onPreExecute() {
                dialog = ProgressDialog.show(
                    this@SearchResultsActivity,
                    getString(R.string.searching),
                    getString(R.string.querying_database),
                    true, // indeterminate
                    true, // cancelable
                )
                // FIXME: Support canceling.
            }

            protected override fun doInBackground(vararg args: Void?): List<Song> {
                val newSongs = ArrayList<Song>()
                for (query in queries) {
                    newSongs.addAll(
                        service!!
                            .songDb!!
                            .query(
                                query.artist,
                                query.title,
                                query.album,
                                null,
                                query.minRating,
                                query.shuffle,
                                query.substring,
                                query.onlyCached
                            )
                    )
                }
                return newSongs
            }

            override fun onPostExecute(newSongs: List<Song>) {
                songs = newSongs
                if (!songs.isEmpty()) {
                    val artistKey = "artist"
                    val titleKey = "title"
                    val data: MutableList<HashMap<String, String?>> = ArrayList()
                    for (song in songs) {
                        val map = HashMap<String, String?>()
                        map[artistKey] = song.artist
                        map[titleKey] = song.title
                        data.add(map)
                    }
                    val adapter = SimpleAdapter(
                        this@SearchResultsActivity,
                        data,
                        R.layout.search_results_row,
                        arrayOf(artistKey, titleKey),
                        intArrayOf(R.id.artist, R.id.title)
                    )
                    val view = findViewById<View>(R.id.results) as ListView
                    view.adapter = adapter
                    registerForContextMenu(view)
                }
                dialog!!.dismiss()
                val message = if (!songs.isEmpty()) resources
                    .getQuantityString(
                        R.plurals.search_found_songs_fmt,
                        songs.size,
                        songs.size
                    ) else getString(R.string.no_results)
                Toast.makeText(this@SearchResultsActivity, message, Toast.LENGTH_SHORT).show()
                if (songs.isEmpty()) finish()
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    override fun onDestroy() {
        Log.d(TAG, "activity destroyed")
        super.onDestroy()
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        view: View,
        menuInfo: ContextMenuInfo
    ) {
        if (view.id == R.id.results) {
            val info = menuInfo as AdapterContextMenuInfo
            val song = songs[info.position]
            menu.setHeaderTitle(song.title)
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play)
            menu.add(0, MENU_ITEM_INSERT, 0, R.string.insert)
            menu.add(0, MENU_ITEM_APPEND, 0, R.string.append)
            menu.add(0, MENU_ITEM_SONG_DETAILS, 0, R.string.song_details_ellipsis)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val song = songs[info.position]
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

    override fun onCreateDialog(id: Int, args: Bundle): Dialog? {
        return if (id == DIALOG_SONG_DETAILS) createDialog(this) else null
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog, args: Bundle) {
        super.onPrepareDialog(id, dialog, args)
        if (id == DIALOG_SONG_DETAILS) prepareDialog(dialog, args)
    }

    fun onAppendButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        service!!.appendSongsToPlaylist(songs)
        setResult(RESULT_OK)
        finish()
    }

    fun onInsertButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        service!!.addSongsToPlaylist(songs, false)
        setResult(RESULT_OK)
        finish()
    }

    fun onReplaceButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        service!!.clearPlaylist()
        service!!.appendSongsToPlaylist(songs)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "SearchResultsActivity"

        // Key for the objects in the bundle that's passed to us by SearchFormActivity.
        const val BUNDLE_ARTIST = "artist"
        const val BUNDLE_TITLE = "title"
        const val BUNDLE_ALBUM = "album"
        const val BUNDLE_MIN_RATING = "min_rating"
        const val BUNDLE_SHUFFLE = "shuffle"
        const val BUNDLE_SUBSTRING = "substring"
        const val BUNDLE_CACHED = "cached"
        const val SONG_KEY = "songs"

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_INSERT = 2
        private const val MENU_ITEM_APPEND = 3
        private const val MENU_ITEM_SONG_DETAILS = 4
        private const val DIALOG_SONG_DETAILS = 1
    }
}
