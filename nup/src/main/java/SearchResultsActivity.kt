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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
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

        // Do the search async on the IO thread since it hits the disk or network.
        service.scope.async(Dispatchers.Main) {
            var err: SearchException? = null
            songs = async(Dispatchers.IO) {
                try {
                    val online = service.networkHelper.isNetworkAvailable
                    if (intent.action == Intent.ACTION_SEARCH) {
                        // I'm not sure when/if this is actually used. Voice searches performed via
                        // Android Auto go through onPlayFromSearch() and onSearch() in NupService.
                        // Probably it's just used if other apps send it to us.
                        searchLocal(
                            service.songDb,
                            intent.getStringExtra(SearchManager.QUERY) ?: "",
                            online = online
                        )
                    } else if (needsServerSearch()) {
                        if (!online) throw SearchException("Network is unavailable")
                        doServerSearch()
                    } else {
                        doLocalSearch()
                    }
                } catch (e: SearchException) {
                    err = e
                    listOf<Song>()
                }
            }.await()

            Log.d(TAG, "Got ${songs.size} song(s)")
            showSearchResultsToast(this@SearchResultsActivity, songs, err)
            displaySongs()
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
                service.addSongToPlaylist(song, forceSelect = true)
                service.unpause()
                true
            }
            MENU_ITEM_INSERT -> {
                service.addSongToPlaylist(song)
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean = createCommonOptionsMenu(menu)

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        handleCommonOptionsItemSelected(item)

    /** Perform the search specified in [intent] using [SongDatabase]. */
    private suspend fun doLocalSearch(): List<Song> =
        service.songDb.query(
            artist = intent.getStringExtra(BUNDLE_ARTIST),
            title = intent.getStringExtra(BUNDLE_TITLE),
            album = intent.getStringExtra(BUNDLE_ALBUM),
            minRating = intent.getIntExtra(BUNDLE_MIN_RATING, 0),
            shuffle = intent.getBooleanExtra(BUNDLE_SHUFFLE, false),
            substring = intent.getBooleanExtra(BUNDLE_SUBSTRING, false),
            onlyCached = intent.getBooleanExtra(BUNDLE_CACHED, false),
        )

    /**
     * Return true if the search request from [intent] contains "advanced" fields and needs to be
     * performed by sending a query to the server rather than via [SongDatabase].
     */
    private fun needsServerSearch(): Boolean {
        return !intent.getStringExtra(BUNDLE_KEYWORDS).isNullOrEmpty() ||
            !intent.getStringExtra(BUNDLE_TAGS).isNullOrEmpty() ||
            intent.getBooleanExtra(BUNDLE_ORDER_BY_LAST_PLAYED, false) ||
            intent.getIntExtra(BUNDLE_MAX_PLAYS, -1) >= 0 ||
            intent.getIntExtra(BUNDLE_FIRST_PLAYED, 0) > 0 ||
            intent.getIntExtra(BUNDLE_LAST_PLAYED, 0) > 0
    }

    /** Perform the search specified in [intent] over the network. */
    @Throws(SearchException::class)
    private suspend fun doServerSearch() = searchServer(
        service.songDb,
        service.downloader,
        artist = intent.getStringExtra(BUNDLE_ARTIST),
        title = intent.getStringExtra(BUNDLE_TITLE),
        album = intent.getStringExtra(BUNDLE_ALBUM),
        shuffle = intent.getBooleanExtra(BUNDLE_SHUFFLE, false),
        keywords = intent.getStringExtra(BUNDLE_KEYWORDS),
        tags = intent.getStringExtra(BUNDLE_TAGS),
        minRating = intent.getIntExtra(BUNDLE_MIN_RATING, 0),
        orderByLastPlayed = intent.getBooleanExtra(BUNDLE_ORDER_BY_LAST_PLAYED, false),
        maxPlays = intent.getIntExtra(BUNDLE_MAX_PLAYS, -1),
        firstPlayed = intent.getIntExtra(BUNDLE_FIRST_PLAYED, 0),
        lastPlayed = intent.getIntExtra(BUNDLE_LAST_PLAYED, 0)
    )

    /** Update the activity to display [songs]. */
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
        service.addSongsToPlaylist(songs)
        setResult(RESULT_OK)
        finish()
    }

    fun onReplaceButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        val wasEmpty = service.playlist.isEmpty()
        service.clearPlaylist()
        service.appendSongsToPlaylist(songs)
        if (!wasEmpty) service.unpause() // https://github.com/derat/nup-android/issues/23
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
        const val BUNDLE_KEYWORDS = "keywords"
        const val BUNDLE_TAGS = "tags"
        const val BUNDLE_ORDER_BY_LAST_PLAYED = "order_by_last_played"
        const val BUNDLE_MAX_PLAYS = "max_plays"
        const val BUNDLE_FIRST_PLAYED = "first_played"
        const val BUNDLE_LAST_PLAYED = "last_played"

        // Keys for saved instance state bundle.
        private const val BUNDLE_SONGS = "songs"

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_INSERT = 2
        private const val MENU_ITEM_APPEND = 3
        private const val MENU_ITEM_SONG_DETAILS = 4
    }
}
