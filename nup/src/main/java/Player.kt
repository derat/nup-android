/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Plays music files. */
class Player(
    private val context: Context,
    private val listener: Listener,
    private val listenerHandler: Handler,
    private val attrs: AudioAttributes,
) : MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private var currentPlayer: FilePlayer? = null
    private var queuedPlayer: FilePlayer? = null
    private var paused = false

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val threadChecker = ThreadChecker(executor)

    /** Used to observe playback-related events. */
    interface Listener {
        /** Called on completion of the currently-playing file. */
        fun onPlaybackComplete()
        /** Called when the playback position of the current file changes. */
        fun onPlaybackPositionChange(file: File, positionMs: Int, durationMs: Int)
        /** Called when the pause state changes. */
        fun onPauseStateChange(paused: Boolean)
        /** Called when an error occurs. */
        fun onPlaybackError(description: String)
    }

    /** Wraps an individual audio file. */
    private inner class FilePlayer(
        val file: File,
        val totalBytes: Long,
        val songGain: Double,
        val peakAmp: Double,
    ) {
        val mediaPlayer = MediaPlayer()
        lateinit var loudnessEnhancer: LoudnessEnhancer

        // Used only if we haven't downloaded the complete file, since MediaPlayer
        // skips all the time when playing from a stream.
        var stream: FileInputStream? = null
            private set

        /** Current size of [file]. */
        val fileBytes: Long
            get() = file.length()

        /**
         * Initialize the object.
         *
         * This must be called after constructing the [FilePlayer]. If it returns false,
         * initialization was unsuccessful the player should be discarded.
         */
        fun prepare(): Boolean {
            threadChecker.assertThread()
            return try {
                val curBytes = fileBytes
                Log.d(TAG, "Created $mediaPlayer for ${file.name} (have $curBytes of $totalBytes)")
                mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                mediaPlayer.setAudioAttributes(attrs)
                mediaPlayer.setOnCompletionListener(this@Player)
                mediaPlayer.setOnErrorListener(this@Player)

                // TODO: Passing MediaPlayer a path rather than an FD seems to maybe make it finally
                // stop skipping. Investigate whether it's possible to also pass a path even when
                // the file isn't fully downloaded yet. I think I tried this before and it made it
                // stop when it got to the initial end of the file, even if more had been downloaded
                // by then, though.
                if (curBytes == totalBytes) {
                    mediaPlayer.setDataSource(file.path)
                } else {
                    stream = FileInputStream(file)
                    mediaPlayer.setDataSource(stream!!.fd)
                }
                mediaPlayer.prepare()

                // This should happen after preparing the MediaPlayer,
                // per https://stackoverflow.com/q/23342655/6882947.
                loudnessEnhancer = LoudnessEnhancer(mediaPlayer.audioSessionId)
                adjustVolume()
                true
            } catch (e: IOException) {
                close()
                listenerHandler.post { listener.onPlaybackError("Unable to prepare $file: $e") }
                false
            }
        }

        /** Release resources. */
        fun close() {
            threadChecker.assertThread()
            try {
                mediaPlayer.release()
                loudnessEnhancer.release()
                stream?.close()
            } catch (e: IOException) {
                listenerHandler.post { listener.onPlaybackError("Unable to close $file: $e") }
            }
        }

        /** Adjust [mediaPlayer]'s volume for current state. */
        fun adjustVolume() {
            threadChecker.assertThread()

            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_specification
            var pct = Math.pow(10.0, (preAmpGain + songGain) / 20)
            if (lowVolume) pct *= LOW_VOLUME_FRACTION.toDouble()
            if (peakAmp > 0) pct = Math.min(pct, 1 / peakAmp)
            Log.d(TAG, "Setting $mediaPlayer volume to ${String.format("%.3f", pct)}")

            // Hooray: MediaPlayer only accepts volumes in the range [0.0, 1.0],
            // while LoudnessEnhancer seems to only accept positive gains (in mB).
            if (pct <= 1) {
                mediaPlayer.setVolume(pct.toFloat(), pct.toFloat())
                loudnessEnhancer.setTargetGain(0)
            } else {
                mediaPlayer.setVolume(1f, 1f)
                val gainmB = Math.round(Math.log10(pct) * 2000).toInt()
                loudnessEnhancer.setTargetGain(gainmB)
            }
        }

        /**
         * Restart playback after a buffer underrun.
         *
         * @return true if we switched to the complete file
         */
        @Throws(IOException::class)
        fun restartPlayback(): Boolean {
            threadChecker.assertThread()

            val curPositionMs = mediaPlayer.currentPosition
            Log.w(TAG, "Restarting $mediaPlayer at $curPositionMs ms")
            var switchedToFile = false
            mediaPlayer.reset()
            if (fileBytes == totalBytes) {
                Log.w(TAG, "Switching $mediaPlayer from stream to complete file")
                stream?.close()
                stream = null
                mediaPlayer.setDataSource(file.path)
                switchedToFile = true
            } else {
                Log.w(TAG, "Setting $mediaPlayer to reuse current stream")
                mediaPlayer.setDataSource(stream!!.fd)
            }

            mediaPlayer.prepare()
            mediaPlayer.seekTo(curPositionMs)
            mediaPlayer.start()
            return switchedToFile
        }
    }

    /** Shut down the player. */
    fun quit() {
        executor.submit {
            resetCurrent()
            resetQueued()
        }
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Stop playing the current song (if any). */
    fun abortPlayback() {
        executor.submit { resetCurrent() }
    }

    /** Start playback of [file]. */
    fun playFile(file: File, totalBytes: Long, gain: Double, peakAmp: Double) {
        executor.submit {
            Log.d(TAG, "Playing $file")
            resetCurrent()
            if (queuedPlayer?.file == file) {
                Log.d(TAG, "Using queued player")
                currentPlayer = queuedPlayer
                queuedPlayer = null
            } else {
                currentPlayer = FilePlayer(file, totalBytes, gain, peakAmp)
                if (!currentPlayer!!.prepare()) currentPlayer = null
            }
            if (currentPlayer != null && !paused) {
                currentPlayer!!.mediaPlayer.start()
                startPositionTimer()
            }
        }
    }

    /** Queue [file] for future playback. */
    fun queueFile(file: File, totalBytes: Long, gain: Double, peakAmp: Double) {
        executor.submit task@{
            if (queuedPlayer?.file == file) return@task

            Log.d(TAG, "Queuing $file")
            val player = FilePlayer(file, totalBytes, gain, peakAmp)
            if (!player.prepare()) return@task

            Log.d(TAG, "Finished preparing $file")
            resetQueued()
            queuedPlayer = player
        }
    }

    /** Reset [currentPlayer]. */
    private fun resetCurrent() {
        threadChecker.assertThread()
        stopPositionTimer()
        currentPlayer?.close()
        currentPlayer = null
    }

    /** Reset [queuedPlayer]. */
    private fun resetQueued() {
        threadChecker.assertThread()
        queuedPlayer?.close()
        queuedPlayer = null
    }

    /** Pause the current song. */
    fun pause() { updatePauseState(PauseUpdateType.PAUSE) }
    /** Unpause the current song. */
    fun unpause() { updatePauseState(PauseUpdateType.UNPAUSE) }
    /** Toggle the current song's paused state. */
    fun togglePause() { updatePauseState(PauseUpdateType.TOGGLE_PAUSE) }

    private enum class PauseUpdateType { PAUSE, UNPAUSE, TOGGLE_PAUSE }

    private fun updatePauseState(type: PauseUpdateType) {
        executor.submit task@{
            val player = currentPlayer ?: return@task

            val newPaused = when (type) {
                PauseUpdateType.PAUSE -> true
                PauseUpdateType.UNPAUSE -> false
                PauseUpdateType.TOGGLE_PAUSE -> !paused
            }
            if (newPaused == paused) return@task

            paused = newPaused
            if (paused) {
                player.mediaPlayer.pause()
                stopPositionTimer()
            } else {
                // If the file is already fully loaded, play from it instead of a stream.
                var switchedToFile = false
                if (player.stream != null && player.fileBytes == player.totalBytes) {
                    try {
                        player.restartPlayback()
                        switchedToFile = true
                    } catch (e: IOException) {
                        Log.e(TAG, "Got error switching to file on unpause: $e")
                    }
                }
                if (!switchedToFile) player.mediaPlayer.start()
                startPositionTimer()
            }
            listenerHandler.post { listener.onPauseStateChange(paused) }
        }
    }

    /** Reduce playback volume (e.g. during a phone call). */
    var lowVolume: Boolean = false
        set(value) {
            executor.submit {
                if (value != lowVolume) {
                    field = value
                    currentPlayer?.adjustVolume()
                    queuedPlayer?.adjustVolume()
                }
            }
        }

    /** Adjust playback volume up or down by specified decibels. */
    var preAmpGain: Double = 0.0
        set(value) {
            executor.submit {
                if (value != preAmpGain) {
                    field = value
                    currentPlayer?.adjustVolume()
                    queuedPlayer?.adjustVolume()
                }
            }
        }

    /** Periodically invoked to notify [listener] about playback position of current song. */
    private val positionTask = task@{
        threadChecker.assertThread()
        currentPlayer ?: return@task
        val player = currentPlayer!!
        val file = player.file
        val positionMs = player.mediaPlayer.currentPosition
        val durationMs = player.mediaPlayer.duration
        listenerHandler.post { listener.onPlaybackPositionChange(file, positionMs, durationMs) }
    }
    private var positionFuture: ScheduledFuture<*>? = null

    /** Start running [positionTask]. */
    private fun startPositionTimer() {
        threadChecker.assertThread()
        stopPositionTimer()
        positionFuture = executor.scheduleAtFixedRate(
            positionTask, 0, POSITION_CHANGE_REPORT_MS, TimeUnit.MILLISECONDS
        )
    }

    /** Stop running [positionTask]. */
    private fun stopPositionTimer() {
        threadChecker.assertThread()
        positionFuture?.cancel(false)
        positionFuture = null
    }

    override fun onCompletion(player: MediaPlayer) {
        executor.submit task@{
            if (currentPlayer?.mediaPlayer !== player) return@task

            val filePlayer = currentPlayer!!
            try {
                // TODO: Any way to get the current position when we're playing a path
                // rather than an FD? May be unnecessary if playing a path actually
                // doesn't have buffer underruns that result in premature end of
                // playback being reported.
                val streamPos =
                    if (filePlayer.stream != null) filePlayer.stream!!.channel.position()
                    else filePlayer.totalBytes
                val totalBytes = filePlayer.totalBytes
                Log.d(TAG, "$player completed playback at $streamPos of $totalBytes")
                if (streamPos < totalBytes && filePlayer.stream != null) {
                    val switchedToFile = filePlayer.restartPlayback()
                    listenerHandler.post {
                        listener.onPlaybackError(
                            if (switchedToFile) "Switched to file after buffer underrun"
                            else "Reloaded stream after buffer underrun"
                        )
                    }
                } else {
                    resetCurrent()
                    listenerHandler.post { listener.onPlaybackComplete() }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Got error while handling completion of $player: $e")
            }
        }
    }

    override fun onError(player: MediaPlayer, what: Int, extra: Int): Boolean {
        listenerHandler.post {
            listener.onPlaybackError(
                "MediaPlayer reported a vague, not-very-useful error: what=$what extra=$extra"
            )
        }
        return false // let completion listener get called
    }

    companion object {
        private const val TAG = "Player"
        private const val POSITION_CHANGE_REPORT_MS = 100L
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
        private const val LOW_VOLUME_FRACTION = 0.2f // in [0.0, 1.0]
    }
}
