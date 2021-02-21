/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
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
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Main activity showing current song and playlist. */
class NupActivity : AppCompatActivity(), NupService.SongListener {
    private var songs = listOf<Song>() // current playlist
    private var curSongIndex = -1 // index of current song in [songs]
    private val curSong: Song?
        get() = if (curSongIndex in 0 until songs.size) songs[curSongIndex] else null

    private var lastPosSec = -1 // last song position time passed to [onSongPositionChange]
    private val songListAdapter = SongListAdapter() // adapts [songs] for [playlistView]

    // Context.mainExecutor is annoyingly an Executor rather than a ScheduledExecutorService,
    // so it seems like we need a Handler instead to be able post delayed tasks.
    private val handler = Handler(Looper.getMainLooper())
    private val playSongTask = Runnable { _service?.playSongAtIndex(curSongIndex) }

    private lateinit var pauseButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var albumImageView: ImageView
    private lateinit var artistLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var albumLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var downloadStatusLabel: TextView
    private lateinit var playlistView: ListView

    public override fun onCreate(savedInstanceState: Bundle?) {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeathOnNetwork()
                .build()
        )
        // TODO: Not including detectActivityLeaks() since I'm getting leaks of Browse*Activity
        // objects that I don't understand.
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .penaltyDeath()
                .build()
        )

        super.onCreate(savedInstanceState)
        Log.d(TAG, "Activity created")

        setContentView(R.layout.main)

        pauseButton = findViewById<Button>(R.id.pause_button)
        prevButton = findViewById<Button>(R.id.prev_button)
        nextButton = findViewById<Button>(R.id.next_button)
        albumImageView = findViewById<ImageView>(R.id.album_image)
        artistLabel = findViewById<TextView>(R.id.artist_label)
        titleLabel = findViewById<TextView>(R.id.title_label)
        albumLabel = findViewById<TextView>(R.id.album_label)
        timeLabel = findViewById<TextView>(R.id.time_label)
        downloadStatusLabel = findViewById<TextView>(R.id.download_status_label)

        playlistView = findViewById<ListView>(R.id.playlist)
        registerForContextMenu(playlistView)
        playlistView.adapter = songListAdapter

        val serviceIntent = Intent(this, NupService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, 0)

        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onDestroy() {
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
        _service?.unregisterListener(this)

        // Shut down the service as well if the playlist is empty.
        val stopService = _service?.songs?.isEmpty() ?: false
        unbindService(connection)
        if (stopService) stopService(Intent(this, NupService::class.java))
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.d(TAG, "Connected to service")
            _service = (binder as NupService.LocalBinder).service
            service.setSongListener(this@NupActivity)

            // Get current state from service.
            onPlaylistChange(service.songs)
            onPauseStateChange(service.paused)
            val song = curSong
            if (song != null && song == service.curSong) {
                onSongPositionChange(song, service.lastPosMs, 0)
                playlistView.smoothScrollToPosition(curSongIndex)
            }

            // TODO: Go to prefs page if server and account are unset.
            if (songs.isEmpty()) {
                startActivity(Intent(this@NupActivity, BrowseTopActivity::class.java))
            }
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

    override fun onSongChange(song: Song, index: Int) {
        runOnUiThread { setCurrentSong(index) }
    }

    override fun onSongPositionChange(song: Song, positionMs: Int, durationMs: Int) {
        runOnUiThread(
            task@{
                if (song != curSong) return@task
                val sec = positionMs / 1000
                if (sec == lastPosSec) return@task
                // MediaPlayer appears to get confused sometimes and report things like 0:01.
                // TODO: Is this still the case?
                val durationSec = Math.max(durationMs / 1000, song.lengthSec)
                timeLabel.text = formatDurationProgress(sec, durationSec)
                lastPosSec = sec
            }
        )
    }

    override fun onPauseStateChange(paused: Boolean) {
        runOnUiThread {
            pauseButton.text = getString(if (paused) R.string.play else R.string.pause)
        }
    }

    override fun onSongCoverLoad(song: Song) {
        runOnUiThread {
            if (song == curSong) {
                albumImageView.visibility = View.VISIBLE
                albumImageView.setImageBitmap(song.coverBitmap)
            }
        }
    }

    override fun onPlaylistChange(songs: List<Song>) {
        runOnUiThread {
            this.songs = songs
            findViewById<View>(R.id.playlist_heading).visibility =
                if (this.songs.isEmpty()) View.INVISIBLE else View.VISIBLE
            setCurrentSong(service.curSongIndex)
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
        if (song == null) {
            artistLabel.text = ""
            titleLabel.text = ""
            albumLabel.text = ""
            timeLabel.text = ""
            downloadStatusLabel.text = ""
            albumImageView.visibility = View.INVISIBLE
        } else {
            artistLabel.text = song.artist
            titleLabel.text = song.title
            albumLabel.text = song.album
            timeLabel.text = formatDurationProgress(
                if (song == service.curSong) service.lastPosMs / 1000 else 0,
                song.lengthSec
            )
            downloadStatusLabel.text = ""

            if (song.coverBitmap != null) {
                albumImageView.visibility = View.VISIBLE
                albumImageView.setImageBitmap(song.coverBitmap)
            } else {
                albumImageView.visibility = View.INVISIBLE
                service.fetchCoverForSongIfMissing(song)
            }
        }

        // Update the displayed time in response to the next position change we get.
        lastPosSec = -1
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.download_all_menu_item)
        val downloadAll = _service?.shouldDownloadAll ?: false
        item.setTitle(if (downloadAll) R.string.dont_download_all else R.string.download_all)
        item.setIcon(
            if (downloadAll) R.drawable.ic_cloud_off_white_24dp
            else R.drawable.ic_cloud_download_white_24dp
        )
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
                true
            }
            R.id.settings_menu_item -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.exit_menu_item -> {
                stopService(Intent(this, NupService::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

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

            val currentlyPlaying = position == curSongIndex
            view.setBackgroundColor(
                ContextCompat.getColor(
                    this@NupActivity,
                    if (currentlyPlaying) R.color.primary else android.R.color.transparent
                )
            )
            val textColor = ContextCompat.getColor(
                this@NupActivity, if (currentlyPlaying) R.color.icons else R.color.primary_text
            )
            artistView.setTextColor(textColor)
            titleView.setTextColor(textColor)
            percentView.setTextColor(textColor)

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
                showDialog(
                    DIALOG_SONG_DETAILS,
                    SongDetailsDialog.createBundle(songs[info.position])
                )
                true
            }
            else -> false
        }
    }

    override fun onCreateDialog(id: Int, args: Bundle): Dialog? {
        return if (id == DIALOG_SONG_DETAILS) SongDetailsDialog.createDialog(this) else null
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog, args: Bundle) {
        super.onPrepareDialog(id, dialog, args)
        if (id == DIALOG_SONG_DETAILS) SongDetailsDialog.prepareDialog(dialog, args)
    }

    /** Shows the song at [index] as current in the UI. */
    private fun setCurrentSong(index: Int) {
        curSongIndex = index
        updateSongDisplay(curSong)
        songListAdapter.notifyDataSetChanged()
        prevButton.isEnabled = curSongIndex > 0
        nextButton.isEnabled = !songs.isEmpty() && curSongIndex < songs.size - 1
        pauseButton.isEnabled = !songs.isEmpty()
    }

    /** Schedule playing the current song in [delayMs]. */
    private fun schedulePlaySong(delayMs: Long) {
        handler.removeCallbacks(playSongTask)
        handler.postDelayed(playSongTask, delayMs)
    }

    companion object {
        private const val TAG = "NupActivity"

        // Wait this many milliseconds before switching tracks in response to the Prev and Next buttons.
        // This avoids requesting a bunch of tracks that we don't want when the user is repeatedly
        // pressing the button to skip through tracks.
        private const val SONG_CHANGE_DELAY_MS = 500L

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_REMOVE_FROM_LIST = 2
        private const val MENU_ITEM_TRUNCATE_LIST = 3
        private const val MENU_ITEM_SONG_DETAILS = 4
        private const val DIALOG_SONG_DETAILS = 1

        // Persistent service to which we connect.
        private var _service: NupService? = null
        public val service: NupService get() = _service!!
    }
}
