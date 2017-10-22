// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.session.MediaSession;
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
                                   SongDatabase.Listener {
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

    // If we receive a playback update with a position more than this many milliseconds beyond the last one we received,
    // we assume that something has gone wrong and ignore it.
    private static final long MAX_POSITION_REPORT_MS = 5000;

    // Report a song unconditionally if we've played it for this many milliseconds.
    private static final long REPORT_PLAYBACK_THRESHOLD_MS = 240 * 1000;

    // Ignore AUDIO_BECOMING_NOISY broadcast intents for this long after a user switch.
    private static final long IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS = 1000;

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

    // Abstracts running background tasks.
    private TaskRunner mTaskRunner;

    // Authenticates with Google Cloud Storage.
    private Authenticator mAuthenticator;

    // Downloads files.
    private Downloader mDownloader;

    // Plays songs.
    private Player mPlayer;
    private Thread mPlayerThread;

    // Caches songs.
    private FileCache mCache;
    private Thread mCacheThread;

    // Stores a local listing of all of the songs on the server.
    private SongDatabase mSongDb;

    // Loads songs' cover art from local disk or the network.
    private CoverLoader mCoverLoader;

    // Reports song playback to the music server.
    private PlaybackReporter mPlaybackReporter;

    // Publishes song metadata and handles remote commands.
    private MediaSessionManager mMediaSessionManager;

    // Token identifying the session managed by |mMediaSessionManager|.
    private MediaSession.Token mMediaSessionToken;

    // Updates the notification.
    private NotificationManager mNotificationManager;
    private NotificationCreator mNotificationCreator;

    private NetworkHelper mNetworkHelper;

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

    // Last time at which the user was foregrounded or backgrounded.
    private Date mLastUserSwitchTime;

    // Used to run tasks on our thread.
    private Handler mHandler = new Handler();

    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager;

    private SharedPreferences mPrefs;

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

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.d(TAG, "Audio becoming noisy");

                // Switching users apparently triggers an AUDIO_BECOMING_NOISY broadcast intent (which usually indicates
                // that headphones have been disconnected). AudioManager.isWiredHeadsetOn() returns true when the
                // notification is sent due to headphones being unplugged (why?), so that doesn't seem to help for
                // ignoring this. Instead, ignore these intents for a brief period after user-switch broadcast intents.
                final boolean userSwitchedRecently = (mLastUserSwitchTime == null) ? true :
                    (new Date()).getTime() - mLastUserSwitchTime.getTime() <= IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS;

                if (mCurrentSongIndex >= 0 && !mPaused && !userSwitchedRecently) {
                    mPlayer.pause();
                    Toast.makeText(NupService.this, "Paused since unplugged.", Toast.LENGTH_SHORT).show();
                }
            } else if (Intent.ACTION_USER_BACKGROUND.equals(intent.getAction())) {
                Log.d(TAG, "User is backgrounded");
                mLastUserSwitchTime = new Date();
            } else if (Intent.ACTION_USER_FOREGROUND.equals(intent.getAction())) {
                Log.d(TAG, "User is foregrounded");
                mLastUserSwitchTime = new Date();
            }
        }
    };

    private AudioManager.OnAudioFocusChangeListener mAudioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "Gained audio focus");
                mPlayer.setLowVolume(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(TAG, "Lost audio focus");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d(TAG, "Transiently lost audio focus");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "Transiently lost audio focus (but can duck)");
                mPlayer.setLowVolume(true);
                break;
            default:
                Log.d(TAG, "Unhandled audio focus change " + focusChange);
                break;
            }
        }
    };

    private SongListener mSongListener = null;
    private HashSet<SongDatabaseUpdateListener> mSongDatabaseUpdateListeners = new HashSet<SongDatabaseUpdateListener>();

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");

        // It'd be nice to set this up before we do anything else, but getExternalFilesDir() blocks. :-/
        new AsyncTask<Void, Void, File>() {
            @Override protected File doInBackground(Void... args) {
                return new File(getExternalFilesDir(null), CRASH_SUBDIRECTORY);
            }
            @Override protected void onPostExecute(File dir) {
                CrashLogger.register(dir);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        mTaskRunner = new TaskRunner();
        mNetworkHelper = new NetworkHelper(this);
        mAuthenticator = new Authenticator(this);
        if (mNetworkHelper.isNetworkAvailable()) {
            authenticateInBackground();
        }

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int result = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        Log.d(TAG, "requested audio focus; got " + result);

        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        filter.addAction(Intent.ACTION_USER_FOREGROUND);
        registerReceiver(mBroadcastReceiver, filter);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mDownloader = new Downloader(mAuthenticator, mPrefs);

        mPlayer = new Player(this, this, mHandler);
        mPlayerThread = new Thread(mPlayer, "Player");
        mPlayerThread.setPriority(Thread.MAX_PRIORITY);
        mPlayerThread.start();

        mCache = new FileCache(this, this, mDownloader, mNetworkHelper);
        mCacheThread = new Thread(mCache, "FileCache");
        mCacheThread.start();

        mSongDb = new SongDatabase(this, this, mCache, mDownloader, mNetworkHelper);
        mCoverLoader = new CoverLoader(this, mDownloader, mTaskRunner, new BitmapDecoder(), mNetworkHelper);
        mPlaybackReporter = new PlaybackReporter(mSongDb, mDownloader, mTaskRunner, mNetworkHelper);

        mMediaSessionManager = new MediaSessionManager(this, new MediaSession.Callback() {
            @Override public void onPause() {
                mPlayer.pause();
            }
            @Override public void onPlay() {
                mPlayer.unpause();
            }
            @Override public void onSkipToNext() {
                playSongAtIndex(mCurrentSongIndex + 1);
            }
            @Override public void onSkipToPrevious() {
                playSongAtIndex(mCurrentSongIndex - 1);
            }
            @Override public void onStop() {
                mPlayer.pause();
            }
        });
        mMediaSessionToken = mMediaSessionManager.getToken();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationCreator = new NotificationCreator(
            this, mNotificationManager, mMediaSessionToken,
            PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0),
            PendingIntent.getService(this, 0, new Intent(ACTION_TOGGLE_PAUSE, Uri.EMPTY, this, NupService.class), 0),
            PendingIntent.getService(this, 0, new Intent(ACTION_PREV_TRACK, Uri.EMPTY, this, NupService.class), 0),
            PendingIntent.getService(this, 0, new Intent(ACTION_NEXT_TRACK, Uri.EMPTY, this, NupService.class), 0));
        Notification notification = mNotificationCreator.createNotification(
            false, getCurrentSong(), mPaused, mPlaybackComplete, mCurrentSongIndex, mSongs.size());
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        unregisterReceiver(mBroadcastReceiver);

        mMediaSessionManager.cleanUp();
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

    /** Updates the currently-displayed notification if needed. */
    private void updateNotification() {
        Notification notification = mNotificationCreator.createNotification(
            true, getCurrentSong(), mPaused, mPlaybackComplete, mCurrentSongIndex, mSongs.size());
        if (notification != null) {
            mNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    /** Toggle whether we're playing the current song or not. */
    public void togglePause() {
        mPlayer.togglePause();
    }

    public void pause() {
        mPlayer.pause();
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

    public void addSongsToPlaylist(List<Song> songs, boolean forcePlay) {
        int index = mCurrentSongIndex;
        boolean alreadyPlayed = insertSongs(songs, index < 0 ? 0 : index + 1);
        if (forcePlay && !alreadyPlayed) {
            playSongAtIndex(index < 0 ? 0 : index + 1);
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
            if (!mSongs.isEmpty() && mCurrentSongIndex < mSongs.size()) {
                playSongAtIndex(mCurrentSongIndex);
            } else {
                mCurrentSongIndex = -1;
                mMediaSessionManager.updateSong(null);
                updatePlaybackState();
            }
        }

        // Maybe the e.g. now-next-to-be-played song isn't downloaded yet.
        if (mDownloadSongId == -1 && !mSongs.isEmpty() && mCurrentSongIndex < mSongs.size() - 1) {
            maybeDownloadAnotherSong(mCurrentSongIndex + 1);
        }
        if (mSongListener != null) {
            mSongListener.onPlaylistChange(mSongs);
        }
        updateNotification();
        mMediaSessionManager.updatePlaylist(mSongs);
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
            Log.d(TAG, "file " + getCurrentSong().getUrl().toString() + " already downloaded; playing");
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
            updatePlaybackState();
        }

        // Enqueue the next song if we already have it.
        Song nextSong = getNextSong();
        if (nextSong != null) {
            FileCacheEntry nextEntry = mCache.getEntry(nextSong.getSongId());
            if (nextEntry != null && nextEntry.isFullyCached()) {
                mPlayer.queueFile(nextEntry.getLocalFile().getPath(), nextEntry.getTotalBytes());
            }
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        mCache.pinSongId(song.getSongId());

        fetchCoverForSongIfMissing(song);
        updateNotification();
        mMediaSessionManager.updateSong(song);
        updatePlaybackState();
        if (mSongListener != null) {
            mSongListener.onSongChange(song, mCurrentSongIndex);
        }
    }

    // Stop playing the current song, if any.
    public void stopPlaying() {
        mCurrentSongPath = null;
        mPlayer.abortPlayback();
    }

    // Start fetching the cover for a song if it's not loaded already.
    public void fetchCoverForSongIfMissing(Song song) {
        if (song.getCoverBitmap() != null || song.getCoverUrl() == null)
            return;

        if (!mSongCoverFetches.contains(song)) {
            mSongCoverFetches.add(song);
            new CoverFetchTask(song).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
            return mCoverLoader.loadCover(mSong.getCoverUrl());
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            storeCoverForSong(mSong, bitmap);
            mSongCoverFetches.remove(mSong);
            if (mSong.getCoverBitmap() != null) {
                if (mSongListener != null)
                    mSongListener.onSongCoverLoad(mSong);
                if (mSong == getCurrentSong()) {
                    updateNotification();
                    mMediaSessionManager.updateSong(mSong);
                }
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
    @Override public void onPlaybackComplete() {
        Util.assertOnMainThread();
        mPlaybackComplete = true;
        updateNotification();
        updatePlaybackState();
        if (mCurrentSongIndex < mSongs.size() - 1) {
            playSongAtIndex(mCurrentSongIndex + 1);
        }
    }

    // Implements Player.Listener.
    @Override public void onPlaybackPositionChange(final String path, final int positionMs, final int durationMs) {
        Util.assertOnMainThread();
        if (!path.equals(mCurrentSongPath))
            return;

        Song song = getCurrentSong();
        if (mSongListener != null)
            mSongListener.onSongPositionChange(song, positionMs, durationMs);

        int elapsed = positionMs - mCurrentSongLastPositionMs;
        if (elapsed > 0 && elapsed <= MAX_POSITION_REPORT_MS) {
            mCurrentSongPlayedMs += elapsed;
            if (!mReportedCurrentSong &&
                (mCurrentSongPlayedMs >= Math.max(durationMs, song.getLengthSec() * 1000) / 2 ||
                 mCurrentSongPlayedMs >= REPORT_PLAYBACK_THRESHOLD_MS)) {
                mPlaybackReporter.report(song.getSongId(), mCurrentSongStartDate);
                mReportedCurrentSong = true;
            }
        }

        mCurrentSongLastPositionMs = positionMs;
        updatePlaybackState();
    }

    // Implements Player.Listener.
    @Override public void onPauseStateChange(final boolean paused) {
        Util.assertOnMainThread();
        mPaused = paused;
        updateNotification();
        if (mSongListener != null) {
            mSongListener.onPauseStateChange(paused);
        }
        updatePlaybackState();
    }

    // Implements Player.Listener.
    @Override public void onPlaybackError(final String description) {
        Util.assertOnMainThread();
        Toast.makeText(NupService.this, description, Toast.LENGTH_LONG).show();
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
                Toast.makeText(NupService.this, "Download of " + mSongIdToSong.get(entry.getSongId()).getUrl().toString() +
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
                    mPlayer.queueFile(entry.getLocalFile().getPath(), entry.getTotalBytes());
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

    /** Starts authenticating with Google Cloud Storage in the background. */
    public void authenticateInBackground() {
        mAuthenticator.authenticateInBackground();
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
        if (mCurrentSongIndex >= 0 && index <= mCurrentSongIndex) {
            mCurrentSongIndex += songs.size();
        }
        if (mDownloadIndex >= 0 && index <= mDownloadIndex) {
            mDownloadIndex += songs.size();
        }

        if (mSongListener != null) {
            mSongListener.onPlaylistChange(mSongs);
        }
        mMediaSessionManager.updatePlaylist(mSongs);

        for (Song song : newSongs) {
            mSongIdToSong.put(song.getSongId(), song);
            FileCacheEntry entry = mCache.getEntry(song.getSongId());
            if (entry != null) {
                song.updateBytes(entry);
                if (mSongListener != null) {
                    mSongListener.onSongFileSizeChange(song);
                }
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
        updateNotification();
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
        mCurrentSongPath = entry.getLocalFile().getPath();
        mPlayer.playFile(mCurrentSongPath, entry.getTotalBytes());
        mCurrentSongStartDate = new Date();
        mCache.updateLastAccessTime(entry.getSongId());
        updateNotification();
        updatePlaybackState();
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

    // Notifies mMediaSessionManager about the current playback state.
    private void updatePlaybackState() {
        mMediaSessionManager.updatePlaybackState(getCurrentSong(), mPaused, mPlaybackComplete,
                                                 mWaitingForDownload, mCurrentSongLastPositionMs,
                                                 mCurrentSongIndex, mSongs.size());
    }
}
