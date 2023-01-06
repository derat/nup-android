/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import androidx.preference.PreferenceManager
import java.io.File
import java.util.Arrays
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that plays music, manages databases, etc. */
class NupService :
    MediaBrowserServiceCompat(),
    Player.Listener,
    FileCache.Listener,
    SongDatabase.Listener {
    /** Listener for changes to song- and playlist-related state. */
    interface SongListener {
        /** Called after the previous playlist has (maybe) been restored. */
        fun onPlaylistRestore()
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

    /** Listener for [FileCache]'s and [CoverLoader]'s sizes changing. */
    interface SizeChangeListener {
        /** Called when the total cached song size changes. */
        fun onCacheSizeChange()
        /** Called when the total size of downloaded covers changes. */
        /* TODO: Right now, we only call this when clearing the covers. */
        fun onCoversSizeChange()
    }

    val scope = MainScope()
    private val binder: IBinder = LocalBinder()

    lateinit var songDb: SongDatabase
        private set
    lateinit var networkHelper: NetworkHelper
        private set
    lateinit var downloader: Downloader
        private set

    private lateinit var player: Player
    private lateinit var cache: FileCache
    private lateinit var coverLoader: CoverLoader
    private lateinit var playbackReporter: PlaybackReporter
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var mediaBrowserHelper: MediaBrowserHelper
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationCreator: NotificationCreator
    private lateinit var audioManager: AudioManager
    private lateinit var audioAttrs: AudioAttributes
    private lateinit var audioFocusReq: AudioFocusRequest
    private lateinit var prefs: SharedPreferences
    private lateinit var windowContext: Context

    private val songIdToSong = mutableMapOf<Long, Song>() // canonical [Song]s indexed by ID
    private val _playlist = mutableListOf<Song>()
    public val playlist: List<Song> get() = _playlist

    var curSongIndex = -1 // index into [_playlist]
        private set
    var lastPosMs = 0 // last playback position for current song
        private set

    val curSong: Song?
        get() = if (curSongIndex in 0..(playlist.size - 1)) playlist[curSongIndex] else null
    val nextSong: Song?
        get() = if (curSongIndex in 0..(playlist.size - 2)) playlist[curSongIndex + 1] else null

    private var curFile: File? = null // local path of [curSong]
    private var playStart: Date? = null // time at which [curSong] was started
    private var playedMs = 0L // total time spent playing [curSong]

    var paused = false // playback currently paused
        private set
    private var haveFocus = false // currently have audio focus
    private var unpauseOnFocusGain = false // paused for transient focus loss
    private var playbackComplete = false // done playing [curSong]
    private var reported = false // already reported playback of [curSong] to server

    private var songsToPreload = 0 // set from pref
    private var downloadSongId = -1L // ID of currently-downloading song
    private var downloadIndex = -1 // index into [playlist] of currently-downloading song
    private var waitingForDownload = false // waiting for file to be download so we can play it
    private var lastDownloadedBytes = 0L // last onCacheDownloadProgress() value for [curSong]
    private var gotPlayRequest = false // received a play media session request

    var playlistRestored = false // restorePlaylist() (maybe) loaded previous playlist
        private set

    /** Download all queued songs instead of honoring pref. */
    var shouldDownloadAll: Boolean = false
        set(value) {
            if (value != shouldDownloadAll) {
                field = value
                if (!playlist.isEmpty() && shouldDownloadAll && downloadSongId == -1L) {
                    maybeDownloadAnotherSong(if (curSongIndex >= 0) curSongIndex else 0)
                }
            }
        }

    /** Report played songs to the server via [playbackReporter]. */
    var shouldReportPlays: Boolean = true

    /** If true, [shouldReportPlays] defaults to false and can be toggled via NupActivity. */
    var guestMode: Boolean = false

    private val songCoverFetches = mutableSetOf<Song>() // songs whose covers are being fetched
    private val songsWithCovers = mutableListOf<Song>() // songs whose covers are in-memory

    private var lastUserSwitch: Date? = null // time when user was last fore/backgrounded

    // Edges in [0.0, 1.0] of the region of cover images typically covered by text.
    // These are updated in onCreate() based on display size and layout dimensions.
    private var coverTextLeft = 0f
    private var coverTextTop = 0.8f
    private var coverTextRight = 0.5f
    private var coverTextBottom = 1f

    // Listen for miscellaneous broadcasts.
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.d(TAG, "Audio becoming noisy")

                    // Switching users apparently triggers an AUDIO_BECOMING_NOISY broadcast
                    // intent (which usually indicates that headphones have been disconnected).
                    // AudioManager.isWiredHeadsetOn() returns true when the notification is
                    // sent due to headphones being unplugged (why?), so that doesn't seem to
                    // help for ignoring this. Instead, ignore these intents for a brief period
                    // after user-switch broadcast intents.
                    val last = lastUserSwitch
                    val userSwitchedRecently = last != null &&
                        Date().time - last.time <= IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS
                    if (curSongIndex >= 0 && !paused && !userSwitchedRecently) {
                        pause()
                        showToast("Paused since unplugged", Toast.LENGTH_SHORT)
                    }
                }
                Intent.ACTION_USER_BACKGROUND -> {
                    Log.d(TAG, "User is backgrounded")
                    lastUserSwitch = Date()
                }
                Intent.ACTION_USER_FOREGROUND -> {
                    Log.d(TAG, "User is foregrounded")
                    lastUserSwitch = Date()
                }
            }
        }
    }

    // Listen for changes to audio focus.
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Gained audio focus")
                haveFocus = true
                if (Build.VERSION.SDK_INT < 26) player.lowVolume = false
                if (unpauseOnFocusGain) unpause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Lost audio focus; pausing")
                haveFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // It's important that we pause here. Otherwise, the Assistant eventually calls
                // onStop() to make us stop entirely, and doesn't seem to start us again.
                // See https://developer.android.com/guide/topics/media-apps/audio-focus.
                Log.d(TAG, "Transiently lost audio focus; pausing")
                haveFocus = false
                pause(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Transiently lost audio focus (but can duck)")
                // https://developer.android.com/guide/topics/media-apps/audio-focus#audio-focus-12:
                // "Automatic ducking (temporarily reducing the audio level of one app so that
                // another can be heard clearly) was introduced in Android 8.0 (API level 26)."
                // I think that we actually don't even receive this anymore, though.
                if (Build.VERSION.SDK_INT < 26) player.lowVolume = true
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
    private val sizeChangeListeners = mutableSetOf<SizeChangeListener>()

    override fun onCreate() {
        Log.d(TAG, "Service created")
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= 26) {
            // TODO: Consider calling setAcceptsDelayedFocusGain(true) and then handling
            // AUDIOFOCUS_REQUEST_DELAYED in getAudioFocus(). I'm not sure how often requests actually
            // get rejected such that this would help.
            audioFocusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setAudioAttributes(audioAttrs)
                .build()
        }

        val filter = IntentFilter()
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        filter.addAction(Intent.ACTION_USER_BACKGROUND)
        filter.addAction(Intent.ACTION_USER_FOREGROUND)
        registerReceiver(broadcastReceiver, filter)

        val res = getResources()
        val mainExec = ContextCompat.getMainExecutor(this)

        networkHelper = NetworkHelper(this, scope)
        downloader = Downloader()
        player = Player(this, this, mainExec, audioAttrs)
        cache = FileCache(this, this, mainExec, downloader, networkHelper)
        songDb = SongDatabase(this, scope, this, mainExec, cache, downloader, networkHelper)
        coverLoader = CoverLoader(this, scope, downloader, networkHelper)
        playbackReporter = PlaybackReporter(scope, songDb, downloader, networkHelper)

        // Sigh. This hits the disk, but it crashes with "java.lang.RuntimeException: Can't create
        // handler inside thread Thread[DefaultDispatcher-worker-2,5,main] that has not called
        // Looper.prepare()" while inflating PreferenceScreen when I call it on the IO thread. I
        // haven't been able to find any official documentation giving a full example of the right
        // way to implement preferences.
        val origPolicy = StrictMode.allowThreadDiskReads()
        try {
            PreferenceManager.setDefaultValues(
                // Passing the service as the context produces "StrictMode policy violation:
                // android.os.strictmode.IncorrectContextUseViolation: The API:LayoutInflater needs
                // a proper configuration. Use UI contexts such as an activity or a context created via
                // createWindowContext(Display, int, Bundle) or
                // createConfigurationContext(Configuration) with a proper configuration."
                createConfigurationContext(res.getConfiguration()),
                R.xml.settings,
                // Without passing true for readAgain, "this method sets the default values only if
                // this method has never been called in the past".
                true,
            )
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
            applyPref(NupPreferences.GAIN_TYPE)
            applyPref(NupPreferences.PRE_AMP_GAIN)
            applyPref(NupPreferences.USERNAME)
            applyPref(NupPreferences.SERVER_URL)
            applyPref(NupPreferences.SONGS_TO_PRELOAD)
            applyPref(NupPreferences.GUEST_MODE)

            restorePlaylist()
            playlistRestored = true
            songListener?.onPlaylistRestore()

            // We need to load prefs before we can send pending reports.
            playbackReporter.start()
        }

        mediaSessionManager = MediaSessionManager(
            this,
            object : MediaSessionCompat.Callback() {
                override fun onAddQueueItem(description: MediaDescriptionCompat) {
                    val id = description.getMediaId() ?: return
                    Log.d(TAG, "MediaSession request to add $id")
                    getSongsForMediaId(id) { appendSongsToPlaylist(it) }
                }
                override fun onAddQueueItem(description: MediaDescriptionCompat, index: Int) {
                    val id = description.getMediaId() ?: return
                    Log.d(TAG, "MediaSession request to add $id at $index")
                    getSongsForMediaId(id) { insertSongs(it, index) }
                }
                override fun onPause() {
                    Log.d(TAG, "MediaSession request to pause")
                    pause()
                }
                override fun onPlay() {
                    Log.d(TAG, "MediaSession request to play")
                    gotPlayRequest = true
                    unpause()
                }
                override fun onPlayFromMediaId(mediaId: String, extras: Bundle) {
                    Log.d(TAG, "MediaSession request to play $mediaId")
                    // Clearing the playlist before doing the potentially-slow async search seems to
                    // convince Android Auto to display a nice "Getting your selection..." message:
                    // https://github.com/derat/nup-android/issues/31
                    clearPlaylist()
                    getSongsForMediaId(mediaId) { songs ->
                        if (songs.isEmpty()) {
                            // This replaces the "Getting your selection..." message with an error.
                            updatePlaybackState(errorMsg = getText(R.string.no_results).toString())
                        } else {
                            appendSongsToPlaylist(songs)
                            unpause()
                        }
                    }
                }
                override fun onPlayFromSearch(query: String, extras: Bundle) {
                    Log.d(TAG, "MediaSession request to play from query \"$query\"")
                    // For some reason, onPrepareFromSearch() never gets called when I do a voice
                    // search from Android Auto, even after updating MediaSessionManager to include
                    // ACTION_PREPARE_FROM_SEARCH in the action bitfield. onPlayFromSearch() only
                    // gets called after the Assistant finishes saying "Okay, asking nup to play
                    // ...", so I was hoping that I could get an early start on the query.
                    scope.launch(Dispatchers.Main) {
                        val songs = async(Dispatchers.IO) {
                            searchLocal(
                                songDb,
                                query,
                                title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE),
                                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                                album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                                online = networkHelper.isNetworkAvailable,
                            )
                        }.await()
                        if (!songs.isEmpty()) {
                            clearPlaylist()
                            appendSongsToPlaylist(songs)
                            unpause()
                        }
                    }
                }
                override fun onRemoveQueueItem(description: MediaDescriptionCompat) {
                    val id = description.getMediaId() ?: return
                    Log.d(TAG, "MediaSession request to remove $id")
                    // TODO: Per the docs, this is supposed to only remove the first occurrence of
                    // the item, not all occurrences.
                    getSongsForMediaId(id) { songs ->
                        songs.map { getSongIndex(it.id) }
                            .filter { it >= 0 }
                            .sortedDescending()
                            .forEach { removeFromPlaylist(it) }
                    }
                }
                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "MediaSession request to seek to $pos")
                    player.seek(pos)
                }
                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession request to skip to next")
                    selectSongAtIndex(curSongIndex + 1)
                }
                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession request to skip to previous")
                    selectSongAtIndex(curSongIndex - 1)
                }
                override fun onSkipToQueueItem(id: Long) {
                    Log.d(TAG, "MediaSession request to skip to $id")
                    selectSongAtIndex(id.toInt())
                }
                override fun onStop() {
                    Log.d(TAG, "MediaSession request to stop")
                    pause()
                }
            }
        )
        sessionToken = mediaSessionManager.session.sessionToken

        mediaBrowserHelper = MediaBrowserHelper(this, songDb, downloader, networkHelper, scope, res)

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationCreator = NotificationCreator(
            this,
            notificationManager,
            mediaSessionManager.session,
        )
        startForeground(NOTIFICATION_ID, notificationCreator.createNotification(false))

        // Create a context that can be used to display toasts.
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        windowContext =
            if (Build.VERSION.SDK_INT >= 30) createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
            else this@NupService

        // TODO: This isn't great for a bunch of reasons:
        // - It doesn't update when the screen is rotated
        //   (https://github.com/derat/nup-android/issues/49) or the activity is resized for some
        //   other reason.
        // - I'm hardcoding 0.5 for the right edge of the text instead of recomputing this
        //   dynamically based on the text width (which would require doing this in the activity
        //   instead).
        // - The TextViews seem to have some additional vertical padding, but I'm not sure what it
        //   is so I'm just multiplying by 3.6 instead of 3 (which seems like it still may not be
        //   enough).
        val coverSize =
            getApplicationContext().getResources().getDisplayMetrics().widthPixels.toFloat()
        coverTextLeft = res.getDimension(R.dimen.horizontal_padding) / coverSize
        coverTextRight = 0.5f
        coverTextBottom = (coverSize - res.getDimension(R.dimen.vertical_padding)) / coverSize
        coverTextTop = coverTextBottom -
            3.6f * (res.getDimension(R.dimen.current_song_text) / coverSize)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        if (Build.VERSION.SDK_INT >= 26) {
            audioManager.abandonAudioFocusRequest(audioFocusReq)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }

        if (this::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }

        savePlaylist()

        // Make sure that coroutines don't run after SongDatabase has been dismantled:
        // https://github.com/derat/nup-android/issues/17
        scope.cancel()

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

    fun addSizeChangeListener(listener: SizeChangeListener) {
        sizeChangeListeners.add(listener)
    }
    fun removeSizeChangeListener(listener: SizeChangeListener) {
        sizeChangeListeners.remove(listener)
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
        // Android Auto appears to pass a rootHints with android.service.media.extra.RECENT set to
        // true, but seemingly only *after* sending a MediaSession play request. This seems to be at
        // odds with the suggestions at
        // https://android-developers.googleblog.com/2020/08/playing-nicely-with-media-controls.html
        val extras = Bundle()
        extras.putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
        return MediaBrowserServiceCompat.BrowserRoot(MediaBrowserHelper.ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaItem>>
    ) = mediaBrowserHelper.onLoadChildren(parentId, result)

    override fun onSearch(
        query: String,
        extras: Bundle,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaItem>>
    ) {
        // Spoken searches in Android Auto go to onPlayFromSearch(). After it returns results, the
        // top-level browsing view contains a browsable item called "Search results" that calls this
        // method when clicked. The args here seem to be similar to onPlayFromSearch(), except we
        // need to return the songs as MediaItems instead of playing them.
        result.detach()
        scope.launch(Dispatchers.Main) {
            val songs = async(Dispatchers.IO) {
                searchLocal(
                    songDb,
                    query,
                    title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE),
                    artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                    album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                    online = networkHelper.isNetworkAvailable,
                )
            }.await()
            val items = mutableListOf<MediaItem>()
            for (song in songs) items.add(mediaBrowserHelper.makeSongItem(song))
            result.sendResult(items)
        }
    }

    /** Read and apply [key]. */
    private suspend fun applyPref(key: String) {
        when (key) {
            NupPreferences.CACHE_SIZE -> {
                val size = readStringPref(key).toLong()
                cache.maxBytes = if (size > 0) size * 1024 * 1024 else size
            }
            NupPreferences.DOWNLOAD_RATE -> {
                val rate = readStringPref(key).toLong()
                cache.maxRate = if (rate > 0) rate * 1024 else rate
            }
            NupPreferences.PASSWORD -> downloader.password = readStringPref(key)
            NupPreferences.GAIN_TYPE -> player.gainType = readStringPref(key)
            NupPreferences.PRE_AMP_GAIN -> player.preAmpGain = readStringPref(key).toDouble()
            NupPreferences.USERNAME -> downloader.username = readStringPref(key)
            NupPreferences.SERVER_URL -> downloader.server = readStringPref(key)
            NupPreferences.SONGS_TO_PRELOAD -> songsToPreload = readStringPref(key).toInt()
            NupPreferences.GUEST_MODE -> {
                guestMode = readBooleanPref(key)
                shouldReportPlays = !guestMode
            }
        }
    }

    /** Return [key]'s value. */
    private suspend fun readStringPref(key: String) =
        scope.async(Dispatchers.IO) { prefs.getString(key, null)!! }.await()
    private suspend fun readBooleanPref(key: String) =
        scope.async(Dispatchers.IO) { prefs.getBoolean(key, false) }.await()

    /** Save playlist so it can be restored the next time the service starts. */
    private fun savePlaylist() {
        // Android Auto likes starting us and then killing us immediately.
        // Avoid overwriting the saved playlist if we didn't get a chance to load it.
        if (!this::prefs.isInitialized || !playlistRestored) return

        val origPolicy = StrictMode.allowThreadDiskWrites()
        try {
            val editor = prefs.edit()
            if (playlist.isEmpty() || (curSongIndex == playlist.size - 1 && playbackComplete)) {
                Log.d(TAG, "Saving empty playlist")
                editor.putString(NupPreferences.PREV_PLAYLIST_SONG_IDS, "")
                editor.putInt(NupPreferences.PREV_PLAYLIST_INDEX, -1)
                editor.putInt(NupPreferences.PREV_POSITION_MS, 0)
            } else {
                Log.d(TAG, "Saving playlist with ${playlist.size} song(s)")
                editor.putString(
                    NupPreferences.PREV_PLAYLIST_SONG_IDS,
                    playlist.map { s -> s.id }.joinToString(",")
                )
                editor.putInt(NupPreferences.PREV_PLAYLIST_INDEX, curSongIndex)
                editor.putInt(NupPreferences.PREV_POSITION_MS, lastPosMs)
            }
            editor.putLong(NupPreferences.PREV_EXIT_MS, Date().getTime())
            editor.commit()
        } finally {
            StrictMode.setThreadPolicy(origPolicy)
        }
    }

    /** Restore the previously-saved playlist (maybe). */
    private suspend fun restorePlaylist() {
        var oldSongs = listOf<Song>()
        var oldIndex = -1
        var oldPosMs = 0
        var oldExitMs = 0L

        scope.async(Dispatchers.IO) {
            oldIndex = prefs.getInt(NupPreferences.PREV_PLAYLIST_INDEX, -1)
            oldPosMs = prefs.getInt(NupPreferences.PREV_POSITION_MS, 0)
            oldExitMs = prefs.getLong(NupPreferences.PREV_EXIT_MS, 0)

            val idsPref = prefs.getString(NupPreferences.PREV_PLAYLIST_SONG_IDS, "")!!
            if (idsPref != "") {
                val ids = idsPref.split(",").map { it.toLong() }
                songDb.getSongs(ids, origIndex = oldIndex).let {
                    oldSongs = it.first
                    oldIndex = it.second
                }
            }
        }.await()

        val elapsedMs = Date().getTime() - oldExitMs
        if (elapsedMs > MAX_RESTORE_AGE_MS || oldSongs.isEmpty()) return

        // This is hacky. By default, auto-pause before restoring the previous state to make sure
        // that we don't abruptly start playing as soon as we're launched. Android Auto can still
        // send a play request at startup if it's been configured to do so. That request can arrive
        // before this method runs, so if we saw it already, start playing here.
        if (!gotPlayRequest) pause()
        Log.d(TAG, "Restoring playlist with ${oldSongs.size} song(s)")
        clearPlaylist()
        addSongsToPlaylist(oldSongs, selectIndex = oldIndex)
        player.seek(oldPosMs.toLong())
    }

    /** Updates the currently-displayed notification if needed.  */
    private fun updateNotification() {
        val notification = notificationCreator.createNotification(true)
        if (notification != null) notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun pause(forTransientFocusLoss: Boolean = false) {
        unpauseOnFocusGain = forTransientFocusLoss && !paused
        player.pause()
    }
    fun unpause() {
        if (!getAudioFocus()) Log.w(TAG, "Couldn't get focus to unpause")
        unpauseOnFocusGain = false
        player.unpause()
    }
    fun togglePause() {
        // Our paused state can be stale, but this is hopefully better than nothing.
        if (paused && !getAudioFocus()) Log.w(TAG, "Couldn't get focus to toggle pause")
        unpauseOnFocusGain = false
        player.togglePause()
    }

    fun seek(posMs: Long) = player.seek(posMs)

    /**
     * Request the audio focus if it isn't already held.
     *
     * More information about this mess:
     * https://developer.android.com/guide/topics/media-apps/audio-focus
     * https://developer.android.com/reference/android/media/AudioFocusRequest
     *
     * See also library/core/src/main/java/com/google/android/exoplayer2/AudioFocusManager.java
     * in https://github.com/google/ExoPlayer.
     *
     * @return true if the audio focus is now held
     */
    private fun getAudioFocus(): Boolean {
        if (haveFocus) return true

        val res =
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.requestAudioFocus(audioFocusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        Log.d(TAG, "Requested audio focus; got $res")
        haveFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return haveFocus
    }

    fun appendSongsToPlaylist(songs: List<Song>) { insertSongs(songs, playlist.size) }
    fun appendSongToPlaylist(song: Song) = appendSongsToPlaylist(ArrayList(Arrays.asList(song)))

    /**
     * Add [songs] to the playlist after the current song (if any).
     *
     * @param selectIndex passed to [insertSongs] to override selection logic
     */
    fun addSongsToPlaylist(songs: List<Song>, selectIndex: Int = -1) =
        insertSongs(
            songs,
            if (curSongIndex < 0) 0 else curSongIndex + 1,
            selectIndex = selectIndex
        )

    /** Add [song] to the playlist after the current song (if any).
     *
     * @param forceSelect force selecting [song]
     */
    fun addSongToPlaylist(song: Song, forceSelect: Boolean = false) =
        addSongsToPlaylist(
            ArrayList(Arrays.asList(song)),
            selectIndex = if (forceSelect && curSongIndex >= 0) curSongIndex + 1 else -1
        )

    /** Remove a range of songs from the playlist. */
    fun removeRangeFromPlaylist(firstIndex: Int, lastIndex: Int) {
        val first = Math.max(firstIndex, 0)
        val last = Math.min(lastIndex, playlist.size - 1)

        var removedPlaying = false
        for (numToRemove in (last - first + 1) downTo 1) {
            _playlist.removeAt(first)
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

        if (playlist.isEmpty()) player.shuffled = false

        if (removedPlaying) {
            if (curSongIndex in 0 until playlist.size) {
                selectSongAtIndex(curSongIndex)
            } else if (!playlist.isEmpty()) {
                pause()
                selectSongAtIndex(playlist.size - 1)
            } else {
                curSongIndex = -1
                mediaSessionManager.updateSong(null, null)
                updatePlaybackState()
            }
        }

        // Maybe the e.g. now-next-to-be-played song isn't downloaded yet.
        if (downloadSongId == -1L && !playlist.isEmpty() && curSongIndex < playlist.size - 1) {
            maybeDownloadAnotherSong(curSongIndex + 1)
        }

        songListener?.onPlaylistChange(playlist)
        mediaSessionManager.updatePlaylist(playlist)
        updatePlaybackState()
        updateNotification()
    }
    /** Convenience wrapper around [removeRangeFromPlaylist]. */
    fun removeFromPlaylist(index: Int) = removeRangeFromPlaylist(index, index)
    /** Convenience wrapper around [removeRangeFromPlaylist]. */
    fun clearPlaylist() = removeRangeFromPlaylist(0, playlist.size - 1)

    /**
     * Select the song at [index] in [playlist].
     *
     * The play/pause state is not changed.
     */
    fun selectSongAtIndex(index: Int) {
        if (index < 0 || index >= playlist.size) return

        curSongIndex = index
        val song = curSong!!

        playStart = null
        lastPosMs = 0
        playedMs = 0
        reported = false
        playbackComplete = false
        lastDownloadedBytes = 0L
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
                player.queueFile(
                    nextEntry.file, nextEntry.totalBytes, next.trackGain, next.albumGain,
                    next.peakAmp
                )
            }
        }

        // Make sure that we won't drop the song that we're currently playing from the cache.
        cache.pinSongId(song.id)
        fetchCoverForSongIfMissing(song)
        mediaSessionManager.updateSong(song, cacheEntry)
        updatePlaybackState()
        updateNotification()
        songListener?.onSongChange(song, curSongIndex)
    }

    /** Get a song's index in the playlist, or -1 if it isn't present. */
    fun getSongIndex(songId: Long) = playlist.indexOfFirst { it.id == songId }

    /** Asynchronously get songs for [id] and pass them to [fn]. */
    fun getSongsForMediaId(id: String, fn: (List<Song>) -> Unit) {
        scope.launch(Dispatchers.Main) {
            fn(async(Dispatchers.IO) { mediaBrowserHelper.getSongsForMediaId(id) }.await())
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
            val bitmap = async(Dispatchers.IO) { coverLoader.getBitmap(song.coverFilename) }.await()
            val brightness = async(Dispatchers.IO) {
                computeBrightness(
                    bitmap, left = coverTextLeft, top = coverTextTop,
                    right = coverTextRight, bottom = coverTextBottom
                )
            }.await()
            storeCoverForSong(song, bitmap, brightness)
            songCoverFetches.remove(song)

            if (song.coverBitmap != null) {
                songListener?.onSongCoverLoad(song)
                if (song == curSong) {
                    mediaSessionManager.updateSong(song, cache.getEntry(song.id))
                    updateNotification()
                }
            }
        }
    }

    val totalCachedBytes: Long get() = cache.totalCachedBytes
    fun clearCache() { cache.clear() }

    val totalCoverBytes: Long get() = coverLoader.totalBytes
    fun clearCovers() {
        scope.launch(Dispatchers.Main) {
            async(Dispatchers.IO) { coverLoader.clear() }.await()
            sizeChangeListeners.forEach { it.onCoversSizeChange() }
        }
    }

    override fun onPlaybackComplete() {
        assertOnMainThread()
        playbackComplete = true
        updatePlaybackState()
        updateNotification()
        if (curSongIndex < playlist.size - 1) selectSongAtIndex(curSongIndex + 1)
        savePlaylist()
    }

    override fun onPlaybackPositionChange(file: File, positionMs: Int, durationMs: Int) {
        assertOnMainThread()
        if (file != curFile) return
        val song = curSong!!
        songListener?.onSongPositionChange(song, positionMs, durationMs)
        val elapsed = positionMs - lastPosMs
        if (elapsed > 0 && elapsed <= MAX_CONTINUOUS_PLAYBACK_MS) {
            playedMs += elapsed.toLong()
            // Report the song after we've played more than half of it (or more than a reasonable
            // fixed amount, to handle long songs). This is based on the Last.fm/Audioscrobbler
            // guidelines (minus the dumb "track must be longer than 30 seconds" rule):
            // https://www.last.fm/api/scrobbling#scrobble-requests
            val halfMs = Math.max(durationMs, (song.lengthSec * 1000).toInt()).toLong() / 2
            if (!reported && playedMs >= Math.min(halfMs, REPORT_PLAYBACK_THRESHOLD_MS)) {
                if (shouldReportPlays) {
                    val start = playStart!!
                    scope.launch(Dispatchers.IO) { playbackReporter.report(song.id, start) }
                } else {
                    Log.d(TAG, "Not reporting play of ${song.id} due to guest mode")
                }
                reported = true
            }
        }
        lastPosMs = positionMs
        updatePlaybackState()
    }

    override fun onPauseStateChange(paused: Boolean) {
        assertOnMainThread()
        this.paused = paused
        songListener?.onPauseStateChange(this.paused)
        updatePlaybackState()
        updateNotification()
        if (this.paused) savePlaylist()
    }

    override fun onPlaybackError(description: String) {
        assertOnMainThread()
        showToast(description, Toast.LENGTH_LONG)
        updatePlaybackState(errorMsg = description)
    }

    override fun onCacheDownloadError(entry: FileCacheEntry, reason: String) {
        assertOnMainThread()
        showToast("Got retryable error: $reason", Toast.LENGTH_SHORT)
    }

    override fun onCacheDownloadFail(entry: FileCacheEntry, reason: String) {
        assertOnMainThread()
        Log.d(TAG, "Download of song ${entry.songId} failed: $reason")

        val fn = songIdToSong[entry.songId]?.filename
        showToast("Download of \"$fn\" failed: $reason", Toast.LENGTH_LONG)

        // TODO: How should this be handled? Just try to download the next song?
        // Remove the song from the playlist?
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
        scope.launch(Dispatchers.IO) { songDb.handleSongCached(song.id) }

        if (song == curSong) {
            if (waitingForDownload) {
                waitingForDownload = false
                playCacheEntry(entry)
            }
            mediaSessionManager.updateSong(song, entry)
        } else if (song == nextSong) {
            player.queueFile(
                entry.file, entry.totalBytes, song.trackGain, song.albumGain, song.peakAmp
            )
        }

        songListener?.onSongFileSizeChange(song)
        sizeChangeListeners.forEach { it.onCacheSizeChange() }

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
            // This code used to start playing if it we were downloading the file fast enough that
            // we were likely to finish before the end of playback, but that would often result in
            // skipping. Downloads are fast enough now that it seems best to always wait for the
            // whole file before playing: https://github.com/derat/nup-android/issues/18
            if (waitingForDownload && entry.isFullyCached) {
                waitingForDownload = false
                playCacheEntry(entry)
            }

            // The media session metadata only has downloading/downloaded/not-downloaded states,
            // so just call this once after the download starts.
            if (downloadedBytes > 0L && lastDownloadedBytes == 0L) {
                mediaSessionManager.updateSong(song, entry)
            }
            lastDownloadedBytes = downloadedBytes
        }
        songListener?.onSongFileSizeChange(song)
    }

    override fun onCacheEviction(entry: FileCacheEntry) {
        assertOnMainThread()
        Log.d(TAG, "Song ${entry.songId} evicted")

        // TODO: I think that there's a bit of a race here, in that (as I understand it from
        // https://discuss.kotlinlang.org/t/coroutines-execution-order-when-launching-them-sequentially/22069),
        // there's no guarantee about the execution order of Dispatchers.IO, and it's possible that
        // handleSongCached and handleSongEvicted calls could get swapped. I'm hopeful that it's
        // unlikely to ever be a problem in practice, and SongDatabase recreates the CachedSongs
        // table on startup in any case.
        scope.launch(Dispatchers.IO) { songDb.handleSongEvicted(entry.songId) }
        val song = songIdToSong[entry.songId]
        if (song != null) {
            song.availableBytes = 0
            song.totalBytes = 0
            songListener?.onSongFileSizeChange(song)
        }
        sizeChangeListeners.forEach { it.onCacheSizeChange() }
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
        Log.d(TAG, "Sync ${if (success) "succeeded" else "failed"}: $message")
        showToast(message, Toast.LENGTH_SHORT)
    }

    override fun onAggregateDataUpdate() {
        assertOnMainThread()
        Log.d(TAG, "Aggregate data updated")
        for (listener in songDatabaseUpdateListeners) listener.onSongDatabaseUpdate()
        mediaBrowserHelper.notifyForSongDatabaseUpdate()
    }

    /**
     * Insert [songs] into the playlist at [index].
     *
     * By default, also selects the first inserted song if no song was already selected or if we
     * were previously at the end of the playlist and we've appended to it. [selectIndex] can be
     * passed to override this behavior.
     *
     * @param songs songs to insert
     * @param index index at which the songs should be inserted
     * @param selectIndex index in updated playlist to select if >= 0
     */
    private fun insertSongs(songs: List<Song>, index: Int, selectIndex: Int = -1) {
        if (index < 0 || index > playlist.size) {
            Log.e(TAG, "Ignoring request to insert ${songs.size} song(s) at index $index")
            return
        }

        // Songs that we didn't already have. We track these so we can check
        // the cache for them later.
        val newSongs = ArrayList<Song>()

        // Use the existing version of each song if we have it already.
        val resSongs = ArrayList<Song>()
        for (song in songs) {
            val ourSong = songIdToSong[song.id]
            if (ourSong != null) {
                resSongs.add(ourSong)
            } else {
                resSongs.add(song)
                newSongs.add(song)
            }
        }

        _playlist.addAll(index, resSongs)
        if (curSongIndex >= 0 && index <= curSongIndex) curSongIndex += resSongs.size
        if (downloadIndex >= 0 && index <= downloadIndex) downloadIndex += resSongs.size

        // Make a half-hearted guess about whether the songs were shuffled. This determines whether
        // "auto" gain adjustment uses per-track adjustments rather than per-album.
        if (songs.size == 1 || !sameAlbum(resSongs)) player.shuffled = true

        songListener?.onPlaylistChange(playlist)
        mediaSessionManager.updatePlaylist(playlist)
        for (song in newSongs) {
            songIdToSong[song.id] = song
            val entry = cache.getEntry(song.id)
            if (entry != null) {
                song.updateBytes(entry)
                songListener?.onSongFileSizeChange(song)
            }
        }

        when {
            // If a particular song was requested, play it.
            selectIndex >= 0 -> selectSongAtIndex(selectIndex)
            // If we didn't have any songs, then start playing the first one we added.
            curSongIndex == -1 -> selectSongAtIndex(0)
            // If we were previously done playing (because we reached the end of the playlist),
            // then start playing the first song we added.
            curSongIndex < playlist.size - 1 && playbackComplete ->
                selectSongAtIndex(curSongIndex + 1)
            else -> {
                // Consider downloading the new songs if we're not already downloading something.
                if (downloadSongId == -1L) maybeDownloadAnotherSong(index)

                // If we just inserted the song to play next, queue it.
                val song = nextSong
                if (index == curSongIndex + 1 && song != null) {
                    val nextEntry = cache.getEntry(song.id)
                    if (nextEntry != null && nextEntry.isFullyCached) {
                        player.queueFile(
                            nextEntry.file, nextEntry.totalBytes, song.trackGain, song.albumGain,
                            song.peakAmp,
                        )
                    }
                }
            }
        }
        updatePlaybackState()
        updateNotification()
    }

    /** Play [entry] (if we aren't paused). */
    private fun playCacheEntry(entry: FileCacheEntry) {
        val song = curSong ?: return
        if (song.id != entry.songId) {
            Log.e(TAG, "Cache entry ${entry.songId} doesn't match current song ${song.id}")
            return
        }
        if (!paused && !getAudioFocus()) Log.w(TAG, "Couldn't get focus to play ${entry.songId}")
        curFile = entry.file
        player.loadFile(entry.file, entry.totalBytes, song.trackGain, song.albumGain, song.peakAmp)
        playStart = Date()
        cache.updateLastAccessTime(entry.songId)
        updatePlaybackState()
        updateNotification()
    }

    /** Try to download the next not-yet-downloaded song in the playlist. */
    private fun maybeDownloadAnotherSong(startIndex: Int) {
        assertOnMainThread()

        if (downloadSongId != -1L) {
            Log.e(TAG, "Aborting prefetch since download of song $downloadSongId is in progress")
            return
        }

        for (idx in startIndex until playlist.size) {
            if (idx - curSongIndex > songsToPreload && !shouldDownloadAll) break

            val song = playlist[idx]
            cache.pinSongId(song.id) // make sure it doesn't get evicted by a later song

            val entry = cache.getEntry(song.id)
            if (entry == null || !entry.isFullyCached) {
                downloadSongId = song.id
                downloadIndex = idx
                cache.downloadSong(song)
                fetchCoverForSongIfMissing(song)
                break
            }
        }
    }

    /**
     * Set [song]'s cover bitmap to [bitmap] and store the bitmap's brightness.
     *
     * Also makes sure that we don't have more than [MAX_LOADED_COVERS] bitmaps in-memory.
     */
    private fun storeCoverForSong(song: Song, bitmap: Bitmap?, brightness: Brightness?) {
        song.coverBitmap = bitmap
        song.coverBrightness = brightness
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
            songsWithCovers[0].coverBrightness = null
            songsWithCovers.removeAt(0)
        }
        songsWithCovers.add(song)
    }

    /** Notify [mediaSessionManager] about the current playback state. */
    private fun updatePlaybackState(errorMsg: String? = null) {
        mediaSessionManager.updatePlaybackState(
            song = curSong,
            paused = paused,
            playbackComplete = playbackComplete,
            buffering = waitingForDownload,
            positionMs = lastPosMs.toLong(),
            songIndex = curSongIndex,
            numSongs = playlist.size,
            errorMsg = errorMsg,
        )
    }

    /** Display a toast containing [text] for [duration], e.g. [Toast.LENGTH_SHORT]. */
    private fun showToast(text: CharSequence, duration: Int) {
        // Passing the service's context produces "StrictMode: StrictMode policy violation:
        // android.os.strictmode.IncorrectContextUseViolation: WindowManager should be accessed
        // from Activity or other visual Context. Use an Activity or a Context created with
        // Context#createWindowContext(int, Bundle), which are adjusted to the configuration
        // and visual bounds of an area on screen."
        Toast.makeText(windowContext, text, duration).show()
    }

    companion object {
        private const val TAG = "NupService"

        private const val NOTIFICATION_ID = 1 // "currently playing" notification (can't be 0)
        private const val MAX_LOADED_COVERS = 3 // max number of cover bitmaps to keep in memory
        private const val MAX_CONTINUOUS_PLAYBACK_MS = 5 * 1000L // position change threshold
        private const val REPORT_PLAYBACK_THRESHOLD_MS = 240 * 1000L // always report after 4 min
        private const val IGNORE_NOISY_AUDIO_AFTER_USER_SWITCH_MS = 1000L
        private const val MAX_RESTORE_AGE_MS = 12 * 3600 * 1000L // max age for restoring state
        private const val COVER_TOP_PCT = 0.8 // fraction from top of image where text starts
        private const val COVER_RIGHT_PCT = 0.5 // fraction from left edge where text (mostly) stops
    }
}
