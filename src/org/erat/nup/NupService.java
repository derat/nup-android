// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.apache.http.HttpException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class NupService extends Service
                        implements Player.Listener,
                                   FileCache.Listener,
                                   SongDatabase.Listener {
    private static final String TAG = "NupService";

    // Identifier used for our "currently playing" notification.
    private static final int NOTIFICATION_ID = 0;

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

    // Points from song ID to Song.
    // This is the canonical set of songs that we've seen.
    private HashMap<Integer,Song> mSongIdToSong = new HashMap<Integer,Song>();

    // Points from cache entry ID to Song.
    private HashMap<Integer,Song> mCacheEntryIdToSong = new HashMap<Integer,Song>();

    // ID of the cache entry that's currently being downloaded.
    private int mDownloadId = -1;

    // Index into |mSongs| of the song that's currently being downloaded.
    private int mDownloadIndex = -1;

    // Are we currently waiting for a file to be downloaded before we can play it?
    private boolean mWaitingForDownload = false;

    private NotificationManager mNotificationManager;

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

    private SongListener mSongListener = null;
    private HashSet<SongDatabaseUpdateListener> mSongDatabaseUpdateListeners = new HashSet<SongDatabaseUpdateListener>();

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");
        CrashLogger.register(new File(getExternalFilesDir(null), CRASH_SUBDIRECTORY));

        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.icon, getString(R.string.startup_notification_message), System.currentTimeMillis());
        notification.flags |= (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR);
        RemoteViews view = new RemoteViews(getPackageName(), R.layout.startup_notification);
        notification.contentView = view;
        notification.contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0);
        mNotificationManager.notify(NOTIFICATION_ID, notification);
        startForeground(NOTIFICATION_ID, notification);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        mSongDb = new SongDatabase(this, this);
        mCoverLoader = new CoverLoader(this);

        mPlayer = new Player(this);
        mPlayerThread = new Thread(mPlayer, "Player");
        mPlayerThread.start();

        mCache = new FileCache(this, this);
        mCacheThread = new Thread(mCache, "FileCache");
        mCacheThread.start();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        mNotificationManager.cancel(NOTIFICATION_ID);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        mPlayer.quit();
        mCache.quit();
        try {
            mPlayerThread.join();
            mCacheThread.join();
        } catch (InterruptedException e) {}
        CrashLogger.unregister();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "received start id " + startId + ": " + intent);
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

    // Create a new persistent notification displaying information about the current song.
    private Notification updateNotification(String artist, String title, String album, Bitmap bitmap) {
        Notification notification = new Notification(R.drawable.icon, artist + " - " + title, System.currentTimeMillis());
        notification.flags |= (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR);

        // TODO: Any way to update an existing remote view?  I couldn't find one. :-(
        RemoteViews view = new RemoteViews(getPackageName(), R.layout.notification);

        if (artist != null && !artist.isEmpty())
            view.setTextViewText(R.id.notification_artist, artist);
        else
            view.setViewVisibility(R.id.notification_artist, View.GONE);

        if (title != null && !title.isEmpty())
            view.setTextViewText(R.id.notification_title, title);
        else
            view.setViewVisibility(R.id.notification_title, View.GONE);

        if (album != null && !album.isEmpty())
            view.setTextViewText(R.id.notification_album, album);
        else
            view.setViewVisibility(R.id.notification_album, View.GONE);

        if (bitmap != null)
            view.setImageViewBitmap(R.id.notification_image, bitmap);
        else
            view.setViewVisibility(R.id.notification_image, View.GONE);

        notification.contentView = view;

        notification.contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0);
        mNotificationManager.notify(NOTIFICATION_ID, notification);
        return notification;
    }

    // Toggle whether we're playing the current song or not.
    public void togglePause() {
        mPlayer.togglePause();
    }

    public void clearPlaylist() {
        mSongs.clear();
        mCurrentSongIndex = -1;
        if (mDownloadId != -1) {
            mCache.abortDownload(mDownloadId);
            mDownloadId = -1;
            mDownloadIndex = -1;
        }

        if (mSongListener != null)
            mSongListener.onPlaylistChange(mSongs);
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
        if (index < 0 || index >= mSongs.size()) {
            Log.e(TAG, "ignoring request to remove song at position " + index +
                  " from " + mSongs.size() + "-length playlist");
            return;
        }

        if (index == mDownloadIndex) {
            mCache.abortDownload(mDownloadId);
            mDownloadId = -1;
            mDownloadIndex = -1;
        }

        if (index == mCurrentSongIndex) {
            stopPlaying();
            if (mCurrentSongIndex < mSongs.size() - 1)
                playSongAtIndex(mCurrentSongIndex + 1);
            else
                mCurrentSongIndex = -1;
        }

        mSongs.remove(index);
        if (index < mCurrentSongIndex)
            mCurrentSongIndex--;
        if (index < mDownloadIndex)
            mDownloadIndex--;
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

        mCache.clearPinnedIds();

        // If we've already downloaded the whole file, start playing it.
        FileCacheEntry cacheEntry = mCache.getEntry(getCurrentSong().getRemotePath());
        if (cacheEntry != null && cacheEntry.isFullyCached()) {
            Log.d(TAG, "file " + getCurrentSong().getRemotePath() + " already downloaded; playing");
            playCacheEntry(cacheEntry);

            // If we're downloading some other song (maybe we were downloading the
            // previously-being-played song), abort it.
            // TODO: This could actually be a future song that we were already
            // downloading and will soon need to start downloading again.
            if (mDownloadId != -1 && mDownloadId != cacheEntry.getId()) {
                mCache.abortDownload(mDownloadId);
                mDownloadId = -1;
                mDownloadIndex = -1;
            }

            maybeDownloadAnotherSong(mCurrentSongIndex + 1);
        } else {
            // Otherwise, start downloading it if we've never tried downloading it before,
            // or if we have but it's not currently being downloaded.
            mPlayer.abort();
            if (cacheEntry == null || mDownloadId != cacheEntry.getId()) {
                if (mDownloadId != -1)
                    mCache.abortDownload(mDownloadId);
                cacheEntry = mCache.downloadFile(song.getRemotePath(), chooseLocalFilenameForSong(song));
                mCacheEntryIdToSong.put(cacheEntry.getId(), song);
                mDownloadId = cacheEntry.getId();
                mDownloadIndex = mCurrentSongIndex;
            }
            mWaitingForDownload = true;
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        mCache.pinId(cacheEntry.getId());

        // Update the notification now if we already have the cover.  We'll update it when the fetch
        // task finishes otherwise.
        if (fetchCoverForSongIfMissing(song))
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());

        if (mSongListener != null)
            mSongListener.onSongChange(song, mCurrentSongIndex);
    }

    // Stop playing the current song, if any.
    public void stopPlaying() {
        mCurrentSongPath = null;
        mPlayer.abort();
    }

    // Returns true if the cover is already loaded or can't be loaded and false if we started a task to fetch it.
    public boolean fetchCoverForSongIfMissing(Song song) {
        if (song.getCoverBitmap() != null)
            return true;

        if (!mSongCoverFetches.contains(song)) {
            mSongCoverFetches.add(song);
            new CoverFetchTask(song).execute();
        }
        return false;
    }

    // Fetches the cover bitmap for a particular song.
    class CoverFetchTask extends AsyncTask<Void, Void, Bitmap> {
        private final Song mSong;

        public CoverFetchTask(Song song) {
            mSong = song;
        }

        @Override
        protected Bitmap doInBackground(Void... args) {
            return mCoverLoader.loadCover(mSong.getArtist(), mSong.getAlbum());
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            storeCoverForSong(mSong, bitmap);
            mSongCoverFetches.remove(mSong);

            if (mSong.getCoverBitmap() != null && mSongListener != null)
                mSongListener.onSongCoverLoad(mSong);

            // We need to update our notification even if the fetch failed.
            // TODO: Some bitmaps appear to result in "bad array lengths" exceptions in android.os.Parcel.readIntArray().
            if (mSong == getCurrentSong())
                updateNotification(mSong.getArtist(), mSong.getTitle(), mSong.getAlbum(), mSong.getCoverBitmap());
        }
    }

    // Reports that we've played a song.
    class ReportPlayedTask extends AsyncTask<Void, Void, Void> {
        private final Song mSong;
        private final Date mStartDate;
        private String mError;

        public ReportPlayedTask(Song song, Date startDate) {
            mSong = song;
            mStartDate = startDate;
        }

        @Override
        protected Void doInBackground(Void... voidArg) {
            Log.d(TAG, "reporting song " + mSong.getSongId() + " started at " + mStartDate);
            try {
                DownloadRequest request = new DownloadRequest(NupService.this, DownloadRequest.Method.POST, "/report_played", null);
                String body = "songId=" + mSong.getSongId() + "&startTime=" + (mStartDate.getTime() / 1000);
                request.setBody(new ByteArrayInputStream(body.getBytes()), body.length());
                request.setHeader("Content-Type", "application/x-www-form-urlencoded");
                request.setHeader("Content-Length", Long.toString(body.length()));

                DownloadResult result = Download.startDownload(request);
                if (result.getStatusCode() != 200)
                    mError = "Got " + result.getStatusCode() + " response while reporting played song: " + result.getReason();
                result.close();
            } catch (DownloadRequest.PrefException e) {
                mError = "Got preferences error while reporting played song: " + e;
            } catch (HttpException e) {
                mError = "Got HTTP error while reporting played song: " + e;
            } catch (IOException e) {
                mError = "Got IO error while reporting played song: " + e;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void voidArg) {
            if (mError != null) {
                Log.e(TAG, "got error while reporting song: " + mError);
                Toast.makeText(NupService.this, mError, Toast.LENGTH_LONG).show();
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
    public void onPlaybackComplete(String path) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mPlaybackComplete = true;
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
                if (path != mCurrentSongPath)
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
                        new ReportPlayedTask(song, mCurrentSongStartDate).execute();
                        mReportedCurrentSong = true;
                    }
                }
                mCurrentSongLastPositionMs = positionMs;
            }
        });
    }

    // Implements Player.Listener.
    @Override
    public void onPauseStateChange(boolean paused) {
        mPaused = paused;
        if (mSongListener != null)
            mSongListener.onPauseStateChange(paused);
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
                Log.d(TAG, "got notification that download " + entry.getId() + " failed: " + reason);
                Toast.makeText(NupService.this, "Download of " + entry.getRemotePath() + " failed: " + reason, Toast.LENGTH_LONG).show();
                if (entry.getId() == mDownloadId) {
                    mDownloadId = -1;
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
                Log.d(TAG, "got notification that download " + entry.getId() + " is done");

                Song song = mCacheEntryIdToSong.get(entry.getId());
                if (song == null)
                    return;

                song.updateBytes(entry);

                if (song == getCurrentSong() && mWaitingForDownload) {
                    mWaitingForDownload = false;
                    playCacheEntry(entry);
                }

                if (mSongListener != null)
                    mSongListener.onSongFileSizeChange(song);

                if (entry.getId() == mDownloadId) {
                    int nextIndex = mDownloadIndex + 1;
                    mDownloadId = -1;
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
                Song song = mCacheEntryIdToSong.get(entry.getId());
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
                Log.d(TAG, "got notification that " + entry.getId() + " has been evicted");
                Song song = mCacheEntryIdToSong.get(entry.getId());
                if (song == null)
                    return;

                song.setAvailableBytes(0);
                song.setTotalBytes(0);
                mCacheEntryIdToSong.remove(entry.getId());
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
            FileCacheEntry entry = mCache.getEntry(song.getRemotePath());
            if (entry != null) {
                mCacheEntryIdToSong.put(entry.getId(), song);
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
        } else if (mDownloadId == -1) {
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
        mCurrentSongPath = entry.getLocalPath();
        mPlayer.playFile(mCurrentSongPath);
        mCurrentSongStartDate = new Date();
    }

    // Try to download the next not-yet-downloaded song in the playlist.
    private void maybeDownloadAnotherSong(int index) {
        if (mDownloadId != -1) {
            Log.e(TAG, "aborting prefetch since download " + mDownloadId + " is still in progress");
            return;
        }

        final int songsToPreload = Integer.valueOf(
            mPrefs.getString(NupPreferences.SONGS_TO_PRELOAD,
                             NupPreferences.SONGS_TO_PRELOAD_DEFAULT));

        for (; index < mSongs.size() && index - mCurrentSongIndex <= songsToPreload; index++) {
            Song song = mSongs.get(index);
            FileCacheEntry entry = mCache.getEntry(song.getRemotePath());
            if (entry != null && entry.isFullyCached()) {
                // We already have this one.  Pin it to make sure that it
                // doesn't get evicted by a later song.
                mCache.pinId(entry.getId());
                continue;
            }

            entry = mCache.downloadFile(song.getRemotePath(), chooseLocalFilenameForSong(song));
            mCacheEntryIdToSong.put(entry.getId(), song);
            mDownloadId = entry.getId();
            mDownloadIndex = index;
            mCache.pinId(entry.getId());
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
