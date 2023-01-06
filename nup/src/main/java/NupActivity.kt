/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton

/** Main activity showing current song and playlist. */
class NupActivity : AppCompatActivity(), NupService.SongListener {
    private var songs = listOf<Song>() // current playlist
    private var curSongIndex = -1 // index of current song in [songs]
    private val curSong: Song?
        get() = if (curSongIndex in 0 until songs.size) songs[curSongIndex] else null

    private val songListAdapter = SongListAdapter() // adapts [songs] for [playlistView]

    // Context.mainExecutor is annoyingly an Executor rather than a ScheduledExecutorService,
    // so it seems like we need a Handler instead to be able post delayed tasks.
    private val handler = Handler(Looper.getMainLooper())
    private val playSongTask = Runnable {
        _service?.selectSongAtIndex(curSongIndex)
        _service?.unpause()
    }

    private var seekbarDragMs = -1 // most-recent [seekbar] position while scrubbing

    private lateinit var currentSongFrame: View
    private lateinit var coverImageView: ImageView
    private lateinit var songTextLayout: View
    private lateinit var artistLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var albumLabel: TextView
    private lateinit var downloadStatusLabel: TextView

    private lateinit var progressRow: View
    private lateinit var positionLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var seekbar: SeekBar

    private lateinit var playbackButtonsRow: View
    private lateinit var pauseButton: MaterialButton
    private lateinit var prevButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private lateinit var playlistView: ListView

    // Used while loading cover images and for songs without cover images.
    private val noCoverBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Activity created")
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, NupService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, 0)

        setContentView(R.layout.main)

        currentSongFrame = findViewById<View>(R.id.current_song_frame)
        coverImageView = findViewById<ImageView>(R.id.cover_image)
        songTextLayout = findViewById<View>(R.id.song_text_layout)
        artistLabel = findViewById<TextView>(R.id.artist_label)
        titleLabel = findViewById<TextView>(R.id.title_label)
        albumLabel = findViewById<TextView>(R.id.album_label)
        downloadStatusLabel = findViewById<TextView>(R.id.download_status_label)

        progressRow = findViewById<View>(R.id.progress_row)
        positionLabel = findViewById<TextView>(R.id.position_label)
        durationLabel = findViewById<TextView>(R.id.duration_label)
        seekbar = findViewById<SeekBar>(R.id.seekbar)
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekbar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                seekbarDragMs = progress
                positionLabel.text = formatDuration(seekbarDragMs / 1000)
            }
            override fun onStartTrackingTouch(seekbar: SeekBar) {
                seekbarDragMs = seekbar.getProgress()
            }
            override fun onStopTrackingTouch(seekbar: SeekBar) {
                // TODO: Only seek if the user dragged? Also try to ignore accidental motion while
                // lifting finger: https://www.erat.org/rants.html#android-sliders
                if (seekbarDragMs >= 0) service.seek(seekbarDragMs.toLong())
                positionLabel.text = formatDuration(service.lastPosMs / 1000)
                seekbarDragMs = -1
            }
        })

        playbackButtonsRow = findViewById<View>(R.id.playback_buttons_row)
        pauseButton = findViewById<MaterialButton>(R.id.pause_button)
        prevButton = findViewById<MaterialButton>(R.id.prev_button)
        nextButton = findViewById<MaterialButton>(R.id.next_button)
        updateButtonStates()

        playlistView = findViewById<ListView>(R.id.playlist)
        registerForContextMenu(playlistView)
        playlistView.adapter = songListAdapter

        volumeControlStream = AudioManager.STREAM_MUSIC

        noCoverBitmap.eraseColor(ContextCompat.getColor(this, R.color.no_song_cover))
    }

    override fun onDestroy() {
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
        _service?.unregisterListener(this)

        // Shut down the service as well if the playlist is empty.
        val stopService = _service?.playlist?.isEmpty() ?: false
        unbindService(connection)
        if (stopService) stopService(Intent(this, NupService::class.java))
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.d(TAG, "Connected to service")
            _service = (binder as NupService.LocalBinder).service
            service.setSongListener(this@NupActivity)

            // Get current state from service.
            onPlaylistChange(service.playlist)
            onPauseStateChange(service.paused)
            val song = curSong
            if (song != null && song == service.curSong) {
                onSongPositionChange(song, service.lastPosMs, -1)
            }

            // TODO: Go to prefs page if server is unset.
            if (service.playlistRestored && songs.isEmpty()) launchBrowser()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.d(TAG, "Disconnected from service")
            _service = null
        }
    }

    fun onPauseButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        service.togglePause()
    }

    fun onPrevButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        if (curSongIndex <= 0) return
        service.stopPlaying()
        setCurrentSong(curSongIndex - 1)
        schedulePlaySong(SONG_CHANGE_DELAY_MS)
    }

    fun onNextButtonClicked(@Suppress("UNUSED_PARAMETER") view: View?) {
        if (curSongIndex >= songs.size - 1) return
        service.stopPlaying()
        setCurrentSong(curSongIndex + 1)
        schedulePlaySong(SONG_CHANGE_DELAY_MS)
    }

    override fun onPlaylistRestore() { if (songs.isEmpty()) launchBrowser() }

    override fun onSongChange(song: Song, index: Int) {
        runOnUiThread { setCurrentSong(index) }
    }

    override fun onSongPositionChange(song: Song, positionMs: Int, durationMs: Int) {
        runOnUiThread(
            task@{
                if (song != curSong) return@task

                // If we've already downloaded the whole song, use the duration computed by
                // MediaPlayer in case it's different from the length in the database.
                val lenMs =
                    if (song.availableBytes == song.totalBytes && durationMs >= 0) durationMs
                    else (song.lengthSec * 1000).toInt()
                seekbar.setMax(lenMs)

                // Avoid messing with the seekbar and labels while dragging.
                val dragging = seekbarDragMs >= 0
                if (!dragging) {
                    seekbar.setProgress(positionMs)
                    positionLabel.text = formatDuration(positionMs / 1000)
                    durationLabel.text = formatDuration(lenMs / 1000)
                }
            }
        )
    }

    override fun onPauseStateChange(paused: Boolean) {
        runOnUiThread {
            pauseButton.setIconResource(if (paused) R.drawable.play else R.drawable.pause)
        }
    }

    override fun onSongCoverLoad(song: Song) {
        runOnUiThread {
            if (song == curSong) {
                coverImageView.setImageBitmap(song.coverBitmap)
                updateSongTextColor(song)
            }
        }
    }

    override fun onPlaylistChange(songs: List<Song>) {
        runOnUiThread {
            this.songs = songs
            findViewById<View>(R.id.playlist_heading).visibility =
                if (this.songs.isEmpty()) View.INVISIBLE else View.VISIBLE
            setCurrentSong(service.curSongIndex)
            playlistView.post({
                playlistView.setSelection(Math.max(curSongIndex - PLAYLIST_SCROLL_THRESHOLD, 0))
            })
        }
    }

    override fun onSongFileSizeChange(song: Song) {
        runOnUiThread {
            val availableBytes = song.availableBytes
            val totalBytes = song.totalBytes
            if (song == curSong) {
                if (availableBytes == totalBytes) {
                    downloadStatusLabel.text = ""
                } else {
                    downloadStatusLabel.text = String.format(
                        "%,d of %,d KB",
                        Math.round(availableBytes / 1024.0),
                        Math.round(totalBytes / 1024.0)
                    )
                }
            }
            songListAdapter.notifyDataSetChanged()
        }
    }

    /** Update onscreen information about the current song. */
    private fun updateSongDisplay(song: Song?) {
        artistLabel.text = song?.artist ?: ""
        titleLabel.text = song?.title ?: ""
        albumLabel.text = song?.album ?: ""
        downloadStatusLabel.text = ""

        if (song != null) {
            val posMs = if (song == service.curSong) service.lastPosMs else 0
            val lenMs = (song.lengthSec * 1000).toInt()
            seekbar.setMax(lenMs)
            seekbar.setProgress(posMs)
            seekbar.setEnabled(true)
            positionLabel.text = formatDuration(posMs / 1000)
            durationLabel.text = formatDuration(lenMs / 1000)
        } else {
            seekbar.setEnabled(false)
            seekbar.setProgress(0)
            positionLabel.text = ""
            durationLabel.text = ""
        }

        coverImageView.setImageBitmap(song?.coverBitmap ?: noCoverBitmap)
        updateSongTextColor(song)
        if (song?.coverBitmap == null && song?.coverFilename != null) {
            service.fetchCoverForSongIfMissing(song)
        }

        // Hide the controls when there's no song.
        val vis = if (song != null) View.VISIBLE else View.INVISIBLE
        currentSongFrame.visibility = vis
        progressRow.visibility = vis
        playbackButtonsRow.visibility = vis
    }

    /** Update text view styles to be legible over [song]'s cover image. */
    private fun updateSongTextColor(song: Song?) {
        // Only display a gradient if the view containing the text has a tag indicating
        // that it'll be drawn over the cover image.
        val overlay = songTextLayout.getTag() == "overlay"
        val brightness = song?.coverBrightness ?: Brightness.DARK

        val style =
            if (!overlay) R.style.CurrentSongText
            else when (brightness) {
                Brightness.DARK, Brightness.DARK_BUSY -> R.style.CurrentSongTextLight
                else -> R.style.CurrentSongTextDark
            }
        for (view in arrayOf(artistLabel, titleLabel, albumLabel, downloadStatusLabel)) {
            TextViewCompat.setTextAppearance(view, style)
        }

        songTextLayout.setBackground(
            if (!overlay) null
            else when (brightness) {
                Brightness.DARK_BUSY ->
                    CoverGradient(ContextCompat.getColor(this, R.color.dark_cover_overlay))
                Brightness.LIGHT_BUSY ->
                    CoverGradient(ContextCompat.getColor(this, R.color.light_cover_overlay))
                else -> null
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // TODO: Material adds gratuitous padding on both sides of menu checkboxes, but I haven't
        // been able to find a good way to remove it (either programatically or via XML).
        // https://stackoverflow.com/questions/69606994 has some discussion, but the workaround is
        // super-gross and involves modifying the default padding of all checkboxes.
        updateDownloadAllMenuItem(menu.findItem(R.id.download_all_menu_item))
        updateReportPlaysMenuItem(menu.findItem(R.id.report_plays_menu_item))
        menu.setGroupVisible(R.id.guest_mode_menu_group, service.guestMode)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // This is hacky, but handle the case where we aren't bound to the service yet.
        _service ?: return false

        return when (item.itemId) {
            R.id.browse_menu_item -> {
                startActivity(Intent(this, BrowseTopActivity::class.java))
                true
            }
            R.id.search_menu_item -> {
                startActivity(Intent(this, SearchFormActivity::class.java))
                true
            }
            R.id.pause_menu_item -> {
                service.pause()
                true
            }
            R.id.download_all_menu_item -> {
                service.shouldDownloadAll = !service.shouldDownloadAll
                updateDownloadAllMenuItem(item)
                true
            }
            R.id.report_plays_menu_item -> {
                // The menu item shouldn't be shown if we aren't in guest mode, but be careful to
                // make sure that we don't get into a state where plays are silently dropped.
                service.shouldReportPlays =
                    if (service.guestMode) !service.shouldReportPlays
                    else true
                updateReportPlaysMenuItem(item)
                true
            }
            R.id.settings_menu_item -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.exit_menu_item -> {
                stopService(Intent(this, NupService::class.java))
                finishAndRemoveTask()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Update the "Download all" menu item's checked state. */
    private fun updateDownloadAllMenuItem(item: MenuItem) =
        item.setChecked(_service?.shouldDownloadAll ?: false)

    /** Update the "Report plays" menu item's checked state. */
    private fun updateReportPlaysMenuItem(item: MenuItem) =
        item.setChecked(_service?.shouldReportPlays ?: false)

    /** Adapts information about the current playlist and song for the song list view. */
    private inner class SongListAdapter : BaseAdapter() {
        override fun getCount(): Int { return songs.size }
        override fun getItem(position: Int): Any { return position }
        override fun getItemId(position: Int): Long { return position.toLong() }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = if (convertView != null) {
                convertView
            } else {
                val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
                inflater.inflate(R.layout.playlist_row, null)
            }

            val artistView = view.findViewById<TextView>(R.id.artist)
            val titleView = view.findViewById<TextView>(R.id.title)
            val percentView = view.findViewById<TextView>(R.id.percent)
            val song = songs[position]

            artistView.text = song.artist
            titleView.text = song.title

            if (song.totalBytes > 0) {
                // CHECK MARK from Dingbats.
                if (song.availableBytes == song.totalBytes) percentView.text = "\u2713"
                else percentView.setText(
                    Math.round(
                        100.0 *
                            song.availableBytes /
                            song.totalBytes
                    ).toInt().toString() + "%"
                )
                percentView.visibility = View.VISIBLE
            } else {
                percentView.visibility = View.GONE
            }

            val active = position == curSongIndex
            view.setBackgroundColor(
                ContextCompat.getColor(
                    this@NupActivity,
                    if (active) R.color.playlist_row_background else android.R.color.transparent
                )
            )

            return view
        }
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        view: View,
        menuInfo: ContextMenuInfo
    ) {
        if (view.id == R.id.playlist) {
            val info = menuInfo as AdapterContextMenuInfo
            val song = songs[info.position]
            menu.setHeaderTitle(song.title)
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play)
            menu.add(0, MENU_ITEM_REMOVE_FROM_LIST, 0, R.string.remove_from_list)
            menu.add(0, MENU_ITEM_TRUNCATE_LIST, 0, R.string.truncate_list)
            menu.add(0, MENU_ITEM_SONG_DETAILS, 0, R.string.song_details_ellipsis)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        return when (item.itemId) {
            MENU_ITEM_PLAY -> {
                setCurrentSong(info.position)
                schedulePlaySong(0)
                true
            }
            MENU_ITEM_REMOVE_FROM_LIST -> {
                service.removeFromPlaylist(info.position)
                true
            }
            MENU_ITEM_TRUNCATE_LIST -> {
                service.removeRangeFromPlaylist(info.position, songs.size - 1)
                true
            }
            MENU_ITEM_SONG_DETAILS -> {
                showSongDetailsDialog(this, songs[info.position])
                true
            }
            else -> false
        }
    }

    /** Shows the song at [index] as current in the UI. */
    private fun setCurrentSong(index: Int) {
        val scroll = curSongIndex != -1
        curSongIndex = index
        updateSongDisplay(curSong)
        songListAdapter.notifyDataSetChanged()
        updateButtonStates()
        if (scroll) scrollPlaylist()
    }

    /** Scrolls [playlistView] so that [curSong] is visible. */
    private fun scrollPlaylist() {
        if (curSong == null) return

        val wantMin = Math.max(curSongIndex - PLAYLIST_SCROLL_THRESHOLD, 0)
        val wantMax = Math.min(curSongIndex + PLAYLIST_SCROLL_THRESHOLD, songs.size - 1)

        // These methods return bogus values (0 and -1, respectively) before the view has been
        // rendered. Note that they also seem to have off-by-one errors at times: getFirst seems
        // to return one less than the first position when it's flush with the top of the view,
        // and getLast returns one greater than the last when it's flush with the bottom.
        val visMin = playlistView.getFirstVisiblePosition()
        val visMax = playlistView.getLastVisiblePosition()

        // Scroll as far as we can while still keeping nearby entries visible. These conditions
        // use >= and <= rather than > and < due to the off-by-one issue described earlier.
        // TODO: This will probably jump back and forth on successive calls to scrollPlaylist() if
        // the device's display is small enough that wantMax-wantMin is greater than visMax-visMin.
        // TODO: The scrolling itself seems to be constant-speed, which looks quite ugly.
        // AbsListView has a nonsensical implementation that prohibits overriding this:
        // https://stackoverflow.com/questions/31064758.
        if (wantMax >= visMax) playlistView.smoothScrollToPosition(songs.size - 1, wantMin)
        else if (wantMin <= visMin) playlistView.smoothScrollToPosition(0, wantMax)
    }

    /** Update state of playback buttons. */
    private fun updateButtonStates() {
        prevButton.isEnabled = curSongIndex > 0
        nextButton.isEnabled = !songs.isEmpty() && curSongIndex < songs.size - 1
        pauseButton.isEnabled = !songs.isEmpty()
    }

    private fun launchBrowser() =
        startActivity(Intent(this@NupActivity, BrowseTopActivity::class.java))

    /** Schedule playing the current song in [delayMs]. */
    private fun schedulePlaySong(delayMs: Long) {
        handler.removeCallbacks(playSongTask)
        handler.postDelayed(playSongTask, delayMs)
    }

    companion object {
        private const val TAG = "NupActivity"
        private const val DISABLED_BUTTON_ALPHA = 64

        // Wait this many milliseconds before switching tracks in response to the Prev and Next buttons.
        // This avoids requesting a bunch of tracks that we don't want when the user is repeatedly
        // pressing the button to skip through tracks.
        private const val SONG_CHANGE_DELAY_MS = 500L

        // Number of playlist entries to keep visible before and after the current song.
        private const val PLAYLIST_SCROLL_THRESHOLD = 2

        // Minimum luminance at which a cover image is considered to be "light", implying that
        // overlaid text should be black rather than white. Chosen based on experimentation, but
        // see also https://stackoverflow.com/a/3943023.
        private const val LIGHT_COVER_MIN_LUMINANCE = 0.4

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_REMOVE_FROM_LIST = 2
        private const val MENU_ITEM_TRUNCATE_LIST = 3
        private const val MENU_ITEM_SONG_DETAILS = 4

        // Persistent service to which we connect.
        private var _service: NupService? = null
        public val service: NupService get() = _service!!
    }
}
