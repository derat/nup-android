/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.session.MediaSession
import android.net.Uri
import android.os.AsyncTask
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.preference.PreferenceManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.Arrays
import java.util.Date
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NupService :
    Service(),
    CoroutineScope,
    Player.Listener,
    FileCache.Listener,
    SongDatabase.Listener {
    interface SongListener {
        /** Called when switching to a new track in the playlist. */
        fun onSongChange(song: Song, index: Int)
        /** Called when the playback position in the current song changes. */
        fun onSongPositionChange(song: Song, positionMs: Int, durationMs: Int)
        /** Called when playback is paused or unpaused. */
        fun onPauseStateChange(paused: Boolean)
        /** Called when the on-disk size of a song changes. */
        fun onSongFileSizeChange(song: Song)
        /** Called when the cover bitmap for a song is successfully loaded. */
        fun onSongCoverLoad(song: Song)
        /** Called when the current set of songs to be played changes. */
        fun onPlaylistChange(songs: List<Song>)
    }

    // Listener for the song database's aggregate data being updated.
    interface SongDatabaseUpdateListener {
        fun onSongDatabaseUpdate()
    }

    // Authenticates with Google Cloud Storage.
    private var authenticator: Authenticator? = null

    // Downloads files.
    private var downloader: Downloader? = null

    // Plays songs.
    private var player: Player? = null
    private var playerThread: Thread? = null

    // Caches songs.
    private var cache: FileCache? = null
    private var cacheThread: Thread? = null

    // Stores a local listing of all of the songs on the server.
    var songDb: SongDatabase? = null
        private set

    // Loads songs' cover art from local disk or the network.
    private var coverLoader: CoverLoader? = null

    // Reports song playback to the music server.
    private var playbackReporter: PlaybackReporter? = null

    // Publishes song metadata and handles remote commands.
    private var mediaSessionManager: MediaSessionManager? = null

    // Token identifying the session managed by |mMediaSessionManager|.
    private var mediaSessionToken: MediaSession.Token? = null

    // Updates the notification.
    private var notificationManager: NotificationManager? = null
    private var notificationCreator: NotificationCreator? = null
    private var networkHelper: NetworkHelper? = null

    // Points from song ID to Song.
    // This is the canonical set of songs that we've seen.
    private val songIdToSong = HashMap<Long, Song>()

    // ID of the song that's currently being downloaded.
    private var downloadSongId: Long = -1

    // Index into |mSongs| of the song that's currently being downloaded.
    private var downloadIndex = -1

    // Are we currently waiting for a file to be downloaded before we can play it?
    private var waitingForDownload = false

    // Have we temporarily been told to download all queued songs?
    private var shouldDownloadAll = false

    // Current playlist.
    private val songs = ArrayList<Song>()

    // Index of the song in |mSongs| that's being played.
    var currentSongIndex = -1
        private set

    // Time at which we started playing the current song.
    private var currentSongStartDate: Date? = null

    // Last playback position we were notified about for the current song.
    var currentSongLastPositionMs = 0
        private set

    // Total time during which we've played the current song, in milliseconds.
    private var currentSongPlayedMs: Long = 0

    // Local path of the song that's being played.
    private var currentSongPath: String? = null

    // Is playback currently paused?
    var paused = false
        private set

    // Are we done playing the current song?
    private var playbackComplete = false

    // Have we reported the fact that we've played the current song?
    private var reportedCurrentSong = false
    private val binder: IBinder = LocalBinder()

    // Songs whose covers are currently being fetched.
    private val songCoverFetches = HashSet<Song?>()

    // Songs whose covers we're currently keeping in memory.
    private val songsWithCovers: MutableList<Song?> = ArrayList()

    // Last time at which the user was foregrounded or backgrounded.
    private var lastUserSwitchTime: Date? = null

    // Used to run tasks on our thread.
    private val handler = Handler()
    private var audioManager: AudioManager? = null
    private var audioAttrs: AudioAttributes? = null
    private var telephonyManager: TelephonyManager? = null
    private var prefs: SharedPreferences? = null

    // Pause when phone calls arrive.
    private val phoneStateListener: PhoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            // I don't want to know who's calling.  Why isn't there a more-limited
            // permission?
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                player!!.pause()
                Toast.makeText(
                    this@NupService,
                    "Paused for incoming call.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                Log.d(TAG, "Audio becoming noisy")

                // Switching users apparently triggers an AUDIO_BECOMING_NOISY broadcast
                // intent (which usually indicates that headphones have been disconnected).
                // AudioManager.isWiredHeadsetOn() returns true when the notification is
                // sent due to headphones being unplugged (why?), so that doesn't seem to
                // help for ignoring this. Instead, ignore these intents for a brief period
                // after user-switch broadcast intents.
                val userSwitchedRecently = (lastUserSwitchTime == null) ||
                    (
                        Date().time - lastUserSwitchTime!!.time <=
                            IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS
                        )
                if (currentSongIndex >= 0 && !paused && !userSwitchedRecently) {
                    player!!.pause()
                    Toast.makeText(
                        this@NupService,
                        "Paused since unplugged.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (Intent.ACTION_USER_BACKGROUND == intent.action) {
                Log.d(TAG, "User is backgrounded")
                lastUserSwitchTime = Date()
            } else if (Intent.ACTION_USER_FOREGROUND == intent.action) {
                Log.d(TAG, "User is foregrounded")
                lastUserSwitchTime = Date()
            }
        }
    }

    private val audioFocusListener = OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Gained audio focus")
                player!!.setLowVolume(false)
            }
            AudioManager.AUDIOFOCUS_LOSS -> Log.d(TAG, "Lost audio focus")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Log.d(TAG, "Transiently lost audio focus")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Transiently lost audio focus (but can duck)")
                player!!.setLowVolume(true)
            }
            else -> Log.d(TAG, "Unhandled audio focus change $focusChange")
        }
    }

    private val prefsListener = OnSharedPreferenceChangeListener { prefs, key ->
        if (key == NupPreferences.PRE_AMP_GAIN) {
            val gain = prefs.getString(key, NupPreferences.PRE_AMP_GAIN_DEFAULT)!!.toDouble()
            player!!.setPreAmpGain(gain)
        }
    }

    private var songListener: SongListener? = null
    private val songDatabaseUpdateListeners = HashSet<SongDatabaseUpdateListener>()

    override fun onCreate() {
        Log.d(TAG, "service created")

        // It'd be nice to set this up before we do anything else, but getExternalFilesDir() blocks.
        // :-/
        object : AsyncTask<Void?, Void?, File>() {
            protected override fun doInBackground(vararg args: Void?): File {
                return File(getExternalFilesDir(null), CRASH_SUBDIRECTORY)
            }

            override fun onPostExecute(dir: File) {
                CrashLogger.register(dir)
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        networkHelper = NetworkHelper(this)
        authenticator = Authenticator(this)
        if (networkHelper!!.isNetworkAvailable) authenticateInBackground()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val result = audioManager!!.requestAudioFocus(
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setAudioAttributes(audioAttrs!!)
                .build()
        )
        Log.d(TAG, "requested audio focus; got $result")

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager!!.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        val filter = IntentFilter()
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        filter.addAction(Intent.ACTION_USER_BACKGROUND)
        filter.addAction(Intent.ACTION_USER_FOREGROUND)
        registerReceiver(broadcastReceiver, filter)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs!!.registerOnSharedPreferenceChangeListener(prefsListener)

        downloader = Downloader(authenticator!!, prefs!!)

        val gain = prefs!!.getString(
            NupPreferences.PRE_AMP_GAIN, NupPreferences.PRE_AMP_GAIN_DEFAULT
        )!!.toDouble()
        player = Player(this, this, handler, audioAttrs!!, gain)
        playerThread = Thread(player, "Player")
        playerThread!!.priority = Thread.MAX_PRIORITY
        playerThread!!.start()

        cache = FileCache(this, this, downloader!!, networkHelper!!)
        cacheThread = Thread(cache, "FileCache")
        cacheThread!!.start()

        songDb = SongDatabase(this, this, cache!!, downloader!!, networkHelper!!)

        coverLoader = CoverLoader(
            File(externalCacheDir!!, COVER_DIR_NAME),
            downloader!!,
            BitmapDecoder(),
            networkHelper!!,
        )

        playbackReporter = PlaybackReporter(songDb!!, downloader!!, networkHelper!!)
        if (networkHelper!!.isNetworkAvailable) {
            launch(Dispatchers.IO) { playbackReporter!!.reportPending() }
        }
        // TODO: Listen for the network coming up and send pending reports then too?

        mediaSessionManager = MediaSessionManager(
            this,
            object : MediaSession.Callback() {
                override fun onPause() { player!!.pause() }
                override fun onPlay() { player!!.unpause() }
                override fun onSkipToNext() { playSongAtIndex(currentSongIndex + 1) }
                override fun onSkipToPrevious() { playSongAtIndex(currentSongIndex - 1) }
                override fun onStop() { player!!.pause() }
            }
        )
        mediaSessionToken = mediaSessionManager!!.token

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationCreator = NotificationCreator(
            this,
            notificationManager!!,
            mediaSessionToken!!,
            PendingIntent.getActivity(this, 0, Intent(this, NupActivity::class.java), 0),
            PendingIntent.getService(
                this,
                0,
                Intent(ACTION_TOGGLE_PAUSE, Uri.EMPTY, this, NupService::class.java),
                0
            ),
            PendingIntent.getService(
                this,
                0,
                Intent(ACTION_PREV_TRACK, Uri.EMPTY, this, NupService::class.java),
                0
            ),
            PendingIntent.getService(
                this,
                0,
                Intent(ACTION_NEXT_TRACK, Uri.EMPTY, this, NupService::class.java),
                0
            )
        )
        val notification = notificationCreator!!.createNotification(
            false,
            currentSong,
            paused,
            playbackComplete,
            currentSongIndex,
            songs.size
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed")
        audioManager!!.abandonAudioFocus(audioFocusListener)
        prefs!!.unregisterOnSharedPreferenceChangeListener(prefsListener)
        telephonyManager!!.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        unregisterReceiver(broadcastReceiver)
        mediaSessionManager!!.cleanUp()
        songDb!!.quit()
        player!!.quit()
        cache!!.quit()
        playerThread!!.join()
        cacheThread!!.join()
        CrashLogger.unregister()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "received start id $startId: $intent")
        when (intent.action) {
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_NEXT_TRACK -> playSongAtIndex(currentSongIndex + 1)
            ACTION_PREV_TRACK -> playSongAtIndex(currentSongIndex - 1)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? { return binder }

    private var coroutineJob = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + coroutineJob

    fun getSongs(): List<Song> { return songs }

    val currentSong: Song?
        get() = if (currentSongIndex in 0..(songs.size - 1)) songs[currentSongIndex] else null
    val nextSong: Song?
        get() = if (currentSongIndex in 0..(songs.size - 2)) songs[currentSongIndex + 1] else null

    fun setSongListener(listener: SongListener?) { songListener = listener }

    fun addSongDatabaseUpdateListener(listener: SongDatabaseUpdateListener) {
        songDatabaseUpdateListeners.add(listener)
    }
    fun removeSongDatabaseUpdateListener(listener: SongDatabaseUpdateListener) {
        songDatabaseUpdateListeners.remove(listener)
    }

    fun getShouldDownloadAll(): Boolean { return shouldDownloadAll }
    fun setShouldDownloadAll(downloadAll: Boolean) {
        if (downloadAll == shouldDownloadAll) return
        shouldDownloadAll = downloadAll
        if (!songs.isEmpty() && downloadAll && downloadSongId == -1L) {
            maybeDownloadAnotherSong(if (currentSongIndex >= 0) currentSongIndex else 0)
        }
    }

    // Unregister an object that might be registered as one or more of our listeners.
    // Typically called when the object is an activity that's getting destroyed so
    // we'll drop our references to it.
    fun unregisterListener(`object`: Any) {
        if (songListener === `object`) songListener = null
    }

    inner class LocalBinder : Binder() {
        val service: NupService
            get() = this@NupService
    }

    /** Updates the currently-displayed notification if needed.  */
    private fun updateNotification() {
        val notification = notificationCreator!!.createNotification(
            true,
            currentSong,
            paused,
            playbackComplete,
            currentSongIndex,
            songs.size
        )
        if (notification != null) notificationManager!!.notify(NOTIFICATION_ID, notification)
    }

    fun togglePause() { player!!.togglePause() }
    fun pause() { player!!.pause() }

    fun clearPlaylist() { removeRangeFromPlaylist(0, songs.size - 1) }

    fun appendSongToPlaylist(song: Song) { appendSongsToPlaylist(ArrayList(Arrays.asList(song))) }
    fun appendSongsToPlaylist(newSongs: List<Song>) { insertSongs(newSongs, songs.size) }

    fun addSongToPlaylist(song: Song, play: Boolean) {
        addSongsToPlaylist(ArrayList(Arrays.asList(song)), play)
    }
    fun addSongsToPlaylist(newSongs: List<Song>, forcePlay: Boolean) {
        val index = currentSongIndex
        val alreadyPlayed = insertSongs(newSongs, if (index < 0) 0 else index + 1)
        if (forcePlay && !alreadyPlayed) playSongAtIndex(if (index < 0) 0 else index + 1)
    }

    fun removeFromPlaylist(index: Int) { removeRangeFromPlaylist(index, index) }

    // Remove a range of songs from the playlist.
    fun removeRangeFromPlaylist(firstIndex: Int, lastIndex: Int) {
        var firstIndex = firstIndex
        var lastIndex = lastIndex
        if (firstIndex < 0) firstIndex = 0
        if (lastIndex >= songs.size) lastIndex = songs.size - 1
        var removedPlaying = false
        for (numToRemove in lastIndex - firstIndex + 1 downTo 1) {
            songs.removeAt(firstIndex)
            if (currentSongIndex == firstIndex) {
                removedPlaying = true
                stopPlaying()
            } else if (currentSongIndex > firstIndex) {
                currentSongIndex--
            }
            if (downloadIndex == firstIndex) {
                cache!!.abortDownload(downloadSongId)
                downloadSongId = -1
                downloadIndex = -1
            } else if (downloadIndex > firstIndex) {
                downloadIndex--
            }
        }
        if (removedPlaying) {
            if (!songs.isEmpty() && currentSongIndex < songs.size) {
                playSongAtIndex(currentSongIndex)
            } else {
                currentSongIndex = -1
                mediaSessionManager!!.updateSong(null)
                updatePlaybackState()
            }
        }

        // Maybe the e.g. now-next-to-be-played song isn't downloaded yet.
        if (downloadSongId == -1L && !songs.isEmpty() && currentSongIndex < songs.size - 1) {
            maybeDownloadAnotherSong(currentSongIndex + 1)
        }
        songListener?.onPlaylistChange(songs)
        updateNotification()
        mediaSessionManager!!.updatePlaylist(songs)
    }

    // Play the song at a particular position in the playlist.
    fun playSongAtIndex(index: Int) {
        if (index < 0 || index >= songs.size) return
        currentSongIndex = index
        val song = currentSong
        currentSongStartDate = null
        currentSongLastPositionMs = 0
        currentSongPlayedMs = 0
        reportedCurrentSong = false
        playbackComplete = false
        cache!!.clearPinnedSongIds()

        // If we've already downloaded the whole file, start playing it.
        var cacheEntry = cache!!.getEntry(currentSong!!.id)
        if (cacheEntry != null && cacheEntry.isFullyCached) {
            Log.d(TAG, "file ${currentSong!!.url} already downloaded; playing")
            playCacheEntry(cacheEntry)

            // If we're downloading some other song (maybe we were downloading the
            // previously-being-played song), abort it.
            // TODO: This could actually be a future song that we were already
            // downloading and will soon need to start downloading again.
            if (downloadSongId != -1L && downloadSongId != cacheEntry.songId) {
                cache!!.abortDownload(downloadSongId)
                downloadSongId = -1
                downloadIndex = -1
            }
            maybeDownloadAnotherSong(currentSongIndex + 1)
        } else {
            // Otherwise, start downloading it if we've never tried downloading it before,
            // or if we have but it's not currently being downloaded.
            player!!.abortPlayback()
            if (cacheEntry == null || downloadSongId != cacheEntry.songId) {
                if (downloadSongId != -1L) cache!!.abortDownload(downloadSongId)
                cacheEntry = cache!!.downloadSong(song!!)
                downloadSongId = song.id
                downloadIndex = currentSongIndex
            }
            waitingForDownload = true
            updatePlaybackState()
        }

        // Enqueue the next song if we already have it.
        val next = nextSong
        if (next != null) {
            val nextEntry = cache!!.getEntry(next.id)
            if (nextEntry != null && nextEntry.isFullyCached) {
                player!!.queueFile(
                    nextEntry.localFile.path,
                    nextEntry.totalBytes,
                    next.albumGain,
                    next.peakAmp
                )
            }
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        cache!!.pinSongId(song!!.id)
        fetchCoverForSongIfMissing(song)
        updateNotification()
        mediaSessionManager!!.updateSong(song)
        updatePlaybackState()
        songListener?.onSongChange(song, currentSongIndex)
    }

    // Stop playing the current song, if any.
    fun stopPlaying() {
        currentSongPath = null
        player!!.abortPlayback()
    }

    // Start fetching the cover for a song if it's not loaded already.
    fun fetchCoverForSongIfMissing(song: Song?) {
        if (song!!.coverBitmap != null || song.coverUrl == null) return
        if (!songCoverFetches.contains(song)) {
            songCoverFetches.add(song)
            CoverFetchTask(song).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    // Fetches the cover bitmap for a particular song.
    internal inner class CoverFetchTask(private val song: Song?) :
        AsyncTask<Void?, Void?, Bitmap?>() {
        protected override fun doInBackground(vararg args: Void?): Bitmap? {
            return coverLoader!!.loadCover(song!!.coverUrl!!)
        }

        override fun onPostExecute(bitmap: Bitmap?) {
            storeCoverForSong(song, bitmap)
            songCoverFetches.remove(song)
            if (song!!.coverBitmap != null) {
                songListener?.onSongCoverLoad(song)
                if (song == currentSong) {
                    updateNotification()
                    mediaSessionManager!!.updateSong(song)
                }
            }
        }
    }

    val totalCachedBytes: Long
        get() = cache!!.totalCachedBytes

    fun clearCache() { cache!!.clear() }

    // Implements Player.Listener.
    override fun onPlaybackComplete() {
        assertOnMainThread()
        playbackComplete = true
        updateNotification()
        updatePlaybackState()
        if (currentSongIndex < songs.size - 1) playSongAtIndex(currentSongIndex + 1)
    }

    // Implements Player.Listener.
    override fun onPlaybackPositionChange(
        path: String?,
        positionMs: Int,
        durationMs: Int
    ) {
        assertOnMainThread()
        if (path != currentSongPath) return
        val song = currentSong!!
        songListener?.onSongPositionChange(song, positionMs, durationMs)
        val elapsed = positionMs - currentSongLastPositionMs
        if (elapsed > 0 && elapsed <= MAX_POSITION_REPORT_MS) {
            currentSongPlayedMs += elapsed.toLong()
            // TODO: Add a currentSongReportThresholdMs or similar member to simplify this.
            if (!reportedCurrentSong && (
                currentSongPlayedMs >= Math.max(durationMs, song.lengthSec * 1000) / 2 ||
                    currentSongPlayedMs >= REPORT_PLAYBACK_THRESHOLD_MS
                )
            ) {
                val start = currentSongStartDate!!
                launch(Dispatchers.IO) { playbackReporter!!.report(song.id, start) }
                reportedCurrentSong = true
            }
        }
        currentSongLastPositionMs = positionMs
        updatePlaybackState()
    }

    // Implements Player.Listener.
    override fun onPauseStateChange(paused: Boolean) {
        assertOnMainThread()
        this.paused = paused
        updateNotification()
        songListener?.onPauseStateChange(this.paused)
        updatePlaybackState()
    }

    // Implements Player.Listener.
    override fun onPlaybackError(description: String?) {
        assertOnMainThread()
        Toast.makeText(this@NupService, description, Toast.LENGTH_LONG).show()
    }

    override fun onCacheDownloadError(entry: FileCacheEntry, reason: String) {
        handler.post {
            Toast.makeText(this@NupService, "Got retryable error: $reason", Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onCacheDownloadFail(entry: FileCacheEntry, reason: String) {
        handler.post {
            Log.d(TAG, "Download of song ${entry.songId} failed: $reason")
            Toast.makeText(
                this@NupService,
                "Download of ${songIdToSong[entry.songId]?.url} failed: $reason",
                Toast.LENGTH_LONG
            ).show()

            if (entry.songId == downloadSongId) {
                downloadSongId = -1
                downloadIndex = -1
                waitingForDownload = false
            }
        }
    }

    override fun onCacheDownloadComplete(entry: FileCacheEntry) {
        handler.post(
            Runnable {
                Log.d(TAG, "Download of song ${entry.songId} completed")

                val song = songIdToSong[entry.songId] ?: return@Runnable
                song.updateBytes(entry)
                songDb!!.handleSongCached(song.id)

                if (song == currentSong && waitingForDownload) {
                    waitingForDownload = false
                    playCacheEntry(entry)
                } else if (song == nextSong) {
                    player!!.queueFile(
                        entry.localFile.path,
                        entry.totalBytes,
                        song.albumGain,
                        song.peakAmp
                    )
                }

                songListener?.onSongFileSizeChange(song)

                if (entry.songId == downloadSongId) {
                    val nextIndex = downloadIndex + 1
                    downloadSongId = -1
                    downloadIndex = -1
                    maybeDownloadAnotherSong(nextIndex)
                }
            }
        )
    }

    override fun onCacheDownloadProgress(
        entry: FileCacheEntry,
        downloadedBytes: Long,
        elapsedMs: Long
    ) {
        handler.post(
            Runnable {
                val song = songIdToSong[entry.songId] ?: return@Runnable
                song.updateBytes(entry)
                if (song == currentSong) {
                    if (waitingForDownload &&
                        canPlaySong(entry, downloadedBytes, elapsedMs, song.lengthSec)
                    ) {
                        waitingForDownload = false
                        playCacheEntry(entry)
                    }
                }
                songListener?.onSongFileSizeChange(song)
            }
        )
    }

    override fun onCacheEviction(entry: FileCacheEntry) {
        handler.post(
            Runnable {
                Log.d(TAG, "Song ${entry.songId} was evicted")
                songDb!!.handleSongEvicted(entry.songId)
                val song = songIdToSong[entry.songId] ?: return@Runnable
                song.availableBytes = 0
                song.totalBytes = 0
                songListener?.onSongFileSizeChange(song)
            }
        )
    }

    // Implements SongDatabase.Listener.
    override fun onAggregateDataUpdate() {
        handler.post {
            Log.d(TAG, "got notice that aggregate data has been updated")
            for (listener in songDatabaseUpdateListeners) listener.onSongDatabaseUpdate()
        }
    }

    /** Starts authenticating with Google Cloud Storage in the background.  */
    fun authenticateInBackground() { authenticator!!.authenticateInBackground() }

    // Insert a list of songs into the playlist at a particular position.
    // Plays the first one, if no song is already playing or if we were previously at the end of the
    // playlist and we've appended to it. Returns true if we started playing.
    private fun insertSongs(insSongs: List<Song>, index: Int): Boolean {
        if (index < 0 || index > songs.size) {
            Log.e(TAG, "ignoring request to insert ${insSongs.size} song(s) at index $index")
            return false
        }

        // Songs that we didn't already have. We track these so we can check
        // the cache for them later.
        val newSongs = ArrayList<Song>()

        // Use our own version of each song if we have it already.
        val resSongs = ArrayList<Song>()
        for (song in insSongs) {
            val ourSong = songIdToSong[song.id]
            if (ourSong != null) {
                resSongs.add(ourSong)
            } else {
                resSongs.add(song)
                newSongs.add(song)
            }
        }

        songs.addAll(index, resSongs)
        if (currentSongIndex >= 0 && index <= currentSongIndex) currentSongIndex += resSongs.size
        if (downloadIndex >= 0 && index <= downloadIndex) downloadIndex += resSongs.size

        songListener?.onPlaylistChange(songs)
        mediaSessionManager!!.updatePlaylist(songs)
        for (song in newSongs) {
            songIdToSong[song.id] = song
            val entry = cache!!.getEntry(song.id)
            if (entry != null) {
                song.updateBytes(entry)
                songListener?.onSongFileSizeChange(song)
            }
        }
        var played = false
        when {
            // If we didn't have any songs, then start playing the first one we added.
            currentSongIndex == -1 -> {
                playSongAtIndex(0)
                played = true
            }
            // If we were previously done playing (because we reached the end of the playlist),
            // then start playing the first song we added.
            currentSongIndex < songs.size - 1 && playbackComplete -> {
                playSongAtIndex(currentSongIndex + 1)
                played = true
            }
            // Otherwise, consider downloading the new songs if we're not already downloading
            // something.
            downloadSongId == -1L -> maybeDownloadAnotherSong(index)
        }
        updateNotification()
        return played
    }

    // Do we have enough of a song downloaded at a fast enough rate that we'll probably
    // finish downloading it before playback reaches the end of the song?
    private fun canPlaySong(
        entry: FileCacheEntry?,
        downloadedBytes: Long,
        elapsedMs: Long,
        songLengthSec: Int
    ): Boolean {
        if (entry!!.isFullyCached) return true
        val bytesPerMs = downloadedBytes.toDouble() / elapsedMs
        val remainingMs = ((entry.totalBytes - entry.cachedBytes) / bytesPerMs).toLong()
        return entry.cachedBytes >= MIN_BYTES_BEFORE_PLAYING &&
            remainingMs + EXTRA_BUFFER_MS <= songLengthSec * 1000
    }

    // Play the local file where a cache entry is stored.
    private fun playCacheEntry(entry: FileCacheEntry?) {
        val song = currentSong
        if (song!!.id != entry!!.songId) {
            Log.e(TAG, "cache entry ${entry.songId} doesn't match current song ${song.id}")
            return
        }
        currentSongPath = entry.localFile.path
        player!!.playFile(currentSongPath!!, entry.totalBytes, song.albumGain, song.peakAmp)
        currentSongStartDate = Date()
        cache!!.updateLastAccessTime(entry.songId)
        updateNotification()
        updatePlaybackState()
    }

    // Try to download the next not-yet-downloaded song in the playlist.
    private fun maybeDownloadAnotherSong(startIndex: Int) {
        if (downloadSongId != -1L) {
            Log.e(TAG, "aborting prefetch since download of song $downloadSongId is in progress")
            return
        }
        val songsToPreload = Integer.valueOf(
            prefs!!.getString(
                NupPreferences.SONGS_TO_PRELOAD,
                NupPreferences.SONGS_TO_PRELOAD_DEFAULT
            )!!
        )
        var index = startIndex
        while (index < songs.size &&
            (shouldDownloadAll || index - currentSongIndex <= songsToPreload)
        ) {
            val song = songs[index]
            var entry = cache!!.getEntry(song.id)
            if (entry != null && entry.isFullyCached) {
                // We already have this one.  Pin it to make sure that it
                // doesn't get evicted by a later song.
                cache!!.pinSongId(song.id)
                index++
                continue
            }
            cache!!.downloadSong(song)
            downloadSongId = song.id
            downloadIndex = index
            cache!!.pinSongId(song.id)
            fetchCoverForSongIfMissing(song)
            index++
        }
    }

    // Set |song|'s cover bitmap to |bitmap|.
    // Also makes sure that we don't have more than |MAX_LOADED_COVERS| bitmaps in-memory.
    private fun storeCoverForSong(song: Song?, bitmap: Bitmap?) {
        song!!.coverBitmap = bitmap
        val existingIndex = songsWithCovers.indexOf(song)

        // If we didn't get a bitmap, bail out early.
        if (bitmap == null) {
            if (existingIndex >= 0) songsWithCovers.removeAt(existingIndex)
            return
        }

        // If the song is already in the list, remove it so we can add it to the end.
        if (existingIndex >= 0) {
            // It's already at the end of the list; we don't need to move it.
            if (existingIndex == songsWithCovers.size - 1) return
            songsWithCovers.removeAt(existingIndex)
        }

        // If we're full, drop the cover from the first song on the list.
        if (songsWithCovers.size == MAX_LOADED_COVERS) {
            songsWithCovers[0]!!.coverBitmap = null
            songsWithCovers.removeAt(0)
        }
        songsWithCovers.add(song)
    }

    // Notifies |mediaSessionManager| about the current playback state.
    private fun updatePlaybackState() {
        mediaSessionManager!!.updatePlaybackState(
            currentSong,
            paused,
            playbackComplete,
            waitingForDownload,
            currentSongLastPositionMs.toLong(),
            currentSongIndex,
            songs.size
        )
    }

    companion object {
        private const val TAG = "NupService"

        // Identifier used for our "currently playing" notification.
        // Note: Using 0 makes the service not run in the foreground and the notification not show up.
        // Yay, that was fun to figure out.
        private const val NOTIFICATION_ID = 1

        // Don't start playing a song until we have at least this many bytes of it.
        private const val MIN_BYTES_BEFORE_PLAYING = 128 * 1024L

        // Don't start playing a song until we think we'll finish downloading the whole file at the
        // current rate sooner than this many milliseconds before the song would end (whew).
        private const val EXTRA_BUFFER_MS = 10 * 1000L

        // Maximum number of cover bitmaps to keep in memory at once.
        private const val MAX_LOADED_COVERS = 3

        // Name of directory where cover images are stored.
        private const val COVER_DIR_NAME = "covers"

        // If we receive a playback update with a position more than this many milliseconds beyond the
        // last one we received, we assume that something has gone wrong and ignore it.
        private const val MAX_POSITION_REPORT_MS = 5 * 1000L

        // Report a song unconditionally if we've played it for this many milliseconds.
        private const val REPORT_PLAYBACK_THRESHOLD_MS = 240 * 1000L

        // Ignore AUDIO_BECOMING_NOISY broadcast intents for this long after a user switch.
        private const val IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS = 1000L

        // Subdirectory where crash reports are written.
        private const val CRASH_SUBDIRECTORY = "crashes"

        // Intent actions.
        private const val ACTION_TOGGLE_PAUSE = "nup_toggle_pause"
        private const val ACTION_NEXT_TRACK = "nup_next_track"
        private const val ACTION_PREV_TRACK = "nup_prev_track"
        private const val ACTION_MEDIA_BUTTON = "nup_media_button"

        // Choose a filename to use for storing a song locally (used to just use a slightly-mangled
        // version of the remote path, but I think I saw a problem -- didn't investigate much).
        private fun chooseLocalFilenameForSong(song: Song) { "${song.id}.mp3" }
    }
}
