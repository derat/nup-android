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
import android.media.MediaPlayer;
import android.preference.PreferenceManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Thread;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

interface NupServiceObserver {
    void onPauseStateChanged(boolean isPaused);
    void onSongChanged(Song currentSong);
    void onCoverLoaded(Song currentSong);
    void onPlaylistChanged(ArrayList<Song> songs);
}

public class NupService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private static final String TAG = "NupService";

    private MediaPlayer mPlayer;
    private boolean mPlayerPrepared = false;
    private Object mPlayerLock = new Object();

    private LocalProxy mProxy;
    private Thread mProxyThread;
    private boolean mProxyRunning = false;
    public boolean isProxyRunning() { return mProxyRunning; }
    public int getProxyPort() { return mProxy.getPort(); }

    private NotificationManager mNotificationManager;
    private final int mNotificationId = 0;
    private RemoteViews mNotificationView;

    private ArrayList<Song> mSongs = new ArrayList<Song>();
    private int mCurrentSongIndex = -1;
    private boolean mPaused = false;

    private HashMap coverCache = new HashMap();

    public class LocalBinder extends Binder {
        NupService getService() {
            return NupService.this;
        }
    }

    public void initProxy() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String urlString = prefs.getString("server_url", "");
        if (urlString.isEmpty()) {
            Toast.makeText(this, "You must enter a server URL in Preferences.", Toast.LENGTH_LONG).show();
            return;
        }

        URI uri;
        try {
            uri = new URI(urlString);
        } catch (java.net.URISyntaxException e) {
            Toast.makeText(this, "Unable to parse server URL: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        boolean useSsl = false;
        int port = uri.getPort();

        String scheme = uri.getScheme();

        if (scheme == null || scheme.equals("http")) {
            if (port < 0)
                port = 80;
        } else if (scheme.equals("https")) {
            useSsl = true;
            if (port < 0)
                port = 443;
        } else {
            Toast.makeText(this, "Unknown server URL scheme \"" + scheme + "\" (should be \"http\" or \"https\").", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            mProxy = new LocalProxy(uri.getHost(), port, useSsl, prefs.getString("username", ""), prefs.getString("password", ""));
            mProxyThread = new Thread(mProxy);
            mProxyThread.start();
            mProxyRunning = true;
        } catch (IOException e) {
            Log.wtf(TAG, "creating proxy failed: " + e);
        }
    }

    private Notification updateNotification(String artist, String title, String album, Bitmap bitmap) {
        Notification notification = new Notification(R.drawable.icon, artist + " - " + title, System.currentTimeMillis());
        notification.flags |= (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR);

        RemoteViews view = new RemoteViews(getPackageName(), R.layout.notification);
        view.setTextViewText(R.id.notification_artist, artist);
        view.setTextViewText(R.id.notification_title, title);
        view.setTextViewText(R.id.notification_album, album);
        if (bitmap != null)
            view.setImageViewBitmap(R.id.notification_image, bitmap);
        notification.contentView = view;

        notification.contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0);
        mNotificationManager.notify(mNotificationId, notification);
        return notification;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = updateNotification("nup", getString(R.string.initial_notification), "", (Bitmap) null);
        startForeground(mNotificationId, notification);

        initProxy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service destroyed");
        mNotificationManager.cancel(mNotificationId);
        mPlayer.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IBinder mBinder = new LocalBinder();

    private NupServiceObserver mObserver;
    private Object mObserverLock = new Object();
    public void addObserver(NupServiceObserver observer) {
        synchronized(mObserverLock) {
            if (mObserver != null) {
                Log.wtf(TAG, "tried to add observer while one is already registered");
            }
            mObserver = observer;
        }
    }
    public void removeObserver(NupServiceObserver observer) {
        synchronized(mObserverLock) {
            if (mObserver != observer) {
                Log.wtf(TAG, "tried to remove non-registered observer");
            }
            mObserver = null;
        }
    }

    public final ArrayList<Song> getSongs() { return mSongs; }
    public final int getCurrentSongIndex() { return mCurrentSongIndex; }
    public final Song getCurrentSong() {
        return (mCurrentSongIndex >= 0 && mCurrentSongIndex < mSongs.size()) ? mSongs.get(mCurrentSongIndex) : null;
    }

    public synchronized void togglePause() {
        synchronized(mPlayerLock) {
            if (!mPlayerPrepared)
                return;
            mPaused = !mPaused;
            if (mPaused) {
                mPlayer.pause();
            } else {
                mPlayer.start();
            }
        }

        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onPauseStateChanged(mPaused);
            }
        }
    }

    public synchronized void setPlaylist(ArrayList<Song> songs) {
        mSongs = songs;
        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onPlaylistChanged(mSongs);
            }
        }
        if (songs.size() > 0)
            playSongAtIndex(0);
    }

    public synchronized void playSongAtIndex(int index) {
        if (index < 0 || index >= mSongs.size()) {
            return;
        }

        Song song = mSongs.get(index);
        String url = "http://localhost:" + mProxy.getPort() + "/music/" + song.getFilename();

        synchronized(mPlayerLock) {
            if (mPlayer != null)
                mPlayer.release();

            mPlayer = new MediaPlayer();
            mPlayer.setOnPreparedListener(this);
            mPlayer.setOnCompletionListener(this);
            mPlayer.setOnErrorListener(this);
            mPlayerPrepared = false;

            try {
                mPlayer.setDataSource(url);
            } catch (IOException e) {
                Toast.makeText(this, "Got exception while setting data source to " + url + ": " + e.toString(), Toast.LENGTH_LONG).show();
                return;
            }
            mPlayer.prepareAsync();
        }

        mCurrentSongIndex = index;
        if (coverCache.containsKey(song.getCoverFilename())) {
            song.setCoverBitmap((Bitmap) coverCache.get(song.getCoverFilename()));
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());
        } else {
            new CoverFetcherTask(this).execute(song);
        }
        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onSongChanged(song);
            }
        }
    }

    class CoverFetcherTask extends AsyncTask<Song, Void, Song> {
        private final NupService mService;
        CoverFetcherTask(NupService service) {
            mService = service;
        }

        @Override
        protected Song doInBackground(Song... songs) {
            Song song = songs[0];
            song.setCoverBitmap(null);
            try {
                URL coverUrl = new URL("http://localhost:" + mProxy.getPort() + "/cover/" + song.getCoverFilename());
                InputStream stream = (InputStream) coverUrl.getContent();
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                song.setCoverBitmap(bitmap);
            } catch (IOException e) {
                Log.e(TAG, "unable to load album cover " + song.getCoverFilename() + ": " + e);
            }
            return song;
        }

        @Override
        protected void onPostExecute(Song song) {
            mService.onCoverFetchDone(song);
        }
    }

    public void onCoverFetchDone(Song song) {
        if (song.getCoverBitmap() != null)
            coverCache.put(song.getCoverFilename(), song.getCoverBitmap());

        Song currentSong = getCurrentSong();
        if (song == getCurrentSong()) {
            // We need to update our notification even if the fetch failed.
            updateNotification(song.getArtist(), song.getTitle(), song.getAlbum(), song.getCoverBitmap());
            if (song.getCoverBitmap() != null) {
                synchronized(mObserverLock) {
                    if (mObserver != null) {
                        mObserver.onCoverLoaded(song);
                    }
                }
            }
        }
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        Log.d(TAG, "onPrepared");

        synchronized(mPlayerLock) {
            mPaused = false;
            mPlayerPrepared = true;
            mPlayer.start();
        }

        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onPauseStateChanged(mPaused);
            }
        }
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        Log.d(TAG, "onCompletion");
        playSongAtIndex(mCurrentSongIndex + 1);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Toast.makeText(this, "MediaPlayer reported a vague, not-very-useful error: what=" + what + " extra=" + extra, Toast.LENGTH_LONG).show();
        // Return false so the completion listener will get called.
        return false;
    }
}
