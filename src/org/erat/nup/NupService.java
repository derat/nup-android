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

interface NupServiceObserver {
    void onPauseStateChanged(boolean isPaused);
}

public class NupService extends Service implements MediaPlayer.OnPreparedListener {
    private NotificationManager mNotificationManager;
    private MediaPlayer mPlayer;
    private final int mNotificationId = 0;

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

        /*
        mPlayer = new MediaPlayer();
        String url = "http://10.0.0.5:8080/music/virt/v-canyon.mp3";
        try {
            mPlayer.setDataSource(url);
        } catch (java.io.IOException err) {
            Log.e(this.toString(), "Got exception while setting data source: " + err.toString());
        }
        mPlayer.setOnPreparedListener(this);
        mPlayer.prepareAsync();
        */
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public void onPrepared(MediaPlayer player) {
        Log.i(this.toString(), "onPrepared");
        mPaused = false;
        mPlayer.start();

        synchronized(mObserverLock) {
            if (mObserver != null) {
                mObserver.onPauseStateChanged(mPaused);
            }
        }
    }

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

    public synchronized void insertTrack(String url, int position) {
    }

    boolean mPaused;
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

    public synchronized void selectTrack(int offset) {
    }
}
