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
                                   FileCache.Listener {
    private static final String TAG = "NupService";

    // Identifier used for our "currently playing" notification.
    private static final int NOTIFICATION_ID = 0;

    // Don't start playing a song until we have at least this many bytes of it.
    private static final long MIN_BYTES_BEFORE_PLAYING = 128 * 1024;

    // Don't start playing a song until we think we'll finish downloading the whole file at the current rate
    // sooner than this many milliseconds before the song would end (whew).
    private static final long EXTRA_BUFFER_MS = 10 * 1000;

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

    // Listener for the server contents (artists and albums) being loaded.
    interface ContentsLoadListener {
        void onContentsLoad();
    }

    // Plays songs.
    private Player mPlayer;
    private Thread mPlayerThread;

    // Downloads files.
    private FileCache mCache;
    private Thread mCacheThread;

    // Points from int song ID to Song.
    // This is the canonical set of songs that we know about.
    private HashMap mSongIdToSong = new HashMap();

    // Points from int cache entry ID to int song ID.
    private HashMap mCacheEntryIdToSong = new HashMap();

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

    // Have we reported the fact that we've played the current song?
    private boolean mReportedCurrentSong = false;

    private final IBinder mBinder = new LocalBinder();

    // Points from cover filename Strings to previously-fetched Bitmaps.
    // TODO: Limit the growth of this or switch it to a real LRU cache or something.
    // Or hey, use the FileCache class that I've written now.  May be a bit heavyweight, though.
    private HashMap mCoverCache = new HashMap();

    // Songs whose covers are currently being fetched.
    private HashSet mSongCoverFetches = new HashSet();

    // List of artists, sorted by decreasing number of albums.
    private List<String> mArtists = new ArrayList<String>();

    // Points from (lowercased) artist String to List of String album names.
    private HashMap mAlbumMap = new HashMap();

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

    private SongListener mSongListener;
    private ContentsLoadListener mContentsLoadListener;

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

        // TODO: Refetch after server prefs are changed or on failure.
        new GetContentsTask().execute();

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
        } catch (InterruptedException e) {
        }
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
    public List<String> getArtists() { return mArtists; }
    public List<String> getAlbumsByArtist(String artist) { return (List<String>) mAlbumMap.get(artist.toLowerCase()); }

    public void setSongListener(SongListener listener) {
        mSongListener = listener;
    }
    public void setContentsLoadListener(ContentsLoadListener listener) {
        mContentsLoadListener = listener;
    }

    // Unregister an object that might be registered as one or more of our listeners.
    // Typically called when the object is an activity that's getting destroyed so
    // we'll drop our references to it.
    void unregisterListener(Object object) {
        if (mSongListener == object)
            mSongListener = null;
        if (mContentsLoadListener == object)
            mContentsLoadListener = null;
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
        Object obj = mSongIdToSong.get(song.getSongId());
        if (obj != null)
            song = (Song) obj;
        mSongs.add(song);

        if (mSongListener != null)
            mSongListener.onPlaylistChange(mSongs);

        FileCacheEntry entry = mCache.getEntry(song.getRemotePath());
        if (entry != null && mSongListener != null) {
            mCacheEntryIdToSong.put(entry.getId(), song);
            song.setAvailableBytes(new File(entry.getLocalPath()).length());
            song.setTotalBytes(entry.getContentLength());
            mSongListener.onSongFileSizeChange(song);
        }

        if (mCurrentSongIndex == -1) {
            playSongAtIndex(0);
        } else if (mDownloadId == -1) {
            maybeDownloadAnotherSong(mSongs.size() - 1);
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

        mCache.clearPinnedIds();

        // If we've already downloaded the whole file, start playing it.
        FileCacheEntry cacheEntry = mCache.getEntry(getCurrentSong().getRemotePath());
        if (cacheEntry != null && isCacheEntryFullyDownloaded(cacheEntry)) {
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

        if (song.getCoverFilename().isEmpty())
            return true;

        if (mCoverCache.containsKey(song.getCoverFilename())) {
            song.setCoverBitmap((Bitmap) mCoverCache.get(song.getCoverFilename()));
            return true;
        }

        if (!mSongCoverFetches.contains(song)) {
            mSongCoverFetches.add(song);
            new CoverFetcherTask().execute(song);
        }
        return false;
    }

    // Fetches the cover bitmap for a particular song.
    // onCoverFetchDone() is called on completion, even if the fetch failed.
    class CoverFetcherTask extends AsyncTask<Song, Void, Song> {
        @Override
        protected Song doInBackground(Song... songs) {
            Song song = songs[0];
            song.setCoverBitmap(null);
            try {
                DownloadRequest request = new DownloadRequest(NupService.this, DownloadRequest.Method.GET, "/cover/" + song.getCoverFilename(), null);
                DownloadResult result = Download.startDownload(request);
                Bitmap bitmap = BitmapFactory.decodeStream(result.getEntity().getContent());
                if (bitmap == null)
                    Log.e(TAG, "unable to create bitmap from " + song.getCoverFilename());
                song.setCoverBitmap(bitmap);
                result.close();
            } catch (DownloadRequest.PrefException e) {
                Log.e(TAG, "got pref exception while downloading " + song.getCoverFilename() + ": " + e);
            } catch (HttpException e) {
                Log.e(TAG, "got HTTP exception while downloading " + song.getCoverFilename() + ": " + e);
            } catch (IOException e) {
                Log.e(TAG, "got IO exception while downloading " + song.getCoverFilename() + ": " + e);
            }
            return song;
        }

        @Override
        protected void onPostExecute(Song song) {
            NupService.this.onCoverFetchDone(song);
        }
    }

    // Called by CoverFetcherTask on the UI thread.
    public void onCoverFetchDone(Song song) {
        mSongCoverFetches.remove(song);

        if (song.getCoverBitmap() != null) {
            mCoverCache.put(song.getCoverFilename(), song.getCoverBitmap());
            if (mSongListener != null)
                mSongListener.onSongCoverLoad(song);
        }

        // We need to update our notification even if the fetch failed.
        // TODO: Some bitmaps appear to result in "bad array lengths" exceptions in android.os.Parcel.readIntArray().
        if (song == getCurrentSong())
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());
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

    // Fetches artists and albums from the server.
    class GetContentsTask extends AsyncTask<Void, Void, String> {
        private static final String TAG = "GetContentsTask";

        // User-friendly description of error, if any, while getting contents.
        private String[] mError = new String[1];

        @Override
        protected String doInBackground(Void... voidArg) {
            return Download.downloadString(NupService.this, "/contents", null, mError);
        }

        @Override
        protected void onPostExecute(String response) {

            if (response == null || response.isEmpty()) {
                Toast.makeText(NupService.this, "Unable to get autocomplete data: " + mError[0], Toast.LENGTH_LONG).show();
                return;
            }

            try {
                JSONObject jsonArtistMap = (JSONObject) new JSONTokener(response).nextValue();
                mArtists.clear();
                mAlbumMap.clear();
                for (Iterator<String> it = jsonArtistMap.keys(); it.hasNext(); ) {
                    String artist = it.next();
                    mArtists.add(artist);

                    JSONArray jsonAlbums = jsonArtistMap.getJSONArray(artist);
                    List<String> albums = new ArrayList<String>();
                    for (int i = 0; i < jsonAlbums.length(); ++i) {
                        albums.add(jsonAlbums.getString(i));
                    }
                    mAlbumMap.put(artist.toLowerCase(), albums);
                }

                // Sort the artist list by number of albums.
                Collections.sort(mArtists, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        int aNum = ((List<String>) mAlbumMap.get(a.toLowerCase())).size();
                        int bNum = ((List<String>) mAlbumMap.get(b.toLowerCase())).size();
                        return (aNum == bNum) ? 0 : (aNum > bNum) ? -1 : 1;
                    }
                });

                Log.d(TAG, "got listing of " + mArtists.size() + " artist(s) from server");
                if (mContentsLoadListener != null)
                    mContentsLoadListener.onContentsLoad();
            } catch (org.json.JSONException e) {
                Toast.makeText(NupService.this, "Unable to parse autocomplete data: " + e, Toast.LENGTH_LONG).show();
            }
        }
    }

    public long getCacheDataBytes() {
        return mCache.getDataBytes();
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

                Object obj = mCacheEntryIdToSong.get(entry.getId());
                if (obj == null)
                    return;

                Song song = (Song) obj;
                song.setAvailableBytes(entry.getContentLength());
                song.setTotalBytes(entry.getContentLength());

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
    public void onCacheDownloadProgress(final FileCacheEntry entry, final long diskBytes, final long downloadedBytes, final long elapsedMs) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Object obj = mCacheEntryIdToSong.get(entry.getId());
                if (obj == null)
                    return;

                Song song = (Song) obj;
                song.setAvailableBytes(diskBytes);
                song.setTotalBytes(entry.getContentLength());

                if (song == getCurrentSong()) {
                    if (mWaitingForDownload && canPlaySong(entry.getContentLength(), diskBytes, downloadedBytes, elapsedMs, song.getLengthSec())) {
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
                Object obj = mCacheEntryIdToSong.get(entry.getId());
                if (obj == null)
                    return;

                Song song = (Song) obj;
                song.setAvailableBytes(0);
                song.setTotalBytes(0);
                mCacheEntryIdToSong.remove(entry.getId());
                if (mSongListener != null)
                    mSongListener.onSongFileSizeChange(song);
            }
        });
    }

    // Do we have enough of a song downloaded at a fast enough rate that we'll probably
    // finish downloading it before playback reaches the end of the song?
    private boolean canPlaySong(long totalBytes, long diskBytes, long downloadedBytes, long elapsedMs, int songLengthSec) {
        double bytesPerMs = (double) downloadedBytes / elapsedMs;
        long remainingMs = (long) ((totalBytes - diskBytes) / bytesPerMs);
        return (diskBytes >= MIN_BYTES_BEFORE_PLAYING && remainingMs + EXTRA_BUFFER_MS <= songLengthSec * 1000);
    }

    // Is |entry| completely downloaded?
    private boolean isCacheEntryFullyDownloaded(FileCacheEntry entry) {
        if (entry.getContentLength() == 0)
            return false;

        File file = new File(entry.getLocalPath());
        return file.exists() && file.length() == entry.getContentLength();
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
            if (entry != null && isCacheEntryFullyDownloaded(entry)) {
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

    // Choose a filename to use for storing a song locally (used to just use a slightly-mangled version
    // of the remote path, but I think I saw a problem -- didn't investigate much).
    private static String chooseLocalFilenameForSong(Song song) {
        return String.format("%d.mp3", song.getSongId());
    }
}
