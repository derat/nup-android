package org.erat.nup;

import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

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
        //player.start();
    }

    public void insertTrack(String url, int position) throws android.os.RemoteException {
    }
    public void togglePause() throws android.os.RemoteException {
    }
    public void selectTrack(int offset) throws android.os.RemoteException {
    }
}
