// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
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
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class NupSearchActivity extends Activity {
    private static final String TAG = "NupSearchActivity";
    private NupService mService;

    private AutoCompleteTextView mArtistEdit, mAlbumEdit;
    private EditText mTitleEdit;
    private CheckBox mShuffleCheckbox, mSubstringCheckbox;
    private Spinner mMinRatingSpinner;

    // Points from (lowercased) artist String to ArrayList of String album names.
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
                    String artist = mArtistEdit.getText().toString().toLowerCase();
                    ArrayList<String> albums = new ArrayList<String>();
                    if (mAlbumMap.containsKey(artist))
                        albums = (ArrayList<String>) mAlbumMap.get(artist);
                    mAlbumEdit.setAdapter(new ArrayAdapter<String>(NupSearchActivity.this, android.R.layout.simple_dropdown_item_1line, albums));
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
                NupSearchActivity.this.mMinRating = parent.getItemAtPosition(pos).toString();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                NupSearchActivity.this.mMinRating = null;
            }
        });

        mShuffleCheckbox = (CheckBox) findViewById(R.id.shuffle_checkbox);
        mSubstringCheckbox = (CheckBox) findViewById(R.id.substring_checkbox);

        bindService(new Intent(this, NupService.class), mConnection, 0);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();
        unbindService(mConnection);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "connected to service");
            mService = ((NupService.LocalBinder) service).getService();
            new GetContentsTask().execute("http://localhost:" + mService.getProxyPort() + "/contents");
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "disconnected from service");
            mService = null;
        }
    };

    class GetContentsTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... urls) {
            try {
                URL url = new URL(urls[0]);
                InputStream stream = (InputStream) url.getContent();
                return Util.getStringFromInputStream(stream);
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        protected void onPostExecute(String response) {
            if (response.isEmpty())
                return;

            try {
                JSONObject jsonArtistMap = (JSONObject) new JSONTokener(response).nextValue();
                ArrayList<String> artists = new ArrayList<String>();
                for (Iterator<String> it = jsonArtistMap.keys(); it.hasNext(); ) {
                    String artist = it.next();
                    artists.add(artist);

                    JSONArray jsonAlbums = jsonArtistMap.getJSONArray(artist);
                    ArrayList<String> albums = new ArrayList<String>();
                    for (int i = 0; i < jsonAlbums.length(); ++i) {
                        albums.add(jsonAlbums.getString(i));
                    }
                    mAlbumMap.put(artist.toLowerCase(), albums);
                }
                mArtistEdit.setAdapter(new ArrayAdapter<String>(NupSearchActivity.this, android.R.layout.simple_dropdown_item_1line, artists));
            } catch (org.json.JSONException e) {
            }
        }
    }

    class SendSearchRequestTask extends AsyncTask<String, Void, String> {
        String nMessage;

        @Override
        protected String doInBackground(String... urls) {
            try {
                URL url = new URL(urls[0]);
                InputStream stream = (InputStream) url.getContent();
                return Util.getStringFromInputStream(stream);
            } catch (IOException e) {
                nMessage = "Query failed: " + e.getMessage();
                return "";
            }
        }

        @Override
        protected void onPostExecute(String response) {
            if (!response.isEmpty()) {
                try {
                    JSONArray jsonSongs = (JSONArray) new JSONTokener(response).nextValue();
                    ArrayList<Song> songs = new ArrayList<Song>();
                    for (int i = 0; i < jsonSongs.length(); ++i) {
                        songs.add(new Song(jsonSongs.getJSONObject(i)));
                    }
                    if (songs.size() > 0) {
                        mService.setPlaylist(songs);
                        nMessage = "Queued " + songs.size() + " song" + (songs.size() == 1 ? "" : "s") + " from server.";
                        finish();
                    } else {
                        nMessage = "No results.";
                    }
                } catch (org.json.JSONException e) {
                    nMessage = "Unable to parse response from server: " + e.getCause();
                }
            }

            Toast.makeText(NupSearchActivity.this, nMessage, Toast.LENGTH_LONG).show();
        }
    }

    public void onSearchButtonClicked(View view) throws IOException {
        class QueryBuilder {
            public ArrayList<String> params = new ArrayList<String>();
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
        new SendSearchRequestTask().execute("http://localhost:" + mService.getProxyPort() + "/query?" + TextUtils.join("&", builder.params));
    }
}
