// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class Player implements Runnable, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private static final String TAG = "Player";

    // Interval between reports to |listener|.
    private static final int POSITION_CHANGE_REPORT_MS = 100;

    private static final int SHUTDOWN_TIMEOUT_MS = 1000;

    // Reduced volume level to use, in the range [0.0, 1.0].
    private static final float LOW_VOLUME_FRACTION = 0.2f;

    interface Listener {
        // Invoked on completion of the currently-playing file.
        void onPlaybackComplete();

        // Invoked when the playback position of the current file changes.
        void onPlaybackPositionChange(String path, int positionMs, int durationMs);

        // Invoked when the pause state changes.
        void onPauseStateChange(boolean paused);

        // Invoked when an error occurs.
        void onPlaybackError(String description);
    }

    private Context context;

    // Currently-playing and queued songs.
    private FilePlayer currentPlayer;
    private FilePlayer queuedPlayer;

    // Is playback paused?
    private boolean paused = false;

    // Is the volume currently lowered?
    private boolean lowVolume = false;

    // Pre-amp gain adjustment in decibels.
    private double preAmpGain = 0;

    // Used to run tasks on our own thread.
    private Handler handler;

    // Notified when things change.
    private final Listener listener;

    // Used to post tasks to |listener|.
    private final Handler listenerHandler;

    // Attributes describing audio files to be played.
    private final AudioAttributes attrs;

    // Used to load queued files in the background.
    private final ExecutorService backgroundLoader = Executors.newSingleThreadExecutor();

    private boolean shouldQuit = false;

    private enum PauseUpdateType {
        PAUSE,
        UNPAUSE,
        TOGGLE_PAUSE,
    }

    /** Wraps an individual file. */
    private class FilePlayer {
        private final String path; // File containing song.
        private final long numBytes; // Total expected size of song (path may be incomplete).
        private final double songGain; // Song-specific gain adjustment in decibels.
        private final double peakAmp; // Song's peak amplitude (1.0 max without clipping).

        // Ideally only used if we haven't downloaded the complete file, since MediaPlayer
        // skips all the time when playing from a stream.
        private FileInputStream stream;

        private MediaPlayer player;
        private AudioAttributes attrs;
        private LoudnessEnhancer loudnessEnhancer;

        private boolean lowVolume = false; // True if the song's volume is temporarily lowered.
        private double preAmpGain = 0; // Pre-amp gain adjustment in decibels.

        public FilePlayer(
                String path,
                long numBytes,
                AudioAttributes attrs,
                double preAmpGain,
                double songGain,
                double peakAmp) {
            this.path = path;
            this.numBytes = numBytes;
            this.attrs = attrs;
            this.songGain = songGain;
            this.peakAmp = peakAmp;
            this.preAmpGain = preAmpGain;
        }

        public String getPath() {
            return path;
        }

        // Returns the current size of |path|.
        public long getFileBytes() {
            return (new File(path)).length();
        }

        // Returns the total expected size of the song.
        public long getNumBytes() {
            return numBytes;
        }

        public FileInputStream getStream() {
            return stream;
        }

        public MediaPlayer getMediaPlayer() {
            return player;
        }

        public boolean prepare() {
            try {
                final long currentBytes = (new File(path)).length();
                player = new MediaPlayer();
                Log.d(
                        TAG,
                        "created "
                                + player
                                + " for "
                                + path
                                + " (have "
                                + currentBytes
                                + " of "
                                + numBytes
                                + ")");

                player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
                player.setAudioAttributes(attrs);
                player.setOnCompletionListener(Player.this);
                player.setOnErrorListener(Player.this);

                // TODO: I am totally jinxing this, but passing MediaPlayer a path rather than an FD
                // seems to maybe make it finally stop skipping. If this is really the case,
                // investigate whether it's possible to also pass a path even when the file isn't
                // fully downloaded yet. I think I tried this before and it made it stop when it got
                // to the initial end of the file, even if more had been downloaded by then, though.
                if (currentBytes == numBytes) {
                    player.setDataSource(path);
                } else {
                    stream = new FileInputStream(path);
                    player.setDataSource(stream.getFD());
                }
                player.prepare();

                // This should happen after preparing the MediaPlayer,
                // per https://stackoverflow.com/q/23342655/6882947.
                loudnessEnhancer = new LoudnessEnhancer(player.getAudioSessionId());
                adjustVolume();

                return true;
            } catch (final IOException e) {
                close();
                listenerHandler.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                listener.onPlaybackError("Unable to prepare " + path + ": " + e);
                            }
                        });
                return false;
            }
        }

        public void close() {
            try {
                if (player != null) {
                    player.release();
                    loudnessEnhancer.release();
                }
                if (stream != null) {
                    stream.close();
                }
            } catch (final IOException e) {
                listenerHandler.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                listener.onPlaybackError("Unable to close " + path + ": " + e);
                            }
                        });
            }
        }

        public void setLowVolume(boolean lowVolume) {
            this.lowVolume = lowVolume;
            adjustVolume();
        }

        public void setPreAmpGain(double preAmpGain) {
            this.preAmpGain = preAmpGain;
            adjustVolume();
        }

        // Adjusts |mPlayer|'s volume appropriately.
        private void adjustVolume() {
            if (player == null || loudnessEnhancer == null) {
                return;
            }

            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_specification
            double pct = Math.pow(10, (preAmpGain + songGain) / 20);
            if (lowVolume) {
                pct *= LOW_VOLUME_FRACTION;
            }
            if (peakAmp > 0) {
                pct = Math.min(pct, 1 / peakAmp);
            }

            Log.d(TAG, "setting " + path + " volume to " + pct);

            // Hooray: MediaPlayer only accepts volumes in the range [0.0, 1.0],
            // while LoudnessEnhancer seems to only accept positive gains (in mB).
            if (pct <= 1) {
                player.setVolume((float) pct, (float) pct);
                loudnessEnhancer.setTargetGain(0);
            } else {
                player.setVolume(1, 1);
                int gainmB = (int) Math.round(Math.log10(pct) * 2000);
                loudnessEnhancer.setTargetGain(gainmB);
            }
        }

        // Restarts playback after a buffer underrun.
        // Returns true if we switched to the complete file.
        public boolean restartPlayback() throws IOException {
            final int currentPositionMs = player.getCurrentPosition();
            final long currentBytes = getFileBytes();
            Log.w(TAG, "restarting " + player + " at " + currentPositionMs + " ms");

            boolean switchedToFile = false;
            player.reset();

            if (currentBytes == numBytes) {
                Log.w(TAG, "switching " + player + " from stream to complete file " + path);
                stream.close();
                stream = null;
                player.setDataSource(path);
                switchedToFile = true;
            } else {
                Log.w(TAG, "setting " + player + " to reuse current stream");
                player.setDataSource(getStream().getFD());
            }

            player.prepare();
            player.seekTo(currentPositionMs);
            player.start();
            return switchedToFile;
        }
    }

    public Player(
            Context context,
            Listener listener,
            Handler listenerHandler,
            AudioAttributes attrs,
            double preAmpGain) {
        this.context = context;
        this.listener = listener;
        this.listenerHandler = listenerHandler;
        this.attrs = attrs;
        this.preAmpGain = preAmpGain;
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (this) {
            if (shouldQuit) return;
            handler = new Handler();
        }
        Looper.loop();
    }

    public void quit() {
        synchronized (this) {
            // The thread hasn't started looping yet; tell it to exit before starting.
            if (handler == null) {
                shouldQuit = true;
                return;
            }
        }
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        backgroundLoader.shutdownNow();
                        try {
                            backgroundLoader.awaitTermination(
                                    SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                        }
                        resetCurrent();
                        resetQueued();
                        Looper.myLooper().quit();
                    }
                });
    }

    public void abortPlayback() {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        resetCurrent();
                    }
                });
    }

    public void playFile(
            final String path, final long numBytes, final double gain, final double peakAmp) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "got request to play " + path);
                        resetCurrent();
                        if (queuedPlayer != null && queuedPlayer.getPath().equals(path)) {
                            Log.d(TAG, "using queued " + queuedPlayer.getMediaPlayer());
                            currentPlayer = queuedPlayer;
                            queuedPlayer = null;
                        } else {
                            currentPlayer =
                                    new FilePlayer(
                                            path, numBytes, attrs, preAmpGain, gain, peakAmp);
                            if (!currentPlayer.prepare()) {
                                currentPlayer = null;
                                return;
                            }
                            currentPlayer.setLowVolume(lowVolume);
                        }

                        if (!paused) {
                            currentPlayer.getMediaPlayer().start();
                            startPositionTimer();
                        }
                    }
                });
    }

    public void queueFile(
            final String path, final long numBytes, final double gain, final double peakAmp) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "got request to queue " + path);
                        if (queuedPlayer != null && queuedPlayer.getPath().equals(path)) {
                            return;
                        }

                        backgroundLoader.submit(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        final FilePlayer player =
                                                new FilePlayer(
                                                        path,
                                                        numBytes,
                                                        attrs,
                                                        preAmpGain,
                                                        gain,
                                                        peakAmp);
                                        if (!player.prepare()) {
                                            return;
                                        }
                                        handler.post(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        Log.d(
                                                                TAG,
                                                                "finished preparing queued file "
                                                                        + path);
                                                        resetQueued();
                                                        queuedPlayer = player;
                                                        queuedPlayer.setLowVolume(lowVolume);
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
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        switch (type) {
                            case PAUSE:
                                if (paused) return;
                                paused = true;
                                break;
                            case UNPAUSE:
                                if (!paused) return;
                                paused = false;
                                break;
                            case TOGGLE_PAUSE:
                                paused = !paused;
                                break;
                        }

                        if (currentPlayer != null) {
                            if (paused) {
                                currentPlayer.getMediaPlayer().pause();
                                stopPositionTimer();
                            } else {
                                // If the file is already fully loaded, play from it instead of a
                                // stream.
                                boolean switchedToFile = false;
                                if (currentPlayer.getStream() != null
                                        && currentPlayer.getFileBytes()
                                                == currentPlayer.getNumBytes()) {
                                    try {
                                        currentPlayer.restartPlayback();
                                        switchedToFile = true;
                                    } catch (IOException e) {
                                        Log.e(
                                                TAG,
                                                "got error while trying to switch to file on"
                                                        + " unpause: "
                                                        + e);
                                    }
                                }
                                if (!switchedToFile) {
                                    currentPlayer.getMediaPlayer().start();
                                }
                                startPositionTimer();
                            }
                        }
                        listenerHandler.post(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        listener.onPauseStateChange(paused);
                                    }
                                });
                    }
                });
    }

    public void setLowVolume(final boolean lowVolume) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (lowVolume == Player.this.lowVolume) return;

                        Player.this.lowVolume = lowVolume;
                        if (currentPlayer != null) currentPlayer.setLowVolume(lowVolume);
                        if (queuedPlayer != null) queuedPlayer.setLowVolume(lowVolume);
                    }
                });
    }

    public void setPreAmpGain(final double preAmpGain) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (preAmpGain == Player.this.preAmpGain) return;

                        Player.this.preAmpGain = preAmpGain;
                        if (currentPlayer != null) currentPlayer.setPreAmpGain(preAmpGain);
                        if (queuedPlayer != null) queuedPlayer.setPreAmpGain(preAmpGain);
                    }
                });
    }

    // Periodically invoked to notify the observer about the playback position of the current song.
    private Runnable positionTask =
            new Runnable() {
                public void run() {
                    if (currentPlayer == null) {
                        Log.w(TAG, "aborting position task; player is null");
                        return;
                    }
                    final String path = currentPlayer.getPath();
                    final int positionMs = currentPlayer.getMediaPlayer().getCurrentPosition();
                    final int durationMs = currentPlayer.getMediaPlayer().getDuration();
                    listenerHandler.post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    listener.onPlaybackPositionChange(path, positionMs, durationMs);
                                }
                            });
                    handler.postDelayed(this, POSITION_CHANGE_REPORT_MS);
                }
            };

    // Start running |mSongPositionTask|.
    private void startPositionTimer() {
        Util.assertOnLooper(handler.getLooper());
        stopPositionTimer();
        handler.post(positionTask);
    }

    // Stop running |mSongPositionTask|.
    private void stopPositionTimer() {
        Util.assertOnLooper(handler.getLooper());
        handler.removeCallbacks(positionTask);
    }

    // Implements MediaPlayer.OnCompletionListener.
    @Override
    public void onCompletion(final MediaPlayer player) {
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (currentPlayer == null || currentPlayer.getMediaPlayer() != player) {
                            return;
                        }
                        try {
                            // TODO: Any way to get the current position when we're playing a path
                            // rather than an FD? May be unnecessary if playing a path actually
                            // doesn't have buffer underruns that result in premature end of
                            // playback being reported.
                            final long streamPosition =
                                    currentPlayer.getStream() != null
                                            ? currentPlayer.getStream().getChannel().position()
                                            : currentPlayer.getNumBytes();
                            final long numBytes = currentPlayer.getNumBytes();
                            Log.d(
                                    TAG,
                                    player
                                            + " completed playback at "
                                            + streamPosition
                                            + " of "
                                            + numBytes);

                            if (streamPosition < numBytes && currentPlayer.getStream() != null) {
                                final boolean switchedToFile = currentPlayer.restartPlayback();
                                listenerHandler.post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                listener.onPlaybackError(
                                                        switchedToFile
                                                                ? "Switched to file after buffer"
                                                                        + " underrun"
                                                                : "Reloaded stream after buffer"
                                                                        + " underrun");
                                            }
                                        });
                            } else {
                                resetCurrent();
                                listenerHandler.post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                listener.onPlaybackComplete();
                                            }
                                        });
                            }
                        } catch (IOException e) {
                            Log.e(
                                    TAG,
                                    "got error while handling completion of " + player + ": " + e);
                        }
                    }
                });
    }

    // Implements MediaPlayer.OnErrorListener.
    @Override
    public boolean onError(MediaPlayer player, final int what, final int extra) {
        listenerHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        listener.onPlaybackError(
                                "MediaPlayer reported a vague, not-very-useful error: what="
                                        + what
                                        + " extra="
                                        + extra);
                    }
                });
        // Return false so the completion listener will get called.
        return false;
    }

    /** Resets |currentPlayer|. */
    private void resetCurrent() {
        Util.assertOnLooper(handler.getLooper());
        stopPositionTimer();
        if (currentPlayer != null) {
            currentPlayer.close();
            currentPlayer = null;
        }
    }

    /** Resets |queuedPlayer|. */
    private void resetQueued() {
        Util.assertOnLooper(handler.getLooper());
        if (queuedPlayer != null) {
            queuedPlayer.close();
            queuedPlayer = null;
        }
    }
}
