// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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

    // Final, expected lengths of |mCurrentPath| and |mQueuedPath|.
    private long mCurrentNumBytes = 0;
    private long mQueuedNumBytes = 0;

    // InputStreams used by |mCurrentPlayer| and |mQueuedPlayer|.
    private FileInputStream mCurrentStream = null;
    private FileInputStream mQueuedStream = null;

    // Is playback paused?
    private boolean mPaused = false;

    // Used to run tasks on our own thread.
    private Handler mHandler = null;

    // Notified when things change.
    private final Listener mListener;

    private boolean mShouldQuit = false;

    public Player(Listener listener) {
        mListener = listener;
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (this) {
            if (mShouldQuit)
                return;
            mHandler = new Handler();
        }
        Looper.loop();
    }

    public void quit() {
        synchronized (this) {
            // The thread hasn't started looping yet; tell it to exit before starting.
            if (mHandler == null) {
                mShouldQuit = true;
                return;
            }
        }
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

    public void playFile(final String path, final long numBytes) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got request to play " + path);
                resetCurrent();
                if (mQueuedPath.equals(path)) {
                    switchToQueued();
                } else {
                    FileInputStream stream = createStream(path);
                    if (stream == null)
                        return;

                    mCurrentPlayer = createPlayer(stream);
                    if (mCurrentPlayer != null) {
                        mCurrentPath = path;
                        mCurrentNumBytes = numBytes;
                        mCurrentStream = stream;
                        playCurrent();
                    } else {
                        closeStream(stream);
                    }
                }
            }
        });
    }

    public void queueFile(final String path, final long numBytes) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "got request to queue " + path);
                if (path.equals(mQueuedPath))
                    return;
                resetQueued();
                FileInputStream stream = createStream(path);
                if (stream == null)
                    return;

                mQueuedPlayer = createPlayer(stream);
                if (mQueuedPlayer != null) {
                    mQueuedPath = path;
                    mQueuedNumBytes = numBytes;
                    mQueuedStream = stream;
                } else {
                    closeStream(stream);
                }
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

    // Create a new MediaPlayer for playing |path| of length |numBytes|.
    // Returns null on error.
    private MediaPlayer createPlayer(FileInputStream stream) {
        try {
            MediaPlayer player = new MediaPlayer();
            Log.d(TAG, "created player " + player);
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            player.setDataSource(stream.getFD());
            player.prepare();
            return player;
        } catch (IOException e) {
            mListener.onPlaybackError("Got error while creating player: " + e);
            return null;
        }
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
        try {
            long streamPosition = mCurrentStream.getChannel().position();
            Log.d(TAG, player + " completed playback at " + streamPosition + " of " + mCurrentNumBytes);
            if (streamPosition < mCurrentNumBytes) {
                mListener.onPlaybackError("Buffer underrun at " + streamPosition + " of " + mCurrentNumBytes);
                int currentPositionMs = mCurrentPlayer.getCurrentPosition();
                Log.d(TAG, player + " not done; resetting and seeking to " + currentPositionMs);
                mCurrentPlayer.reset();
                mCurrentPlayer.setDataSource(mCurrentStream.getFD());
                mCurrentPlayer.prepare();
                mCurrentPlayer.seekTo(currentPositionMs);
                mCurrentPlayer.start();
            } else {
                resetCurrent();
                mListener.onPlaybackComplete();
            }
        } catch (IOException e) {
            Log.e(TAG, "got error while handling completion of " + player + ": " + e);
        }
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
        mCurrentNumBytes = 0;
        if (mCurrentStream != null)
            closeStream(mCurrentStream);
        mCurrentStream = null;
    }

    // Reset |mQueuedPlayer| and |mQueuedPath|.
    private void resetQueued() {
        if (mQueuedPlayer != null)
            mQueuedPlayer.release();
        mQueuedPlayer = null;
        mQueuedPath = "";
        mQueuedNumBytes = 0;
        if (mQueuedStream != null)
            closeStream(mQueuedStream);
        mQueuedStream = null;
    }

    // Switch to |mQueuedPlayer| and start playing it.
    private void switchToQueued() {
        mCurrentPlayer = mQueuedPlayer;
        mCurrentPath = mQueuedPath;
        mCurrentNumBytes = mQueuedNumBytes;
        mCurrentStream = mQueuedStream;

        mQueuedPlayer = null;
        mQueuedPath = "";
        mQueuedNumBytes = 0;
        mQueuedStream = null;

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

    // Create a new FileInputStream for reading |path|.  Returns null on error.
    private FileInputStream createStream(final String path) {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(path);
        } catch (FileNotFoundException e) {
            mListener.onPlaybackError("Unable to open " + path + ": " + e);
        }
        return stream;
    }

    // Close |stream|.
    private void closeStream(final FileInputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            mListener.onPlaybackError("Unable close stream: " + e);
        }
    }
}
