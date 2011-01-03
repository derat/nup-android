// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;

public class NupActivity extends Activity implements NupServiceObserver {
    private static final String TAG = "NupActivity";
    private NupService mService;

    private Button mPauseButton;
    private ImageView mAlbumImageView;
    private TextView mArtistLabel, mTitleLabel, mAlbumLabel, mTimeLabel;
    private ListView mPlaylistView;

    // Last song-position time passed to onSongPositionChanged(), in seconds.
    private int lastSongPositionSec = -1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "activity created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mPauseButton = (Button) findViewById(R.id.pause_button);
        mAlbumImageView = (ImageView) findViewById(R.id.album_image);
        mArtistLabel = (TextView) findViewById(R.id.artist_label);
        mTitleLabel = (TextView) findViewById(R.id.title_label);
        mAlbumLabel = (TextView) findViewById(R.id.album_label);
        mTimeLabel = (TextView) findViewById(R.id.time_label);
        mPlaylistView = (ListView) findViewById(R.id.playlist);

        Intent serviceIntent = new Intent(this, NupService.class);
        startService(serviceIntent);
        bindService(new Intent(this, NupService.class), mConnection, 0);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();

        boolean stopService = false;
        if (mService != null) {
            // Shut down the service as well if the playlist is empty.
            if (mService.getSongs().size() == 0)
                stopService = true;
            mService.removeObserver(this);
        }
        unbindService(mConnection);
        if (stopService)
            stopService(new Intent(this, NupService.class));
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "connected to service");
            mService = ((NupService.LocalBinder) service).getService();
            mService.addObserver(NupActivity.this);

            Song song = mService.getCurrentSong();
            if (song != null)
                onSongChanged(song);
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "disconnected from service");
            mService = null;
        }
    };

    public void onPauseButtonClicked(View view) {
        mService.togglePause();
    }

    public void onPrevButtonClicked(View view) {
        mService.playSongAtIndex(mService.getCurrentSongIndex() - 1);
    }

    public void onNextButtonClicked(View view) {
        mService.playSongAtIndex(mService.getCurrentSongIndex() + 1);
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
        if (currentSong.getCoverBitmap() != null)
            mAlbumImageView.setImageBitmap(currentSong.getCoverBitmap());
        // FIXME: clear image view otherwise
        lastSongPositionSec = -1;
    }

    @Override
    public void onCoverLoaded(Song currentSong) {
        mAlbumImageView.setImageBitmap(currentSong.getCoverBitmap());
    }

    @Override
    public void onPlaylistChanged(ArrayList<Song> songs) {
        mPlaylistView.setAdapter(new SongListAdapter(this, songs));
    }

    @Override
    public void onSongPositionChanged(int positionMs, int durationMs) {
        int positionSec = positionMs / 1000;
        if (positionSec == lastSongPositionSec)
            return;
        mTimeLabel.setText(formatTimeString(positionSec, durationMs / 1000));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.search_menu_item:
            if (!mService.isProxyRunning()) {
                Toast.makeText(this, "Server must be configured in Preferences.", Toast.LENGTH_LONG).show();
            } else {
                startActivity(new Intent(this, NupSearchActivity.class));
            }
            return true;
        case R.id.preferences_menu_item:
            startActivity(new Intent(this, NupPreferenceActivity.class));
            return true;
        case R.id.exit_menu_item:
            stopService(new Intent(this, NupService.class));
            finish();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private class SongListAdapter extends BaseAdapter {
        private final Context mContext;
        private final ArrayList<Song> mSongs;

        public SongListAdapter(Context context, ArrayList<Song> songs) {
            mContext = context;
            mSongs = songs;
        }

        @Override
        public int getCount() { return mSongs.size(); }
        @Override
        public Object getItem(int position) { return position; }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView != null) {
                view = convertView;
            } else {
                LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                view = inflater.inflate(R.layout.playlist_row, null);
            }

            Song song = mSongs.get(position);
            ((TextView) view.findViewById(R.id.artist)).setText(song.getArtist());
            ((TextView) view.findViewById(R.id.title)).setText(song.getTitle());
            return view;
        }
    }
}
