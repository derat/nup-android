/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.NupService.SongDatabaseUpdateListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener

/** Displays a form for searching for songs. */
class SearchFormActivity : AppCompatActivity(), SongDatabaseUpdateListener {
    private lateinit var artistEdit: AutoCompleteTextView
    private lateinit var albumEdit: AutoCompleteTextView
    private lateinit var titleEdit: EditText
    private lateinit var shuffleCheckbox: CheckBox
    private lateinit var substringCheckbox: CheckBox
    private lateinit var cachedCheckbox: CheckBox
    private lateinit var minRatingSpinner: AutoCompleteTextView
    private lateinit var keywordsEdit: EditText
    private lateinit var tagsEdit: AutoCompleteTextView
    private lateinit var orderByLastPlayedCheckbox: CheckBox
    private lateinit var maxPlaysEdit: EditText
    private lateinit var firstPlayedSpinner: AutoCompleteTextView
    private lateinit var lastPlayedSpinner: AutoCompleteTextView

    private lateinit var artistEditAdapter: ArrayAdapter<String>
    private lateinit var albumEditAdapter: ArrayAdapter<String>
    private lateinit var tagsEditAdapter: ArrayAdapter<String>

    private var tags = listOf<String>() // server-supplied tags
    private var tagsPrefix = "" // current already-typed text for tag autocomplete suggestions

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Activity created")
        super.onCreate(savedInstanceState)

        // When the keyboard is shown, pan the window instead of resizing it. Otherwise, the buttons
        // at the bottom get pushed up and can obscure the focused text field.
        // TODO: Find a way to keep the buttons onscreen while also not obscuring the text field.
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        )

        setTitle(R.string.search)
        setContentView(R.layout.search)

        artistEdit = findViewById<AutoCompleteTextView>(R.id.artist_edit_text)
        artistEditAdapter = createAutocompleteAdapter(artistEdit)

        titleEdit = findViewById<EditText>(R.id.title_edit_text)

        // When the artist is changed, update the album field's suggestions.
        albumEdit = findViewById<AutoCompleteTextView>(R.id.album_edit_text)
        albumEditAdapter = createAutocompleteAdapter(albumEdit)
        artistEdit.doAfterTextChanged { _ -> updateAlbumSuggestions() }

        minRatingSpinner = configureSpinner(R.id.min_rating_spinner, R.array.min_ratings)
        shuffleCheckbox = findViewById<CheckBox>(R.id.shuffle_checkbox)
        substringCheckbox = findViewById<CheckBox>(R.id.substring_checkbox)
        cachedCheckbox = findViewById<CheckBox>(R.id.cached_checkbox)
        keywordsEdit = findViewById<EditText>(R.id.keywords_edit_text)

        tagsEdit = findViewById<AutoCompleteTextView>(R.id.tags_edit_text)
        tagsEditAdapter = createAutocompleteAdapter(tagsEdit)
        tagsEdit.doAfterTextChanged { _ -> updateTagsSuggestions() }

        orderByLastPlayedCheckbox = findViewById<CheckBox>(R.id.order_by_last_played_checkbox)
        maxPlaysEdit = findViewById<EditText>(R.id.max_plays_edit_text)
        firstPlayedSpinner = configureSpinner(R.id.first_played_spinner, R.array.played_times)
        lastPlayedSpinner = configureSpinner(R.id.last_played_spinner, R.array.played_times)

        if (service.networkHelper.isNetworkAvailable) fetchTags()

        service.addSongDatabaseUpdateListener(this)
        onSongDatabaseUpdate()
    }

    override fun onDestroy() {
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
        service.removeSongDatabaseUpdateListener(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = createCommonOptionsMenu(menu)

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        handleCommonOptionsItemSelected(item)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RESULTS_REQUEST_CODE && resultCode == RESULT_OK) finish()
    }

    /** Create an [ArrayAdapter] for [view]. */
    private fun createAutocompleteAdapter(view: AutoCompleteTextView): ArrayAdapter<String> {
        val adapter = ArrayAdapter<String>(
            this@SearchFormActivity,
            android.R.layout.simple_dropdown_item_1line
        )
        view.setAdapter(adapter)
        return adapter
    }

    /** Configure AutoCompleteTextView [viewId] to use string array [valuesId]. */
    private fun configureSpinner(viewId: Int, valuesId: Int): AutoCompleteTextView {
        val spinner = findViewById<AutoCompleteTextView>(viewId)
        spinner.setAdapter(
            ArrayAdapter(
                this@SearchFormActivity,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(valuesId)
            )
        )
        return spinner
    }

    /** Thrown if an error is encountered while fetching tags. */
    class FetchTagsException(reason: String) : Exception(reason)

    /** Asynchronously fetch tags from the server and update [tags]. */
    private fun fetchTags() {
        service.scope.async(Dispatchers.Main) {
            try {
                tags = async(Dispatchers.IO) {
                    val (response, error) = service.downloader.downloadString("/tags")
                    response ?: throw FetchTagsException(error!!)
                    try {
                        JSONArray(JSONTokener(response)).iterator<String>().asSequence().toList()
                    } catch (e: JSONException) {
                        throw FetchTagsException("Couldn't parse tags: $e")
                    }
                }.await()
                updateTagsSuggestions()
            } catch (e: FetchTagsException) {
                Toast.makeText(this@SearchFormActivity, e.message!!, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Update the autocomplete suggestions shown for the album field. */
    private fun updateAlbumSuggestions() {
        val artist = artistEdit.text.toString().trim()
        val albums =
            if (artist.isEmpty()) service.songDb.albumsSortedAlphabetically
            else service.songDb.albumsByArtist(artist)

        albumEditAdapter.clear()
        albumEditAdapter.addAll(albums.map { it.key.album }.toMutableList())
        albumEditAdapter.notifyDataSetChanged()
    }

    /** Update the autocomplete suggestions shown for the tags field. */
    private fun updateTagsSuggestions() {
        // Chop off the last word (if any), as long as it doesn't have trailing space.
        val pre = tagsEdit.text.toString().replace("(^|\\s)\\S+$".toRegex(), "$1")

        // Bail out if we're already showing the correct suggestions.
        if (pre == tagsPrefix && tagsEditAdapter.getCount() > 0) return

        // Find all the tags that have been used already.
        val seen = pre.trim().split("\\s+".toRegex()).map { it.removePrefix("-") }.toSet()

        // Append all not-yet-used tags in both positive and negative form.
        tagsEditAdapter.clear()
        tags.filter { !seen.contains(it) }.forEach { tag ->
            tagsEditAdapter.add("$pre$tag ")
            tagsEditAdapter.add("$pre-$tag ")
        }
        tagsEditAdapter.notifyDataSetChanged()
        tagsPrefix = pre
    }

    fun onSearchButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        val intent = Intent(this, SearchResultsActivity::class.java)
        intent.putExtra(SearchResultsActivity.BUNDLE_ARTIST, artistEdit.text.toString().trim())
        intent.putExtra(SearchResultsActivity.BUNDLE_TITLE, titleEdit.text.toString().trim())
        intent.putExtra(SearchResultsActivity.BUNDLE_ALBUM, albumEdit.text.toString().trim())
        intent.putExtra(SearchResultsActivity.BUNDLE_SHUFFLE, shuffleCheckbox.isChecked)
        intent.putExtra(SearchResultsActivity.BUNDLE_SUBSTRING, substringCheckbox.isChecked)
        intent.putExtra(SearchResultsActivity.BUNDLE_CACHED, cachedCheckbox.isChecked)

        // This is a hack, but Android doesn't supply a Material spinner, and the only way to
        // determine the index of an AutoCompleteTextView's selected item is apparently by listening
        // for clicks.
        val stars = minRatingSpinner.text.toString().length
        if (stars > 0) intent.putExtra(SearchResultsActivity.BUNDLE_MIN_RATING, stars)

        intent.putExtra(SearchResultsActivity.BUNDLE_KEYWORDS, keywordsEdit.text.toString().trim())
        intent.putExtra(SearchResultsActivity.BUNDLE_TAGS, tagsEdit.text.toString().trim())
        intent.putExtra(
            SearchResultsActivity.BUNDLE_ORDER_BY_LAST_PLAYED,
            orderByLastPlayedCheckbox.isChecked
        )

        val maxPlays = maxPlaysEdit.text.toString().trim()
        if (!maxPlays.isEmpty()) {
            intent.putExtra(SearchResultsActivity.BUNDLE_MAX_PLAYS, maxPlays.toInt())
        }

        val playedStr = getResources().getStringArray(R.array.played_times)
        val playedSec = getResources().getStringArray(R.array.played_times_sec).map { it.toInt() }
        val firstPlayed = playedSec[playedStr.indexOf(firstPlayedSpinner.text.toString())]
        if (firstPlayed > 0) intent.putExtra(SearchResultsActivity.BUNDLE_FIRST_PLAYED, firstPlayed)
        val lastPlayed = playedSec[playedStr.indexOf(lastPlayedSpinner.text.toString())]
        if (lastPlayed > 0) intent.putExtra(SearchResultsActivity.BUNDLE_LAST_PLAYED, lastPlayed)

        startActivityForResult(intent, RESULTS_REQUEST_CODE)
    }

    fun onResetButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        artistEdit.setText("")
        albumEdit.setText("")
        titleEdit.setText("")
        shuffleCheckbox.isChecked = false
        substringCheckbox.isChecked = true
        cachedCheckbox.isChecked = false
        minRatingSpinner.setText("")
        keywordsEdit.setText("")
        tagsEdit.setText("")
        orderByLastPlayedCheckbox.isChecked = false
        maxPlaysEdit.setText("")
        firstPlayedSpinner.setText("")
        lastPlayedSpinner.setText("")
    }

    override fun onSongDatabaseSyncChange(state: SongDatabase.SyncState, updatedSongs: Int) {}
    override fun onSongDatabaseUpdate() {
        artistEditAdapter.clear()
        artistEditAdapter.addAll(
            service.songDb.artistsSortedByNumSongs.map { it.key.artist }.toMutableList()
        )
        artistEditAdapter.notifyDataSetChanged()

        updateAlbumSuggestions()
    }

    companion object {
        private const val TAG = "SearchFormActivity"

        // IDs used to identify activities that we start.
        private const val RESULTS_REQUEST_CODE = 1
    }
}
