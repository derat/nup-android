package org.erat.nup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;

public class nup extends Activity implements NupServiceObserver {
    private Button mPauseButton;

    private NupService mService;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mPauseButton = (Button) findViewById(R.id.pauseButton);
        doBindService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(this.toString(), "service connected");
            mService = ((NupService.LocalBinder)service).getService();
            mService.setObserver(nup.this);
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.i(this.toString(), "service disconnected");
            mService = null;
        }
    };

    boolean mIsBound = false;

    void doBindService() {
        if (!mIsBound) {
            bindService(new Intent(this, NupService.class), mConnection, Context.BIND_AUTO_CREATE);
            mIsBound = true;
        }
    }

    void doUnbindService() {
        if (mIsBound) {
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    public void onPauseButtonClicked(View view) {
        Log.i(this.toString(), "Pause button clicked");
        mService.togglePause();
    }

    @Override
    public void onPauseStateChanged(boolean isPaused) {
        mPauseButton.setText(isPaused ? "Play" : "Pause");
    }
}
