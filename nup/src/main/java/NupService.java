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
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class NupService extends Service
        implements Player.Listener, FileCache.Listener, SongDatabase.Listener {
    private static final String TAG = "NupService";

    // Identifier used for our "currently playing" notification.
    // Note: Using 0 makes the service not run in the foreground and the notification not show up.
    // Yay, that was fun to figure out.
    private static final int NOTIFICATION_ID = 1;

    // Don't start playing a song until we have at least this many bytes of it.
    private static final long MIN_BYTES_BEFORE_PLAYING = 128 * 1024;

    // Don't start playing a song until we think we'll finish downloading the whole file at the
    // current rate sooner than this many milliseconds before the song would end (whew).
    private static final long EXTRA_BUFFER_MS = 10 * 1000;

    // Maximum number of cover bitmaps to keep in memory at once.
    private static final int MAX_LOADED_COVERS = 3;

    // If we receive a playback update with a position more than this many milliseconds beyond the
    // last one we received, we assume that something has gone wrong and ignore it.
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

        // Invoked when the on-disk size of a song changes (because we're downloading it or it got
        // evicted from the cache).
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
    private TaskRunner taskRunner;

    // Authenticates with Google Cloud Storage.
    private Authenticator authenticator;

    // Downloads files.
    private Downloader downloader;

    // Plays songs.
    private Player player;
    private Thread playerThread;

    // Caches songs.
    private FileCache cache;
    private Thread cacheThread;

    // Stores a local listing of all of the songs on the server.
    private SongDatabase songDb;

    // Loads songs' cover art from local disk or the network.
    private CoverLoader coverLoader;

    // Reports song playback to the music server.
    private PlaybackReporter playbackReporter;

    // Publishes song metadata and handles remote commands.
    private MediaSessionManager mediaSessionManager;

    // Token identifying the session managed by |mMediaSessionManager|.
    private MediaSession.Token mediaSessionToken;

    // Updates the notification.
    private NotificationManager notificationManager;
    private NotificationCreator notificationCreator;

    private NetworkHelper networkHelper;

    // Points from song ID to Song.
    // This is the canonical set of songs that we've seen.
    private HashMap<Long, Song> songIdToSong = new HashMap<Long, Song>();

    // ID of the song that's currently being downloaded.
    private long downloadSongId = -1;

    // Index into |mSongs| of the song that's currently being downloaded.
    private int downloadIndex = -1;

    // Are we currently waiting for a file to be downloaded before we can play it?
    private boolean waitingForDownload = false;

    // Have we temporarily been told to download all queued songs?
    private boolean shouldDownloadAll = false;

    // Current playlist.
    private List<Song> songs = new ArrayList<Song>();

    // Index of the song in |mSongs| that's being played.
    private int currentSongIndex = -1;

    // Time at which we started playing the current song.
    private Date currentSongStartDate;

    // Last playback position we were notified about for the current song.
    private int currentSongLastPositionMs = 0;

    // Total time during which we've played the current song, in milliseconds.
    private long currentSongPlayedMs = 0;

    // Local path of the song that's being played.
    private String currentSongPath;

    // Is playback currently paused?
    private boolean paused = false;

    // Are we done playing the current song?
    private boolean playbackComplete = false;

    // Have we reported the fact that we've played the current song?
    private boolean reportedCurrentSong = false;

    private final IBinder binder = new LocalBinder();

    // Songs whose covers are currently being fetched.
    private HashSet<Song> songCoverFetches = new HashSet<Song>();

    // Songs whose covers we're currently keeping in memory.
    private List<Song> songsWithCovers = new ArrayList<Song>();

    // Last time at which the user was foregrounded or backgrounded.
    private Date lastUserSwitchTime;

    // Used to run tasks on our thread.
    private Handler handler = new Handler();

    private AudioManager audioManager;
    private AudioAttributes audioAttrs;
    private TelephonyManager telephonyManager;

    private SharedPreferences prefs;

    // Pause when phone calls arrive.
    private PhoneStateListener phoneStateListener =
            new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    // I don't want to know who's calling.  Why isn't there a more-limited
                    // permission?
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        player.pause();
                        Toast.makeText(
                                        NupService.this,
                                        "Paused for incoming call.",
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                }
            };

    private BroadcastReceiver broadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                        Log.d(TAG, "Audio becoming noisy");

                        // Switching users apparently triggers an AUDIO_BECOMING_NOISY broadcast
                        // intent (which usually indicates that headphones have been disconnected).
                        // AudioManager.isWiredHeadsetOn() returns true when the notification is
                        // sent due to headphones being unplugged (why?), so that doesn't seem to
                        // help for ignoring this. Instead, ignore these intents for a brief period
                        // after user-switch broadcast intents.
                        final boolean userSwitchedRecently =
                                (lastUserSwitchTime == null)
                                        ? true
                                        : (new Date()).getTime() - lastUserSwitchTime.getTime()
                                                <= IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS;

                        if (currentSongIndex >= 0 && !paused && !userSwitchedRecently) {
                            player.pause();
                            Toast.makeText(
                                            NupService.this,
                                            "Paused since unplugged.",
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    } else if (Intent.ACTION_USER_BACKGROUND.equals(intent.getAction())) {
                        Log.d(TAG, "User is backgrounded");
                        lastUserSwitchTime = new Date();
                    } else if (Intent.ACTION_USER_FOREGROUND.equals(intent.getAction())) {
                        Log.d(TAG, "User is foregrounded");
                        lastUserSwitchTime = new Date();
                    }
                }
            };

    private AudioManager.OnAudioFocusChangeListener audioFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            Log.d(TAG, "Gained audio focus");
                            player.setLowVolume(false);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            Log.d(TAG, "Lost audio focus");
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            Log.d(TAG, "Transiently lost audio focus");
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            Log.d(TAG, "Transiently lost audio focus (but can duck)");
                            player.setLowVolume(true);
                            break;
                        default:
                            Log.d(TAG, "Unhandled audio focus change " + focusChange);
                            break;
                    }
                }
            };

    private OnSharedPreferenceChangeListener prefsListener =
            new OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if (key.equals(NupPreferences.PRE_AMP_GAIN)) {
                        double gain =
                                Double.parseDouble(
                                        prefs.getString(key, NupPreferences.PRE_AMP_GAIN_DEFAULT));
                        player.setPreAmpGain(gain);
                    }
                }
            };

    private SongListener songListener = null;
    private HashSet<SongDatabaseUpdateListener> songDatabaseUpdateListeners =
            new HashSet<SongDatabaseUpdateListener>();

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");

        // It'd be nice to set this up before we do anything else, but getExternalFilesDir() blocks.
        // :-/
        new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... args) {
                return new File(getExternalFilesDir(null), CRASH_SUBDIRECTORY);
            }

            @Override
            protected void onPostExecute(File dir) {
                CrashLogger.register(dir);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        taskRunner = new TaskRunner();
        networkHelper = new NetworkHelper(this);
        authenticator = new Authenticator(this);
        if (networkHelper.isNetworkAvailable()) {
            authenticateInBackground();
        }

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioAttrs =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
        int result =
                audioManager.requestAudioFocus(
                        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                .setOnAudioFocusChangeListener(audioFocusListener)
                                .setAudioAttributes(audioAttrs)
                                .build());
        Log.d(TAG, "requested audio focus; got " + result);

        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        filter.addAction(Intent.ACTION_USER_FOREGROUND);
        registerReceiver(broadcastReceiver, filter);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        downloader = new Downloader(authenticator, prefs);

        double gain =
                Double.parseDouble(
                        prefs.getString(
                                NupPreferences.PRE_AMP_GAIN, NupPreferences.PRE_AMP_GAIN_DEFAULT));
        player = new Player(this, this, handler, audioAttrs, gain);
        playerThread = new Thread(player, "Player");
        playerThread.setPriority(Thread.MAX_PRIORITY);
        playerThread.start();

        cache = new FileCache(this, this, downloader, networkHelper);
        cacheThread = new Thread(cache, "FileCache");
        cacheThread.start();

        songDb = new SongDatabase(this, this, cache, downloader, networkHelper);
        coverLoader =
                new CoverLoader(
                        this.getExternalCacheDir(),
                        downloader,
                        taskRunner,
                        new BitmapDecoder(),
                        networkHelper);
        playbackReporter = new PlaybackReporter(songDb, downloader, taskRunner, networkHelper);

        mediaSessionManager =
                new MediaSessionManager(
                        this,
                        new MediaSession.Callback() {
                            @Override
                            public void onPause() {
                                player.pause();
                            }

                            @Override
                            public void onPlay() {
                                player.unpause();
                            }

                            @Override
                            public void onSkipToNext() {
                                playSongAtIndex(currentSongIndex + 1);
                            }

                            @Override
                            public void onSkipToPrevious() {
                                playSongAtIndex(currentSongIndex - 1);
                            }

                            @Override
                            public void onStop() {
                                player.pause();
                            }
                        });
        mediaSessionToken = mediaSessionManager.getToken();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationCreator =
                new NotificationCreator(
                        this,
                        notificationManager,
                        mediaSessionToken,
                        PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0),
                        PendingIntent.getService(
                                this,
                                0,
                                new Intent(ACTION_TOGGLE_PAUSE, Uri.EMPTY, this, NupService.class),
                                0),
                        PendingIntent.getService(
                                this,
                                0,
                                new Intent(ACTION_PREV_TRACK, Uri.EMPTY, this, NupService.class),
                                0),
                        PendingIntent.getService(
                                this,
                                0,
                                new Intent(ACTION_NEXT_TRACK, Uri.EMPTY, this, NupService.class),
                                0));
        Notification notification =
                notificationCreator.createNotification(
                        false,
                        getCurrentSong(),
                        paused,
                        playbackComplete,
                        currentSongIndex,
                        songs.size());
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        audioManager.abandonAudioFocus(audioFocusListener);
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        unregisterReceiver(broadcastReceiver);

        mediaSessionManager.cleanUp();
        songDb.quit();
        player.quit();
        cache.quit();
        try {
            playerThread.join();
        } catch (InterruptedException e) {
        }
        try {
            cacheThread.join();
        } catch (InterruptedException e) {
        }

        CrashLogger.unregister();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "received start id " + startId + ": " + intent);
        if (intent != null && intent.getAction() != null) {
            if (ACTION_TOGGLE_PAUSE.equals(intent.getAction())) {
                togglePause();
            } else if (ACTION_NEXT_TRACK.equals(intent.getAction())) {
                playSongAtIndex(currentSongIndex + 1);
            } else if (ACTION_PREV_TRACK.equals(intent.getAction())) {
                playSongAtIndex(currentSongIndex - 1);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean getPaused() {
        return paused;
    }

    public List<Song> getSongs() {
        return songs;
    }

    public int getCurrentSongIndex() {
        return currentSongIndex;
    }

    public Song getCurrentSong() {
        return (currentSongIndex >= 0 && currentSongIndex < songs.size())
                ? songs.get(currentSongIndex)
                : null;
    }

    public Song getNextSong() {
        return (currentSongIndex >= 0 && currentSongIndex + 1 < songs.size())
                ? songs.get(currentSongIndex + 1)
                : null;
    }

    public int getCurrentSongLastPositionMs() {
        return currentSongLastPositionMs;
    }

    public SongDatabase getSongDb() {
        return songDb;
    }

    public void setSongListener(SongListener listener) {
        songListener = listener;
    }

    public void addSongDatabaseUpdateListener(SongDatabaseUpdateListener listener) {
        songDatabaseUpdateListeners.add(listener);
    }

    public void removeSongDatabaseUpdateListener(SongDatabaseUpdateListener listener) {
        songDatabaseUpdateListeners.remove(listener);
    }

    public boolean getShouldDownloadAll() {
        return shouldDownloadAll;
    }

    public void setShouldDownloadAll(boolean downloadAll) {
        if (downloadAll == shouldDownloadAll) return;
        shouldDownloadAll = downloadAll;
        if (!songs.isEmpty() && downloadAll && downloadSongId == -1)
            maybeDownloadAnotherSong(currentSongIndex >= 0 ? currentSongIndex : 0);
    }

    // Unregister an object that might be registered as one or more of our listeners.
    // Typically called when the object is an activity that's getting destroyed so
    // we'll drop our references to it.
    void unregisterListener(Object object) {
        if (songListener == object) songListener = null;
    }

    public class LocalBinder extends Binder {
        NupService getService() {
            return NupService.this;
        }
    }

    /** Updates the currently-displayed notification if needed. */
    private void updateNotification() {
        Notification notification =
                notificationCreator.createNotification(
                        true,
                        getCurrentSong(),
                        paused,
                        playbackComplete,
                        currentSongIndex,
                        songs.size());
        if (notification != null) notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /** Toggle whether we're playing the current song or not. */
    public void togglePause() {
        player.togglePause();
    }

    public void pause() {
        player.pause();
    }

    public void clearPlaylist() {
        removeRangeFromPlaylist(0, songs.size() - 1);
    }

    public void appendSongToPlaylist(Song song) {
        appendSongsToPlaylist(new ArrayList<Song>(Arrays.asList(song)));
    }

    public void appendSongsToPlaylist(List<Song> newSongs) {
        insertSongs(newSongs, songs.size());
    }

    public void addSongToPlaylist(Song song, boolean play) {
        addSongsToPlaylist(new ArrayList<Song>(Arrays.asList(song)), play);
    }

    public void addSongsToPlaylist(List<Song> newSongs, boolean forcePlay) {
        int index = currentSongIndex;
        boolean alreadyPlayed = insertSongs(newSongs, index < 0 ? 0 : index + 1);
        if (forcePlay && !alreadyPlayed) {
            playSongAtIndex(index < 0 ? 0 : index + 1);
        }
    }

    public void removeFromPlaylist(int index) {
        removeRangeFromPlaylist(index, index);
    }

    // Remove a range of songs from the playlist.
    public void removeRangeFromPlaylist(int firstIndex, int lastIndex) {
        if (firstIndex < 0) firstIndex = 0;
        if (lastIndex >= songs.size()) lastIndex = songs.size() - 1;

        boolean removedPlaying = false;
        for (int numToRemove = lastIndex - firstIndex + 1; numToRemove > 0; --numToRemove) {
            songs.remove(firstIndex);

            if (currentSongIndex == firstIndex) {
                removedPlaying = true;
                stopPlaying();
            } else if (currentSongIndex > firstIndex) {
                currentSongIndex--;
            }

            if (downloadIndex == firstIndex) {
                cache.abortDownload(downloadSongId);
                downloadSongId = -1;
                downloadIndex = -1;
            } else if (downloadIndex > firstIndex) {
                downloadIndex--;
            }
        }

        if (removedPlaying) {
            if (!songs.isEmpty() && currentSongIndex < songs.size()) {
                playSongAtIndex(currentSongIndex);
            } else {
                currentSongIndex = -1;
                mediaSessionManager.updateSong(null);
                updatePlaybackState();
            }
        }

        // Maybe the e.g. now-next-to-be-played song isn't downloaded yet.
        if (downloadSongId == -1 && !songs.isEmpty() && currentSongIndex < songs.size() - 1) {
            maybeDownloadAnotherSong(currentSongIndex + 1);
        }
        if (songListener != null) songListener.onPlaylistChange(songs);
        updateNotification();
        mediaSessionManager.updatePlaylist(songs);
    }

    // Play the song at a particular position in the playlist.
    public void playSongAtIndex(int index) {
        if (index < 0 || index >= songs.size()) return;

        currentSongIndex = index;
        Song song = getCurrentSong();

        currentSongStartDate = null;
        currentSongLastPositionMs = 0;
        currentSongPlayedMs = 0;
        reportedCurrentSong = false;
        playbackComplete = false;

        cache.clearPinnedSongIds();

        // If we've already downloaded the whole file, start playing it.
        FileCacheEntry cacheEntry = cache.getEntry(getCurrentSong().id);
        if (cacheEntry != null && cacheEntry.isFullyCached()) {
            Log.d(TAG, "file " + getCurrentSong().url.toString() + " already downloaded; playing");
            playCacheEntry(cacheEntry);

            // If we're downloading some other song (maybe we were downloading the
            // previously-being-played song), abort it.
            // TODO: This could actually be a future song that we were already
            // downloading and will soon need to start downloading again.
            if (downloadSongId != -1 && downloadSongId != cacheEntry.songId) {
                cache.abortDownload(downloadSongId);
                downloadSongId = -1;
                downloadIndex = -1;
            }

            maybeDownloadAnotherSong(currentSongIndex + 1);
        } else {
            // Otherwise, start downloading it if we've never tried downloading it before,
            // or if we have but it's not currently being downloaded.
            player.abortPlayback();
            if (cacheEntry == null || downloadSongId != cacheEntry.songId) {
                if (downloadSongId != -1) cache.abortDownload(downloadSongId);
                cacheEntry = cache.downloadSong(song);
                downloadSongId = song.id;
                downloadIndex = currentSongIndex;
            }
            waitingForDownload = true;
            updatePlaybackState();
        }

        // Enqueue the next song if we already have it.
        Song nextSong = getNextSong();
        if (nextSong != null) {
            FileCacheEntry nextEntry = cache.getEntry(nextSong.id);
            if (nextEntry != null && nextEntry.isFullyCached()) {
                player.queueFile(
                        nextEntry.getLocalFile().getPath(),
                        nextEntry.getTotalBytes(),
                        nextSong.albumGain,
                        nextSong.peakAmp);
            }
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        cache.pinSongId(song.id);

        fetchCoverForSongIfMissing(song);
        updateNotification();
        mediaSessionManager.updateSong(song);
        updatePlaybackState();
        if (songListener != null) {
            songListener.onSongChange(song, currentSongIndex);
        }
    }

    // Stop playing the current song, if any.
    public void stopPlaying() {
        currentSongPath = null;
        player.abortPlayback();
    }

    // Start fetching the cover for a song if it's not loaded already.
    public void fetchCoverForSongIfMissing(Song song) {
        if (song.getCoverBitmap() != null || song.coverUrl == null) return;

        if (!songCoverFetches.contains(song)) {
            songCoverFetches.add(song);
            new CoverFetchTask(song).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    // Fetches the cover bitmap for a particular song.
    class CoverFetchTask extends AsyncTask<Void, Void, Bitmap> {
        private final Song song;

        public CoverFetchTask(Song song) {
            this.song = song;
        }

        @Override
        protected Bitmap doInBackground(Void... args) {
            return coverLoader.loadCover(song.coverUrl);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            storeCoverForSong(song, bitmap);
            songCoverFetches.remove(song);
            if (song.getCoverBitmap() != null) {
                if (songListener != null) songListener.onSongCoverLoad(song);
                if (song == getCurrentSong()) {
                    updateNotification();
                    mediaSessionManager.updateSong(song);
                }
            }
        }
    }

    public long getTotalCachedBytes() {
        return cache.getTotalCachedBytes();
    }

    public void clearCache() {
        cache.clear();
    }

    // Implements Player.Listener.
    @Override
    public void onPlaybackComplete() {
        Util.assertOnMainThread();
        playbackComplete = true;
        updateNotification();
        updatePlaybackState();
        if (currentSongIndex < songs.size() - 1) {
            playSongAtIndex(currentSongIndex + 1);
        }
    }

    // Implements Player.Listener.
    @Override
    public void onPlaybackPositionChange(
            final String path, final int positionMs, final int durationMs) {
        Util.assertOnMainThread();
        if (!path.equals(currentSongPath)) return;

        Song song = getCurrentSong();
        if (songListener != null) songListener.onSongPositionChange(song, positionMs, durationMs);

        int elapsed = positionMs - currentSongLastPositionMs;
        if (elapsed > 0 && elapsed <= MAX_POSITION_REPORT_MS) {
            currentSongPlayedMs += elapsed;
            if (!reportedCurrentSong
                    && (currentSongPlayedMs >= Math.max(durationMs, song.lengthSec * 1000) / 2
                            || currentSongPlayedMs >= REPORT_PLAYBACK_THRESHOLD_MS)) {
                playbackReporter.report(song.id, currentSongStartDate);
                reportedCurrentSong = true;
            }
        }

        currentSongLastPositionMs = positionMs;
        updatePlaybackState();
    }

    // Implements Player.Listener.
    @Override
    public void onPauseStateChange(final boolean paused) {
        Util.assertOnMainThread();
        this.paused = paused;
        updateNotification();
        if (songListener != null) songListener.onPauseStateChange(this.paused);
        updatePlaybackState();
    }

    // Implements Player.Listener.
    @Override
    public void onPlaybackError(final String description) {
        Util.assertOnMainThread();
        Toast.makeText(NupService.this, description, Toast.LENGTH_LONG).show();
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadError(final FileCacheEntry entry, final String reason) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(
                                        NupService.this,
                                        "Got retryable error: " + reason,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadFail(final FileCacheEntry entry, final String reason) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.d(
                                TAG,
                                "got notification that download of song "
                                        + entry.songId
                                        + " failed: "
                                        + reason);
                        Toast.makeText(
                                        NupService.this,
                                        "Download of "
                                                + songIdToSong.get(entry.songId).url.toString()
                                                + " failed: "
                                                + reason,
                                        Toast.LENGTH_LONG)
                                .show();
                        if (entry.songId == downloadSongId) {
                            downloadSongId = -1;
                            downloadIndex = -1;
                            waitingForDownload = false;
                        }
                    }
                });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadComplete(final FileCacheEntry entry) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.d(
                                TAG,
                                "got notification that download of song "
                                        + entry.songId
                                        + " is done");

                        Song song = songIdToSong.get(entry.songId);
                        if (song == null) return;

                        song.updateBytes(entry);
                        songDb.handleSongCached(song.id);

                        if (song == getCurrentSong() && waitingForDownload) {
                            waitingForDownload = false;
                            playCacheEntry(entry);
                        } else if (song == getNextSong()) {
                            player.queueFile(
                                    entry.getLocalFile().getPath(),
                                    entry.getTotalBytes(),
                                    song.albumGain,
                                    song.peakAmp);
                        }

                        if (songListener != null) songListener.onSongFileSizeChange(song);

                        if (entry.songId == downloadSongId) {
                            int nextIndex = downloadIndex + 1;
                            downloadSongId = -1;
                            downloadIndex = -1;
                            maybeDownloadAnotherSong(nextIndex);
                        }
                    }
                });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheDownloadProgress(
            final FileCacheEntry entry, final long downloadedBytes, final long elapsedMs) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Song song = songIdToSong.get(entry.songId);
                        if (song == null) return;

                        song.updateBytes(entry);

                        if (song == getCurrentSong()) {
                            if (waitingForDownload
                                    && canPlaySong(
                                            entry, downloadedBytes, elapsedMs, song.lengthSec)) {
                                waitingForDownload = false;
                                playCacheEntry(entry);
                            }
                        }

                        if (songListener != null) songListener.onSongFileSizeChange(song);
                    }
                });
    }

    // Implements FileCache.Listener.
    @Override
    public void onCacheEviction(final FileCacheEntry entry) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.d(
                                TAG,
                                "got notification that song " + entry.songId + " has been evicted");
                        songDb.handleSongEvicted(entry.songId);

                        Song song = songIdToSong.get(entry.songId);
                        if (song == null) return;

                        song.setAvailableBytes(0);
                        song.setTotalBytes(0);
                        if (songListener != null) songListener.onSongFileSizeChange(song);
                    }
                });
    }

    // Implements SongDatabase.Listener.
    @Override
    public void onAggregateDataUpdate() {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "got notice that aggregate data has been updated");
                        for (SongDatabaseUpdateListener listener : songDatabaseUpdateListeners)
                            listener.onSongDatabaseUpdate();
                    }
                });
    }

    /** Starts authenticating with Google Cloud Storage in the background. */
    public void authenticateInBackground() {
        authenticator.authenticateInBackground();
    }

    // Insert a list of songs into the playlist at a particular position.
    // Plays the first one, if no song is already playing or if we were previously at the end of the
    // playlist and we've appended to it. Returns true if we started playing.
    private boolean insertSongs(List<Song> insSongs, int index) {
        if (index < 0 || index > songs.size()) {
            Log.e(
                    TAG,
                    "ignoring request to insert " + insSongs.size() + " song(s) at index " + index);
            return false;
        }

        // Songs that we didn't already have. We track these so we can check
        // the cache for them later.
        ArrayList<Song> newSongs = new ArrayList<Song>();

        // Use our own version of each song if we have it already.
        ArrayList<Song> tmpSongs = new ArrayList<Song>();
        for (Song song : insSongs) {
            Song ourSong = songIdToSong.get(song.id);
            if (ourSong != null) {
                tmpSongs.add(ourSong);
            } else {
                tmpSongs.add(song);
                newSongs.add(song);
            }
        }
        insSongs = tmpSongs;

        songs.addAll(index, insSongs);
        if (currentSongIndex >= 0 && index <= currentSongIndex) {
            currentSongIndex += insSongs.size();
        }
        if (downloadIndex >= 0 && index <= downloadIndex) {
            downloadIndex += insSongs.size();
        }

        if (songListener != null) songListener.onPlaylistChange(songs);
        mediaSessionManager.updatePlaylist(songs);

        for (Song song : newSongs) {
            songIdToSong.put(song.id, song);
            FileCacheEntry entry = cache.getEntry(song.id);
            if (entry != null) {
                song.updateBytes(entry);
                if (songListener != null) songListener.onSongFileSizeChange(song);
            }
        }

        boolean played = false;
        if (currentSongIndex == -1) {
            // If we didn't have any songs, then start playing the first one we added.
            playSongAtIndex(0);
            played = true;
        } else if (currentSongIndex < songs.size() - 1 && playbackComplete) {
            // If we were previously done playing (because we reached the end of the playlist),
            // then start playing the first song we added.
            playSongAtIndex(currentSongIndex + 1);
            played = true;
        } else if (downloadSongId == -1) {
            // Otherwise, consider downloading the new songs if we're not already downloading
            // something.
            maybeDownloadAnotherSong(index);
        }
        updateNotification();
        return played;
    }

    // Do we have enough of a song downloaded at a fast enough rate that we'll probably
    // finish downloading it before playback reaches the end of the song?
    private boolean canPlaySong(
            FileCacheEntry entry, long downloadedBytes, long elapsedMs, int songLengthSec) {
        if (entry.isFullyCached()) return true;
        double bytesPerMs = (double) downloadedBytes / elapsedMs;
        long remainingMs = (long) ((entry.getTotalBytes() - entry.getCachedBytes()) / bytesPerMs);
        return entry.getCachedBytes() >= MIN_BYTES_BEFORE_PLAYING
                && remainingMs + EXTRA_BUFFER_MS <= songLengthSec * 1000;
    }

    // Play the local file where a cache entry is stored.
    private void playCacheEntry(FileCacheEntry entry) {
        Song song = getCurrentSong();
        if (song.id != entry.songId) {
            Log.e(
                    TAG,
                    "not playing: cache entry "
                            + entry.songId
                            + " doesn't match current song "
                            + song.id);
            return;
        }
        currentSongPath = entry.getLocalFile().getPath();
        player.playFile(currentSongPath, entry.getTotalBytes(), song.albumGain, song.peakAmp);
        currentSongStartDate = new Date();
        cache.updateLastAccessTime(entry.songId);
        updateNotification();
        updatePlaybackState();
    }

    // Try to download the next not-yet-downloaded song in the playlist.
    private void maybeDownloadAnotherSong(int index) {
        if (downloadSongId != -1) {
            Log.e(
                    TAG,
                    "aborting prefetch since download of song "
                            + downloadSongId
                            + " is still in progress");
            return;
        }

        final int songsToPreload =
                Integer.valueOf(
                        prefs.getString(
                                NupPreferences.SONGS_TO_PRELOAD,
                                NupPreferences.SONGS_TO_PRELOAD_DEFAULT));

        for (;
                index < songs.size()
                        && (shouldDownloadAll || index - currentSongIndex <= songsToPreload);
                index++) {
            Song song = songs.get(index);
            FileCacheEntry entry = cache.getEntry(song.id);
            if (entry != null && entry.isFullyCached()) {
                // We already have this one.  Pin it to make sure that it
                // doesn't get evicted by a later song.
                cache.pinSongId(song.id);
                continue;
            }

            entry = cache.downloadSong(song);
            downloadSongId = song.id;
            downloadIndex = index;
            cache.pinSongId(song.id);
            fetchCoverForSongIfMissing(song);
            return;
        }
    }

    // Set |song|'s cover bitmap to |bitmap|.
    // Also makes sure that we don't have more than |MAX_LOADED_COVERS| bitmaps in-memory.
    private void storeCoverForSong(Song song, Bitmap bitmap) {
        song.setCoverBitmap(bitmap);
        final int existingIndex = songsWithCovers.indexOf(song);

        // If we didn't get a bitmap, bail out early.
        if (bitmap == null) {
            if (existingIndex >= 0) songsWithCovers.remove(existingIndex);
            return;
        }

        // If the song is already in the list, remove it so we can add it to the end.
        if (existingIndex >= 0) {
            // It's already at the end of the list; we don't need to move it.
            if (existingIndex == songsWithCovers.size() - 1) return;
            songsWithCovers.remove(existingIndex);
        }

        // If we're full, drop the cover from the first song on the list.
        if (songsWithCovers.size() == MAX_LOADED_COVERS) {
            songsWithCovers.get(0).setCoverBitmap(null);
            songsWithCovers.remove(0);
        }

        songsWithCovers.add(song);
    }

    // Choose a filename to use for storing a song locally (used to just use a slightly-mangled
    // version of the remote path, but I think I saw a problem -- didn't investigate much).
    private static String chooseLocalFilenameForSong(Song song) {
        return String.format("%d.mp3", song.id);
    }

    // Notifies |mediaSessionManager| about the current playback state.
    private void updatePlaybackState() {
        mediaSessionManager.updatePlaybackState(
                getCurrentSong(),
                paused,
                playbackComplete,
                waitingForDownload,
                currentSongLastPositionMs,
                currentSongIndex,
                songs.size());
    }
}
