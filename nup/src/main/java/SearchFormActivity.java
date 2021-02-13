// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.List;

public class SearchFormActivity extends Activity implements NupService.SongDatabaseUpdateListener {
    private static final String TAG = "SearchFormActivity";

    // IDs used to identify activities that we start.
    private static final int RESULTS_REQUEST_CODE = 1;

    // Various parts of our UI.
    private AutoCompleteTextView artistEdit, albumEdit;
    private EditText titleEdit;
    private CheckBox shuffleCheckbox, substringCheckbox, cachedCheckbox;
    private Spinner minRatingSpinner;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setTitle(R.string.search);
        setContentView(R.layout.search);

        artistEdit = (AutoCompleteTextView) findViewById(R.id.artist_edit_text);
        titleEdit = (EditText) findViewById(R.id.title_edit_text);

        // When the album field gets the focus, set its suggestions based on the currently-entered
        // artist.
        albumEdit = (AutoCompleteTextView) findViewById(R.id.album_edit_text);
        albumEdit.setOnFocusChangeListener(
                new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus) {
                            String artist = artistEdit.getText().toString();
                            List<String> albums = new ArrayList<String>();
                            List<StatsRow> albumsWithCounts =
                                    artist.trim().isEmpty()
                                            ? NupActivity.getService()
                                                    .getSongDb()
                                                    .getAlbumsSortedAlphabetically()
                                            : NupActivity.getService()
                                                    .getSongDb()
                                                    .getAlbumsByArtist(artist);
                            if (albumsWithCounts != null) {
                                for (StatsRow stats : albumsWithCounts) {
                                    albums.add(stats.key.album);
                                }
                            }
                            albumEdit.setAdapter(
                                    new ArrayAdapter<String>(
                                            SearchFormActivity.this,
                                            android.R.layout.simple_dropdown_item_1line,
                                            albums));
                        }
                    }
                });

        minRatingSpinner = (Spinner) findViewById(R.id.min_rating_spinner);
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(
                        this, R.array.min_rating_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        minRatingSpinner.setAdapter(adapter);

        shuffleCheckbox = (CheckBox) findViewById(R.id.shuffle_checkbox);
        substringCheckbox = (CheckBox) findViewById(R.id.substring_checkbox);
        cachedCheckbox = (CheckBox) findViewById(R.id.cached_checkbox);

        NupActivity.getService().addSongDatabaseUpdateListener(this);
        onSongDatabaseUpdate();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
        NupActivity.getService().removeSongDatabaseUpdateListener(this);
    }

    private void resetForm() {
        artistEdit.setText("");
        albumEdit.setText("");
        titleEdit.setText("");
        shuffleCheckbox.setChecked(false);
        substringCheckbox.setChecked(true);
        cachedCheckbox.setChecked(false);
        minRatingSpinner.setSelection(0, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RESULTS_REQUEST_CODE) {
            if (resultCode == RESULT_OK) finish();
        }
    }

    public void onSearchButtonClicked(View view) {
        Intent intent = new Intent(this, SearchResultsActivity.class);
        intent.putExtra(
                SearchResultsActivity.BUNDLE_ARTIST, artistEdit.getText().toString().trim());
        intent.putExtra(SearchResultsActivity.BUNDLE_TITLE, titleEdit.getText().toString().trim());
        intent.putExtra(SearchResultsActivity.BUNDLE_ALBUM, albumEdit.getText().toString().trim());
        intent.putExtra(
                SearchResultsActivity.BUNDLE_MIN_RATING,
                minRatingSpinner.getSelectedItemPosition() / 4.0);
        intent.putExtra(SearchResultsActivity.BUNDLE_SHUFFLE, shuffleCheckbox.isChecked());
        intent.putExtra(SearchResultsActivity.BUNDLE_SUBSTRING, substringCheckbox.isChecked());
        intent.putExtra(SearchResultsActivity.BUNDLE_CACHED, cachedCheckbox.isChecked());
        startActivityForResult(intent, RESULTS_REQUEST_CODE);
    }

    public void onResetButtonClicked(View view) {
        resetForm();
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        List<String> artists = new ArrayList<String>();
        List<StatsRow> artistsWithCounts =
                NupActivity.getService().getSongDb().getArtistsSortedByNumSongs();
        if (artistsWithCounts != null) {
            for (StatsRow stats : artistsWithCounts) artists.add(stats.key.artist);
        }
        artistEdit.setAdapter(
                new ArrayAdapter<String>(
                        SearchFormActivity.this,
                        android.R.layout.simple_dropdown_item_1line,
                        artists));
    }
}
