/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.MediaSessionCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import androidx.preference.PreferenceManager
import java.io.File
import java.util.Arrays
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Foreground service that plays music, manages databases, etc. */
class NupService :
    MediaBrowserServiceCompat(),
    Player.Listener,
    FileCache.Listener,
    SongDatabase.Listener {
    /** Listener for changes to song- and playlist-related state. */
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

    /** Listener for [SongDatabase]'s aggregate data being updated. */
    interface SongDatabaseUpdateListener {
        /** Called when the synchronization state changes. */
        fun onSongDatabaseSyncChange(state: SongDatabase.SyncState, updatedSongs: Int)
        /** Called when data is updated. */
        fun onSongDatabaseUpdate()
    }

    /** Listener for [FileCache]'s size changing. */
    interface FileCacheSizeChangeListener {
        fun onFileCacheSizeChange()
    }

    private val scope = MainScope()
    private val binder: IBinder = LocalBinder()

    lateinit var songDb: SongDatabase
        private set

    private lateinit var networkHelper: NetworkHelper
    private lateinit var downloader: Downloader
    private lateinit var player: Player
    private lateinit var cache: FileCache
    private lateinit var coverLoader: CoverLoader
    private lateinit var playbackReporter: PlaybackReporter
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var mediaSessionToken: MediaSessionCompat.Token
    private lateinit var mediaBrowserHelper: MediaBrowserHelper
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationCreator: NotificationCreator
    private lateinit var audioManager: AudioManager
    private lateinit var audioAttrs: AudioAttributes
    private lateinit var audioFocusReq: AudioFocusRequest
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var prefs: SharedPreferences

    private val songIdToSong = mutableMapOf<Long, Song>() // canonical [Song]s indexed by ID
    private val _songs = mutableListOf<Song>() // current playlist
    public val songs: List<Song> get() = _songs

    var curSongIndex = -1 // index into [_songs]
        private set
    var lastPosMs = 0 // last playback position for current song
        private set

    val curSong: Song?
        get() = if (curSongIndex in 0..(songs.size - 1)) songs[curSongIndex] else null
    val nextSong: Song?
        get() = if (curSongIndex in 0..(songs.size - 2)) songs[curSongIndex + 1] else null

    private var curFile: File? = null // local path of [curSong]
    private var playStart: Date? = null // time at which [curSong] was started
    private var playedMs: Long = 0 // total time spent playing [curSong]

    var paused = false // playback currently paused
        private set
    private var playbackComplete = false // done playing [curSong]
    private var reported = false // already reported playback of [curSong] to server

    private var songsToPreload = 0 // set from pref
    private var downloadSongId: Long = -1 // ID of currently-downloading song
    private var downloadIndex = -1 // index into [songs] of currently-downloading song
    private var waitingForDownload = false // waiting for file to be download so we can play it

    /** Download all queued songs instead of honoring pref. */
    var shouldDownloadAll: Boolean = false
        set(value) {
            if (value != shouldDownloadAll) {
                field = value
                if (!songs.isEmpty() && shouldDownloadAll && downloadSongId == -1L) {
                    maybeDownloadAnotherSong(if (curSongIndex >= 0) curSongIndex else 0)
                }
            }
        }

    private val songCoverFetches = mutableSetOf<Song>() // songs whose covers are being fetched
    private val songsWithCovers = mutableListOf<Song>() // songs whose covers are in-memory

    private var lastUserSwitch: Date? = null // time when user was last fore/backgrounded

    // Pause when phone calls arrive.
    private val phoneStateListener: PhoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            // I don't want to know who's calling.  Why isn't there a more-limited
            // permission?
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                player.pause()
                Toast.makeText(
                    this@NupService,
                    "Paused for incoming call",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Listen for miscellaneous broadcasts.
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
                val last = lastUserSwitch
                val userSwitchedRecently = last == null ||
                    Date().time - last.time <= IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS
                if (curSongIndex >= 0 && !paused && !userSwitchedRecently) {
                    player.pause()
                    Toast.makeText(
                        this@NupService,
                        "Paused since unplugged",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (Intent.ACTION_USER_BACKGROUND == intent.action) {
                Log.d(TAG, "User is backgrounded")
                lastUserSwitch = Date()
            } else if (Intent.ACTION_USER_FOREGROUND == intent.action) {
                Log.d(TAG, "User is foregrounded")
                lastUserSwitch = Date()
            }
        }
    }

    // Listen for changes to audio focus.
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Gained audio focus")
                player.lowVolume = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> Log.d(TAG, "Lost audio focus")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Log.d(TAG, "Transiently lost audio focus")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Transiently lost audio focus (but can duck)")
                player.lowVolume = true
            }
            else -> Log.d(TAG, "Unhandled audio focus change $focusChange")
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener {
        prefs, key ->
        run {
            check(prefs == this@NupService.prefs) { "Wrong prefs $prefs" }
            scope.launch(Dispatchers.Main) { applyPref(key) }
        }
    }

    private var songListener: SongListener? = null
    private val songDatabaseUpdateListeners = mutableSetOf<SongDatabaseUpdateListener>()
    private val fileCacheSizeChangeListeners = mutableSetOf<FileCacheSizeChangeListener>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // It'd be nice to set this up before we do anything else, but getExternalFilesDir() blocks.
        scope.launch(Dispatchers.Main) {
            val dir = async(Dispatchers.IO) {
                File(getExternalFilesDir(null), CRASH_DIR_NAME)
            }.await()
            CrashLogger.register(dir)
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioFocusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setAudioAttributes(audioAttrs)
            .build()
        val result = audioManager.requestAudioFocus(audioFocusReq)
        Log.d(TAG, "Requested audio focus; got $result")

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        val filter = IntentFilter()
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        filter.addAction(Intent.ACTION_USER_BACKGROUND)
        filter.addAction(Intent.ACTION_USER_FOREGROUND)
        registerReceiver(broadcastReceiver, filter)

        networkHelper = NetworkHelper(this)
        downloader = Downloader()
        player = Player(this, this, mainExecutor, audioAttrs)
        cache = FileCache(this, this, mainExecutor, downloader, networkHelper)
        songDb = SongDatabase(this, this, mainExecutor, cache, downloader, networkHelper)
        coverLoader = CoverLoader(this, downloader, networkHelper)
        playbackReporter = PlaybackReporter(songDb, downloader, networkHelper)

        // Sigh. This hits the disk, but it crashes with "java.lang.RuntimeException: Can't create
        // handler inside thread Thread[DefaultDispatcher-worker-2,5,main] that has not called
        // Looper.prepare()" while inflating PreferenceScreen when I call it on the IO thread. I
        // haven't been able to find any official documentation giving a full example of the right
        // way to implement preferences.
        val origPolicy = StrictMode.allowThreadDiskReads()
        try {
            PreferenceManager.setDefaultValues(this@NupService, R.xml.settings, false)
        } finally {
            StrictMode.setThreadPolicy(origPolicy)
        }

        // Read prefs on the IO thread to avoid blocking the UI.
        scope.launch(Dispatchers.Main) {
            prefs = async(Dispatchers.IO) {
                PreferenceManager.getDefaultSharedPreferences(this@NupService)
            }.await()
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)

            // Load initial settings.
            applyPref(NupPreferences.CACHE_SIZE)
            applyPref(NupPreferences.DOWNLOAD_RATE)
            applyPref(NupPreferences.PASSWORD)
            applyPref(NupPreferences.PRE_AMP_GAIN)
            applyPref(NupPreferences.USERNAME)
            applyPref(NupPreferences.SERVER_URL)
            applyPref(NupPreferences.SONGS_TO_PRELOAD)

            if (networkHelper.isNetworkAvailable) {
                launch(Dispatchers.IO) { playbackReporter.reportPending() }
                // TODO: Listen for the network coming up and send pending reports then too?
            }
        }

        mediaSessionManager = MediaSessionManager(
            this,
            object : MediaSessionCompat.Callback() {
                override fun onPause() { player.pause() }
                override fun onPlay() { player.unpause() }
                override fun onPlayFromMediaId(mediaId: String, extras: Bundle) {
                    scope.launch(Dispatchers.Main) {
                        val songs = async(Dispatchers.IO) {
                            mediaBrowserHelper.getSongsFromMediaId(mediaId)
                        }.await()
                        if (!songs.isEmpty()) {
                            // TODO: Figure out how queue management should work here.
                            clearPlaylist()
                            addSongsToPlaylist(songs, false /* forcePlay */)
                        }
                    }
                }
                override fun onSkipToNext() { playSongAtIndex(curSongIndex + 1) }
                override fun onSkipToPrevious() { playSongAtIndex(curSongIndex - 1) }
                override fun onSkipToQueueItem(id: Long) {
                    // TODO: The same song might be in the playlist multiple times.
                    // I'm not sure how to tell which one the user selected.
                    playSongWithId(id)
                }
                override fun onStop() { player.pause() }
            }
        )
        sessionToken = mediaSessionManager.token

        mediaBrowserHelper = MediaBrowserHelper(songDb, this.getResources())

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationCreator = NotificationCreator(
            this,
            notificationManager,
            mediaSessionManager.token,
            PendingIntent.getActivity(this, 0, Intent(this, NupActivity::class.java), 0),
            PendingIntent.getService(
                this, 0, Intent(ACTION_TOGGLE_PAUSE, Uri.EMPTY, this, NupService::class.java), 0
            ),
            PendingIntent.getService(
                this, 0, Intent(ACTION_PREV_TRACK, Uri.EMPTY, this, NupService::class.java), 0
            ),
            PendingIntent.getService(
                this, 0, Intent(ACTION_NEXT_TRACK, Uri.EMPTY, this, NupService::class.java), 0
            )
        )
        val notification = notificationCreator.createNotification(
            false,
            curSong,
            paused,
            playbackComplete,
            curSongIndex,
            songs.size
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        audioManager.abandonAudioFocusRequest(audioFocusReq)
        if (this::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        unregisterReceiver(broadcastReceiver)
        mediaSessionManager.release()
        songDb.quit()
        player.quit()
        cache.quit()
        CrashLogger.unregister()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received start ID $startId: $intent")
        MediaButtonReceiver.handleIntent(mediaSessionManager.session, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        // We usually use our own binder, but MediaBrowserServiceCompat expects to use its own:
        // https://stackoverflow.com/questions/38601271/android-auto-app-never-calls-ongetroot
        // Without this, Android Audio just shows a spinner and errors like "oneway function results
        // will be dropped but finished with status UNKNOWN_TRANSACTION and parcel size 0" get
        // logged.
        return when (intent.getAction()) {
            MediaBrowserServiceCompat.SERVICE_INTERFACE -> super.onBind(intent)
            else -> binder
        }
    }

    fun setSongListener(listener: SongListener?) { songListener = listener }

    fun addSongDatabaseUpdateListener(listener: SongDatabaseUpdateListener) {
        songDatabaseUpdateListeners.add(listener)
    }
    fun removeSongDatabaseUpdateListener(listener: SongDatabaseUpdateListener) {
        songDatabaseUpdateListeners.remove(listener)
    }

    fun addFileCacheSizeChangeListener(listener: FileCacheSizeChangeListener) {
        fileCacheSizeChangeListeners.add(listener)
    }
    fun removeFileCacheSizeChangeListener(listener: FileCacheSizeChangeListener) {
        fileCacheSizeChangeListeners.remove(listener)
    }

    /**
     * Unregister an object that might be registered as one or more of our listeners.
     *
     * Typically called when the object is an activity that's getting destroyed so
     * we'll drop our references to it.
     */
    fun unregisterListener(`object`: Any) {
        if (songListener === `object`) songListener = null
    }

    inner class LocalBinder : Binder() {
        val service: NupService
            get() = this@NupService
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): MediaBrowserServiceCompat.BrowserRoot {
        // See https://developer.android.com/training/cars/media#tabs-opt-in.
        val extras = Bundle()
        extras.putBoolean("android.media.browse.AUTO_TABS_OPT_IN_HINT", true)
        return MediaBrowserServiceCompat.BrowserRoot(MediaBrowserHelper.ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaItem>>
    ) = mediaBrowserHelper.onLoadChildren(parentId, result)

    /** Read and apply [key]. */
    private suspend fun applyPref(key: String) {
        when (key) {
            NupPreferences.CACHE_SIZE -> {
                val size = readPref(key).toLong()
                cache.maxBytes = if (size > 0) size * 1024 * 1024 else size
            }
            NupPreferences.DOWNLOAD_RATE -> {
                val rate = readPref(key).toLong()
                cache.maxRate = if (rate > 0) rate * 1024 else rate
            }
            NupPreferences.PASSWORD -> downloader.password = readPref(key)
            NupPreferences.PRE_AMP_GAIN -> player.preAmpGain = readPref(key).toDouble()
            NupPreferences.USERNAME -> downloader.username = readPref(key)
            NupPreferences.SERVER_URL -> downloader.server = readPref(key)
            NupPreferences.SONGS_TO_PRELOAD -> songsToPreload = readPref(key).toInt()
        }
    }

    /** Return [key]'s value. */
    private suspend fun readPref(key: String) =
        scope.async(Dispatchers.IO) { prefs.getString(key, null)!! }.await()

    /** Updates the currently-displayed notification if needed.  */
    private fun updateNotification() {
        val notification = notificationCreator.createNotification(
            true,
            curSong,
            paused,
            playbackComplete,
            curSongIndex,
            songs.size
        )
        if (notification != null) notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun togglePause() { player.togglePause() }
    fun pause() { player.pause() }

    fun clearPlaylist() { removeRangeFromPlaylist(0, songs.size - 1) }

    fun appendSongToPlaylist(song: Song) { appendSongsToPlaylist(ArrayList(Arrays.asList(song))) }
    fun appendSongsToPlaylist(newSongs: List<Song>) { insertSongs(newSongs, songs.size) }

    fun addSongToPlaylist(song: Song, play: Boolean) {
        addSongsToPlaylist(ArrayList(Arrays.asList(song)), play)
    }
    fun addSongsToPlaylist(newSongs: List<Song>, forcePlay: Boolean) {
        val index = curSongIndex
        val alreadyPlayed = insertSongs(newSongs, if (index < 0) 0 else index + 1)
        if (forcePlay && !alreadyPlayed) playSongAtIndex(if (index < 0) 0 else index + 1)
    }

    fun removeFromPlaylist(index: Int) { removeRangeFromPlaylist(index, index) }

    /** Remove a range of songs from the playlist. */
    fun removeRangeFromPlaylist(firstIndex: Int, lastIndex: Int) {
        val first = Math.max(firstIndex, 0)
        val last = Math.min(lastIndex, songs.size - 1)

        var removedPlaying = false
        for (numToRemove in (last - first + 1) downTo 1) {
            _songs.removeAt(first)
            if (curSongIndex == first) {
                removedPlaying = true
                stopPlaying()
            } else if (curSongIndex > first) {
                curSongIndex--
            }
            if (downloadIndex == first) {
                cache.abortDownload(downloadSongId)
                downloadSongId = -1
                downloadIndex = -1
            } else if (downloadIndex > first) {
                downloadIndex--
            }
        }

        if (removedPlaying) {
            if (!songs.isEmpty() && curSongIndex < songs.size) {
                playSongAtIndex(curSongIndex)
            } else {
                curSongIndex = -1
                mediaSessionManager.updateSong(null)
                updatePlaybackState()
            }
        }

        // Maybe the e.g. now-next-to-be-played song isn't downloaded yet.
        if (downloadSongId == -1L && !songs.isEmpty() && curSongIndex < songs.size - 1) {
            maybeDownloadAnotherSong(curSongIndex + 1)
        }

        songListener?.onPlaylistChange(songs)
        updateNotification()
        mediaSessionManager.updatePlaylist(songs)
    }

    /** Play the song at [index] in [songs]. */
    fun playSongAtIndex(index: Int) {
        if (index < 0 || index >= songs.size) return

        curSongIndex = index
        val song = curSong!!

        playStart = null
        lastPosMs = 0
        playedMs = 0
        reported = false
        playbackComplete = false
        cache.clearPinnedSongIds()

        // If we've already downloaded the whole file, start playing it.
        var cacheEntry = cache.getEntry(song.id)
        if (cacheEntry != null && cacheEntry.isFullyCached) {
            Log.d(TAG, "\"${song.filename}\" already downloaded; playing")
            playCacheEntry(cacheEntry)

            // If we're downloading some other song (maybe we were downloading the
            // previously-being-played song), abort it.
            // TODO: This could actually be a future song that we were already
            // downloading and will soon need to start downloading again.
            if (downloadSongId != -1L && downloadSongId != cacheEntry.songId) {
                cache.abortDownload(downloadSongId)
                downloadSongId = -1
                downloadIndex = -1
            }
            maybeDownloadAnotherSong(curSongIndex + 1)
        } else {
            // Otherwise, start downloading it if we've never tried downloading it before,
            // or if we have but it's not currently being downloaded.
            player.abortPlayback()
            if (cacheEntry == null || downloadSongId != cacheEntry.songId) {
                if (downloadSongId != -1L) cache.abortDownload(downloadSongId)
                cache.downloadSong(song)
                downloadSongId = song.id
                downloadIndex = curSongIndex
            }
            waitingForDownload = true
            updatePlaybackState()
        }

        // Enqueue the next song if we already have it.
        val next = nextSong
        if (next != null) {
            val nextEntry = cache.getEntry(next.id)
            if (nextEntry != null && nextEntry.isFullyCached) {
                player.queueFile(nextEntry.file, nextEntry.totalBytes, next.albumGain, next.peakAmp)
            }
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        cache.pinSongId(song.id)
        fetchCoverForSongIfMissing(song)
        updateNotification()
        mediaSessionManager.updateSong(song)
        updatePlaybackState()
        songListener?.onSongChange(song, curSongIndex)
    }

    /** Play already-queued song [id]. */
    fun playSongWithId(id: Long) {
        val idx = songs.indexOfFirst { it.id == id }
        if (idx == -1) {
            Log.e(TAG, "Ignoring request to play song $id not in playlist")
        } else {
            playSongAtIndex(idx)
        }
    }

    /** Stop playing the current song, if any. */
    fun stopPlaying() {
        curFile = null
        player.abortPlayback()
    }

    /** Start fetching [song]'s cover if it's not loaded already. */
    fun fetchCoverForSongIfMissing(song: Song) {
        if (song.coverBitmap != null ||
            song.coverFilename == "" ||
            songCoverFetches.contains(song)
        ) {
            return
        }

        songCoverFetches.add(song)
        scope.launch(Dispatchers.Main) {
            val bitmap = async(Dispatchers.IO) { coverLoader.loadCover(song.coverFilename) }.await()
            storeCoverForSong(song, bitmap)
            songCoverFetches.remove(song)

            if (song.coverBitmap != null) {
                songListener?.onSongCoverLoad(song)
                if (song == curSong) {
                    updateNotification()
                    mediaSessionManager.updateSong(song)
                }
            }
        }
    }

    val totalCachedBytes: Long get() = cache.totalCachedBytes

    fun clearCache() { cache.clear() }

    override fun onPlaybackComplete() {
        assertOnMainThread()
        playbackComplete = true
        updateNotification()
        updatePlaybackState()
        if (curSongIndex < songs.size - 1) playSongAtIndex(curSongIndex + 1)
    }

    override fun onPlaybackPositionChange(file: File, positionMs: Int, durationMs: Int) {
        assertOnMainThread()
        if (file != curFile) return
        val song = curSong!!
        songListener?.onSongPositionChange(song, positionMs, durationMs)
        val elapsed = positionMs - lastPosMs
        if (elapsed > 0 && elapsed <= MAX_POSITION_REPORT_MS) {
            playedMs += elapsed.toLong()
            // TODO: Add a currentSongReportThresholdMs or similar member to simplify this.
            if (!reported && (
                playedMs >= Math.max(durationMs, song.lengthSec * 1000) / 2 ||
                    playedMs >= REPORT_PLAYBACK_THRESHOLD_MS
                )
            ) {
                val start = playStart!!
                scope.launch(Dispatchers.IO) { playbackReporter.report(song.id, start) }
                reported = true
            }
        }
        lastPosMs = positionMs
        updatePlaybackState()
    }

    override fun onPauseStateChange(paused: Boolean) {
        assertOnMainThread()
        this.paused = paused
        updateNotification()
        songListener?.onPauseStateChange(this.paused)
        updatePlaybackState()
    }

    override fun onPlaybackError(description: String) {
        assertOnMainThread()
        Toast.makeText(this@NupService, description, Toast.LENGTH_LONG).show()
    }

    override fun onCacheDownloadError(entry: FileCacheEntry, reason: String) {
        assertOnMainThread()
        Toast.makeText(this@NupService, "Got retryable error: $reason", Toast.LENGTH_SHORT).show()
    }

    override fun onCacheDownloadFail(entry: FileCacheEntry, reason: String) {
        assertOnMainThread()
        Log.d(TAG, "Download of song ${entry.songId} failed: $reason")

        Toast.makeText(
            this@NupService,
            "Download of \"${songIdToSong[entry.songId]?.filename}\" failed: $reason",
            Toast.LENGTH_LONG
        ).show()

        if (entry.songId == downloadSongId) {
            downloadSongId = -1
            downloadIndex = -1
            waitingForDownload = false
        }
    }

    override fun onCacheDownloadComplete(entry: FileCacheEntry) {
        assertOnMainThread()
        Log.d(TAG, "Download of song ${entry.songId} completed")

        val song = songIdToSong[entry.songId] ?: return
        song.updateBytes(entry)
        songDb.handleSongCached(song.id)

        if (song == curSong && waitingForDownload) {
            waitingForDownload = false
            playCacheEntry(entry)
        } else if (song == nextSong) {
            player.queueFile(entry.file, entry.totalBytes, song.albumGain, song.peakAmp)
        }

        songListener?.onSongFileSizeChange(song)
        for (listener in fileCacheSizeChangeListeners) listener.onFileCacheSizeChange()

        if (entry.songId == downloadSongId) {
            val nextIndex = downloadIndex + 1
            downloadSongId = -1
            downloadIndex = -1
            maybeDownloadAnotherSong(nextIndex)
        }
    }

    override fun onCacheDownloadProgress(
        entry: FileCacheEntry,
        downloadedBytes: Long,
        elapsedMs: Long
    ) {
        assertOnMainThread()

        val song = songIdToSong[entry.songId] ?: return
        song.updateBytes(entry)
        if (song == curSong) {
            if (waitingForDownload &&
                canPlaySong(entry, downloadedBytes, elapsedMs, song.lengthSec)
            ) {
                waitingForDownload = false
                playCacheEntry(entry)
            }
        }
        songListener?.onSongFileSizeChange(song)
    }

    override fun onCacheEviction(entry: FileCacheEntry) {
        assertOnMainThread()
        Log.d(TAG, "Song ${entry.songId} evicted")

        songDb.handleSongEvicted(entry.songId)
        val song = songIdToSong[entry.songId]
        if (song != null) {
            song.availableBytes = 0
            song.totalBytes = 0
            songListener?.onSongFileSizeChange(song)
        }
        for (listener in fileCacheSizeChangeListeners) listener.onFileCacheSizeChange()
    }

    override fun onSyncChange(state: SongDatabase.SyncState, updatedSongs: Int) {
        assertOnMainThread()
        Log.d(TAG, "Sync state changed to ${state.name} ($updatedSongs song(s))")
        for (listener in songDatabaseUpdateListeners) {
            listener.onSongDatabaseSyncChange(state, updatedSongs)
        }
    }

    override fun onSyncDone(success: Boolean, message: String) {
        assertOnMainThread()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onAggregateDataUpdate() {
        assertOnMainThread()
        Log.d(TAG, "Aggregate data updated")
        for (listener in songDatabaseUpdateListeners) listener.onSongDatabaseUpdate()
    }

    /**
     * Insert a list of songs into the playlist at a particular position.
     *
     * Plays the first one, if no song is already playing or if we were previously at the end of the
     * playlist and we've appended to it.
     *
     * @return true if playback started
     */
    private fun insertSongs(insSongs: List<Song>, index: Int): Boolean {
        if (index < 0 || index > songs.size) {
            Log.e(TAG, "Ignoring request to insert ${insSongs.size} song(s) at index $index")
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

        _songs.addAll(index, resSongs)
        if (curSongIndex >= 0 && index <= curSongIndex) curSongIndex += resSongs.size
        if (downloadIndex >= 0 && index <= downloadIndex) downloadIndex += resSongs.size

        songListener?.onPlaylistChange(songs)
        mediaSessionManager.updatePlaylist(songs)
        for (song in newSongs) {
            songIdToSong[song.id] = song
            val entry = cache.getEntry(song.id)
            if (entry != null) {
                song.updateBytes(entry)
                songListener?.onSongFileSizeChange(song)
            }
        }
        var played = false
        when {
            // If we didn't have any songs, then start playing the first one we added.
            curSongIndex == -1 -> {
                playSongAtIndex(0)
                played = true
            }
            // If we were previously done playing (because we reached the end of the playlist),
            // then start playing the first song we added.
            curSongIndex < songs.size - 1 && playbackComplete -> {
                playSongAtIndex(curSongIndex + 1)
                played = true
            }
            // Otherwise, consider downloading the new songs if we're not already downloading
            // something.
            downloadSongId == -1L -> maybeDownloadAnotherSong(index)
        }
        updateNotification()
        return played
    }

    /** Estimate if we're downloading [entry] fast enough to play it uninterrupted. */
    private fun canPlaySong(
        entry: FileCacheEntry,
        downloadedBytes: Long,
        elapsedMs: Long,
        songLengthSec: Int
    ): Boolean {
        if (entry.isFullyCached) return true
        val bytesPerMs = downloadedBytes.toDouble() / elapsedMs
        val remainingMs = ((entry.totalBytes - entry.cachedBytes) / bytesPerMs).toLong()
        return entry.cachedBytes >= MIN_BYTES_BEFORE_PLAYING &&
            remainingMs + EXTRA_BUFFER_MS <= songLengthSec * 1000
    }

    /** Play [entry] . */
    private fun playCacheEntry(entry: FileCacheEntry) {
        val song = curSong ?: return
        if (song.id != entry.songId) {
            Log.e(TAG, "Cache entry ${entry.songId} doesn't match current song ${song.id}")
            return
        }
        curFile = entry.file
        player.playFile(entry.file, entry.totalBytes, song.albumGain, song.peakAmp)
        playStart = Date()
        cache.updateLastAccessTime(entry.songId)
        updateNotification()
        updatePlaybackState()
    }

    /** Try to download the next not-yet-downloaded song in the playlist. */
    private fun maybeDownloadAnotherSong(startIndex: Int) {
        assertOnMainThread()

        if (downloadSongId != -1L) {
            Log.e(TAG, "Aborting prefetch since download of song $downloadSongId is in progress")
            return
        }
        var index = startIndex
        while (index < songs.size &&
            (shouldDownloadAll || index - curSongIndex <= songsToPreload)
        ) {
            val song = songs[index]
            var entry = cache.getEntry(song.id)
            if (entry != null && entry.isFullyCached) {
                // We already have this one. Pin it to make sure that it
                // doesn't get evicted by a later song.
                cache.pinSongId(song.id)
                index++
                continue
            }
            cache.downloadSong(song)
            downloadSongId = song.id
            downloadIndex = index
            cache.pinSongId(song.id)
            fetchCoverForSongIfMissing(song)
            index++
        }
    }

    /**
     * Set [song]'s cover bitmap to [bitmap].
     *
     * Also makes sure that we don't have more than |MAX_LOADED_COVERS| bitmaps in-memory.
     */
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
            songsWithCovers[0].coverBitmap = null
            songsWithCovers.removeAt(0)
        }
        songsWithCovers.add(song)
    }

    /** Notify [mediaSessionManager] about the current playback state. */
    private fun updatePlaybackState() {
        mediaSessionManager.updatePlaybackState(
            curSong,
            paused,
            playbackComplete,
            waitingForDownload,
            lastPosMs.toLong(),
            curSongIndex,
            songs.size
        )
    }

    companion object {
        private const val TAG = "NupService"

        private const val NOTIFICATION_ID = 1 // "currently playing" notification (can't be 0)
        private const val MIN_BYTES_BEFORE_PLAYING = 128 * 1024L // bytes needed before playing
        private const val EXTRA_BUFFER_MS = 10 * 1000L // headroom needed to play song
        private const val MAX_LOADED_COVERS = 3 // max number of cover bitmaps to keep in memory
        private const val CRASH_DIR_NAME = "crashes" // files subdir where crashes are written
        private const val MAX_POSITION_REPORT_MS = 5 * 1000L // threshold for playback updates
        private const val REPORT_PLAYBACK_THRESHOLD_MS = 240 * 1000L // reporting threshold
        private const val IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS = 1000L

        // Intent actions.
        private const val ACTION_TOGGLE_PAUSE = "nup_toggle_pause"
        private const val ACTION_NEXT_TRACK = "nup_next_track"
        private const val ACTION_PREV_TRACK = "nup_prev_track"
        private const val ACTION_MEDIA_BUTTON = "nup_media_button"
    }
}
