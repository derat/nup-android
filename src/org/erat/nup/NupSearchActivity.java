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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class NupSearchActivity extends Activity {
    private static final String TAG = "NupSearchActivity";
    private NupService mService;

    private EditText mArtistEdit, mTitleEdit, mAlbumEdit;
    private CheckBox mShuffleCheckbox, mSubstringCheckbox;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search);

        mArtistEdit = (EditText) findViewById(R.id.artist_edit_text);
        mTitleEdit = (EditText) findViewById(R.id.title_edit_text);
        mAlbumEdit = (EditText) findViewById(R.id.album_edit_text);
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
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "disconnected from service");
            mService = null;
        }
    };

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
            public void addStringParam(EditText view, String paramName) throws java.io.UnsupportedEncodingException {
                String value = view.getText().toString().trim();
                if (!value.isEmpty()) {
                    String param = paramName + "=" + URLEncoder.encode(value, "UTF-8");
                    params.add(param);
                }
            }
            public void addBoolParam(CheckBox view, String paramName) {
                params.add(paramName + "=" + (view.isChecked() ? "1" : "0"));
            }
        }
        QueryBuilder builder = new QueryBuilder();
        builder.addStringParam(mArtistEdit, "artist");
        builder.addStringParam(mTitleEdit, "title");
        builder.addStringParam(mAlbumEdit, "album");
        builder.addBoolParam(mShuffleCheckbox, "shuffle");
        builder.addBoolParam(mSubstringCheckbox, "substring");
        new SendSearchRequestTask().execute("http://localhost:" + mService.getProxyPort() + "/query?" + TextUtils.join("&", builder.params));
    }
}
