package org.erat.nup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class NupActivity extends Activity implements NupServiceObserver {
    private NupService mService;

    private Button mPauseButton;
    private TextView mArtistLabel, mTitleLabel, mAlbumLabel, mTimeLabel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(this.toString(), "activity created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mPauseButton = (Button) findViewById(R.id.pause_button);
        mArtistLabel = (TextView) findViewById(R.id.artist_label);
        mTitleLabel = (TextView) findViewById(R.id.title_label);
        mAlbumLabel = (TextView) findViewById(R.id.album_label);
        mTimeLabel = (TextView) findViewById(R.id.time_label);

        Intent serviceIntent = new Intent(this, NupService.class);
        startService(serviceIntent);
        bindService(new Intent(this, NupService.class), mConnection, 0);
    }

    @Override
    protected void onDestroy() {
        Log.i(this.toString(), "activity destroyed");
        super.onDestroy();
        if (mService != null)
            mService.removeObserver(this);
        unbindService(mConnection);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(this.toString(), "connected to service");
            mService = ((NupService.LocalBinder) service).getService();
            mService.addObserver(NupActivity.this);
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.i(this.toString(), "disconnected from service");
            mService = null;
        }
    };

    class SendSearchRequestTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... urls) {
            final String userAgent = "whatever";
            AndroidHttpClient client = AndroidHttpClient.newInstance(userAgent, NupActivity.this);
            HttpResponse response;
            try {
                response = client.execute(new HttpGet(urls[0]));
            } catch (IOException err) {
                Log.e(this.toString(), "query failed");
                return "";
            }
            Log.i(this.toString(), "got response from server");

            // Yay.
            StringBuilder sb = new StringBuilder();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } catch (IOException err) {
                return "";
            }

            try {
                JSONArray songs;
                songs = (JSONArray) new JSONTokener(sb.toString()).nextValue();
                for (int i = 0; i < songs.length(); ++i) {
                    JSONObject song = songs.getJSONObject(i);
                    Log.i(this.toString(), song.getString("artist") + " - " + song.getString("title"));
                }
            } catch (org.json.JSONException err) {
                Log.e(this.toString(), "unable to parse json");
                return "";
            }

            return "foo";
        }

        @Override
        protected void onPostExecute(String result) {
            if (mService != null) {
            }
        }
    }

    public void onSearchButtonClicked(View view) throws IOException {
        new SendSearchRequestTask().execute("http://10.0.0.5:8080/query?artist=Bola");
    }

    public void onPauseButtonClicked(View view) {
        mService.togglePause();
    }


    public void onExitButtonClicked(View view) {
        stopService(new Intent(this, NupService.class));
        finish();
    }

    @Override
    public void onPauseStateChanged(boolean isPaused) {
        mPauseButton.setText(isPaused ? "Play" : "Pause");
    }

    @Override
    public void onCurrentTrackChanged(String artist, String title, String album) {
        mArtistLabel.setText(artist);
        mTitleLabel.setText(title);
        mAlbumLabel.setText(album);
    }
}
