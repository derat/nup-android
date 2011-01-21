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

import org.apache.http.HttpException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

public class SearchActivity extends Activity
                            implements NupService.ContentsLoadListener {
    private static final String TAG = "SearchActivity";

    private static final int BROWSE_REQUEST_CODE = 1;

    private AutoCompleteTextView mArtistEdit, mAlbumEdit;
    private EditText mTitleEdit;
    private CheckBox mShuffleCheckbox, mSubstringCheckbox;
    private Spinner mMinRatingSpinner;

    // Points from (lowercased) artist String to List of String album names.
    private HashMap mAlbumMap = new HashMap();

    private String mMinRating = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
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
                    List<String> albums = NupActivity.getService().getAlbumsByArtist(artist);
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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == BROWSE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Bundle bundle = data.getExtras();
                mArtistEdit.setText(bundle.getString(BrowseActivity.BUNDLE_ARTIST));
                mAlbumEdit.setText(bundle.getString(BrowseActivity.BUNDLE_ALBUM));
            }
        }
    }

    class SendSearchRequestTask extends AsyncTask<String, Void, String> {
        // User-friendly description of the error, if any.
        private String[] mError = new String[1];

        @Override
        protected String doInBackground(String... urls) {
            return Download.downloadString(SearchActivity.this, urls[0], urls[1], mError);
        }

        @Override
        protected void onPostExecute(String response) {
            String message;
            if (response == null || response.isEmpty()) {
                message = "Query failed: " + mError[0];
            } else {
                try {
                    JSONArray jsonSongs = (JSONArray) new JSONTokener(response).nextValue();
                    List<Song> songs = new ArrayList<Song>();
                    for (int i = 0; i < jsonSongs.length(); ++i) {
                        songs.add(new Song(jsonSongs.getJSONObject(i)));
                    }
                    if (songs.size() > 0) {
                        NupActivity.getService().setPlaylist(songs);
                        message = "Queued " + songs.size() + " song" + (songs.size() == 1 ? "" : "s") + " from server.";
                        finish();
                    } else {
                        message = "No results.";
                    }
                } catch (org.json.JSONException e) {
                    message = "Unable to parse response: " + e.getCause();
                }
            }

            Toast.makeText(SearchActivity.this, message, Toast.LENGTH_LONG).show();
        }
    }

    public void onSearchButtonClicked(View view) throws IOException {
        class QueryBuilder {
            public List<String> params = new ArrayList<String>();
            public void addTextViewParam(String paramName, TextView view) throws java.io.UnsupportedEncodingException {
                String value = view.getText().toString().trim();
                if (!value.isEmpty()) {
                    String param = paramName + "=" + URLEncoder.encode(value, "UTF-8");
                    params.add(param);
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
        new SendSearchRequestTask().execute("/query", TextUtils.join("&", builder.params));
    }

    public void onBrowseButtonClicked(View view) throws IOException {
        startActivityForResult(new Intent(this, BrowseActivity.class), BROWSE_REQUEST_CODE);
    }

    // Implements NupService.ContentsLoadListener.
    @Override
    public void onContentsLoad() {
        final List<String> artists = NupActivity.getService().getArtists();
        if (artists != null)
            mArtistEdit.setAdapter(new ArrayAdapter<String>(SearchActivity.this, android.R.layout.simple_dropdown_item_1line, artists));
    }
}
