// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

class Player implements Runnable,
                        MediaPlayer.OnCompletionListener,
                        MediaPlayer.OnErrorListener {
    private static final String TAG = "Player";

    // Interval between reports to mListener.
    private static final int POSITION_CHANGE_REPORT_MS = 100;

    interface Listener {
        // Invoked on completion of the currently-playing file.
        void onPlaybackComplete();

        // Invoked when the playback position of the current file changes.
        void onPlaybackPositionChange(
            String path, int positionMs, int durationMs);

        // Invoked when the pause state changes.
        void onPauseStateChange(boolean paused);

        // Invoked when an error occurs.
        void onPlaybackError(String description);
    }

    // Plays the current song or queues the next one.
    private MediaPlayer mCurrentPlayer = null;
    private MediaPlayer mQueuedPlayer = null;

    // Paths currently loaded by |mCurrentPlayer| and |mQueuedPlayer|.
    private String mCurrentPath = "";
    private String mQueuedPath = "";

    // Is playback paused?
    private boolean mPaused = false;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    // Notified when things change.
    private final Listener mListener;

    public Player(Listener listener) {
        mListener = listener;
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
                resetCurrent();
                resetQueued();
                Looper.myLooper().quit();
            }
        });
    }

    public void abortPlayback() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                resetCurrent();
            }
        });
    }

    public void playFile(final String path) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got request to play " + path);
                resetCurrent();
                if (mQueuedPath.equals(path)) {
                    switchToQueued();
                } else {
                    mCurrentPlayer = createPlayer(path);
                    if (mCurrentPlayer != null) {
                        mCurrentPath = path;
                        playCurrent();
                    }
                }
            }
        });
    }

    public void queueFile(final String path) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got request to queue " + path);
                if (path.equals(mQueuedPath))
                    return;
                resetQueued();
                mQueuedPlayer = createPlayer(path);
                if (mQueuedPlayer != null)
                    mQueuedPath = path;
            }
        });
    }

    public void pause() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mPaused || mCurrentPlayer == null)
                    return;

                mPaused = true;
                mCurrentPlayer.pause();
                stopPositionTimer();
                mListener.onPauseStateChange(mPaused);
            }
        });
    }

    public void togglePause() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCurrentPlayer == null)
                    return;

                mPaused = !mPaused;
                if (mPaused) {
                    mCurrentPlayer.pause();
                    stopPositionTimer();
                } else {
                    mCurrentPlayer.start();
                    startPositionTimer();
                }
                mListener.onPauseStateChange(mPaused);
            }
        });
    }

    // Create a new MediaPlayer for playing |path|.
    // Returns null on error.
    private MediaPlayer createPlayer(String path) {
        MediaPlayer player = new MediaPlayer();
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);

        try {
            player.setDataSource(path);
        } catch (IOException e) {
            mListener.onPlaybackError("Got error while setting data source to " + path + ": " + e);
            return null;
        }
        try {
            player.prepare();
        } catch (IOException e) {
            mListener.onPlaybackError("Got error while preparing " + path + ": " + e);
            return null;
        }

        Log.d(TAG, "created player " + player + " for " + path);
        return player;
    }

    // Periodically invoked to notify the observer about the playback position of the current song.
    private Runnable mPositionTask = new Runnable() {
        public void run() {
            if (mCurrentPlayer == null) {
                Log.w(TAG, "aborting position task; player is null");
                return;
            }
            mListener.onPlaybackPositionChange(
                mCurrentPath, mCurrentPlayer.getCurrentPosition(), mCurrentPlayer.getDuration());
            mHandler.postDelayed(this, POSITION_CHANGE_REPORT_MS);
        }
    };

    // Start running |mSongPositionTask|.
    private void startPositionTimer() {
        stopPositionTimer();
        mHandler.post(mPositionTask);
    }

    // Stop running |mSongPositionTask|.
    private void stopPositionTimer() {
        mHandler.removeCallbacks(mPositionTask);
    }

    // Implements MediaPlayer.OnCompletionListener.
    @Override
    public void onCompletion(final MediaPlayer player) {
        Log.d(TAG, "player " + player + " completed playback");
        resetCurrent();
        mListener.onPlaybackComplete();
    }

    // Implements MediaPlayer.OnErrorListener.
    @Override
    public boolean onError(MediaPlayer player, int what, int extra) {
        mListener.onPlaybackError("MediaPlayer reported a vague, not-very-useful error: what=" + what + " extra=" + extra);
        // Return false so the completion listener will get called.
        return false;
    }

    // Reset |mCurrentPlayer| and |mCurrentPath|.
    private void resetCurrent() {
        stopPositionTimer();
        if (mCurrentPlayer != null)
            mCurrentPlayer.release();
        mCurrentPlayer = null;
        mCurrentPath = "";
    }

    // Reset |mQueuedPlayer| and |mQueuedPath|.
    private void resetQueued() {
        if (mQueuedPlayer != null)
            mQueuedPlayer.release();
        mQueuedPlayer = null;
        mQueuedPath = "";
    }

    // Switch to |mQueuedPlayer| and start playing it.
    private void switchToQueued() {
        mCurrentPlayer = mQueuedPlayer;
        mCurrentPath = mQueuedPath;
        mQueuedPlayer = null;
        mQueuedPath = "";

        if (mCurrentPlayer != null)
            playCurrent();
    }

    // Start playing the file loaded by |mCurrentPlayer|.
    private void playCurrent() {
        if (mCurrentPlayer == null) {
            Log.e(TAG, "ignoring request to play uninitialized current file");
            return;
        }

        Log.d(TAG, "playing " + mCurrentPath + " using " + mCurrentPlayer);
        mCurrentPlayer.start();
        startPositionTimer();
        if (mPaused) {
            mPaused = false;
            mListener.onPauseStateChange(mPaused);
        }
    }
}
