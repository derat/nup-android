/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.View.OnFocusChangeListener
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import org.erat.nup.NupActivity.Companion.service
import org.erat.nup.NupService.SongDatabaseUpdateListener

class SearchFormActivity : Activity(), SongDatabaseUpdateListener {
    // Various parts of our UI.
    private var artistEdit: AutoCompleteTextView? = null
    private var albumEdit: AutoCompleteTextView? = null
    private var titleEdit: EditText? = null
    private var shuffleCheckbox: CheckBox? = null
    private var substringCheckbox: CheckBox? = null
    private var cachedCheckbox: CheckBox? = null
    private var minRatingSpinner: Spinner? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "activity created")
        super.onCreate(savedInstanceState)
        setTitle(R.string.search)
        setContentView(R.layout.search)
        artistEdit = findViewById<View>(R.id.artist_edit_text) as AutoCompleteTextView
        titleEdit = findViewById<View>(R.id.title_edit_text) as EditText

        // When the album field gets the focus, set its suggestions based on the currently-entered
        // artist.
        albumEdit = findViewById<View>(R.id.album_edit_text) as AutoCompleteTextView
        albumEdit!!.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val artist = artistEdit!!.text.toString()
                val albums: MutableList<String> = ArrayList()
                val albumsWithCounts = if (artist.trim { it <= ' ' }.isEmpty()) {
                    service!!.songDb.getAlbumsSortedAlphabetically()
                } else {
                    service!!.songDb.getAlbumsByArtist(artist)
                }

                for (stats in albumsWithCounts) albums.add(stats.key.album)
                albumEdit!!.setAdapter(
                    ArrayAdapter(
                        this@SearchFormActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        albums
                    )
                )
            }
        }
        minRatingSpinner = findViewById<View>(R.id.min_rating_spinner) as Spinner
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.min_rating_array, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        minRatingSpinner!!.adapter = adapter
        shuffleCheckbox = findViewById<View>(R.id.shuffle_checkbox) as CheckBox
        substringCheckbox = findViewById<View>(R.id.substring_checkbox) as CheckBox
        cachedCheckbox = findViewById<View>(R.id.cached_checkbox) as CheckBox
        service!!.addSongDatabaseUpdateListener(this)
        onSongDatabaseUpdate()
    }

    override fun onDestroy() {
        Log.d(TAG, "activity destroyed")
        super.onDestroy()
        service!!.removeSongDatabaseUpdateListener(this)
    }

    private fun resetForm() {
        artistEdit!!.setText("")
        albumEdit!!.setText("")
        titleEdit!!.setText("")
        shuffleCheckbox!!.isChecked = false
        substringCheckbox!!.isChecked = true
        cachedCheckbox!!.isChecked = false
        minRatingSpinner!!.setSelection(0, true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RESULTS_REQUEST_CODE && resultCode == RESULT_OK) finish()
    }

    fun onSearchButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        val intent = Intent(this, SearchResultsActivity::class.java)
        intent.putExtra(
            SearchResultsActivity.BUNDLE_ARTIST, artistEdit!!.text.toString().trim { it <= ' ' }
        )
        intent.putExtra(
            SearchResultsActivity.BUNDLE_TITLE,
            titleEdit!!.text.toString().trim { it <= ' ' }
        )
        intent.putExtra(
            SearchResultsActivity.BUNDLE_ALBUM,
            albumEdit!!.text.toString().trim { it <= ' ' }
        )
        intent.putExtra(
            SearchResultsActivity.BUNDLE_MIN_RATING,
            minRatingSpinner!!.selectedItemPosition / 4.0
        )
        intent.putExtra(SearchResultsActivity.BUNDLE_SHUFFLE, shuffleCheckbox!!.isChecked)
        intent.putExtra(SearchResultsActivity.BUNDLE_SUBSTRING, substringCheckbox!!.isChecked)
        intent.putExtra(SearchResultsActivity.BUNDLE_CACHED, cachedCheckbox!!.isChecked)
        startActivityForResult(intent, RESULTS_REQUEST_CODE)
    }

    fun onResetButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        resetForm()
    }

    // Implements NupService.SongDatabaseUpdateListener.
    override fun onSongDatabaseUpdate() {
        val artists: MutableList<String> = ArrayList()
        val artistsWithCounts = service!!.songDb.getArtistsSortedByNumSongs()
        for (stats in artistsWithCounts) artists.add(stats.key.artist)
        artistEdit!!.setAdapter(
            ArrayAdapter(
                this@SearchFormActivity,
                android.R.layout.simple_dropdown_item_1line,
                artists
            )
        )
    }

    companion object {
        private const val TAG = "SearchFormActivity"

        // IDs used to identify activities that we start.
        private const val RESULTS_REQUEST_CODE = 1
    }
}
