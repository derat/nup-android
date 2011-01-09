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
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.apache.http.HttpException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Runnable;
import java.lang.Thread;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class NupService extends Service
                        implements Player.SongCompleteListener,
                                   Player.PositionChangeListener,
                                   FileCache.DownloadListener {
    private static final String TAG = "NupService";

    // Identifier used for our "currently playing" notification.
    private static final int NOTIFICATION_ID = 0;

    // Don't start playing a song until we have at least this many bytes of it.
    private static final long MIN_BYTES_BEFORE_PLAYING = 64 * 1024;

    // Don't start playing a song until we think we'll finish downloading the whole file at the current rate
    // sooner than this many milliseconds before the song would end (whew).
    private static final long EXTRA_BUFFER_MS = 10 * 1000;

    // If we receive a playback position report with a timestamp more than this many milliseconds beyond the last
    // on we received, we assume that something has gone wrong and ignore it.
    private static final long MAX_POSITION_REPORT_MS = 1000;

    // Report a song if we've played it for this many milliseconds.
    private static final long REPORT_PLAYBACK_THRESHOLD_MS = 240 * 1000;

    // Listener for changes to a new song.
    interface SongChangeListener {
        void onSongChange(Song song, int index);
    }

    // Listener for the cover bitmap being successfully loaded for a song.
    interface CoverLoadListener {
        void onCoverLoad(Song song);
    }

    // Listener for changes to the current playlist.
    interface PlaylistChangeListener {
        void onPlaylistChange(List<Song> songs);
    }

    // Plays songs.
    private Player mPlayer;

    // Thread where mPlayerThread runs.
    private Thread mPlayerThread;

    private FileCache mFileCache;
    private Thread mFileCacheThread;

    private int mCurrentFileCacheHandle = -1;
    private boolean mWaitingForFileCache = false;

    private NotificationManager mNotificationManager;

    // Current playlist.
    private List<Song> mSongs = new ArrayList<Song>();

    // Index of the song in mSongs that's being played.
    private int mCurrentSongIndex = -1;

    // Time at which we started playing the current song.
    private Date mCurrentSongStartDate;

    // Last playback position we were notified about for the current song.
    private long mCurrentSongLastPositionMs = 0;

    // Total time during which we've played the current song, in milliseconds.
    private long mCurrentSongPlayedMs = 0;

    // Have we reported the fact that we've played the current song?
    private boolean mReportedCurrentSong = false;

    private final IBinder mBinder = new LocalBinder();

    // Points from cover filename Strings to previously-fetched Bitmaps.
    // TODO: Limit the growth of this or switch it to a real LRU cache or something.
    private HashMap mCoverCache = new HashMap();

    // Songs whose covers are currently being fetched.
    private HashSet mSongCoverFetches = new HashSet();

    // Used to run tasks on our thread.
    private Handler mHandler = new Handler();

    private SongChangeListener mSongChangeListener;
    private CoverLoadListener mCoverLoadListener;
    private PlaylistChangeListener mPlaylistChangeListener;
    private Player.PositionChangeListener mPositionChangeListener;

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = updateNotification("nup", getString(R.string.initial_notification), null, (Bitmap) null);
        startForeground(NOTIFICATION_ID, notification);

        mPlayer = new Player();
        mPlayerThread = new Thread(mPlayer, "Player");
        mPlayerThread.start();
        mPlayer.setSongCompleteListener(this);
        mPlayer.setPositionChangeListener(this);

        mFileCache = new FileCache(this);
        mFileCacheThread = new Thread(mFileCache, "FileCache");
        mFileCacheThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        mNotificationManager.cancel(NOTIFICATION_ID);
        mPlayer.quit();
        mFileCache.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public final List<Song> getSongs() { return mSongs; }
    public final int getCurrentSongIndex() { return mCurrentSongIndex; }

    public final Song getCurrentSong() {
        return (mCurrentSongIndex >= 0 && mCurrentSongIndex < mSongs.size()) ? mSongs.get(mCurrentSongIndex) : null;
    }

    public void setSongChangeListener(SongChangeListener listener) {
        mSongChangeListener = listener;
    }
    public void setCoverLoadListener(CoverLoadListener listener) {
        mCoverLoadListener = listener;
    }
    public void setPlaylistChangeListener(PlaylistChangeListener listener) {
        mPlaylistChangeListener = listener;
    }
    public void setPositionChangeListener(Player.PositionChangeListener listener) {
        mPositionChangeListener = listener;
    }
    void setPauseToggleListener(Player.PauseToggleListener listener) {
        mPlayer.setPauseToggleListener(listener);
    }
    void setPlaybackErrorListener(Player.PlaybackErrorListener listener) {
        mPlayer.setPlaybackErrorListener(listener);
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

    // Replace the current playlist with a new one.
    // Plays the first song in the new list.
    public void setPlaylist(List<Song> songs) {
        mSongs = songs;
        mCurrentSongIndex = -1;
        if (mPlaylistChangeListener != null)
            mPlaylistChangeListener.onPlaylistChange(mSongs);
        if (songs.size() > 0)
            playSongAtIndex(0);
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

        File cacheFile = mFileCache.getLocalFile(getCurrentSong().getUrlPath());
        if (cacheFile.exists()) {
            Log.d(TAG, "file " + getCurrentSong().getUrlPath() + " already in cache; playing");
            mPlayer.playFile(cacheFile.getAbsolutePath());
            mCurrentSongStartDate = new Date();
        } else {
            mPlayer.abort();
            if (mCurrentFileCacheHandle != -1)
                mFileCache.abortDownload(mCurrentFileCacheHandle);
            mCurrentFileCacheHandle = mFileCache.downloadFile(song.getUrlPath(), this);
            mWaitingForFileCache = true;
        }

        // Update the notification now if we already have the cover.  We'll update it when the fetch
        // task finishes otherwise.
        if (fetchCoverForSongIfMissing(song))
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());

        if (mSongChangeListener != null)
            mSongChangeListener.onSongChange(song, mCurrentSongIndex);
    }

    // Stop playing the current song, if any.
    public void stopPlaying() {
        mPlayer.abort();
    }

    // Returns true if the cover is already loaded and false if we started a task to fetch it.
    public boolean fetchCoverForSongIfMissing(Song song) {
        if (song.getCoverBitmap() != null)
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
                Bitmap bitmap = BitmapFactory.decodeStream(result.getStream());
                song.setCoverBitmap(bitmap);
            } catch (DownloadRequest.PrefException e) {
            } catch (HttpException e) {
            } catch (IOException e) {
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
            if (mCoverLoadListener != null)
                mCoverLoadListener.onCoverLoad(song);
        }

        // We need to update our notification even if the fetch failed.
        if (song == getCurrentSong()) {
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());
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
                request.addHeader("Content-type", "application/x-www-form-urlencoded");
                String body = "songId=" + mSong.getSongId() + "&startTime=" + (mStartDate.getTime() / 1000);
                request.setBody(new ByteArrayInputStream(body.getBytes()));
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

    public long getCacheDataBytes() {
        return mFileCache.getDataBytes();
    }

    public void clearCache() {
        // FIXME: need to abort current downloads, i guess
        mFileCache.clear();
    }

    // Implements Player.SongCompleteListener.
    @Override
    public void onSongComplete() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                playSongAtIndex(mCurrentSongIndex + 1);
            }
        });
    }

    // Implements Player.PositionChangeListener.
    @Override
    public void onPositionChange(final int positionMs, final int durationMs) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mPositionChangeListener != null)
                    mPositionChangeListener.onPositionChange(positionMs, durationMs);

                if (positionMs > mCurrentSongLastPositionMs &&
                    positionMs <= mCurrentSongLastPositionMs + MAX_POSITION_REPORT_MS) {
                    mCurrentSongPlayedMs += (positionMs - mCurrentSongLastPositionMs);
                    if (!mReportedCurrentSong &&
                        (mCurrentSongPlayedMs >= durationMs / 2 ||
                         mCurrentSongPlayedMs > REPORT_PLAYBACK_THRESHOLD_MS)) {
                        new ReportPlayedTask(getCurrentSong(), mCurrentSongStartDate).execute();
                        mReportedCurrentSong = true;
                    }
                }
                mCurrentSongLastPositionMs = positionMs;
            }
        });
    }

    // Implements FileCache.DownloadListener.
    @Override
    public void onDownloadFail(final int handle) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got notification that download " + handle + " failed");
                if (handle == mCurrentFileCacheHandle) {
                    mCurrentFileCacheHandle = -1;
                    mWaitingForFileCache = false;
                }
            }
        });
    }

    // Implements FileCache.DownloadListener.
    @Override
    public void onDownloadComplete(final int handle) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got notification that download " + handle + " is complete");
                if (handle == mCurrentFileCacheHandle) {
                    if (mWaitingForFileCache) {
                        mWaitingForFileCache = false;
                        mPlayer.playFile(mFileCache.getLocalFile(getCurrentSong().getUrlPath()).getAbsolutePath());
                        mCurrentSongStartDate = new Date();
                    }
                    mCurrentFileCacheHandle = -1;
                }
            }
        });
    }

    // Implements FileCache.DownloadListener.
    @Override
    public void onDownloadProgress(final int handle, final long receivedBytes, final long totalBytes, final long elapsedMs) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (handle == mCurrentFileCacheHandle && mWaitingForFileCache) {
                    Song song = getCurrentSong();
                    double bytesPerMs = (double) receivedBytes / elapsedMs;
                    long remainingMs = (long) ((totalBytes - receivedBytes) / bytesPerMs);
                    if (receivedBytes >= MIN_BYTES_BEFORE_PLAYING && remainingMs + EXTRA_BUFFER_MS <= song.getLengthSec() * 1000) {
                        Log.d(TAG, "download " + handle + " is at " + receivedBytes + " bytes out of " + totalBytes + " total " +
                              "and is estimated to finish in " + remainingMs + " ms; playing");
                        mWaitingForFileCache = false;
                        mPlayer.playFile(mFileCache.getLocalFile(getCurrentSong().getUrlPath()).getAbsolutePath());
                        mCurrentSongStartDate = new Date();
                    }
                }
            }
        });
    }
}
