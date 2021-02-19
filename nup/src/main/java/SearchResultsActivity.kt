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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.SongDetailsDialog.createBundle
import org.erat.nup.SongDetailsDialog.createDialog
import org.erat.nup.SongDetailsDialog.prepareDialog

/** Displays a list of songs from a search result. */
class SearchResultsActivity : Activity() {
    private val scope = MainScope()
    private var songs = listOf<Song>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Activity created")
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
            var onlyCached: Boolean,
        )

        val queries = mutableListOf<Query>()
        if (intent.action == Intent.ACTION_SEARCH) {
            // TODO: I think that this isn't actually used. If it were, we'd need
            // to deduplicate the returned songs later
            val queryString = intent.getStringExtra(SearchManager.QUERY)
            queries.add(Query(queryString, null, null, -1.0, false, true, false))
            queries.add(Query(null, null, queryString, -1.0, false, true, false))
            queries.add(Query(null, queryString, null, -1.0, false, true, false))
        } else {
            queries.add(
                Query(
                    artist = intent.getStringExtra(BUNDLE_ARTIST),
                    title = intent.getStringExtra(BUNDLE_TITLE),
                    album = intent.getStringExtra(BUNDLE_ALBUM),
                    minRating = intent.getDoubleExtra(BUNDLE_MIN_RATING, -1.0),
                    shuffle = intent.getBooleanExtra(BUNDLE_SHUFFLE, false),
                    substring = intent.getBooleanExtra(BUNDLE_SUBSTRING, false),
                    onlyCached = intent.getBooleanExtra(BUNDLE_CACHED, false),
                )
            )
        }

        scope.async(Dispatchers.Main) {
            // TODO: Support canceling.
            val dialog = ProgressDialog.show(
                this@SearchResultsActivity,
                getString(R.string.searching),
                getString(R.string.querying_database),
                true, // indeterminate
                true, // cancelable
            )

            songs = async(Dispatchers.IO) {
                val newSongs = mutableListOf<Song>()
                for (query in queries) {
                    newSongs.addAll(
                        service.songDb.query(
                            artist = query.artist,
                            title = query.title,
                            album = query.album,
                            minRating = query.minRating,
                            shuffle = query.shuffle,
                            substring = query.substring,
                            onlyCached = query.onlyCached,
                        )
                    )
                }
                newSongs
            }.await()

            if (!songs.isEmpty()) {
                val artistKey = "artist"
                val titleKey = "title"
                val data = mutableListOf<Map<String, String>>()
                for (song in songs) {
                    data.add(mapOf(artistKey to song.artist, titleKey to song.title))
                }

                val view = findViewById<View>(R.id.results) as ListView
                view.adapter = SimpleAdapter(
                    this@SearchResultsActivity,
                    data,
                    R.layout.search_results_row,
                    arrayOf(artistKey, titleKey),
                    intArrayOf(R.id.artist, R.id.title)
                )
                registerForContextMenu(view)
            }

            dialog.dismiss()

            Toast.makeText(
                this@SearchResultsActivity,
                if (!songs.isEmpty()) {
                    resources.getQuantityString(
                        R.plurals.search_found_songs_fmt,
                        songs.size,
                        songs.size,
                    )
                } else {
                    getString(R.string.no_results)
                },
                Toast.LENGTH_SHORT
            ).show()

            if (songs.isEmpty()) finish()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        view: View,
        menuInfo: ContextMenuInfo
    ) {
        if (view.id == R.id.results) {
            val info = menuInfo as AdapterContextMenuInfo
            menu.setHeaderTitle(songs[info.position].title)
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
                service.addSongToPlaylist(song, true)
                true
            }
            MENU_ITEM_INSERT -> {
                service.addSongToPlaylist(song, false)
                true
            }
            MENU_ITEM_APPEND -> {
                service.appendSongToPlaylist(song)
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
        service.appendSongsToPlaylist(songs)
        setResult(RESULT_OK)
        finish()
    }

    fun onInsertButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        service.addSongsToPlaylist(songs, false)
        setResult(RESULT_OK)
        finish()
    }

    fun onReplaceButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        service.clearPlaylist()
        service.appendSongsToPlaylist(songs)
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

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_INSERT = 2
        private const val MENU_ITEM_APPEND = 3
        private const val MENU_ITEM_SONG_DETAILS = 4

        private const val DIALOG_SONG_DETAILS = 1
    }
}
