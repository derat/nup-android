// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.erat.nup.Player
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class Player(
        private val context: Context,
        // Notified when things change.
        private val listener: Listener,
        // Used to post tasks to |listener|.
        private val listenerHandler: Handler,
        // Attributes describing audio files to be played.
        private val attrs: AudioAttributes,
        preAmpGain: Double) : Runnable, OnCompletionListener, MediaPlayer.OnErrorListener {
    internal interface Listener {
        // Invoked on completion of the currently-playing file.
        fun onPlaybackComplete()

        // Invoked when the playback position of the current file changes.
        fun onPlaybackPositionChange(path: String?, positionMs: Int, durationMs: Int)

        // Invoked when the pause state changes.
        fun onPauseStateChange(paused: Boolean)

        // Invoked when an error occurs.
        fun onPlaybackError(description: String?)
    }

    // Currently-playing and queued songs.
    private var currentPlayer: FilePlayer? = null
    private var queuedPlayer: FilePlayer? = null

    // Is playback paused?
    private var paused = false

    // Is the volume currently lowered?
    private var lowVolume = false

    // Pre-amp gain adjustment in decibels.
    private var preAmpGain = 0.0

    // Used to run tasks on our own thread.
    private var handler: Handler? = null

    // Used to load queued files in the background.
    private val backgroundLoader = Executors.newSingleThreadExecutor()
    private var shouldQuit = false

    private enum class PauseUpdateType {
        PAUSE, UNPAUSE, TOGGLE_PAUSE
    }

    /** Wraps an individual file.  */
    private inner class FilePlayer(
            // File containing song.
            val path: String,
            // Returns the total expected size of the song.
            val numBytes // Total expected size of song (path may be incomplete).
            : Long,
            private val attrs: AudioAttributes,
            preAmpGain: Double,
            // Song-specific gain adjustment in decibels.
            private val songGain: Double,
            // Song's peak amplitude (1.0 max without clipping).
            private val peakAmp: Double) {

        // Ideally only used if we haven't downloaded the complete file, since MediaPlayer
        // skips all the time when playing from a stream.
        var stream: FileInputStream? = null
            private set
        var mediaPlayer: MediaPlayer? = null
            private set
        private var loudnessEnhancer: LoudnessEnhancer? = null
        private var lowVolume = false // True if the song's volume is temporarily lowered.
        private var preAmpGain = 0.0 // Pre-amp gain adjustment in decibels.

        // Returns the current size of |path|.
        val fileBytes: Long
            get() = File(path).length()

        fun prepare(): Boolean {
            return try {
                val currentBytes = File(path).length()
                mediaPlayer = MediaPlayer()
                Log.d(
                        TAG,
                        "created "
                                + mediaPlayer
                                + " for "
                                + path
                                + " (have "
                                + currentBytes
                                + " of "
                                + numBytes
                                + ")")
                mediaPlayer!!.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                mediaPlayer!!.setAudioAttributes(attrs)
                mediaPlayer!!.setOnCompletionListener(this@Player)
                mediaPlayer!!.setOnErrorListener(this@Player)

                // TODO: I am totally jinxing this, but passing MediaPlayer a path rather than an FD
                // seems to maybe make it finally stop skipping. If this is really the case,
                // investigate whether it's possible to also pass a path even when the file isn't
                // fully downloaded yet. I think I tried this before and it made it stop when it got
                // to the initial end of the file, even if more had been downloaded by then, though.
                if (currentBytes == numBytes) {
                    mediaPlayer!!.setDataSource(path)
                } else {
                    stream = FileInputStream(path)
                    mediaPlayer!!.setDataSource(stream!!.fd)
                }
                mediaPlayer!!.prepare()

                // This should happen after preparing the MediaPlayer,
                // per https://stackoverflow.com/q/23342655/6882947.
                loudnessEnhancer = LoudnessEnhancer(mediaPlayer!!.audioSessionId)
                adjustVolume()
                true
            } catch (e: IOException) {
                close()
                listenerHandler.post { listener.onPlaybackError("Unable to prepare $path: $e") }
                false
            }
        }

        fun close() {
            try {
                if (mediaPlayer != null) {
                    mediaPlayer!!.release()
                    loudnessEnhancer!!.release()
                }
                if (stream != null) {
                    stream!!.close()
                }
            } catch (e: IOException) {
                listenerHandler.post { listener.onPlaybackError("Unable to close $path: $e") }
            }
        }

        fun setLowVolume(lowVolume: Boolean) {
            this.lowVolume = lowVolume
            adjustVolume()
        }

        fun setPreAmpGain(preAmpGain: Double) {
            this.preAmpGain = preAmpGain
            adjustVolume()
        }

        // Adjusts |mPlayer|'s volume appropriately.
        private fun adjustVolume() {
            if (mediaPlayer == null || loudnessEnhancer == null) {
                return
            }

            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_specification
            var pct = Math.pow(10.0, (preAmpGain + songGain) / 20)
            if (lowVolume) {
                pct *= LOW_VOLUME_FRACTION.toDouble()
            }
            if (peakAmp > 0) {
                pct = Math.min(pct, 1 / peakAmp)
            }
            Log.d(TAG, "setting $path volume to $pct")

            // Hooray: MediaPlayer only accepts volumes in the range [0.0, 1.0],
            // while LoudnessEnhancer seems to only accept positive gains (in mB).
            if (pct <= 1) {
                mediaPlayer!!.setVolume(pct.toFloat(), pct.toFloat())
                loudnessEnhancer!!.setTargetGain(0)
            } else {
                mediaPlayer!!.setVolume(1f, 1f)
                val gainmB = Math.round(Math.log10(pct) * 2000).toInt()
                loudnessEnhancer!!.setTargetGain(gainmB)
            }
        }

        // Restarts playback after a buffer underrun.
        // Returns true if we switched to the complete file.
        @Throws(IOException::class)
        fun restartPlayback(): Boolean {
            val currentPositionMs = mediaPlayer!!.currentPosition
            val currentBytes = fileBytes
            Log.w(TAG, "restarting " + mediaPlayer + " at " + currentPositionMs + " ms")
            var switchedToFile = false
            mediaPlayer!!.reset()
            if (currentBytes == numBytes) {
                Log.w(TAG, "switching " + mediaPlayer + " from stream to complete file " + path)
                stream!!.close()
                stream = null
                mediaPlayer!!.setDataSource(path)
                switchedToFile = true
            } else {
                Log.w(TAG, "setting " + mediaPlayer + " to reuse current stream")
                mediaPlayer!!.setDataSource(stream!!.fd)
            }
            mediaPlayer!!.prepare()
            mediaPlayer!!.seekTo(currentPositionMs)
            mediaPlayer!!.start()
            return switchedToFile
        }

        init {
            this.preAmpGain = preAmpGain
        }
    }

    override fun run() {
        Looper.prepare()
        synchronized(this) {
            if (shouldQuit) return
            handler = Handler()
        }
        Looper.loop()
    }

    fun quit() {
        synchronized(this) {
            // The thread hasn't started looping yet; tell it to exit before starting.
            if (handler == null) {
                shouldQuit = true
                return
            }
        }
        handler!!.post {
            backgroundLoader.shutdownNow()
            try {
                backgroundLoader.awaitTermination(
                        SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
            }
            resetCurrent()
            resetQueued()
            Looper.myLooper()!!.quit()
        }
    }

    fun abortPlayback() {
        handler!!.post { resetCurrent() }
    }

    fun playFile(
            path: String, numBytes: Long, gain: Double, peakAmp: Double) {
        handler!!.post(
                Runnable {
                    Log.d(TAG, "got request to play $path")
                    resetCurrent()
                    if (queuedPlayer != null && queuedPlayer!!.path == path) {
                        Log.d(TAG, "using queued " + queuedPlayer!!.mediaPlayer)
                        currentPlayer = queuedPlayer
                        queuedPlayer = null
                    } else {
                        currentPlayer = FilePlayer(
                                path, numBytes, attrs, preAmpGain, gain, peakAmp)
                        if (!currentPlayer!!.prepare()) {
                            currentPlayer = null
                            return@Runnable
                        }
                        currentPlayer!!.setLowVolume(lowVolume)
                    }
                    if (!paused) {
                        currentPlayer!!.mediaPlayer!!.start()
                        startPositionTimer()
                    }
                })
    }

    fun queueFile(
            path: String, numBytes: Long, gain: Double, peakAmp: Double) {
        handler!!.post(
                Runnable {
                    Log.d(TAG, "got request to queue $path")
                    if (queuedPlayer != null && queuedPlayer!!.path == path) {
                        return@Runnable
                    }
                    backgroundLoader.submit(
                            Runnable {
                                val player = FilePlayer(
                                        path,
                                        numBytes,
                                        attrs,
                                        preAmpGain,
                                        gain,
                                        peakAmp)
                                if (!player.prepare()) {
                                    return@Runnable
                                }
                                handler!!.post {
                                    Log.d(
                                            TAG, "finished preparing queued file "
                                            + path)
                                    resetQueued()
                                    queuedPlayer = player
                                    queuedPlayer!!.setLowVolume(lowVolume)
                                }
                            })
                })
    }

    fun pause() {
        updatePauseState(PauseUpdateType.PAUSE)
    }

    fun unpause() {
        updatePauseState(PauseUpdateType.UNPAUSE)
    }

    fun togglePause() {
        updatePauseState(PauseUpdateType.TOGGLE_PAUSE)
    }

    private fun updatePauseState(type: PauseUpdateType) {
        handler!!.post(
                Runnable {
                    paused = when (type) {
                        PauseUpdateType.PAUSE -> {
                            if (paused) return@Runnable
                            true
                        }
                        PauseUpdateType.UNPAUSE -> {
                            if (!paused) return@Runnable
                            false
                        }
                        PauseUpdateType.TOGGLE_PAUSE -> !paused
                    }
                    if (currentPlayer != null) {
                        if (paused) {
                            currentPlayer!!.mediaPlayer!!.pause()
                            stopPositionTimer()
                        } else {
                            // If the file is already fully loaded, play from it instead of a
                            // stream.
                            var switchedToFile = false
                            if (currentPlayer!!.stream != null
                                    && currentPlayer!!.fileBytes
                                    == currentPlayer!!.numBytes) {
                                try {
                                    currentPlayer!!.restartPlayback()
                                    switchedToFile = true
                                } catch (e: IOException) {
                                    Log.e(
                                            TAG,
                                            "got error while trying to switch to file on"
                                                    + " unpause: "
                                                    + e)
                                }
                            }
                            if (!switchedToFile) {
                                currentPlayer!!.mediaPlayer!!.start()
                            }
                            startPositionTimer()
                        }
                    }
                    listenerHandler.post { listener.onPauseStateChange(paused) }
                })
    }

    fun setLowVolume(lowVolume: Boolean) {
        handler!!.post(
                Runnable {
                    if (lowVolume == this@Player.lowVolume) return@Runnable
                    this@Player.lowVolume = lowVolume
                    if (currentPlayer != null) currentPlayer!!.setLowVolume(lowVolume)
                    if (queuedPlayer != null) queuedPlayer!!.setLowVolume(lowVolume)
                })
    }

    fun setPreAmpGain(preAmpGain: Double) {
        handler!!.post(
                Runnable {
                    if (preAmpGain == this@Player.preAmpGain) return@Runnable
                    this@Player.preAmpGain = preAmpGain
                    if (currentPlayer != null) currentPlayer!!.setPreAmpGain(preAmpGain)
                    if (queuedPlayer != null) queuedPlayer!!.setPreAmpGain(preAmpGain)
                })
    }

    // Periodically invoked to notify the observer about the playback position of the current song.
    private val positionTask: Runnable = object : Runnable {
        override fun run() {
            if (currentPlayer == null) {
                Log.w(TAG, "aborting position task; player is null")
                return
            }
            val path = currentPlayer!!.path
            val positionMs = currentPlayer!!.mediaPlayer!!.currentPosition
            val durationMs = currentPlayer!!.mediaPlayer!!.duration
            listenerHandler.post { listener.onPlaybackPositionChange(path, positionMs, durationMs) }
            handler!!.postDelayed(this, POSITION_CHANGE_REPORT_MS.toLong())
        }
    }

    // Start running |mSongPositionTask|.
    private fun startPositionTimer() {
        Util.assertOnLooper(handler!!.looper)
        stopPositionTimer()
        handler!!.post(positionTask)
    }

    // Stop running |mSongPositionTask|.
    private fun stopPositionTimer() {
        Util.assertOnLooper(handler!!.looper)
        handler!!.removeCallbacks(positionTask)
    }

    // Implements MediaPlayer.OnCompletionListener.
    override fun onCompletion(player: MediaPlayer) {
        handler!!.post(
                Runnable {
                    if (currentPlayer == null || currentPlayer!!.mediaPlayer !== player) {
                        return@Runnable
                    }
                    try {
                        // TODO: Any way to get the current position when we're playing a path
                        // rather than an FD? May be unnecessary if playing a path actually
                        // doesn't have buffer underruns that result in premature end of
                        // playback being reported.
                        val streamPosition = if (currentPlayer!!.stream != null) currentPlayer!!.stream!!.channel.position() else currentPlayer!!.numBytes
                        val numBytes = currentPlayer!!.numBytes
                        Log.d(
                                TAG,
                                player
                                        .toString() + " completed playback at "
                                        + streamPosition
                                        + " of "
                                        + numBytes)
                        if (streamPosition < numBytes && currentPlayer!!.stream != null) {
                            val switchedToFile = currentPlayer!!.restartPlayback()
                            listenerHandler.post {
                                listener.onPlaybackError(
                                        if (switchedToFile) "Switched to file after buffer"
                                                + " underrun" else "Reloaded stream after buffer"
                                                + " underrun")
                            }
                        } else {
                            resetCurrent()
                            listenerHandler.post { listener.onPlaybackComplete() }
                        }
                    } catch (e: IOException) {
                        Log.e(
                                TAG,
                                "got error while handling completion of $player: $e")
                    }
                })
    }

    // Implements MediaPlayer.OnErrorListener.
    override fun onError(player: MediaPlayer, what: Int, extra: Int): Boolean {
        listenerHandler.post {
            listener.onPlaybackError(
                    "MediaPlayer reported a vague, not-very-useful error: what="
                            + what
                            + " extra="
                            + extra)
        }
        // Return false so the completion listener will get called.
        return false
    }

    /** Resets |currentPlayer|.  */
    private fun resetCurrent() {
        Util.assertOnLooper(handler!!.looper)
        stopPositionTimer()
        if (currentPlayer != null) {
            currentPlayer!!.close()
            currentPlayer = null
        }
    }

    /** Resets |queuedPlayer|.  */
    private fun resetQueued() {
        Util.assertOnLooper(handler!!.looper)
        if (queuedPlayer != null) {
            queuedPlayer!!.close()
            queuedPlayer = null
        }
    }

    companion object {
        private const val TAG = "Player"

        // Interval between reports to |listener|.
        private const val POSITION_CHANGE_REPORT_MS = 100
        private const val SHUTDOWN_TIMEOUT_MS = 1000

        // Reduced volume level to use, in the range [0.0, 1.0].
        private const val LOW_VOLUME_FRACTION = 0.2f
    }

    init {
        this.preAmpGain = preAmpGain
    }
}