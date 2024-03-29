/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Plays music files. */
class Player(
    private val context: Context,
    private val listener: Listener,
    private val listenerExecutor: Executor,
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
        val trackGain: Double,
        val albumGain: Double,
        val peakAmp: Double,
    ) {
        val mediaPlayer = MediaPlayer()
        lateinit var loudnessEnhancer: LoudnessEnhancer

        // If we have the entire file downloaded, then we play it directly. If we only have part of
        // it, we play from a stream so we can check the current byte position in onCompletion() to
        // detect buffer underruns.
        //
        // We unfortunately can't always play from streams due to MediaPlayer apparently having
        // broken buffering code that skips incessantly from streams (but oddly not from files). We
        // can't always play from files since we don't have any way to detect buffer underruns in
        // onCompletion(). If we start playing from an incomplete file, MediaPlayer seems to only
        // play to the end of the original file length even if it grows while playing. I tried using
        // the duration to detect underruns, but it also seems to be wrong in some cases (likely
        // when playing files that don't have XING headers).
        //
        // TODO: Consider removing this code since we hopefully rarely/never start playing
        // incomplete files now: https://github.com/derat/nup-android/issues/18
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
                listenerExecutor.execute { listener.onPlaybackError("Unable to prepare $file: $e") }
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
                listenerExecutor.execute { listener.onPlaybackError("Unable to close $file: $e") }
            }
        }

        /** Adjust [mediaPlayer]'s volume for current state. */
        fun adjustVolume() {
            threadChecker.assertThread()

            val songGain: Double = when (gainType) {
                NupPreferences.GAIN_TYPE_AUTO -> if (shuffled) trackGain else albumGain
                NupPreferences.GAIN_TYPE_ALBUM -> albumGain
                NupPreferences.GAIN_TYPE_TRACK -> trackGain
                else -> 0.0
            }

            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_specification
            var pct = Math.pow(10.0, (preAmpGain + songGain) / 20.0)
            if (lowVolume) pct *= LOW_VOLUME_FRACTION.toDouble()
            if (peakAmp > 0) pct = Math.min(pct, 1 / peakAmp)
            Log.d(TAG, "Setting $mediaPlayer volume to ${String.format("%.3f", pct)}")

            // Sigh: MediaPlayer only accepts volumes in the range [0.0, 1.0],
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
    } // FilePlayer

    /** Shut down the player. */
    fun quit() {
        executor.execute {
            resetCurrent()
            resetQueued()
        }
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Stop playing the current song (if any). */
    fun abortPlayback() {
        executor.execute { resetCurrent() }
    }

    /**
     * Set [file] as the currently-playing song.
     *
     * The play/pause state is not changed. If we're not paused, the song will start playing.
     */
    fun loadFile(
        file: File,
        totalBytes: Long,
        trackGain: Double,
        albumGain: Double,
        peakAmp: Double
    ) {
        executor.execute {
            Log.d(TAG, "Playing $file")
            resetCurrent()
            if (queuedPlayer?.file == file) {
                Log.d(TAG, "Using queued player")
                currentPlayer = queuedPlayer
                queuedPlayer = null
            } else {
                currentPlayer = FilePlayer(file, totalBytes, trackGain, albumGain, peakAmp)
                if (!currentPlayer!!.prepare()) currentPlayer = null
            }
            if (currentPlayer != null && !paused) {
                currentPlayer!!.mediaPlayer.start()
                startPositionTask()
            }
        }
    }

    /** Queue [file] for future playback. */
    fun queueFile(
        file: File,
        totalBytes: Long,
        trackGain: Double,
        albumGain: Double,
        peakAmp: Double
    ) {
        executor.execute task@{
            if (queuedPlayer?.file == file) return@task

            Log.d(TAG, "Queuing $file")
            val player = FilePlayer(file, totalBytes, trackGain, albumGain, peakAmp)
            if (!player.prepare()) return@task

            Log.d(TAG, "Finished preparing $file")
            resetQueued()
            queuedPlayer = player
        }
    }

    /** Reset [currentPlayer]. */
    private fun resetCurrent() {
        threadChecker.assertThread()
        stopPositionTask()
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
        executor.execute task@{
            val newPaused = when (type) {
                PauseUpdateType.PAUSE -> true
                PauseUpdateType.UNPAUSE -> false
                PauseUpdateType.TOGGLE_PAUSE -> !paused
            }
            if (newPaused == paused) return@task

            paused = newPaused

            val player = currentPlayer
            if (player != null) {
                if (paused) {
                    player.mediaPlayer.pause()
                    stopPositionTask()
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
                    startPositionTask()
                }
            }

            listenerExecutor.execute { listener.onPauseStateChange(paused) }
        }
    }

    /** Seek to the specified position. */
    fun seek(posMs: Long) {
        executor.execute {
            Log.d(TAG, "Seeking to $posMs ms")
            currentPlayer?.mediaPlayer?.seekTo(posMs.toInt())
            reportPosition()
        }
    }

    /** Reduce playback volume (e.g. during a phone call). */
    var lowVolume: Boolean = false
        set(value) {
            executor.execute {
                if (value != lowVolume) {
                    field = value
                    adjustVolumes()
                }
            }
        }

    /** Type of gain adjustment to perform. See GAIN_TYPE_* in [NupPreferences]. */
    var gainType = NupPreferences.GAIN_TYPE_NONE
        set(value) {
            executor.execute {
                if (value != gainType) {
                    field = value
                    adjustVolumes()
                }
            }
        }

    /** Playback volume adjustment as positive or negative decibels. */
    var preAmpGain: Double = 0.0
        set(value) {
            executor.execute {
                if (value != preAmpGain) {
                    field = value
                    adjustVolumes()
                }
            }
        }

    /** Whether the songs being played were shuffled or not. */
    var shuffled = false
        set(value) {
            executor.execute {
                if (value != shuffled) {
                    field = value
                    if (gainType == NupPreferences.GAIN_TYPE_AUTO) adjustVolumes()
                }
            }
        }

    /** Notify [listener] about playback position of current song. */
    private fun reportPosition() {
        threadChecker.assertThread()
        val player = currentPlayer
        player ?: return

        val file = player.file
        val posMs = player.mediaPlayer.currentPosition
        val durMs = player.mediaPlayer.duration
        listenerExecutor.execute { listener.onPlaybackPositionChange(file, posMs, durMs) }
    }

    /** Call [reportPosition] and reschedule to run again after next second. */
    private val positionTask = object : Runnable {
        override fun run() {
            reportPosition()

            // Schedule ourselves to run again after the next second boundary is crossed.
            val posMs = currentPlayer?.mediaPlayer?.currentPosition ?: return
            val partMs = posMs.toLong() % 1000L
            val delayMs = (1000L + POSITION_CHANGE_DELAY_MS - partMs) % 1000L
            positionFuture = executor.schedule(this, delayMs, TimeUnit.MILLISECONDS)
        }
    }
    private var positionFuture: ScheduledFuture<*>? = null

    /** Start running [positionTask]. */
    private fun startPositionTask() {
        threadChecker.assertThread()
        stopPositionTask()
        positionTask.run() // also schedules future calls
    }

    /** Stop running [positionTask]. */
    private fun stopPositionTask() {
        threadChecker.assertThread()
        positionFuture?.cancel(false)
        positionFuture = null
    }

    private fun adjustVolumes() {
        threadChecker.assertThread()
        currentPlayer?.adjustVolume()
        queuedPlayer?.adjustVolume()
    }

    override fun onCompletion(player: MediaPlayer) {
        executor.execute task@{
            if (currentPlayer?.mediaPlayer !== player) return@task

            val filePlayer = currentPlayer!!
            try {
                val streamPos =
                    if (filePlayer.stream != null) filePlayer.stream!!.channel.position()
                    else filePlayer.totalBytes
                val totalBytes = filePlayer.totalBytes
                Log.d(TAG, "$player completed playback at $streamPos of $totalBytes bytes")
                if (streamPos < totalBytes && filePlayer.stream != null) {
                    val switchedToFile = filePlayer.restartPlayback()
                    listenerExecutor.execute {
                        listener.onPlaybackError(
                            if (switchedToFile) "Switched to file after buffer underrun"
                            else "Reloaded stream after buffer underrun"
                        )
                    }
                } else {
                    // Call onPlaybackPositionChange() one final time to say that we're at the end.
                    // Otherwise, we may show a not-quite-complete position at the end of the final
                    // track: https://github.com/derat/nup-android/issues/20
                    val file = filePlayer.file
                    val durationMs = filePlayer.mediaPlayer.duration
                    listenerExecutor.execute {
                        listener.onPlaybackPositionChange(file, durationMs, durationMs)
                    }
                    resetCurrent()
                    listenerExecutor.execute { listener.onPlaybackComplete() }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Got error while handling completion of $player: $e")
            }
        }
    }

    override fun onError(player: MediaPlayer, what: Int, extra: Int): Boolean {
        listenerExecutor.execute {
            listener.onPlaybackError(
                "MediaPlayer reported a vague, not-very-useful error: what=$what extra=$extra"
            )
        }
        return false // let completion listener get called
    }

    companion object {
        private const val TAG = "Player"
        private const val POSITION_CHANGE_DELAY_MS = 50L // delay after second to report position
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
        private const val LOW_VOLUME_FRACTION = 0.2f // in [0.0, 1.0]
    }
}
