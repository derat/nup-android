// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

class Player implements Runnable, MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private static final String TAG = "Player";

    // Interval between repotrs to mPositionChangeListener.
    private static final int POSITION_CHANGE_REPORT_MS = 100;

    // Listener for completion of the currently-playing song.
    interface SongCompleteListener {
        void onSongComplete();
    }

    // Listener for changes in the playback position of the current song.
    interface PositionChangeListener {
        void onPositionChange(int positionMs, int durationMs);
    }

    // Listener for changes in the pause state.
    interface PauseToggleListener {
        void onPauseToggle(boolean paused);
    }

    // Listener for error messages.
    interface PlaybackErrorListener {
        void onPlaybackError(String description);
    }

    // Plays the current song.  On song change, we throw out the old one and create a new one, which seems non-ideal but
    // avoids a bunch of issues with invalid state changes that seem to be caused by prepareAsync().
    private MediaPlayer mMediaPlayer;

    private boolean mPrepared = false;

    private boolean mPaused = false;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    private SongCompleteListener mSongCompleteListener;
    private PositionChangeListener mPositionChangeListener;
    private PauseToggleListener mPauseToggleListener;
    private PlaybackErrorListener mPlaybackErrorListener;

    void setSongCompleteListener(SongCompleteListener listener) {
        mSongCompleteListener = listener;
    }
    void setPositionChangeListener(PositionChangeListener listener) {
        mPositionChangeListener = listener;
    }
    void setPauseToggleListener(PauseToggleListener listener) {
        mPauseToggleListener = listener;
    }
    void setPlaybackErrorListener(PlaybackErrorListener listener) {
        mPlaybackErrorListener = listener;
    }

    @Override
    public void run() {
        Looper.prepare();
        mHandler = new Handler();
        Looper.loop();
    }

    public void quit() {
        abort();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
    }

    public void abort() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                stopPositionTimer();
                if (mMediaPlayer != null)
                    mMediaPlayer.release();
                mPrepared = false;
            }
        });
    }

    public void playFile(final String url) {
        abort();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setOnPreparedListener(Player.this);
                mMediaPlayer.setOnCompletionListener(Player.this);
                mMediaPlayer.setOnErrorListener(Player.this);

                try {
                    mMediaPlayer.setDataSource(url);
                } catch (final IOException e) {
                    if (mPlaybackErrorListener != null)
                        mPlaybackErrorListener.onPlaybackError("Got exception while setting data source to " + url + ": " + e.toString());
                    return;
                }
                mMediaPlayer.prepareAsync();
            }
        });
    }

    public void pause() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!mPrepared || mPaused)
                    return;

                mPaused = true;
                mMediaPlayer.pause();
                stopPositionTimer();
                if (mPauseToggleListener != null)
                    mPauseToggleListener.onPauseToggle(mPaused);
            }
        });
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

                if (mPauseToggleListener != null)
                    mPauseToggleListener.onPauseToggle(mPaused);
            }
        });
    }

    // Periodically invoked to notify the observer about the playback position of the current song.
    private Runnable mPositionTask = new Runnable() {
        public void run() {
            if (!mPrepared)
                return;
            if (mPositionChangeListener != null)
                mPositionChangeListener.onPositionChange(mMediaPlayer.getCurrentPosition(), mMediaPlayer.getDuration());
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
        if (mSongCompleteListener != null)
            mSongCompleteListener.onSongComplete();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (mPlaybackErrorListener != null)
            mPlaybackErrorListener.onPlaybackError("MediaPlayer reported a vague, not-very-useful error: what=" + what + " extra=" + extra);
        // Return false so the completion listener will get called.
        return false;
    }
}
