// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
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

import java.util.ArrayList;
import java.util.List;

public class NupActivity extends Activity implements NupService.SongListener {
    private static final String TAG = "NupActivity";

    // Wait this many milliseconds before switching tracks in response to the Prev and Next buttons.
    // This avoids requsting a bunch of tracks that we don't want when the user is repeatedly
    // pressing the button to skip through tracks.
    private static final int SONG_CHANGE_DELAY_MS = 500;

    // IDs for items in our context menus.
    private static final int MENU_ITEM_PLAY = 1;
    private static final int MENU_ITEM_REMOVE_FROM_LIST = 2;
    private static final int MENU_ITEM_TRUNCATE_LIST = 3;
    private static final int MENU_ITEM_SONG_DETAILS = 4;

    private static final int DIALOG_SONG_DETAILS = 1;

    // Persistent service to which we connect.
    private static NupService service;

    // UI components that we update dynamically.
    private Button pauseButton, prevButton, nextButton;
    private ImageView albumImageView;
    private TextView artistLabel, titleLabel, albumLabel, timeLabel, downloadStatusLabel;
    private ListView playlistView;

    // Last song-position time passed to onSongPositionChange(), in seconds.
    // Used to rate-limit how often we update the display so we only do it on integral changes.
    private int lastSongPositionSec = -1;

    // Songs in the current playlist.
    private List<Song> songs = new ArrayList<Song>();

    // Position in |songs| of the song that we're currently displaying.
    private int currentSongIndex = -1;

    // Adapts the song listing to |playlistView|.
    private SongListAdapter songListAdapter = new SongListAdapter();

    // Used to run tasks on our thread.
    private Handler handler = new Handler();

    // Task that tells the service to play our currently-selected song.
    private Runnable playSongTask =
            new Runnable() {
                @Override
                public void run() {
                    service.playSongAtIndex(currentSongIndex);
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .penaltyDeathOnNetwork()
                        .build());

        StrictMode.setVmPolicy(
                new StrictMode.VmPolicy.Builder()
                        .detectLeakedClosableObjects()
                        .detectLeakedSqlLiteObjects()
                        // TODO: Not including detectActivityLeaks() since I'm getting leaks of
                        // Browse*Activity
                        // objects that I
                        // don't understand.
                        .penaltyLog()
                        .penaltyDeath()
                        .build());

        super.onCreate(savedInstanceState);

        Log.d(TAG, "activity created");
        setContentView(R.layout.main);

        pauseButton = (Button) findViewById(R.id.pause_button);
        prevButton = (Button) findViewById(R.id.prev_button);
        nextButton = (Button) findViewById(R.id.next_button);
        albumImageView = (ImageView) findViewById(R.id.album_image);
        artistLabel = (TextView) findViewById(R.id.artist_label);
        titleLabel = (TextView) findViewById(R.id.title_label);
        albumLabel = (TextView) findViewById(R.id.album_label);
        timeLabel = (TextView) findViewById(R.id.time_label);
        downloadStatusLabel = (TextView) findViewById(R.id.download_status_label);

        playlistView = (ListView) findViewById(R.id.playlist);
        registerForContextMenu(playlistView);
        playlistView.setAdapter(songListAdapter);

        Intent serviceIntent = new Intent(this, NupService.class);
        startService(serviceIntent);
        bindService(new Intent(this, NupService.class), connection, 0);

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "activity destroyed");
        super.onDestroy();

        boolean stopService = false;
        if (service != null) {
            service.unregisterListener(this);
            // Shut down the service as well if the playlist is empty.
            if (service.getSongs().size() == 0) stopService = true;
        }
        unbindService(connection);
        if (stopService) stopService(new Intent(this, NupService.class));
    }

    private ServiceConnection connection =
            new ServiceConnection() {
                public void onServiceConnected(ComponentName className, IBinder binder) {
                    Log.d(TAG, "connected to service");
                    service = ((NupService.LocalBinder) binder).getService();
                    service.setSongListener(NupActivity.this);

                    // Get current state from service.
                    onPlaylistChange(service.getSongs());
                    onPauseStateChange(service.getPaused());
                    if (getCurrentSong() != null) {
                        onSongPositionChange(
                                service.getCurrentSong(),
                                service.getCurrentSongLastPositionMs(),
                                0);
                        playlistView.smoothScrollToPosition(currentSongIndex);
                    }

                    // TODO: Go to prefs page if server and account are unset.
                    if (songs.isEmpty()) {
                        startActivity(new Intent(NupActivity.this, BrowseTopActivity.class));
                    }
                }

                public void onServiceDisconnected(ComponentName className) {
                    Log.d(TAG, "disconnected from service");
                    service = null;
                }
            };

    public static NupService getService() {
        return NupActivity.service;
    }

    public void onPauseButtonClicked(View view) {
        service.togglePause();
    }

    public void onPrevButtonClicked(View view) {
        if (currentSongIndex <= 0) return;

        service.stopPlaying();
        updateCurrentSongIndex(currentSongIndex - 1);
        schedulePlaySongTask(SONG_CHANGE_DELAY_MS);
    }

    public void onNextButtonClicked(View view) {
        if (currentSongIndex >= songs.size() - 1) return;

        service.stopPlaying();
        updateCurrentSongIndex(currentSongIndex + 1);
        schedulePlaySongTask(SONG_CHANGE_DELAY_MS);
    }

    // Implements NupService.SongListener.
    @Override
    public void onSongChange(Song song, int index) {
        updateCurrentSongIndex(index);
    }

    // Implements NupService.SongListener.
    @Override
    public void onSongPositionChange(final Song song, final int positionMs, final int durationMs) {
        runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (song != getCurrentSong()) return;

                        int positionSec = positionMs / 1000;
                        if (positionSec == lastSongPositionSec) return;
                        // MediaPlayer appears to get confused sometimes and report things like
                        // 0:01.
                        int durationSec = Math.max(durationMs / 1000, getCurrentSong().lengthSec);
                        timeLabel.setText(
                                Util.formatDurationProgressString(positionSec, durationSec));
                        lastSongPositionSec = positionSec;
                    }
                });
    }

    // Implements NupService.SongListener.
    @Override
    public void onPauseStateChange(final boolean isPaused) {
        runOnUiThread(
                new Runnable() {
                    public void run() {
                        pauseButton.setText(getString(isPaused ? R.string.play : R.string.pause));
                    }
                });
    }

    // Implements NupService.SongListener.
    @Override
    public void onSongCoverLoad(Song song) {
        if (song == getCurrentSong()) {
            albumImageView.setVisibility(View.VISIBLE);
            albumImageView.setImageBitmap(song.getCoverBitmap());
        }
    }

    // Implements NupService.SongListener.
    @Override
    public void onPlaylistChange(final List<Song> newSongs) {
        runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        songs = newSongs;
                        findViewById(R.id.playlist_heading)
                                .setVisibility(songs.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                        updateCurrentSongIndex(service.getCurrentSongIndex());
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
                downloadStatusLabel.setText("");
            } else {
                downloadStatusLabel.setText(
                        String.format(
                                "%,d of %,d KB",
                                Math.round(availableBytes / 1024.0),
                                Math.round(totalBytes / 1024.0)));
            }
        }

        songListAdapter.notifyDataSetChanged();
    }

    // Update the onscreen information about the current song.
    private void updateSongDisplay(Song song) {
        if (song == null) {
            artistLabel.setText("");
            titleLabel.setText("");
            albumLabel.setText("");
            timeLabel.setText("");
            downloadStatusLabel.setText("");
            albumImageView.setVisibility(View.INVISIBLE);
        } else {
            artistLabel.setText(song.artist);
            titleLabel.setText(song.title);
            albumLabel.setText(song.album);
            timeLabel.setText(
                    Util.formatDurationProgressString(
                            (song == service.getCurrentSong())
                                    ? service.getCurrentSongLastPositionMs() / 1000
                                    : 0,
                            song.lengthSec));
            downloadStatusLabel.setText("");
            if (song.getCoverBitmap() != null) {
                albumImageView.setVisibility(View.VISIBLE);
                albumImageView.setImageBitmap(song.getCoverBitmap());
            } else {
                albumImageView.setVisibility(View.INVISIBLE);
                service.fetchCoverForSongIfMissing(song);
            }
        }

        // Update the displayed time in response to the next position change we get.
        lastSongPositionSec = -1;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.download_all_menu_item);
        // TODO: This sometimes runs before the service is bound, resulting in a crash.
        // Find a better way to handle it.
        final boolean downloadAll = service != null ? service.getShouldDownloadAll() : false;
        item.setTitle(downloadAll ? R.string.dont_download_all : R.string.download_all);
        item.setIcon(
                downloadAll
                        ? R.drawable.ic_cloud_off_white_24dp
                        : R.drawable.ic_cloud_download_white_24dp);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.browse_menu_item:
                if (service != null) startActivity(new Intent(this, BrowseTopActivity.class));
                return true;
            case R.id.search_menu_item:
                if (service != null) startActivity(new Intent(this, SearchFormActivity.class));
                return true;
            case R.id.pause_menu_item:
                if (service != null) service.pause();
                return true;
            case R.id.download_all_menu_item:
                if (service != null) service.setShouldDownloadAll(!service.getShouldDownloadAll());
                return true;
            case R.id.settings_menu_item:
                if (service != null) startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.exit_menu_item:
                if (service != null) stopService(new Intent(this, NupService.class));
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // Adapts our information about the current playlist and song for the song list view.
    private class SongListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView != null) {
                view = convertView;
            } else {
                LayoutInflater inflater =
                        (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                view = inflater.inflate(R.layout.playlist_row, null);
            }

            TextView artistView = (TextView) view.findViewById(R.id.artist);
            TextView titleView = (TextView) view.findViewById(R.id.title);
            TextView percentView = (TextView) view.findViewById(R.id.percent);

            Song song = songs.get(position);

            artistView.setText(song.artist);
            titleView.setText(song.title);

            if (song.getTotalBytes() > 0) {
                if (song.getAvailableBytes() == song.getTotalBytes())
                    percentView.setText("\u2713"); // CHECK MARK from Dingbats
                else
                    percentView.setText(
                            (int)
                                            Math.round(
                                                    100.0
                                                            * song.getAvailableBytes()
                                                            / song.getTotalBytes())
                                    + "%");
                percentView.setVisibility(View.VISIBLE);
            } else {
                percentView.setVisibility(View.GONE);
            }

            boolean currentlyPlaying = (position == currentSongIndex);
            view.setBackgroundColor(
                    getResources()
                            .getColor(
                                    currentlyPlaying
                                            ? R.color.primary
                                            : android.R.color.transparent));
            artistView.setTextColor(
                    getResources()
                            .getColor(currentlyPlaying ? R.color.icons : R.color.primary_text));
            titleView.setTextColor(
                    getResources()
                            .getColor(currentlyPlaying ? R.color.icons : R.color.primary_text));
            percentView.setTextColor(
                    getResources()
                            .getColor(currentlyPlaying ? R.color.icons : R.color.primary_text));

            return view;
        }
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        if (view.getId() == R.id.playlist) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            Song song = songs.get(info.position);
            menu.setHeaderTitle(song.title);
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play);
            menu.add(0, MENU_ITEM_REMOVE_FROM_LIST, 0, R.string.remove_from_list);
            menu.add(0, MENU_ITEM_TRUNCATE_LIST, 0, R.string.truncate_list);
            menu.add(0, MENU_ITEM_SONG_DETAILS, 0, R.string.song_details_ellipsis);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Song song = songs.get(info.position);
        switch (item.getItemId()) {
            case MENU_ITEM_PLAY:
                updateCurrentSongIndex(info.position);
                schedulePlaySongTask(0);
                return true;
            case MENU_ITEM_REMOVE_FROM_LIST:
                service.removeFromPlaylist(info.position);
                return true;
            case MENU_ITEM_TRUNCATE_LIST:
                service.removeRangeFromPlaylist(info.position, songs.size() - 1);
                return true;
            case MENU_ITEM_SONG_DETAILS:
                if (song != null)
                    showDialog(DIALOG_SONG_DETAILS, SongDetailsDialog.createBundle(song));
                return true;
            default:
                return false;
        }
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        if (id == DIALOG_SONG_DETAILS) return SongDetailsDialog.createDialog(this);
        return null;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        super.onPrepareDialog(id, dialog, args);
        if (id == DIALOG_SONG_DETAILS) SongDetailsDialog.prepareDialog(dialog, args);
    }

    private Song getCurrentSong() {
        if (currentSongIndex >= 0 && currentSongIndex < songs.size()) {
            return songs.get(currentSongIndex);
        }
        return null;
    }

    private void updateCurrentSongIndex(int index) {
        currentSongIndex = index;
        updateSongDisplay(getCurrentSong());
        songListAdapter.notifyDataSetChanged();

        prevButton.setEnabled(currentSongIndex > 0);
        nextButton.setEnabled(!songs.isEmpty() && currentSongIndex < songs.size() - 1);
        pauseButton.setEnabled(!songs.isEmpty());
    }

    private void schedulePlaySongTask(int delayMs) {
        handler.removeCallbacks(playSongTask);
        handler.postDelayed(playSongTask, delayMs);
    }
}
