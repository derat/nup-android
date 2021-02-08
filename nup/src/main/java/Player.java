// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.app.Activity;
import android.content.Context;
import android.media.audiofx.LoudnessEnhancer;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Reduced volume level to use, in the range [0.0, 1.0].
    private static final float LOW_VOLUME_FRACTION = 0.2f;

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

    private Context mContext;

    // Currently-playing and queued songs.
    private FilePlayer mCurrentPlayer;
    private FilePlayer mQueuedPlayer;

    // Is playback paused?
    private boolean mPaused = false;

    // Is the volume currently lowered?
    private boolean mLowVolume = false;

    // Pre-amp gain adjustment in decibels.
    private double mPreAmpGain = 0;

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
        private final String mPath;     // File containing song.
        private final long mNumBytes;   // Total expected size of song (mPath may be incomplete).
        private final double mSongGain; // Song-specific gain adjustment in decibels.
        private final double mPeakAmp;  // Song's peak amplitude, with 1.0 being max without clipping.

        // Ideally only used if we haven't downloaded the complete file, since MediaPlayer
        // skips all the time when playing from a stream.
        private FileInputStream mStream;

        private MediaPlayer mPlayer;
        private LoudnessEnhancer mLoudnessEnhancer;

        private boolean mLowVolume = false; // True if the song's volume is temporarily lowered.
        private double mPreAmpGain = 0;     // Pre-amp gain adjustment in decibels.

        public FilePlayer(String path, long numBytes, double preAmpGain, double songGain, double peakAmp) {
            mPath = path;
            mNumBytes = numBytes;
            mSongGain = songGain;
            mPeakAmp = peakAmp;
            mPreAmpGain = preAmpGain;
        }

        public String getPath() {
            return mPath;
        }

        // Returns the current size of mPath.
        public long getFileBytes() {
            return (new File(mPath)).length();
        }

        // Returns the total expected size of the song.
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
                final long currentBytes = (new File(mPath)).length();
                mPlayer = new MediaPlayer();
                Log.d(TAG, "created " + mPlayer + " for " + mPath
                        + " (have " + currentBytes + " of " + mNumBytes + ")");

                mPlayer.setWakeMode(mContext, PowerManager.PARTIAL_WAKE_LOCK);
                mPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                mPlayer.setOnCompletionListener(Player.this);
                mPlayer.setOnErrorListener(Player.this);

                // TODO: I am totally jinxing this, but passing MediaPlayer a path rather than an FD
                // seems to maybe make it finally stop skipping. If this is really the case,
                // investigate whether it's possible to also pass a path even when the file isn't
                // fully downloaded yet. I think I tried this before and it made it stop when it got
                // to the initial end of the file, even if more had been downloaded by then, though.
                if (currentBytes == mNumBytes) {
                    mPlayer.setDataSource(mPath);
                } else {
                    mStream = new FileInputStream(mPath);
                    mPlayer.setDataSource(mStream.getFD());
                }
                mPlayer.prepare();

                // This should happen after preparing the MediaPlayer,
                // per https://stackoverflow.com/q/23342655/6882947.
                mLoudnessEnhancer = new LoudnessEnhancer(mPlayer.getAudioSessionId());
                adjustVolume();

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
                    mLoudnessEnhancer.release();
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

        public void setLowVolume(boolean lowVolume) {
            mLowVolume = lowVolume;
            adjustVolume();
        }

        public void setPreAmpGain(double preAmpGain) {
            mPreAmpGain = preAmpGain;
            adjustVolume();
        }

        // Adjusts |mPlayer|'s volume appropriately.
        private void adjustVolume() {
            if (mPlayer == null || mLoudnessEnhancer == null) {
                return;
            }

            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_specification
            double pct = Math.pow(10, (mPreAmpGain + mSongGain) / 20);
            if (mLowVolume) {
                pct *= LOW_VOLUME_FRACTION;
            }
            if (mPeakAmp > 0) {
                pct = Math.min(pct, mPeakAmp);
            }

            Log.d(TAG, "setting " + mPath + " volume to " + pct);

            // Hooray: MediaPlayer only accepts volumes in the range [0.0, 1.0],
            // while LoudnessEnhancer seems to only accept positive gains (in mB).
            if (pct <= 1) {
                mPlayer.setVolume((float) pct, (float) pct);
                mLoudnessEnhancer.setTargetGain(0);
            } else {
                mPlayer.setVolume(1, 1);
                int gainmB = (int) Math.round(Math.log10(pct) * 20000);
                mLoudnessEnhancer.setTargetGain(gainmB);
            }
        }

        // Restarts playback after a buffer underrun.
        // Returns true if we switched to the complete file.
        public boolean restartPlayback() throws IOException {
            final int currentPositionMs = mPlayer.getCurrentPosition();
            final long currentBytes = getFileBytes();
            Log.w(TAG, "restarting " + mPlayer + " at " + currentPositionMs + " ms");

            boolean switchedToFile = false;
            mPlayer.reset();

            if (currentBytes == mNumBytes) {
                Log.w(TAG, "switching " + mPlayer + " from stream to complete file " + mPath);
                mStream.close();
                mStream = null;
                mPlayer.setDataSource(mPath);
                switchedToFile = true;
            } else {
                Log.w(TAG, "setting " + mPlayer + " to reuse current stream");
                mPlayer.setDataSource(getStream().getFD());
            }

            mPlayer.prepare();
            mPlayer.seekTo(currentPositionMs);
            mPlayer.start();
            return switchedToFile;
        }
    }

    public Player(Context context, Listener listener, Handler listenerHandler, double preAmpGain) {
        mContext = context;
        mListener = listener;
        mListenerHandler = listenerHandler;
        mPreAmpGain = preAmpGain;
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

    public void playFile(final String path, final long numBytes,
                         final double gain, final double peakAmp) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                Log.d(TAG, "got request to play " + path);
                resetCurrent();
                if (mQueuedPlayer != null && mQueuedPlayer.getPath().equals(path)) {
                    Log.d(TAG, "using queued " + mQueuedPlayer.getMediaPlayer());
                    mCurrentPlayer = mQueuedPlayer;
                    mQueuedPlayer = null;
                } else {
                    mCurrentPlayer = new FilePlayer(path, numBytes, mPreAmpGain, gain, peakAmp);
                    if (!mCurrentPlayer.prepare()) {
                        mCurrentPlayer = null;
                        return;
                    }
                    mCurrentPlayer.setLowVolume(mLowVolume);
                }

                if (!mPaused) {
                    mCurrentPlayer.getMediaPlayer().start();
                    startPositionTimer();
                }
            }
        });
    }

    public void queueFile(final String path, final long numBytes,
                          final double gain, final double peakAmp) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                Log.d(TAG, "got request to queue " + path);
                if (mQueuedPlayer != null && mQueuedPlayer.getPath().equals(path)) {
                    return;
                }

                mBackgroundLoader.submit(new Runnable() {
                    @Override public void run() {
                        final FilePlayer player = new FilePlayer(path, numBytes, mPreAmpGain, gain, peakAmp);
                        if (!player.prepare()) {
                            return;
                        }
                        mHandler.post(new Runnable() {
                            @Override public void run() {
                                Log.d(TAG, "finished preparing queued file " + path);
                                resetQueued();
                                mQueuedPlayer = player;
                                mQueuedPlayer.setLowVolume(mLowVolume);
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

                if (mCurrentPlayer != null) {
                    if (mPaused) {
                        mCurrentPlayer.getMediaPlayer().pause();
                        stopPositionTimer();
                    } else {
                        // If the file is already fully loaded, play from it instead of a stream.
                        boolean switchedToFile = false;
                        if (mCurrentPlayer.getStream() != null && mCurrentPlayer.getFileBytes() == mCurrentPlayer.getNumBytes()) {
                            try {
                                mCurrentPlayer.restartPlayback();
                                switchedToFile = true;
                            } catch (IOException e) {
                                Log.e(TAG, "got error while trying to switch to file on unpause: " + e);
                            }
                        }
                        if (!switchedToFile) {
                            mCurrentPlayer.getMediaPlayer().start();
                        }
                        startPositionTimer();
                    }
                }
                mListenerHandler.post(new Runnable() {
                    @Override public void run() {
                        mListener.onPauseStateChange(mPaused);
                    }
                });
            }
        });
    }

    public void setLowVolume(final boolean lowVolume) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mLowVolume == lowVolume) {
                    return;
                }

                mLowVolume = lowVolume;
                if (mCurrentPlayer != null) {
                    mCurrentPlayer.setLowVolume(lowVolume);
                }
                if (mQueuedPlayer != null) {
                    mQueuedPlayer.setLowVolume(lowVolume);
                }
            }
        });
    }

    public void setPreAmpGain(final double preAmpGain) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mPreAmpGain == preAmpGain) {
                    return;
                }

                mPreAmpGain = preAmpGain;
                if (mCurrentPlayer != null) {
                    mCurrentPlayer.setPreAmpGain(preAmpGain);
                }
                if (mQueuedPlayer != null) {
                    mQueuedPlayer.setPreAmpGain(preAmpGain);
                }
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
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mCurrentPlayer == null || mCurrentPlayer.getMediaPlayer() != player) {
                    return;
                }
                try {
                    // TODO: Any way to get the current position when we're playing a path rather
                    // than an FD? May be unnecessary if playing a path actually doesn't have buffer
                    // underruns that result in premature end of playback being reported.
                    final long streamPosition = mCurrentPlayer.getStream() != null
                        ? mCurrentPlayer.getStream().getChannel().position()
                        : mCurrentPlayer.getNumBytes();
                    final long numBytes = mCurrentPlayer.getNumBytes();
                    Log.d(TAG, player + " completed playback at " + streamPosition + " of " + numBytes);

                    if (streamPosition < numBytes && mCurrentPlayer.getStream() != null) {
                        final boolean switchedToFile = mCurrentPlayer.restartPlayback();
                        mListenerHandler.post(new Runnable() {
                            @Override public void run() {
                                mListener.onPlaybackError(
                                        switchedToFile ?
                                        "Switched to file after buffer underrun" :
                                        "Reloaded stream after buffer underrun");
                            }
                        });
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
        });
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
