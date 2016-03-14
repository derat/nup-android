// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

class Player implements Runnable,
                        MediaPlayer.OnCompletionListener,
                        MediaPlayer.OnErrorListener {
    private static final String TAG = "Player";

    // Interval between reports to mListener.
    private static final int POSITION_CHANGE_REPORT_MS = 100;

    private static final int SHUTDOWN_TIMEOUT_MS = 1000;

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

    // Currently-playing and queued songs.
    private FilePlayer mCurrentPlayer;
    private FilePlayer mQueuedPlayer;

    // Is playback paused?
    private boolean mPaused = false;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    // Notified when things change.
    private final Listener mListener;

    // Used to post tasks to mListener.
    private final Handler mListenerHandler;

    // Used to load queued files in the background.
    private final ExecutorService mBackgroundLoader = Executors.newSingleThreadExecutor();

    private boolean mShouldQuit = false;

    private enum PauseUpdateType {
        PAUSE,
        UNPAUSE,
        TOGGLE_PAUSE,
    }

    /** Wraps an individual file. */
    private class FilePlayer {
        private final String mPath;
        private final long mNumBytes;

        private FileInputStream mStream;
        private MediaPlayer mPlayer;

        public FilePlayer(String path, long numBytes) {
            mPath = path;
            mNumBytes = numBytes;
        }

        public String getPath() {
            return mPath;
        }

        public long getNumBytes() {
            return mNumBytes;
        }

        public FileInputStream getStream() {
            return mStream;
        }

        public MediaPlayer getMediaPlayer() {
            return mPlayer;
        }

        public boolean prepare() {
            try {
                mStream = new FileInputStream(mPath);
                mPlayer = new MediaPlayer();
                Log.d(TAG, "created player " + mPlayer + " for " + mPath);
                mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mPlayer.setOnCompletionListener(Player.this);
                mPlayer.setOnErrorListener(Player.this);
                mPlayer.setDataSource(mStream.getFD());
                mPlayer.prepare();
                return true;
            } catch (final IOException e) {
                close();
                mListenerHandler.post(new Runnable() {
                    @Override public void run() {
                        mListener.onPlaybackError("Unable to prepare " + mPath + ": " + e);
                    }
                });
                return false;
            }
        }

        public void close() {
            try {
                if (mPlayer != null) {
                    mPlayer.release();
                }
                if (mStream != null) {
                    mStream.close();
                }
            } catch (final IOException e) {
                mListenerHandler.post(new Runnable() {
                    @Override public void run() {
                        mListener.onPlaybackError("Unable to close " + mPath + ": " + e);
                    }
                });
            }
        }
    }

    public Player(Listener listener, Handler listenerHandler) {
        mListener = listener;
        mListenerHandler = listenerHandler;
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
            @Override public void run() {
                mBackgroundLoader.shutdownNow();
                try {
                    mBackgroundLoader.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {}
                resetCurrent();
                resetQueued();
                Looper.myLooper().quit();
            }
        });
    }

    public void abortPlayback() {
        mHandler.post(new Runnable() {
            @Override public void run() {
                resetCurrent();
            }
        });
    }

    public void playFile(final String path, final long numBytes) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                Log.d(TAG, "got request to play " + path);
                resetCurrent();
                if (mQueuedPlayer != null && mQueuedPlayer.getPath().equals(path)) {
                    Log.d(TAG, "using queued player " + mQueuedPlayer.getMediaPlayer());
                    mCurrentPlayer = mQueuedPlayer;
                    mQueuedPlayer = null;
                } else {
                    mCurrentPlayer = new FilePlayer(path, numBytes);
                    if (!mCurrentPlayer.prepare()) {
                        mCurrentPlayer = null;
                        return;
                    }
                }

                mCurrentPlayer.getMediaPlayer().start();
                startPositionTimer();
                if (mPaused) {
                    mPaused = false;
                    mListenerHandler.post(new Runnable() {
                        @Override public void run() {
                            mListener.onPauseStateChange(mPaused);
                        }
                    });
                }
            }
        });
    }

    public void queueFile(final String path, final long numBytes) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                Log.d(TAG, "got request to queue " + path);
                if (mQueuedPlayer != null && mQueuedPlayer.getPath().equals(path)) {
                    return;
                }

                mBackgroundLoader.submit(new Runnable() {
                    @Override public void run() {
                        final FilePlayer player = new FilePlayer(path, numBytes);
                        if (!player.prepare()) {
                            return;
                        }
                        mHandler.post(new Runnable() {
                            @Override public void run() {
                                Log.d(TAG, "finished preparing queued file " + path);
                                resetQueued();
                                mQueuedPlayer = player;
                            }
                        });
                    }
                });
            }
        });
    }

    public void pause() {
        updatePauseState(PauseUpdateType.PAUSE);
    }

    public void unpause() {
        updatePauseState(PauseUpdateType.UNPAUSE);
    }

    public void togglePause() {
        updatePauseState(PauseUpdateType.TOGGLE_PAUSE);
    }

    private void updatePauseState(final PauseUpdateType type) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mCurrentPlayer == null) {
                    return;
                }

                switch (type) {
                    case PAUSE:
                        if (mPaused) {
                            return;
                        }
                        mPaused = true;
                        break;
                    case UNPAUSE:
                        if (!mPaused) {
                            return;
                        }
                        mPaused = false;
                        break;
                    case TOGGLE_PAUSE:
                        mPaused = !mPaused;
                        break;
                }

                if (mPaused) {
                    mCurrentPlayer.getMediaPlayer().pause();
                    stopPositionTimer();
                } else {
                    mCurrentPlayer.getMediaPlayer().start();
                    startPositionTimer();
                }
                mListenerHandler.post(new Runnable() {
                    @Override public void run() {
                        mListener.onPauseStateChange(mPaused);
                    }
                });
            }
        });
    }

    // Periodically invoked to notify the observer about the playback position of the current song.
    private Runnable mPositionTask = new Runnable() {
        public void run() {
            if (mCurrentPlayer == null) {
                Log.w(TAG, "aborting position task; player is null");
                return;
            }
            final String path = mCurrentPlayer.getPath();
            final int positionMs = mCurrentPlayer.getMediaPlayer().getCurrentPosition();
            final int durationMs = mCurrentPlayer.getMediaPlayer().getDuration();
            mListenerHandler.post(new Runnable() {
                @Override public void run() {
                    mListener.onPlaybackPositionChange(path, positionMs, durationMs);
                }
            });
            mHandler.postDelayed(this, POSITION_CHANGE_REPORT_MS);
        }
    };

    // Start running |mSongPositionTask|.
    private void startPositionTimer() {
        Util.assertOnLooper(mHandler.getLooper());
        stopPositionTimer();
        mHandler.post(mPositionTask);
    }

    // Stop running |mSongPositionTask|.
    private void stopPositionTimer() {
        Util.assertOnLooper(mHandler.getLooper());
        mHandler.removeCallbacks(mPositionTask);
    }

    // Implements MediaPlayer.OnCompletionListener.
    @Override public void onCompletion(final MediaPlayer player) {
        Util.assertOnLooper(mHandler.getLooper());
        if (mCurrentPlayer == null || mCurrentPlayer.getMediaPlayer() != player) {
            return;
        }
        try {
            final long streamPosition = mCurrentPlayer.getStream().getChannel().position();
            final long numBytes = mCurrentPlayer.getNumBytes();
            Log.d(TAG, player + " completed playback at " + streamPosition + " of " + numBytes);
            if (streamPosition < numBytes) {
                mListenerHandler.post(new Runnable() {
                    @Override public void run() {
                        mListener.onPlaybackError("Buffer underrun at " + streamPosition + " of " + numBytes);
                    }
                });
                int currentPositionMs = player.getCurrentPosition();
                Log.w(TAG, player + " not done; resetting and seeking to " + currentPositionMs + " ms");
                player.reset();
                player.setDataSource(mCurrentPlayer.getStream().getFD());
                player.prepare();
                player.seekTo(currentPositionMs);
                player.start();
            } else {
                resetCurrent();
                mListenerHandler.post(new Runnable() {
                    @Override public void run() {
                        mListener.onPlaybackComplete();
                    }
                });
            }
        } catch (IOException e) {
            Log.e(TAG, "got error while handling completion of " + player + ": " + e);
        }
    }

    // Implements MediaPlayer.OnErrorListener.
    @Override public boolean onError(MediaPlayer player, final int what, final int extra) {
        mListenerHandler.post(new Runnable() {
            @Override public void run() {
                mListener.onPlaybackError("MediaPlayer reported a vague, not-very-useful error: what=" + what + " extra=" + extra);
            }
        });
        // Return false so the completion listener will get called.
        return false;
    }

    /** Resets mCurrentPlayer. */
    private void resetCurrent() {
        Util.assertOnLooper(mHandler.getLooper());
        stopPositionTimer();
        if (mCurrentPlayer != null) {
            mCurrentPlayer.close();
            mCurrentPlayer = null;
        }
    }

    /** Resets mQueuedPlayer. */
    private void resetQueued() {
        Util.assertOnLooper(mHandler.getLooper());
        if (mQueuedPlayer != null) {
            mQueuedPlayer.close();
            mQueuedPlayer = null;
        }
    }
}
