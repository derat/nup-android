// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NupActivity extends Activity
                         implements Player.PositionChangeListener,
                                    Player.PauseToggleListener,
                                    Player.PlaybackErrorListener,
                                    NupService.SongChangeListener,
                                    NupService.CoverLoadListener,
                                    NupService.PlaylistChangeListener {
    private static final String TAG = "NupActivity";

    // Wait this many milliseconds before switching tracks in response to the Prev and Next buttons.
    // This avoids requsting a bunch of tracks that we don't want when the user is repeatedly
    // pressing the button to skip through tracks.
    private static final int SONG_CHANGE_DELAY_MS = 500;

    // Persistent service to which we connect.
    private static NupService mService;

    // UI components that we update dynamically.
    private Button mPauseButton;
    private ImageView mAlbumImageView;
    private TextView mArtistLabel, mTitleLabel, mAlbumLabel, mTimeLabel;
    private ListView mPlaylistView;

    // Last song-position time passed to onPositionChange(), in seconds.
    // Used to rate-limit how often we update the display so we only do it on integral changes.
    private int lastSongPositionSec = -1;

    // Songs in the current playlist.
    private List<Song> mSongs = new ArrayList<Song>();

    // Position in mSongs of the song that we're currently displaying.
    private int mCurrentSongIndex = -1;

    // Adapts the song listing to mPlaylistView.
    private SongListAdapter mSongListAdapter = new SongListAdapter();

    // Used to run tasks on our thread.
    private Handler mHandler = new Handler();

    // Task that tells the service to play our currently-selected song.
    private Runnable mPlaySongTask = new Runnable() {
        @Override
        public void run() {
            mService.playSongAtIndex(mCurrentSongIndex);
        }
    };

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
        registerForContextMenu(mPlaylistView);
        mPlaylistView.setAdapter(mSongListAdapter);

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
        }
        unbindService(mConnection);
        if (stopService)
            stopService(new Intent(this, NupService.class));
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "connected to service");
            mService = ((NupService.LocalBinder) service).getService();

            mService.setSongChangeListener(NupActivity.this);
            mService.setCoverLoadListener(NupActivity.this);
            mService.setPlaylistChangeListener(NupActivity.this);

            mService.getPlayer().setPositionChangeListener(NupActivity.this);
            mService.getPlayer().setPauseToggleListener(NupActivity.this);
            mService.getPlayer().setPlaybackErrorListener(NupActivity.this);

            // Get current state from service.
            onPlaylistChange(mService.getSongs());
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "disconnected from service");
            mService = null;
        }
    };

    public static NupService getService() {
        return NupActivity.mService;
    }

    public void onPauseButtonClicked(View view) {
        mService.togglePause();
    }

    public void onPrevButtonClicked(View view) {
        if (mCurrentSongIndex <= 0)
            return;

        mService.stopPlaying();
        updateCurrentSongIndex(mCurrentSongIndex - 1);
        schedulePlaySongTask(SONG_CHANGE_DELAY_MS);
    }

    public void onNextButtonClicked(View view) {
        if (mCurrentSongIndex >= mSongs.size() - 1)
            return;

        mService.stopPlaying();
        updateCurrentSongIndex(mCurrentSongIndex + 1);
        schedulePlaySongTask(SONG_CHANGE_DELAY_MS);
    }

    public void onSearchButtonClicked(View view) {
        startActivity(new Intent(this, SearchActivity.class));
    }

    // Implements NupService.SongChangeListener.
    @Override
    public void onSongChange(final Song song, final int index) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateCurrentSongIndex(index);
            }
        });
    }

    // Implements NupService.CoverLoadListener.
    @Override
    public void onCoverLoad(final Song song) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (song == getCurrentSong()) {
                    mAlbumImageView.setVisibility(View.VISIBLE);
                    mAlbumImageView.setImageBitmap(song.getCoverBitmap());
                }
            }
        });
    }

    // Implements NupService.PlaylistChangeListener.
    @Override
    public void onPlaylistChange(final List<Song> songs) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mSongs = songs;
                findViewById(R.id.playlist_heading).setVisibility(mSongs.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                updateCurrentSongIndex(mService.getCurrentSongIndex());
            }
        });
    }

    // Implements Player.PositionChangeListener.
    @Override
    public void onPositionChange(final int positionMs, final int durationMs) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int positionSec = positionMs / 1000;
                if (positionSec == lastSongPositionSec)
                    return;
                mTimeLabel.setText(Util.formatTimeString(positionSec, durationMs / 1000));
            }
        });
    }

    // Implements Player.PauseToggleListener.
    @Override
    public void onPauseToggle(final boolean isPaused) {
        runOnUiThread(new Runnable() {
            public void run() {
                mPauseButton.setText(getString(isPaused ? R.string.play : R.string.pause));
            }
        });
    }

    // Implements Player.PlaybackErrorListener.
    @Override
    public void onPlaybackError(final String description) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(NupActivity.this, description, Toast.LENGTH_LONG).show();
            }
        });
    }

    // Update the onscreen information about the current song.
    private void updateSongDisplay(Song song) {
        mArtistLabel.setText(song != null ? song.getArtist() : "");
        mTitleLabel.setText(song != null ? song.getTitle() : "");
        mAlbumLabel.setText(song != null ? song.getAlbum() : "");
        mTimeLabel.setText(song != null ? Util.formatTimeString(0, song.getLengthSec()) : "");

        if (song != null && song.getCoverBitmap() != null) {
            mAlbumImageView.setVisibility(View.VISIBLE);
            mAlbumImageView.setImageBitmap(song.getCoverBitmap());
        } else {
            mAlbumImageView.setVisibility(View.INVISIBLE);
        }

        if (song != null && song.getCoverBitmap() == null)
            mService.fetchCoverForSongIfMissing(song);

        // Update the time in response to the next position change we get.
        lastSongPositionSec = -1;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.settings_menu_item:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        case R.id.exit_menu_item:
            stopService(new Intent(this, NupService.class));
            finish();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    // Adapts our information about the current playlist and song for the song list view.
    private class SongListAdapter extends BaseAdapter {
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
                LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                view = inflater.inflate(R.layout.playlist_row, null);
            }

            Song song = mSongs.get(position);
            ((TextView) view.findViewById(R.id.artist)).setText(song.getArtist());
            ((TextView) view.findViewById(R.id.title)).setText(song.getTitle());

            boolean currentlyPlaying = (position == mCurrentSongIndex);
            view.setBackgroundColor(getResources().getColor(currentlyPlaying ? R.color.playlist_highlight_bg : android.R.color.transparent));
            return view;
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.playlist) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = mSongs.get(info.position);
            menu.setHeaderTitle(song.getArtist() + " - " + song.getTitle());
            menu.add(getString(R.string.play));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        updateCurrentSongIndex(info.position);
        schedulePlaySongTask(0);
        return true;
    }

    private Song getCurrentSong() {
        if (mCurrentSongIndex >= 0 && mCurrentSongIndex < mSongs.size())
            return mSongs.get(mCurrentSongIndex);
        else
            return null;
    }

    private void updateCurrentSongIndex(int index) {
        mCurrentSongIndex = index;
        updateSongDisplay(getCurrentSong());
        mSongListAdapter.notifyDataSetChanged();
    }

    private void schedulePlaySongTask(int delayMs) {
        mHandler.removeCallbacks(mPlaySongTask);
        mHandler.postDelayed(mPlaySongTask, delayMs);
    }
}
