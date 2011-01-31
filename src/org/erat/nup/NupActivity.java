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
import android.os.StrictMode;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class NupActivity extends Activity
                         implements NupService.SongListener {
    private static final String TAG = "NupActivity";

    // Wait this many milliseconds before switching tracks in response to the Prev and Next buttons.
    // This avoids requsting a bunch of tracks that we don't want when the user is repeatedly
    // pressing the button to skip through tracks.
    private static final int SONG_CHANGE_DELAY_MS = 500;

    // IDs for items in our context menus.
    private static final int MENU_ITEM_PLAY = 1;
    private static final int MENU_ITEM_REMOVE_FROM_LIST = 2;

    // Persistent service to which we connect.
    private static NupService mService;

    // UI components that we update dynamically.
    private Button mPauseButton, mPrevButton, mNextButton;
    private ImageView mAlbumImageView;
    private TextView mArtistLabel, mTitleLabel, mAlbumLabel, mTimeLabel, mDownloadStatusLabel;
    private ListView mPlaylistView;

    // Last song-position time passed to onSongPositionChange(), in seconds.
    // Used to rate-limit how often we update the display so we only do it on integral changes.
    private int mLastSongPositionSec = -1;

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
        //StrictMode.enableDefaults();
        super.onCreate(savedInstanceState);

        Log.d(TAG, "activity created");
        setContentView(R.layout.main);

        mPauseButton = (Button) findViewById(R.id.pause_button);
        mPrevButton = (Button) findViewById(R.id.prev_button);
        mNextButton = (Button) findViewById(R.id.next_button);
        mAlbumImageView = (ImageView) findViewById(R.id.album_image);
        mArtistLabel = (TextView) findViewById(R.id.artist_label);
        mTitleLabel = (TextView) findViewById(R.id.title_label);
        mAlbumLabel = (TextView) findViewById(R.id.album_label);
        mTimeLabel = (TextView) findViewById(R.id.time_label);
        mDownloadStatusLabel = (TextView) findViewById(R.id.download_status_label);

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
            mService.unregisterListener(this);
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
            mService.setSongListener(NupActivity.this);

            // Get current state from service.
            onPlaylistChange(mService.getSongs());
            onPauseStateChange(mService.getPaused());
            if (getCurrentSong() != null) {
                onSongPositionChange(mService.getCurrentSong(), mService.getCurrentSongLastPositionMs(), 0);
                mPlaylistView.smoothScrollToPosition(mCurrentSongIndex);
            }
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

    // Implements NupService.SongListener.
    @Override
    public void onSongChange(Song song, int index) {
        updateCurrentSongIndex(index);
    }

    // Implements NupService.SongListener.
    @Override
    public void onSongPositionChange(final Song song, final int positionMs, final int durationMs) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (song != getCurrentSong())
                    return;

                int positionSec = positionMs / 1000;
                if (positionSec == mLastSongPositionSec)
                    return;
                // MediaPlayer appears to get confused sometimes and report things like 0:01.
                int durationSec = Math.max(durationMs / 1000, getCurrentSong().getLengthSec());
                mTimeLabel.setText(Util.formatTimeString(positionSec, durationSec));
                mLastSongPositionSec = positionSec;
            }
        });
    }

    // Implements NupService.SongListener.
    @Override
    public void onPauseStateChange(final boolean isPaused) {
        runOnUiThread(new Runnable() {
            public void run() {
                mPauseButton.setText(getString(isPaused ? R.string.play : R.string.pause));
            }
        });
    }

    // Implements NupService.SongListener.
    @Override
    public void onSongCoverLoad(Song song) {
        if (song == getCurrentSong()) {
            mAlbumImageView.setVisibility(View.VISIBLE);
            mAlbumImageView.setImageBitmap(song.getCoverBitmap());
        }
    }

    // Implements NupService.SongListener.
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

    // Implements NupService.SongListener.
    @Override
    public void onSongFileSizeChange(Song song) {
        final long availableBytes = song.getAvailableBytes();
        final long totalBytes = song.getTotalBytes();

        if (song == getCurrentSong()) {
            if (availableBytes == totalBytes) {
                mDownloadStatusLabel.setText("");
            } else {
                mDownloadStatusLabel.setText(
                    String.format("%,d of %,d KB",
                                  Math.round(availableBytes / 1024.0),
                                  Math.round(totalBytes / 1024.0)));
            }

        }

        mSongListAdapter.notifyDataSetChanged();
    }

    // Update the onscreen information about the current song.
    private void updateSongDisplay(Song song) {
        if (song == null) {
            mArtistLabel.setText("");
            mTitleLabel.setText("");
            mAlbumLabel.setText("");
            mTimeLabel.setText("");
            mDownloadStatusLabel.setText("");
            mAlbumImageView.setVisibility(View.INVISIBLE);
        } else {
            mArtistLabel.setText(song.getArtist());
            mTitleLabel.setText(song.getTitle());
            mAlbumLabel.setText(song.getAlbum());
            mTimeLabel.setText(
                Util.formatTimeString(
                    (song == mService.getCurrentSong()) ?
                        mService.getCurrentSongLastPositionMs() / 1000 :
                        0,
                    song.getLengthSec()));
            mDownloadStatusLabel.setText("");
            if (song.getCoverBitmap() != null) {
                mAlbumImageView.setVisibility(View.VISIBLE);
                mAlbumImageView.setImageBitmap(song.getCoverBitmap());
            } else {
                mAlbumImageView.setVisibility(View.INVISIBLE);
                mService.fetchCoverForSongIfMissing(song);
            }
        }

        // Update the displayed time in response to the next position change we get.
        mLastSongPositionSec = -1;
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

            TextView percentView = (TextView) view.findViewById(R.id.percent);
            if (song.getTotalBytes() > 0) {
                if (song.getAvailableBytes() == song.getTotalBytes())
                    percentView.setText("\u2713");  // CHECK MARK from Dingbats
                else
                    percentView.setText((int) Math.round(100.0 * song.getAvailableBytes() / song.getTotalBytes()) + "%");
                percentView.setVisibility(View.VISIBLE);
            } else {
                percentView.setVisibility(View.GONE);
            }

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
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play);
            menu.add(0, MENU_ITEM_REMOVE_FROM_LIST, 0, R.string.remove_from_list);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        switch (item.getItemId()) {
            case MENU_ITEM_PLAY:
                updateCurrentSongIndex(info.position);
                schedulePlaySongTask(0);
                return true;
            case MENU_ITEM_REMOVE_FROM_LIST:
                mService.removeFromPlaylist(info.position);
                return true;
            default:
                return false;
        }
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

        mPrevButton.setEnabled(mCurrentSongIndex > 0);
        mNextButton.setEnabled(!mSongs.isEmpty() && mCurrentSongIndex < mSongs.size() - 1);
        mPauseButton.setEnabled(!mSongs.isEmpty());
    }

    private void schedulePlaySongTask(int delayMs) {
        mHandler.removeCallbacks(mPlaySongTask);
        mHandler.postDelayed(mPlaySongTask, delayMs);
    }
}
