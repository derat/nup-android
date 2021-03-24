/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.erat.nup.NupActivity.Companion.service

/** Displays a list of songs from a search result. */
class SearchResultsActivity : AppCompatActivity() {
    private var songs = listOf<Song>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Activity created")
        super.onCreate(savedInstanceState)

        setTitle(R.string.search_results)
        setContentView(R.layout.search_results)

        // If we're being restored e.g. after an orientation change, restore the saved results.
        // The cast here is pretty convoluted: https://stackoverflow.com/a/36570969
        if (savedInstanceState != null) {
            val serialized = savedInstanceState.getSerializable(BUNDLE_SONGS)
            if (serialized is List<*>) {
                songs = serialized.filterIsInstance<Song>()
                displaySongs()
                return
            }
        }

        // Do the search async on the IO thread since it hits the disk.
        service.scope.async(Dispatchers.Main) {
            songs = async(Dispatchers.IO) {
                if (intent.action == Intent.ACTION_SEARCH) {
                    // I'm not sure when/if this is actually used. Voice searches performed via
                    // Android Auto go through onPlayFromSearch() and onSearch() in NupService.
                    // Probably it's just used if other apps send it to us.
                    searchForSongs(service.songDb, intent.getStringExtra(SearchManager.QUERY) ?: "")
                } else {
                    service.songDb.query(
                        artist = intent.getStringExtra(BUNDLE_ARTIST),
                        title = intent.getStringExtra(BUNDLE_TITLE),
                        album = intent.getStringExtra(BUNDLE_ALBUM),
                        minRating = intent.getDoubleExtra(BUNDLE_MIN_RATING, -1.0),
                        shuffle = intent.getBooleanExtra(BUNDLE_SHUFFLE, false),
                        substring = intent.getBooleanExtra(BUNDLE_SUBSTRING, false),
                        onlyCached = intent.getBooleanExtra(BUNDLE_CACHED, false),
                    )
                }
            }.await()

            displaySongs()

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

    override fun onSaveInstanceState(outState: Bundle) {
        // Standard list implementations are apparently serializable:
        // https://stackoverflow.com/a/1387966
        outState.putSerializable(BUNDLE_SONGS, songs as Serializable)
        super.onSaveInstanceState(outState)
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
                showSongDetailsDialog(this, song)
                true
            }
            else -> false
        }
    }

    // Update the activity to display [songs].
    private fun displaySongs() {
        findViewById<View>(R.id.progress)!!.visibility = View.GONE

        if (songs.isEmpty()) return

        val artistKey = "artist"
        val titleKey = "title"
        val data = mutableListOf<Map<String, String>>()
        for (song in songs) data.add(mapOf(artistKey to song.artist, titleKey to song.title))

        val view = findViewById<ListView>(R.id.results)
        view.adapter = SimpleAdapter(
            this,
            data,
            R.layout.search_results_row,
            arrayOf(artistKey, titleKey),
            intArrayOf(R.id.artist, R.id.title)
        )
        registerForContextMenu(view)

        findViewById<Button>(R.id.append_button)!!.isEnabled = true
        findViewById<Button>(R.id.insert_button)!!.isEnabled = true
        findViewById<Button>(R.id.replace_button)!!.isEnabled = true
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

        // Keys for objects in intent bundle that's passed to us by SearchFormActivity.
        const val BUNDLE_ARTIST = "artist"
        const val BUNDLE_TITLE = "title"
        const val BUNDLE_ALBUM = "album"
        const val BUNDLE_MIN_RATING = "min_rating"
        const val BUNDLE_SHUFFLE = "shuffle"
        const val BUNDLE_SUBSTRING = "substring"
        const val BUNDLE_CACHED = "cached"

        // Keys for saved instance state bundle.
        private const val BUNDLE_SONGS = "songs"

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_INSERT = 2
        private const val MENU_ITEM_APPEND = 3
        private const val MENU_ITEM_SONG_DETAILS = 4
    }
}
