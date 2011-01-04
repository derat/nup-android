// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;

interface PlayerObserver {
    void onSongComplete();
    void onSongPositionChange(int positionMs, int durationMs);
    void onPauseToggle(boolean paused);
    void onError(String description);
}

class Player implements Runnable, MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private static final String TAG = "Player";

    private static final int POSITION_CHANGE_REPORT_MS = 100;

    // Observer that gets notified about changes in our state.
    private final PlayerObserver mObserver;

    // Plays the current song.  On song change, we throw out the old one and create a new one, which seems non-ideal but
    // avoids a bunch of issues with invalid state changes that seem to be caused by prepareAsync().
    private MediaPlayer mMediaPlayer;;

    private boolean mPrepared = false;

    private boolean mPaused = false;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    private Runnable mLastPlayTask;

    Player(PlayerObserver observer) {
        mObserver = observer;
    }

    @Override
    public void run() {
        Looper.prepare();
        mHandler = new Handler();
        Looper.loop();
    }

    public void quit() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mMediaPlayer != null)
                    mMediaPlayer.release();
                Looper.myLooper().quit();
            }
        });
    }

    public void playSong(final String url, int delayMs) {
        if (mLastPlayTask != null)
            mHandler.removeCallbacks(mLastPlayTask);

        stopPositionTimer();
        mLastPlayTask = new Runnable() {
            @Override
            public void run() {
                stopPositionTimer();
                if (mMediaPlayer != null)
                    mMediaPlayer.release();

                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setOnPreparedListener(Player.this);
                mMediaPlayer.setOnCompletionListener(Player.this);
                mMediaPlayer.setOnErrorListener(Player.this);
                mPrepared = false;

                try {
                    mMediaPlayer.setDataSource(url);
                } catch (final IOException e) {
                    mObserver.onError("Got exception while setting data source to " + url + ": " + e.toString());
                    return;
                }
                mMediaPlayer.prepareAsync();
            }
        };
        mHandler.postDelayed(mLastPlayTask, delayMs);
    }

    public void togglePause() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!mPrepared)
                    return;

                mPaused = !mPaused;
                if (mPaused) {
                    mMediaPlayer.pause();
                    stopPositionTimer();
                } else {
                    mMediaPlayer.start();
                    startPositionTimer();
                }

                mObserver.onPauseToggle(mPaused);
            }
        });
    }

    // Periodically invoked to notify the observer about the playback position of the current song.
    private Runnable mPositionTask = new Runnable() {
        public void run() {
            if (!mPrepared)
                return;
            mObserver.onSongPositionChange(mMediaPlayer.getCurrentPosition(), mMediaPlayer.getDuration());
            mHandler.postDelayed(this, POSITION_CHANGE_REPORT_MS);
        }
    };

    // Start running mSongPositionTask.
    private void startPositionTimer() {
        stopPositionTimer();
        mHandler.post(mPositionTask);
    }

    // Stop running mSongPositionTask.
    private void stopPositionTimer() {
        mHandler.removeCallbacks(mPositionTask);
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        Log.d(TAG, "onPrepared");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mPrepared = true;
                mMediaPlayer.start();
                startPositionTimer();
            }
        });
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        Log.d(TAG, "onCompletion");
        mObserver.onSongComplete();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        mObserver.onError("MediaPlayer reported a vague, not-very-useful error: what=" + what + " extra=" + extra);
        // Return false so the completion listener will get called.
        return false;
    }
}
