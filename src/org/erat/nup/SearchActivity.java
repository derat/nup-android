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
    private ArrayList<Song> mSearchResults = new ArrayList<Song>();

    // Latest search request, if any.
    private SendSearchRequestTask mSearchRequestTask = null;

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
    public static ArrayList<Song> getSearchResults() {
        return mSingleton.mSearchResults;
    }

    public void onSearchButtonClicked(View view) {
        sendQuery();
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
        class QueryBuilder {
            public List<String> params = new ArrayList<String>();
            public void addTextViewParam(String paramName, TextView view) {
                String value = view.getText().toString().trim();
                if (!value.isEmpty()) {
                    try {
                        String param = paramName + "=" + URLEncoder.encode(value, "UTF-8");
                        params.add(param);
                    } catch (java.io.UnsupportedEncodingException e) {
                    }
                }
            }
            public void addCheckBoxParam(String paramName, CheckBox view) {
                params.add(paramName + "=" + (view.isChecked() ? "1" : "0"));
            }
            public void addStringParam(String paramName, String value) {
                params.add(paramName + "=" + value);
            }
        }

        QueryBuilder builder = new QueryBuilder();
        builder.addTextViewParam("artist", mArtistEdit);
        builder.addTextViewParam("title", mTitleEdit);
        builder.addTextViewParam("album", mAlbumEdit);
        builder.addCheckBoxParam("shuffle", mShuffleCheckbox);
        builder.addCheckBoxParam("substring", mSubstringCheckbox);
        if (mMinRating != null && !mMinRating.isEmpty())
            builder.addStringParam("minRating", mMinRating);

        // Make a half-hearted attempt to abort the previous request, if there is one.
        // It's fine if it's already started; it'll abort when the background portion
        // finishes and onPostExecute() sees that there's a newer task.
        if (mSearchRequestTask != null)
            mSearchRequestTask.cancel(false);
        mSearchRequestTask = new SendSearchRequestTask();
        mSearchRequestTask.execute("/query", TextUtils.join("&", builder.params));
    }

    private class SendSearchRequestTask extends AsyncTask<String, Void, String> {
        // User-friendly description of the error, if any.
        private String[] mError = new String[1];

        // Message that we display onscreen while waiting for the results.
        private Toast mToast;

        @Override
        protected void onPreExecute() {
            mToast = Toast.makeText(SearchActivity.this, "Sending query...", Toast.LENGTH_LONG);
            mToast.show();
        }

        @Override
        protected String doInBackground(String... urls) {
            return Download.downloadString(SearchActivity.this, urls[0], urls[1], mError);
        }

        @Override
        protected void onPostExecute(String response) {
            // Looks like another request got sent while we were busy.
            // Give up without doing anything.
            if (mSearchRequestTask != this)
                return;
            mSearchRequestTask = null;

            String message;
            if (response == null || response.isEmpty()) {
                message = "Query failed: " + mError[0];
            } else {
                try {
                    JSONArray jsonSongs = (JSONArray) new JSONTokener(response).nextValue();
                    if (jsonSongs.length() > 0) {
                        mSearchResults.clear();
                        for (int i = 0; i < jsonSongs.length(); ++i)
                            mSearchResults.add(new Song(jsonSongs.getJSONObject(i)));
                        message = "Got " + mSearchResults.size() + " song" + (mSearchResults.size() == 1 ? "" : "s") + ".";
                        startActivityForResult(new Intent(SearchActivity.this, SearchResultsActivity.class), RESULTS_REQUEST_CODE);
                    } else {
                        message = "No results.";
                    }
                } catch (org.json.JSONException e) {
                    message = "Unable to parse response: " + e.getCause();
                }
            }

            mToast.setText(message);
            mToast.setDuration(Toast.LENGTH_SHORT);
            mToast.show();
        }
    }
}
