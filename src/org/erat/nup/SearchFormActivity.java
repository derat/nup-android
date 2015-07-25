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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class SearchFormActivity extends Activity
                                implements NupService.SongDatabaseUpdateListener {
    private static final String TAG = "SearchFormActivity";

    // IDs used to identify activities that we start.
    private static final int RESULTS_REQUEST_CODE = 1;

    // Various parts of our UI.
    private AutoCompleteTextView mArtistEdit, mAlbumEdit;
    private EditText mTitleEdit;
    private CheckBox mShuffleCheckbox, mSubstringCheckbox, mCachedCheckbox;
    private Spinner mMinRatingSpinner;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setTitle(R.string.search);
        setContentView(R.layout.search);

        mArtistEdit = (AutoCompleteTextView) findViewById(R.id.artist_edit_text);
        mTitleEdit = (EditText) findViewById(R.id.title_edit_text);

        // When the album field gets the focus, set its suggestions based on the currently-entered artist.
        mAlbumEdit = (AutoCompleteTextView) findViewById(R.id.album_edit_text);
        mAlbumEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    String artist = mArtistEdit.getText().toString();
                    List<String> albums = new ArrayList<String>();
                    List<StringIntPair> albumsWithCounts =
                        artist.trim().isEmpty() ?
                        NupActivity.getService().getSongDb().getAlbumsSortedAlphabetically() :
                        NupActivity.getService().getSongDb().getAlbumsByArtist(artist);
                    if (albumsWithCounts != null) {
                        for (StringIntPair pair : albumsWithCounts) {
                            albums.add(pair.getString());
                        }
                    }
                    mAlbumEdit.setAdapter(new ArrayAdapter<String>(SearchFormActivity.this, android.R.layout.simple_dropdown_item_1line, albums));
                }
            }
        });

        mMinRatingSpinner = (Spinner) findViewById(R.id.min_rating_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.min_rating_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mMinRatingSpinner.setAdapter(adapter);

        mShuffleCheckbox = (CheckBox) findViewById(R.id.shuffle_checkbox);
        mSubstringCheckbox = (CheckBox) findViewById(R.id.substring_checkbox);
        mCachedCheckbox = (CheckBox) findViewById(R.id.cached_checkbox);

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
        mArtistEdit.setText("");
        mAlbumEdit.setText("");
        mTitleEdit.setText("");
        mShuffleCheckbox.setChecked(false);
        mSubstringCheckbox.setChecked(true);
        mCachedCheckbox.setChecked(false);
        mMinRatingSpinner.setSelection(0, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RESULTS_REQUEST_CODE) {
            if (resultCode == RESULT_OK)
                finish();
        }
    }

    public void onSearchButtonClicked(View view) {
        Intent intent = new Intent(this, SearchResultsActivity.class);
        intent.putExtra(SearchResultsActivity.BUNDLE_ARTIST,
                        mArtistEdit.getText().toString().trim());
        intent.putExtra(SearchResultsActivity.BUNDLE_TITLE,
                        mTitleEdit.getText().toString().trim());
        intent.putExtra(SearchResultsActivity.BUNDLE_ALBUM,
                        mAlbumEdit.getText().toString().trim());
        intent.putExtra(SearchResultsActivity.BUNDLE_MIN_RATING,
                        mMinRatingSpinner.getSelectedItemPosition() / 4.0);
        intent.putExtra(SearchResultsActivity.BUNDLE_SHUFFLE,
                        mShuffleCheckbox.isChecked());
        intent.putExtra(SearchResultsActivity.BUNDLE_SUBSTRING,
                        mSubstringCheckbox.isChecked());
        intent.putExtra(SearchResultsActivity.BUNDLE_CACHED,
                        mCachedCheckbox.isChecked());
        startActivityForResult(intent, RESULTS_REQUEST_CODE);
    }

    public void onResetButtonClicked(View view) {
        resetForm();
    }

    // Implements NupService.SongDatabaseUpdateListener.
    @Override
    public void onSongDatabaseUpdate() {
        List<String> artists = new ArrayList<String>();
        List<StringIntPair> artistsWithCounts = NupActivity.getService().getSongDb().getArtistsSortedByNumSongs();
        if (artistsWithCounts != null) {
            for (StringIntPair pair : artistsWithCounts) {
                artists.add(pair.getString());
            }
        }
        mArtistEdit.setAdapter(new ArrayAdapter<String>(SearchFormActivity.this, android.R.layout.simple_dropdown_item_1line, artists));
    }
}
