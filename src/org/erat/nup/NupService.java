// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.Rating;
import android.media.RemoteControlClient;
import android.media.RemoteControlClient.MetadataEditor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class NupService extends Service
                        implements Player.Listener,
                                   FileCache.Listener,
                                   SongDatabase.Listener,
                                   RemoteControlClient.OnGetPlaybackPositionListener {
    private static final String TAG = "NupService";

    // Identifier used for our "currently playing" notification.
    // Note: Using 0 makes the service not run in the foreground and the notification not show up.  Yay, that was fun to
    // figure out.
    private static final int NOTIFICATION_ID = 1;

    // Don't start playing a song until we have at least this many bytes of it.
    private static final long MIN_BYTES_BEFORE_PLAYING = 128 * 1024;

    // Don't start playing a song until we think we'll finish downloading the whole file at the current rate
    // sooner than this many milliseconds before the song would end (whew).
    private static final long EXTRA_BUFFER_MS = 10 * 1000;

    // Maximum number of cover bitmaps to keep in memory at once.
    private static final int MAX_LOADED_COVERS = 3;

    // If we receive a playback position report with a timestamp more than this many milliseconds beyond the last
    // on we received, we assume that something has gone wrong and ignore it.
    private static final long MAX_POSITION_REPORT_MS = 1000;

    // Report a song if we've played it for this many milliseconds.
    private static final long REPORT_PLAYBACK_THRESHOLD_MS = 240 * 1000;

    // Subdirectory where crash reports are written.
    private static final String CRASH_SUBDIRECTORY = "crashes";

    // Intent actions.
    private static final String ACTION_TOGGLE_PAUSE = "nup_toggle_pause";
    private static final String ACTION_NEXT_TRACK = "nup_next_track";
    private static final String ACTION_PREV_TRACK = "nup_prev_track";
    private static final String ACTION_MEDIA_BUTTON = "nup_media_button";

    interface SongListener {
        // Invoked when we switch to a new track in the playlist.
        void onSongChange(Song song, int index);

        // Invoked when the playback position in the current song changes.
        void onSongPositionChange(Song song, int positionMs, int durationMs);

        // Invoked when playback is paused or unpaused.
        void onPauseStateChange(boolean paused);

        // Invoked when the on-disk size of a song changes (because we're
        // downloading it or it got evicted from the cache).
        void onSongFileSizeChange(Song song);

        // Invoked when the cover bitmap for a song is successfully loaded.
        void onSongCoverLoad(Song song);

        // Invoked when the current set of songs to be played changes.
        void onPlaylistChange(List<Song> songs);
    }

    // Listener for the song database's aggregate data being updated.
    interface SongDatabaseUpdateListener {
        void onSongDatabaseUpdate();
    }

    // Plays songs.
    private Player mPlayer;
    private Thread mPlayerThread;

    // Downloads files.
    private FileCache mCache;
    private Thread mCacheThread;

    // Stores a local listing of all of the songs on the server.
    private SongDatabase mSongDb;

    // Loads songs' cover art from local disk or the network.
    private CoverLoader mCoverLoader;

    // Reports song playback to the music server.
    private PlaybackReporter mPlaybackReporter;

    // Points from song ID to Song.
    // This is the canonical set of songs that we've seen.
    private HashMap<Long,Song> mSongIdToSong = new HashMap<Long,Song>();

    // ID of the song that's currently being downloaded.
    private long mDownloadSongId = -1;

    // Index into |mSongs| of the song that's currently being downloaded.
    private int mDownloadIndex = -1;

    // Are we currently waiting for a file to be downloaded before we can play it?
    private boolean mWaitingForDownload = false;

    // Have we temporarily been told to download all queued songs?
    private boolean mShouldDownloadAll = false;

    // Current playlist.
    private List<Song> mSongs = new ArrayList<Song>();

    // Index of the song in |mSongs| that's being played.
    private int mCurrentSongIndex = -1;

    // Time at which we started playing the current song.
    private Date mCurrentSongStartDate;

    // Last playback position we were notified about for the current song.
    private int mCurrentSongLastPositionMs = 0;

    // Total time during which we've played the current song, in milliseconds.
    private long mCurrentSongPlayedMs = 0;

    // Local path of the song that's being played.
    private String mCurrentSongPath;

    // Is playback currently paused?
    private boolean mPaused = false;

    // Are we done playing the current song?
    private boolean mPlaybackComplete = false;

    // Have we reported the fact that we've played the current song?
    private boolean mReportedCurrentSong = false;

    private final IBinder mBinder = new LocalBinder();

    // Songs whose covers are currently being fetched.
    private HashSet<Song> mSongCoverFetches = new HashSet<Song>();

    // Songs whose covers we're currently keeping in memory.
    private List<Song> mSongsWithCovers = new ArrayList<Song>();

    // Used to run tasks on our thread.
    private Handler mHandler = new Handler();

    // Used to pass commands from the notification to this service.
    private PendingIntent mTogglePauseIntent;
    private PendingIntent mPrevTrackIntent;
    private PendingIntent mNextTrackIntent;

    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager;

    private SharedPreferences mPrefs;

    private PendingIntent mMediaButtonPendingIntent;
    private RemoteControlClient mRemoteControlClient;

    // Pause when phone calls arrive.
    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            // I don't want to know who's calling.  Why isn't there a more-limited permission?
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                mPlayer.pause();
                Toast.makeText(NupService.this, "Paused for incoming call.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private BroadcastReceiver mNoisyAudioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && mCurrentSongIndex >= 0 && !mPaused) {
                mPlayer.pause();
                Toast.makeText(NupService.this, "Paused since unplugged.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private AudioManager.OnAudioFocusChangeListener mAudioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            // TODO: Actually do something in response to focus changes.
            switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "audio focus gain");
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(TAG, "audio focus loss");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d(TAG, "audio focus loss transient");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "audio focus loss transient can duck");
                break;
            default:
                Log.d(TAG, "audio focus " + focusChange);
                break;
            }
        }
    };

    private SongListener mSongListener = null;
    private HashSet<SongDatabaseUpdateListener> mSongDatabaseUpdateListeners = new HashSet<SongDatabaseUpdateListener>();

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");
        CrashLogger.register(new File(getExternalFilesDir(null), CRASH_SUBDIRECTORY));

        if (Util.isNetworkAvailable(this))
            new AuthenticateTask(this).execute();

        mTogglePauseIntent = PendingIntent.getService(this, 0, new Intent(ACTION_TOGGLE_PAUSE, Uri.EMPTY, this, NupService.class), 0);
        mPrevTrackIntent = PendingIntent.getService(this, 0, new Intent(ACTION_PREV_TRACK, Uri.EMPTY, this, NupService.class), 0);
        mNextTrackIntent = PendingIntent.getService(this, 0, new Intent(ACTION_NEXT_TRACK, Uri.EMPTY, this, NupService.class), 0);

        updateNotification();

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int result = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        Log.d(TAG, "requested audio focus; got " + result);

        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        registerReceiver(mNoisyAudioReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        Intent mediaButtonIntent = new Intent(this, NupService.class);
        mediaButtonIntent.setAction(ACTION_MEDIA_BUTTON);
        mMediaButtonPendingIntent = PendingIntent.getService(this, 0, mediaButtonIntent, 0);
        mAudioManager.registerMediaButtonEventReceiver(mMediaButtonPendingIntent);
        mRemoteControlClient = new RemoteControlClient(mMediaButtonPendingIntent);
        mRemoteControlClient.setOnGetPlaybackPositionListener(this);
        mAudioManager.registerRemoteControlClient(mRemoteControlClient);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mPlayer = new Player(this);
        mPlayerThread = new Thread(mPlayer, "Player");
        mPlayerThread.setPriority(Thread.MAX_PRIORITY);
        mPlayerThread.start();

        mCache = new FileCache(this, this);
        mCacheThread = new Thread(mCache, "FileCache");
        mCacheThread.start();

        mSongDb = new SongDatabase(this, this, mCache);
        mCoverLoader = new CoverLoader(this);
        mPlaybackReporter = new PlaybackReporter(this, mSongDb);

    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        unregisterReceiver(mNoisyAudioReceiver);
        mAudioManager.unregisterMediaButtonEventReceiver(mMediaButtonPendingIntent);

        mSongDb.quit();
        mPlayer.quit();
        mCache.quit();
        try { mPlayerThread.join(); } catch (InterruptedException e) {}
        try { mCacheThread.join(); } catch (InterruptedException e) {}

        CrashLogger.unregister();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "received start id " + startId + ": " + intent);
        if (intent != null && intent.getAction() != null) {
            if (ACTION_TOGGLE_PAUSE.equals(intent.getAction())) {
                togglePause();
            } else if (ACTION_NEXT_TRACK.equals(intent.getAction())) {
                playSongAtIndex(mCurrentSongIndex + 1);
            } else if (ACTION_PREV_TRACK.equals(intent.getAction())) {
                playSongAtIndex(mCurrentSongIndex - 1);
            } else if (ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                KeyEvent event = (KeyEvent) intent.getExtras().get(Intent.EXTRA_KEY_EVENT);
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        playSongAtIndex(mCurrentSongIndex + 1);
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        mPlayer.pause();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        mPlayer.unpause();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        mPlayer.togglePause();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        playSongAtIndex(mCurrentSongIndex - 1);
                        break;
                    case KeyEvent.KEYCODE_MEDIA_STOP:
                        mPlayer.pause();
                        break;
                    default:
                        Toast.makeText(NupService.this, "Unhandled key event " + event.getKeyCode(), Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public boolean getPaused() { return mPaused; }
    public List<Song> getSongs() { return mSongs; }
    public int getCurrentSongIndex() { return mCurrentSongIndex; }
    public Song getCurrentSong() {
        return (mCurrentSongIndex >= 0 && mCurrentSongIndex < mSongs.size()) ? mSongs.get(mCurrentSongIndex) : null;
    }
    public Song getNextSong() {
        return (mCurrentSongIndex >= 0 && mCurrentSongIndex + 1 < mSongs.size()) ? mSongs.get(mCurrentSongIndex + 1) : null;
    }
    public int getCurrentSongLastPositionMs() { return mCurrentSongLastPositionMs; }
    public SongDatabase getSongDb() { return mSongDb; }

    public void setSongListener(SongListener listener) {
        mSongListener = listener;
    }

    public void addSongDatabaseUpdateListener(SongDatabaseUpdateListener listener) {
        mSongDatabaseUpdateListeners.add(listener);
    }
    public void removeSongDatabaseUpdateListener(SongDatabaseUpdateListener listener) {
        mSongDatabaseUpdateListeners.remove(listener);
    }

    public boolean getShouldDownloadAll() { return mShouldDownloadAll; }
    public void setShouldDownloadAll(boolean downloadAll) {
        if (downloadAll == mShouldDownloadAll)
            return;
        mShouldDownloadAll = downloadAll;
        if (!mSongs.isEmpty() && downloadAll && mDownloadSongId == -1)
            maybeDownloadAnotherSong(mCurrentSongIndex >= 0 ? mCurrentSongIndex : 0);
    }

    // Unregister an object that might be registered as one or more of our listeners.
    // Typically called when the object is an activity that's getting destroyed so
    // we'll drop our references to it.
    void unregisterListener(Object object) {
        if (mSongListener == object)
            mSongListener = null;
    }

    public class LocalBinder extends Binder {
        NupService getService() {
            return NupService.this;
        }
    }

    // Update the notification text.
    private void updateNotification() {
        final Song song = getCurrentSong();

        // TODO: Navigate the labyrinth of new media playback classes:
        // https://developer.android.com/about/versions/android-5.0.html#MediaPlaybackControl
        // https://developer.android.com/guide/topics/ui/notifiers/notifications.html#controllingMedia
        // https://developer.android.com/reference/android/app/Notification.MediaStyle.html
        // https://developer.android.com/reference/android/media/session/MediaSession.html
        // https://developer.android.com/reference/android/media/session/MediaController.html
        // etc.
        Notification.Builder builder = new Notification.Builder(this)
            .setContentTitle(song != null ? song.getArtist() : getString(R.string.startup_message_title))
            .setContentText(song != null ? song.getTitle() : getString(R.string.startup_message_text))
            .setSmallIcon(R.drawable.status)
            .setColor(getResources().getColor(R.color.primary))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0))
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false);

        if (song != null) {
            builder.setLargeIcon(song.getCoverBitmap());

            boolean showPlayPause = !mPlaybackComplete;
            boolean showPrev = mCurrentSongIndex > 0;
            boolean showNext = !mSongs.isEmpty() && mCurrentSongIndex < mSongs.size() - 1;
            int numActions = (showPlayPause ? 1 : 0) + (showPrev ? 1 : 0) + (showNext ? 1 : 0);
            boolean showLabels = numActions < 3;

            if (showPlayPause) {
                builder.addAction(mPaused ? R.drawable.ic_play_arrow_black_36dp : R.drawable.ic_pause_black_36dp,
                                  showLabels ? getString(mPaused ? R.string.play : R.string.pause) : "",
                                  mTogglePauseIntent);
            }
            if (showPrev) {
                builder.addAction(R.drawable.ic_skip_previous_black_36dp,
                                  showLabels ? getString(R.string.prev) : "",
                                  mPrevTrackIntent);
            }
            if (showNext) {
                builder.addAction(R.drawable.ic_skip_next_black_36dp,
                                  showLabels ? getString(R.string.next) : "",
                                  mNextTrackIntent);
            }
        }

        startForeground(NOTIFICATION_ID, builder.build());
    }

    // Update the track metadata displayed via Bluetooth.
    private void updateMetadata() {
        MetadataEditor editor = mRemoteControlClient.editMetadata(true);
        final Song song = getCurrentSong();
        if (song != null) {
            editor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, song.getArtist());
            editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, song.getTitle());
            editor.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, song.getAlbum());
            editor.putLong(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER, (long) song.getDiscNum());
            editor.putLong(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, (long) song.getTrackNum());
            editor.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, (long) song.getLengthSec() * 1000);

            Bitmap bitmap = song.getCoverBitmap();
            if (bitmap != null) {
                // Pass a copy of the original bitmap. Apparently the later apply() call recycles the bitmap, which then
                // causes a crash when we try to use it later: https://code.google.com/p/android/issues/detail?id=74967
                editor.putBitmap(MetadataEditor.BITMAP_KEY_ARTWORK, bitmap.copy(bitmap.getConfig(), true));
            }

            if (song.getRating() >= 0.0) {
                editor.putObject(MetadataEditor.RATING_KEY_BY_USER,
                                 Rating.newStarRating(Rating.RATING_5_STARS, (float) (1.0 + song.getRating() * 4.0)));
            }
        }
        editor.apply();
    }

    // Toggle whether we're playing the current song or not.
    public void togglePause() {
        mPlayer.togglePause();
    }

    public void clearPlaylist() {
        removeRangeFromPlaylist(0, mSongs.size() - 1);
    }

    public void appendSongToPlaylist(Song song) {
        List<Song> songs = new ArrayList<Song>();
        songs.add(song);
        appendSongsToPlaylist(songs);
    }

    public void appendSongsToPlaylist(List<Song> songs) {
        insertSongs(songs, mSongs.size());
    }

    public void addSongToPlaylist(Song song, boolean play) {
        List<Song> songs = new ArrayList<Song>();
        songs.add(song);
        addSongsToPlaylist(songs, play);
    }

    public void addSongsToPlaylist(List<Song> songs, boolean play) {
        if (mCurrentSongIndex < 0) {
            // This should play automatically.
            insertSongs(songs, 0);
        } else {
            boolean alreadyPlayed = insertSongs(songs, mCurrentSongIndex + 1);
            if (play && !alreadyPlayed)
                playSongAtIndex(mCurrentSongIndex + 1);
        }
    }

    public void removeFromPlaylist(int index) {
        removeRangeFromPlaylist(index, index);
    }

    // Remove a range of songs from the playlist.
    public void removeRangeFromPlaylist(int firstIndex, int lastIndex) {
        if (firstIndex < 0)
            firstIndex = 0;
        if (lastIndex >= mSongs.size())
            lastIndex = mSongs.size() - 1;

        boolean removedPlaying = false;
        for (int numToRemove = lastIndex - firstIndex + 1; numToRemove > 0; --numToRemove) {
            mSongs.remove(firstIndex);

            if (mCurrentSongIndex == firstIndex) {
                removedPlaying = true;
                stopPlaying();
            } else if (mCurrentSongIndex > firstIndex) {
                mCurrentSongIndex--;
            }

            if (mDownloadIndex == firstIndex) {
                mCache.abortDownload(mDownloadSongId);
                mDownloadSongId = -1;
                mDownloadIndex = -1;
            } else if (mDownloadIndex > firstIndex) {
                mDownloadIndex--;
            }
        }

        if (removedPlaying) {
            updateNotification();
            if (mCurrentSongIndex < mSongs.size())
                playSongAtIndex(mCurrentSongIndex);
            else
                mCurrentSongIndex = -1;
        }

        if (mSongListener != null)
            mSongListener.onPlaylistChange(mSongs);
    }

    // Play the song at a particular position in the playlist.
    public void playSongAtIndex(int index) {
        if (index < 0 || index >= mSongs.size())
            return;

        mCurrentSongIndex = index;
        Song song = getCurrentSong();

        mCurrentSongStartDate = null;
        mCurrentSongLastPositionMs = 0;
        mCurrentSongPlayedMs = 0;
        mReportedCurrentSong = false;
        mPlaybackComplete = false;

        mCache.clearPinnedSongIds();

        // If we've already downloaded the whole file, start playing it.
        FileCacheEntry cacheEntry = mCache.getEntry(getCurrentSong().getSongId());
        if (cacheEntry != null && cacheEntry.isFullyCached()) {
            Log.d(TAG, "file " + getCurrentSong().getUri().toString() + " already downloaded; playing");
            playCacheEntry(cacheEntry);

            // If we're downloading some other song (maybe we were downloading the
            // previously-being-played song), abort it.
            // TODO: This could actually be a future song that we were already
            // downloading and will soon need to start downloading again.
            if (mDownloadSongId != -1 && mDownloadSongId != cacheEntry.getSongId()) {
                mCache.abortDownload(mDownloadSongId);
                mDownloadSongId = -1;
                mDownloadIndex = -1;
            }

            maybeDownloadAnotherSong(mCurrentSongIndex + 1);
        } else {
            // Otherwise, start downloading it if we've never tried downloading it before,
            // or if we have but it's not currently being downloaded.
            mPlayer.abortPlayback();
            if (cacheEntry == null || mDownloadSongId != cacheEntry.getSongId()) {
                if (mDownloadSongId != -1)
                    mCache.abortDownload(mDownloadSongId);
                cacheEntry = mCache.downloadSong(song);
                mDownloadSongId = song.getSongId();
                mDownloadIndex = mCurrentSongIndex;
            }
            mWaitingForDownload = true;
            mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_BUFFERING, -1, 1.0f);
        }

        // Enqueue the next song if we already have it.
        Song nextSong = getNextSong();
        if (nextSong != null) {
            FileCacheEntry nextEntry = mCache.getEntry(nextSong.getSongId());
            if (nextEntry != null && nextEntry.isFullyCached()) {
                mPlayer.queueFile(nextEntry.getLocalFile(this).getPath(), nextEntry.getTotalBytes());
            }
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        mCache.pinSongId(song.getSongId());

        fetchCoverForSongIfMissing(song);
        updateNotification();
        updateMetadata();

        if (mSongListener != null)
            mSongListener.onSongChange(song, mCurrentSongIndex);
    }

    // Stop playing the current song, if any.
    public void stopPlaying() {
        mCurrentSongPath = null;
        mPlayer.abortPlayback();
    }

    // Start fetching the cover for a song if it's not loaded already.
    public void fetchCoverForSongIfMissing(Song song) {
        if (song.getCoverBitmap() != null || song.getCoverUri() == null)
            return;

        if (!mSongCoverFetches.contains(song)) {
            mSongCoverFetches.add(song);
            new CoverFetchTask(song).execute();
        }
    }

    // Fetches the cover bitmap for a particular song.
    class CoverFetchTask extends AsyncTask<Void, Void, Bitmap> {
        private final Song mSong;

        public CoverFetchTask(Song song) {
            mSong = song;
        }

        @Override
        protected Bitmap doInBackground(Void... args) {
            return mCoverLoader.loadCover(mSong.getCoverUri());
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            storeCoverForSong(mSong, bitmap);
            mSongCoverFetches.remove(mSong);
            if (mSong.getCoverBitmap() != null) {
                if (mSongListener != null)
                    mSongListener.onSongCoverLoad(mSong);
                if (mSong == getCurrentSong())
                    updateNotification();
            }
        }
    }

    public long getTotalCachedBytes() {
        return mCache.getTotalCachedBytes();
    }

    public void clearCache() {
        mCache.clear();
    }

    // Implements Player.Listener.
    @Override
    public void onPlaybackComplete() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mPlaybackComplete = true;
                updateNotification();
                updateMetadata();
                mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED, -1, 1.0f);
                if (mCurrentSongIndex < mSongs.size() - 1)
                    playSongAtIndex(mCurrentSongIndex + 1);
            }
        });
    }

    // Implements Player.Listener.
    @Override
    public void onPlaybackPositionChange(final String path, final int positionMs, final int durationMs) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!path.equals(mCurrentSongPath))
                    return;

                Song song = getCurrentSong();
                if (mSongListener != null)
                    mSongListener.onSongPositionChange(song, positionMs, durationMs);

                if (positionMs > mCurrentSongLastPositionMs &&
                    positionMs <= mCurrentSongLastPositionMs + MAX_POSITION_REPORT_MS) {
                    mCurrentSongPlayedMs += (positionMs - mCurrentSongLastPositionMs);
                    if (!mReportedCurrentSong &&
                        (mCurrentSongPlayedMs >= Math.max(durationMs, song.getLengthSec() * 1000) / 2 ||
                         mCurrentSongPlayedMs >= REPORT_PLAYBACK_THRESHOLD_MS)) {
                        mPlaybackReporter.report(song.getSongId(), mCurrentSongStartDate);
                        mReportedCurrentSong = true;
                    }
                }

                mCurrentSongLastPositionMs = positionMs;
                mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING, positionMs, 1.0f);
            }
        });
    }

    // Implements Player.Listener.
    @Override
    public void onPauseStateChange(boolean paused) {
        mPaused = paused;
        updateNotification();
        if (mSongListener != null)
            mSongListener.onPauseStateChange(paused);
        mRemoteControlClient.setPlaybackState(
            paused ? RemoteControlClient.PLAYSTATE_PAUSED : RemoteControlClient.PLAYSTATE_PLAYING,
            mCurrentSongLastPositionMs, 1.0f);
    }

    // Implements Player.Listener.
    @Override
    public void onPlaybackError(final String description) {
        mHandler.post(new Runnable() {
            public void run() {
                Toast.makeText(NupService.this, description, Toast.LENGTH_LONG).show();
            }
        });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadError(final FileCacheEntry entry, final String reason) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NupService.this, "Got retryable error: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadFail(final FileCacheEntry entry, final String reason) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got notification that download of song " + entry.getSongId() + " failed: " + reason);
                Toast.makeText(NupService.this, "Download of " + mSongIdToSong.get(entry.getSongId()).getUri().toString() +
                               " failed: " + reason, Toast.LENGTH_LONG).show();
                if (entry.getSongId() == mDownloadSongId) {
                    mDownloadSongId = -1;
                    mDownloadIndex = -1;
                    mWaitingForDownload = false;
                }
            }
        });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadComplete(final FileCacheEntry entry) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got notification that download of song " + entry.getSongId() + " is done");

                Song song = mSongIdToSong.get(entry.getSongId());
                if (song == null)
                    return;

                song.updateBytes(entry);
                mSongDb.handleSongCached(song.getSongId());

                if (song == getCurrentSong() && mWaitingForDownload) {
                    mWaitingForDownload = false;
                    playCacheEntry(entry);
                } else if (song == getNextSong()) {
                    mPlayer.queueFile(entry.getLocalFile(NupService.this).getPath(), entry.getTotalBytes());
                }

                if (mSongListener != null)
                    mSongListener.onSongFileSizeChange(song);

                if (entry.getSongId() == mDownloadSongId) {
                    int nextIndex = mDownloadIndex + 1;
                    mDownloadSongId = -1;
                    mDownloadIndex = -1;
                    maybeDownloadAnotherSong(nextIndex);
                }
            }
        });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadProgress(final FileCacheEntry entry, final long downloadedBytes, final long elapsedMs) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Song song = mSongIdToSong.get(entry.getSongId());
                if (song == null)
                    return;

                song.updateBytes(entry);

                if (song == getCurrentSong()) {
                    if (mWaitingForDownload && canPlaySong(entry, downloadedBytes, elapsedMs, song.getLengthSec())) {
                        mWaitingForDownload = false;
                        playCacheEntry(entry);
                    }
                }

                if (mSongListener != null)
                    mSongListener.onSongFileSizeChange(song);
            }
        });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheEviction(final FileCacheEntry entry) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got notification that song " + entry.getSongId() + " has been evicted");
                mSongDb.handleSongEvicted(entry.getSongId());

                Song song = mSongIdToSong.get(entry.getSongId());
                if (song == null)
                    return;

                song.setAvailableBytes(0);
                song.setTotalBytes(0);
                if (mSongListener != null)
                    mSongListener.onSongFileSizeChange(song);
            }
        });
    }

    // Implements SongDatabase.Listener.
    @Override
    public void onAggregateDataUpdate() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got notice that aggregate data has been updated");
                for (SongDatabaseUpdateListener listener : mSongDatabaseUpdateListeners)
                    listener.onSongDatabaseUpdate();
            }
        });
    }

    // Implements RemoteControlClient.OnGetPlaybackPositionListener.
    @Override
    public long onGetPlaybackPosition() {
        return mCurrentSongLastPositionMs;
    }

    // Insert a list of songs into the playlist at a particular position.
    // Plays the first one, if no song is already playing or if we were previously at the end of the
    // playlist and we've appended to it.  Returns true if we started playing.
    private boolean insertSongs(List<Song> songs, int index) {
        if (index < 0 || index > mSongs.size()) {
            Log.e(TAG, "ignoring request to insert " + songs.size() + " song(s) at index " + index);
            return false;
        }

        // Songs that we didn't already have.  We track these so we can check
        // the cache for them later.
        ArrayList<Song> newSongs = new ArrayList<Song>();

        // Use our own version of each song if we have it already.
        ArrayList<Song> tmpSongs = new ArrayList<Song>();
        for (Song song : songs) {
            Song ourSong = mSongIdToSong.get(song.getSongId());
            if (ourSong != null) {
                tmpSongs.add(ourSong);
            } else {
                tmpSongs.add(song);
                newSongs.add(song);
            }
        }
        songs = tmpSongs;

        mSongs.addAll(index, songs);
        if (mCurrentSongIndex >= 0 && index <= mCurrentSongIndex)
            mCurrentSongIndex += songs.size();
        if (mDownloadIndex >= 0 && index <= mDownloadIndex)
            mDownloadIndex += songs.size();

        if (mSongListener != null)
            mSongListener.onPlaylistChange(mSongs);

        for (Song song : newSongs) {
            mSongIdToSong.put(song.getSongId(), song);
            FileCacheEntry entry = mCache.getEntry(song.getSongId());
            if (entry != null) {
                song.updateBytes(entry);
                if (mSongListener != null)
                    mSongListener.onSongFileSizeChange(song);
            }
        }

        boolean played = false;
        if (mCurrentSongIndex == -1) {
            // If we didn't have any songs, then start playing the first one we added.
            playSongAtIndex(0);
            played = true;
        } else if (mCurrentSongIndex < mSongs.size() - 1 && mPlaybackComplete) {
            // If we were previously done playing (because we reached the end of the playlist),
            // then start playing the first song we added.
            playSongAtIndex(mCurrentSongIndex + 1);
            played = true;
        } else if (mDownloadSongId == -1) {
            // Otherwise, consider downloading the new songs if we're not already downloading something.
            maybeDownloadAnotherSong(index);
        }
        return played;
    }

    // Do we have enough of a song downloaded at a fast enough rate that we'll probably
    // finish downloading it before playback reaches the end of the song?
    private boolean canPlaySong(FileCacheEntry entry, long downloadedBytes, long elapsedMs, int songLengthSec) {
        if (entry.isFullyCached())
            return true;
        double bytesPerMs = (double) downloadedBytes / elapsedMs;
        long remainingMs = (long) ((entry.getTotalBytes() - entry.getCachedBytes()) / bytesPerMs);
        return (entry.getCachedBytes() >= MIN_BYTES_BEFORE_PLAYING && remainingMs + EXTRA_BUFFER_MS <= songLengthSec * 1000);
    }

    // Play the local file where a cache entry is stored.
    private void playCacheEntry(FileCacheEntry entry) {
        mCurrentSongPath = entry.getLocalFile(this).getPath();
        mPlayer.playFile(mCurrentSongPath, entry.getTotalBytes());
        mCurrentSongStartDate = new Date();
        mCache.updateLastAccessTime(entry.getSongId());
        updateNotification();
    }

    // Try to download the next not-yet-downloaded song in the playlist.
    private void maybeDownloadAnotherSong(int index) {
        if (mDownloadSongId != -1) {
            Log.e(TAG, "aborting prefetch since download of song " + mDownloadSongId + " is still in progress");
            return;
        }

        final int songsToPreload = Integer.valueOf(
            mPrefs.getString(NupPreferences.SONGS_TO_PRELOAD,
                             NupPreferences.SONGS_TO_PRELOAD_DEFAULT));

        for (; index < mSongs.size() && (mShouldDownloadAll || index - mCurrentSongIndex <= songsToPreload); index++) {
            Song song = mSongs.get(index);
            FileCacheEntry entry = mCache.getEntry(song.getSongId());
            if (entry != null && entry.isFullyCached()) {
                // We already have this one.  Pin it to make sure that it
                // doesn't get evicted by a later song.
                mCache.pinSongId(song.getSongId());
                continue;
            }

            entry = mCache.downloadSong(song);
            mDownloadSongId = song.getSongId();
            mDownloadIndex = index;
            mCache.pinSongId(song.getSongId());
            fetchCoverForSongIfMissing(song);
            return;
        }
    }

    // Set |song|'s cover bitmap to |bitmap|.
    // Also makes sure that we don't have more than |MAX_LOADED_COVERS| bitmaps in-memory.
    private void storeCoverForSong(Song song, Bitmap bitmap) {
        song.setCoverBitmap(bitmap);
        final int existingIndex = mSongsWithCovers.indexOf(song);

        // If we didn't get a bitmap, bail out early.
        if (bitmap == null) {
            if (existingIndex >= 0)
                mSongsWithCovers.remove(existingIndex);
            return;
        }

        // If the song is already in the list, remove it so we can add it to the end.
        if (existingIndex >= 0) {
            // It's already at the end of the list; we don't need to move it.
            if (existingIndex == mSongsWithCovers.size() - 1)
                return;
            mSongsWithCovers.remove(existingIndex);
        }

        // If we're full, drop the cover from the first song on the list.
        if (mSongsWithCovers.size() == MAX_LOADED_COVERS) {
            mSongsWithCovers.get(0).setCoverBitmap(null);
            mSongsWithCovers.remove(0);
        }

        mSongsWithCovers.add(song);
    }

    // Choose a filename to use for storing a song locally (used to just use a slightly-mangled version
    // of the remote path, but I think I saw a problem -- didn't investigate much).
    private static String chooseLocalFilenameForSong(Song song) {
        return String.format("%d.mp3", song.getSongId());
    }
}
