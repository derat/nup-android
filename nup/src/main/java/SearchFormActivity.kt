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

/** Displays a form for searching for songs. */
class SearchFormActivity : Activity(), SongDatabaseUpdateListener {
    private lateinit var artistEdit: AutoCompleteTextView
    private lateinit var albumEdit: AutoCompleteTextView
    private lateinit var titleEdit: EditText
    private lateinit var shuffleCheckbox: CheckBox
    private lateinit var substringCheckbox: CheckBox
    private lateinit var cachedCheckbox: CheckBox
    private lateinit var minRatingSpinner: Spinner

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Activity created")
        super.onCreate(savedInstanceState)

        setTitle(R.string.search)
        setContentView(R.layout.search)
        artistEdit = findViewById<AutoCompleteTextView>(R.id.artist_edit_text)
        titleEdit = findViewById<EditText>(R.id.title_edit_text)

        // When the album field gets the focus, set its suggestions based on the currently-entered
        // artist.
        albumEdit = findViewById<AutoCompleteTextView>(R.id.album_edit_text)
        albumEdit.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val artist = artistEdit.text.toString()
                val albums = mutableListOf<String>()
                val albumsWithCounts = if (artist.trim().isEmpty()) {
                    service!!.songDb.albumsSortedAlphabetically
                } else {
                    service!!.songDb.albumsByArtist(artist)
                }
                for (stats in albumsWithCounts) albums.add(stats.key.album)

                albumEdit.setAdapter(
                    ArrayAdapter(
                        this@SearchFormActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        albums
                    )
                )
            }
        }

        minRatingSpinner = findViewById<Spinner>(R.id.min_rating_spinner)
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.min_rating_array, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        minRatingSpinner.adapter = adapter

        shuffleCheckbox = findViewById<CheckBox>(R.id.shuffle_checkbox)
        substringCheckbox = findViewById<CheckBox>(R.id.substring_checkbox)
        cachedCheckbox = findViewById<CheckBox>(R.id.cached_checkbox)

        service!!.addSongDatabaseUpdateListener(this)
        onSongDatabaseUpdate()
    }

    override fun onDestroy() {
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
        service!!.removeSongDatabaseUpdateListener(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RESULTS_REQUEST_CODE && resultCode == RESULT_OK) finish()
    }

    fun onSearchButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        val intent = Intent(this, SearchResultsActivity::class.java)
        intent.putExtra(SearchResultsActivity.BUNDLE_ARTIST, artistEdit.text.toString().trim())
        intent.putExtra(SearchResultsActivity.BUNDLE_TITLE, titleEdit.text.toString().trim())
        intent.putExtra(SearchResultsActivity.BUNDLE_ALBUM, albumEdit.text.toString().trim())
        intent.putExtra(
            SearchResultsActivity.BUNDLE_MIN_RATING,
            minRatingSpinner.selectedItemPosition / 4.0
        )
        intent.putExtra(SearchResultsActivity.BUNDLE_SHUFFLE, shuffleCheckbox.isChecked)
        intent.putExtra(SearchResultsActivity.BUNDLE_SUBSTRING, substringCheckbox.isChecked)
        intent.putExtra(SearchResultsActivity.BUNDLE_CACHED, cachedCheckbox.isChecked)
        startActivityForResult(intent, RESULTS_REQUEST_CODE)
    }

    fun onResetButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        artistEdit.setText("")
        albumEdit.setText("")
        titleEdit.setText("")
        shuffleCheckbox.isChecked = false
        substringCheckbox.isChecked = true
        cachedCheckbox.isChecked = false
        minRatingSpinner.setSelection(0, true)
    }

    override fun onSongDatabaseUpdate() {
        val artists = mutableListOf<String>()
        val artistsWithCounts = service!!.songDb.artistsSortedByNumSongs
        for (stats in artistsWithCounts) artists.add(stats.key.artist)
        artistEdit.setAdapter(
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
