// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

public class SearchActivity extends Activity
                            implements NupService.ContentsLoadListener {
    private static final String TAG = "SearchActivity";

    // IDs used to identify activities that we start.
    private static final int BROWSE_REQUEST_CODE = 1;
    private static final int RESULTS_REQUEST_CODE = 2;

    // Various parts of our UI.
    private AutoCompleteTextView mArtistEdit, mAlbumEdit;
    private EditText mTitleEdit;
    private CheckBox mShuffleCheckbox, mSubstringCheckbox;
    private Spinner mMinRatingSpinner;

    // Points from (lowercased) artist String to List of String album names.
    private HashMap mAlbumMap = new HashMap();

    // Minimum rating set by |mMinRatingSpinner|.
    private String mMinRating = null;

    // Search results received from the server.
    private List<Song> mSearchResults = new ArrayList<Song>();

    // Latest search request, if any.
    private QueryTask mQueryTask = null;

    // Pointer to ourself used by SearchResultsActivity to get search results.
    private static SearchActivity mSingleton = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setTitle(R.string.search);
        setContentView(R.layout.search);
        mSingleton = this;

        mArtistEdit = (AutoCompleteTextView) findViewById(R.id.artist_edit_text);
        mTitleEdit = (EditText) findViewById(R.id.title_edit_text);

        // When the album field gets the focus, set its suggestions based on the currently-entered artist.
        mAlbumEdit = (AutoCompleteTextView) findViewById(R.id.album_edit_text);
        mAlbumEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    String artist = mArtistEdit.getText().toString();
                    List<String> albums = null;
                    if (artist.trim().isEmpty()) {
                        albums = NupActivity.getService().getAllAlbums();
                    } else {
                        albums = NupActivity.getService().getAlbumsByArtist(artist);
                    }
                    if (albums == null)
                        albums = new ArrayList<String>();
                    mAlbumEdit.setAdapter(new ArrayAdapter<String>(SearchActivity.this, android.R.layout.simple_dropdown_item_1line, albums));
                }
            }
        });

        mMinRatingSpinner = (Spinner) findViewById(R.id.min_rating_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.min_rating_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mMinRatingSpinner.setAdapter(adapter);
        mMinRatingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                SearchActivity.this.mMinRating = parent.getItemAtPosition(pos).toString();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                SearchActivity.this.mMinRating = null;
            }
        });

        mShuffleCheckbox = (CheckBox) findViewById(R.id.shuffle_checkbox);
        mSubstringCheckbox = (CheckBox) findViewById(R.id.substring_checkbox);

        NupActivity.getService().setContentsLoadListener(this);
        onContentsLoad();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
        NupActivity.getService().unregisterListener(this);
        mSingleton = null;
    }

    private void resetForm() {
        mArtistEdit.setText("");
        mAlbumEdit.setText("");
        mTitleEdit.setText("");
        mShuffleCheckbox.setChecked(false);
        mSubstringCheckbox.setChecked(false);
        // TODO: Reset min rating spinner.
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == BROWSE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                resetForm();

                String artist = data.getStringExtra(BrowseActivity.BUNDLE_ARTIST);
                if (artist != null)
                    mArtistEdit.setText(artist);

                String album = data.getStringExtra(BrowseActivity.BUNDLE_ALBUM);
                if (album != null)
                    mAlbumEdit.setText(album);

                // At least one of these should always be set, but whatever...
                if (artist != null || album != null)
                    sendQuery();
            }
        } else if (requestCode == RESULTS_REQUEST_CODE) {
            mSearchResults.clear();
            if (resultCode == RESULT_OK)
                finish();
        }
    }

    // Export our latest results for SearchResultsActivity.
    // (Serializing them and passing them via the intent is hella slow.)
    public static List<Song> getSearchResults() {
        return mSingleton.mSearchResults;
    }

    public void onSearchButtonClicked(View view) {
        sendQuery();
    }

    public void onResetButtonClicked(View view) {
        resetForm();
    }

    public void onBrowseButtonClicked(View view) {
        startActivityForResult(new Intent(this, BrowseActivity.class), BROWSE_REQUEST_CODE);
    }

    // Implements NupService.ContentsLoadListener.
    @Override
    public void onContentsLoad() {
        final List<String> artists = NupActivity.getService().getArtistsSortedByNumAlbums();
        if (artists != null)
            mArtistEdit.setAdapter(new ArrayAdapter<String>(SearchActivity.this, android.R.layout.simple_dropdown_item_1line, artists));
    }

    private void sendQuery() {
        QueryParams params = new QueryParams();
        params.artist = mArtistEdit.getText().toString().trim();
        params.title = mTitleEdit.getText().toString().trim();
        params.album = mAlbumEdit.getText().toString().trim();
        params.minRating = mMinRating;
        params.shuffle = mShuffleCheckbox.isChecked();
        params.substring = mSubstringCheckbox.isChecked();

        // Make a half-hearted attempt to abort the previous request, if there is one.
        // It's fine if it's already started; it'll abort when the background portion
        // finishes and onPostExecute() sees that there's a newer task.
        if (mQueryTask != null)
            mQueryTask.cancel(false);
        mQueryTask = new QueryTask();
        mQueryTask.execute(params);
    }

    private class QueryParams {
        public String artist, title, album, minRating;
        boolean shuffle, substring;
    }

    private class QueryTask extends AsyncTask<QueryParams, Void, List<Song>> {
        // Message that we display onscreen while waiting for the results.
        private Toast mToast;

        @Override
        protected void onPreExecute() {
            mToast = Toast.makeText(SearchActivity.this, "Querying database...", Toast.LENGTH_LONG);
            mToast.show();
        }

        @Override
        protected List<Song> doInBackground(QueryParams... params) {
            return NupActivity.getService().getSongDb().query(
                params[0].artist, params[0].title, params[0].album, params[0].minRating,
                params[0].shuffle, params[0].substring);
        }

        @Override
        protected void onPostExecute(List<Song> songs) {
            // Looks like another request got sent while we were busy.
            // Give up without doing anything.
            if (mQueryTask != this)
                return;
            mQueryTask = null;

            mSearchResults = songs;
            String message;
            if (!mSearchResults.isEmpty()) {
                message = getResources().getQuantityString(
                    R.plurals.search_got_songs_fmt, mSearchResults.size(), mSearchResults.size());
                startActivityForResult(new Intent(SearchActivity.this, SearchResultsActivity.class), RESULTS_REQUEST_CODE);
            } else {
                message = "No results.";
            }

            mToast.setText(message);
            mToast.setDuration(Toast.LENGTH_SHORT);
            mToast.show();
        }
    }
}
