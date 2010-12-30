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

public class NupActivity extends Activity implements NupServiceObserver {
    private Button mPauseButton;

    private NupService mService;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(this.toString(), "activity created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mPauseButton = (Button) findViewById(R.id.pauseButton);

        Intent serviceIntent = new Intent(this, NupService.class);
        startService(serviceIntent);
        bindService(new Intent(this, NupService.class), mConnection, 0);
    }

    @Override
    protected void onDestroy() {
        Log.i(this.toString(), "activity destroyed");
        super.onDestroy();
        unbindService(mConnection);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(this.toString(), "connected to service");
            mService = ((NupService.LocalBinder) service).getService();
            mService.setObserver(NupActivity.this);
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.i(this.toString(), "disconnected from service");
            mService = null;
        }
    };

    boolean mIsBound = false;

    public void onPauseButtonClicked(View view) {
        mService.togglePause();
    }
    public void onExitButtonClicked(View view) {
        stopService(new Intent(this, NupService.class));
        finish();
    }

    @Override
    public void onPauseStateChanged(boolean isPaused) {
        mPauseButton.setText(isPaused ? "Play" : "Pause");
    }
}
