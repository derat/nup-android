package org.erat.nup;

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
    private MediaPlayer curPlayer, nextPlayer;

    public class LocalBinder extends Binder {
        NupService getService() {
            return NupService.this;
        }
    }

    @Override
    public void onCreate() {
        curPlayer = new MediaPlayer();
        String url = "http://10.0.0.5:8080/music/virt/v-canyon.mp3";
        try {
            curPlayer.setDataSource(url);
        } catch (java.io.IOException err) {
            Log.e(this.toString(), "Got exception while setting data source: " + err.toString());
        }
        curPlayer.setOnPreparedListener(this);
        Log.i(this.toString(), "Preparing");
        curPlayer.prepareAsync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(this.toString(), "Received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
        if (mObserver != null) {
            mObserver.onPauseStateChanged(mPaused);
        }
        //player.start();
    }

    NupServiceObserver mObserver;
    public void setObserver(NupServiceObserver observer) {
        mObserver = observer;
    }

    public void insertTrack(String url, int position) {
    }

    boolean mPaused;
    public void togglePause() {
        mPaused = !mPaused;
        if (mObserver != null) {
            mObserver.onPauseStateChanged(mPaused);
        }
    }

    public void selectTrack(int offset) {
    }
}
