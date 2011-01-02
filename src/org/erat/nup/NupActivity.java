package org.erat.nup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class NupActivity extends Activity implements NupServiceObserver {
    private NupService mService;

    private Button mPauseButton;
    private TextView mArtistLabel, mTitleLabel, mAlbumLabel, mTimeLabel;
    private EditText mArtistEdit, mTitleEdit, mAlbumEdit;
    private ImageView mAlbumImageView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(this.toString(), "activity created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mPauseButton = (Button) findViewById(R.id.pause_button);
        mAlbumImageView = (ImageView) findViewById(R.id.album_image);
        mArtistLabel = (TextView) findViewById(R.id.artist_label);
        mTitleLabel = (TextView) findViewById(R.id.title_label);
        mAlbumLabel = (TextView) findViewById(R.id.album_label);
        mTimeLabel = (TextView) findViewById(R.id.time_label);
        mArtistEdit = (EditText) findViewById(R.id.artist_edit_text);
        mTitleEdit = (EditText) findViewById(R.id.title_edit_text);
        mAlbumEdit = (EditText) findViewById(R.id.album_edit_text);

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

    class SendSearchRequestTask extends AsyncTask<String, Void, List<Song>> {
        // TODO: Report success/error instead of returning song list.
        @Override
        protected List<Song> doInBackground(String... urls) {
            List<Song> songs = new ArrayList<Song>();
            String jsonData;
            try {
                URL url = new URL(urls[0]);
                InputStream stream = (InputStream) url.getContent();
                jsonData = Util.getStringFromInputStream(stream);
                Log.i(this.toString(), "got " + jsonData.length() + "-byte string");
            } catch (IOException err) {
                Log.e(this.toString(), "query failed: " + err);
                return songs;
            }

            try {
                JSONArray jsonSongs = (JSONArray) new JSONTokener(jsonData).nextValue();
                Log.i(this.toString(), "got " + jsonSongs.length() + " song(s) from server");
                for (int i = 0; i < jsonSongs.length(); ++i) {
                    songs.add(new Song(jsonSongs.getJSONObject(i)));
                }
                if (mService != null) {
                    mService.setPlaylist(songs);
                }
                return songs;
            } catch (org.json.JSONException err) {
                Log.e(this.toString(), "unable to parse json");
                return songs;
            }
        }

        @Override
        protected void onPostExecute(List<Song> songs) {
        }
    }

    public void onSearchButtonClicked(View view) throws IOException {
        class QueryBuilder {
            public List<String> params = new ArrayList<String>();
            public void addStringParam(EditText view, String paramName) throws java.io.UnsupportedEncodingException {
                String value = view.getText().toString().trim();
                if (!value.isEmpty()) {
                    String param = paramName + "=" + URLEncoder.encode(value, "UTF-8");
                    params.add(param);
                }
            }
        }
        QueryBuilder builder = new QueryBuilder();
        builder.addStringParam(mArtistEdit, "artist");
        builder.addStringParam(mTitleEdit, "title");
        builder.addStringParam(mAlbumEdit, "album");
        new SendSearchRequestTask().execute("http://localhost:" + mService.getProxyPort() + "/query?" + TextUtils.join("&", builder.params));
    }

    public void onPauseButtonClicked(View view) {
        mService.togglePause();
    }

    public void onNextButtonClicked(View view) {
        // FIXME: not threadsafe
        int index = mService.getCurrentSongIndex();
        if (index + 1 < mService.getSongs().size())
            mService.playSongAtIndex(index + 1);
    }

    @Override
    public void onPauseStateChanged(boolean isPaused) {
        mPauseButton.setText(isPaused ? "Play" : "Pause");
    }

    String formatTimeString(int curSec, int totalSec) {
        return String.format("[%d:%02d / %d:%02d]", curSec / 60, curSec % 60, totalSec / 60, totalSec % 60);
    }

    @Override
    public void onSongChanged(Song currentSong) {
        mArtistLabel.setText(currentSong.getArtist());
        mTitleLabel.setText(currentSong.getTitle());
        mAlbumLabel.setText(currentSong.getAlbum());
        mTimeLabel.setText(formatTimeString(0, currentSong.getLengthSec()));

        // FIXME: don't do this on UI thread
        try {
            URL imageUrl = new URL("http://localhost:" + mService.getProxyPort() + "/cover/" + currentSong.getCoverFilename());
            Bitmap bitmap = BitmapFactory.decodeStream((InputStream) imageUrl.getContent());
            mAlbumImageView.setImageBitmap(bitmap);
        } catch (IOException err) {
            Log.e(this.toString(), "unable to load album cover bitmap from file " + currentSong.getCoverFilename() + ": " + err);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.preferences_menu_item:
            return true;
        case R.id.quit_menu_item:
            stopService(new Intent(this, NupService.class));
            finish();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }
}
