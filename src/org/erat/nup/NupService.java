package org.erat.nup;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import java.io.IOException;
import java.lang.Thread;
import java.util.List;

interface NupServiceObserver {
    void onPauseStateChanged(boolean isPaused);
    void onSongChanged(Song currentSong);
}

public class NupService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener {
    private NotificationManager mNotificationManager;
    private MediaPlayer mPlayer;
    private LocalProxy mProxy;
    private Thread mProxyThread;
    private final int mNotificationId = 0;

    public int getProxyPort() { return mProxy.getPort(); }

    public class LocalBinder extends Binder {
        NupService getService() {
            return NupService.this;
        }
    }

    @Override
    public void onCreate() {
        Log.i(this.toString(), "service created");
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.icon, "Playing a song", System.currentTimeMillis());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, NupActivity.class), 0);
        notification.setLatestEventInfo(this, "nup", "music player", contentIntent);
        mNotificationManager.notify(mNotificationId, notification);
        startForeground(mNotificationId, notification);

        mPlayer = new MediaPlayer();
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);

        try {
            mProxy = new LocalProxy("10.0.0.5", 8080, false);
            mProxyThread = new Thread(mProxy);
            mProxyThread.start();
        } catch (IOException err) {
            Log.wtf(this.toString(), "creating proxy failed: " + err);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(this.toString(), "received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(this.toString(), "service destroyed");
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
                Log.wtf(this.toString(), "tried to add observer while one is already registered");
            }
            mObserver = observer;
        }
    }
    public void removeObserver(NupServiceObserver observer) {
        synchronized(mObserverLock) {
            if (mObserver != observer) {
                Log.wtf(this.toString(), "tried to remove non-registered observer");
            }
            mObserver = null;
        }
    }

    private List<Song> mSongs;
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
            Log.e(this.toString(), "ignoring request to play song " + index + " (" + mSongs.size() + " in playlist)");
            return;
        }

        mCurrentSongIndex = index;
        Song song = mSongs.get(mCurrentSongIndex);
        String url = "http://localhost:" + mProxy.getPort() + "/music/" + song.getFilename();
        mPlayer.reset();
        try {
            mPlayer.setDataSource(url);
        } catch (IOException err) {
            Log.e(this.toString(), "got exception while setting data source to " + url + ": " + err.toString());
            return;
        }
        mPlayer.prepareAsync();
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        Log.i(this.toString(), "onPrepared");
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
        Log.i(this.toString(), "onCompletion");
        playSongAtIndex(mCurrentSongIndex + 1);
    }
}
