package org.erat.nup;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.preference.PreferenceManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import java.io.IOException;
import java.lang.Thread;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

interface NupServiceObserver {
    void onPauseStateChanged(boolean isPaused);
    void onSongChanged(Song currentSong);
}

public class NupService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener {
    private static final String TAG = "LocalProxy";
    private NotificationManager mNotificationManager;
    private MediaPlayer mPlayer;
    private LocalProxy mProxy;
    private Thread mProxyThread;
    private final int mNotificationId = 0;
    private boolean mProxyRunning = false;

    public boolean isProxyRunning() { return mProxyRunning; }
    public int getProxyPort() { return mProxy.getPort(); }

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
        } catch (java.net.URISyntaxException err) {
            Toast.makeText(this, "Unable to parse server URL: " + err.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        boolean useSsl = false;
        int port = uri.getPort();

        String scheme = uri.getScheme();

        if (scheme == null || scheme == "http") {
            if (port < 0)
                port = 80;
        } else if (scheme == "https") {
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
        } catch (IOException err) {
            Log.wtf(TAG, "creating proxy failed: " + err);
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "service created");
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.icon, "Playing a song", System.currentTimeMillis());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0);
        notification.setLatestEventInfo(this, "nup", "music player", contentIntent);
        mNotificationManager.notify(mNotificationId, notification);
        startForeground(mNotificationId, notification);

        mPlayer = new MediaPlayer();
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);

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
        mPlayer.stop();
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

    private List<Song> mSongs = new ArrayList<Song>();
    private int mCurrentSongIndex;
    private boolean mPaused;

    public final List<Song> getSongs() { return mSongs; }
    public final int getCurrentSongIndex() { return mCurrentSongIndex; }

    public synchronized void togglePause() {
        mPaused = !mPaused;
        if (mPaused) {
            mPlayer.pause();
        } else {
            mPlayer.start();
        }

        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onPauseStateChanged(mPaused);
            }
        }
    }

    public synchronized void setPlaylist(List<Song> songs) {
        mSongs = songs;
        if (songs.size() > 0)
            playSongAtIndex(0);
    }

    public synchronized void playSongAtIndex(int index) {
        if (index < 0 || index >= mSongs.size()) {
            return;
        }

        mCurrentSongIndex = index;
        Song song = mSongs.get(mCurrentSongIndex);
        String url = "http://localhost:" + mProxy.getPort() + "/music/" + song.getFilename();
        mPlayer.reset();
        try {
            mPlayer.setDataSource(url);
        } catch (IOException err) {
            Log.e(TAG, "got exception while setting data source to " + url + ": " + err.toString());
            return;
        }
        mPlayer.prepareAsync();
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        Log.d(TAG, "onPrepared");
        mPaused = false;
        mPlayer.start();

        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onSongChanged(mSongs.get(mCurrentSongIndex));
                mObserver.onPauseStateChanged(mPaused);
            }
        }
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        Log.d(TAG, "onCompletion");
        playSongAtIndex(mCurrentSongIndex + 1);
    }
}
