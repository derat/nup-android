// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.
package org.erat.nup

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.widget.*
import android.widget.AdapterView.AdapterContextMenuInfo
import org.erat.nup.NupActivity
import org.erat.nup.NupService
import org.erat.nup.NupService.LocalBinder
import org.erat.nup.NupService.SongListener
import org.erat.nup.SongDetailsDialog.createBundle
import org.erat.nup.SongDetailsDialog.createDialog
import org.erat.nup.SongDetailsDialog.prepareDialog
import org.erat.nup.Util.formatDurationProgressString
import java.util.*

internal class NupActivity : Activity(), SongListener {
    // UI components that we update dynamically.
    private var pauseButton: Button? = null
    private var prevButton: Button? = null
    private var nextButton: Button? = null
    private var albumImageView: ImageView? = null
    private var artistLabel: TextView? = null
    private var titleLabel: TextView? = null
    private var albumLabel: TextView? = null
    private var timeLabel: TextView? = null
    private var downloadStatusLabel: TextView? = null
    private var playlistView: ListView? = null

    // Last song-position time passed to onSongPositionChange(), in seconds.
    // Used to rate-limit how often we update the display so we only do it on integral changes.
    private var lastSongPositionSec = -1

    // Songs in the current playlist.
    private var songs: List<Song>? = ArrayList()

    // Position in |songs| of the song that we're currently displaying.
    private var currentSongIndex = -1

    // Adapts the song listing to |playlistView|.
    private val songListAdapter = SongListAdapter()

    // Used to run tasks on our thread.
    private val handler = Handler()

    // Task that tells the service to play our currently-selected song.
    private val playSongTask = Runnable { service!!.playSongAtIndex(currentSongIndex) }
    public override fun onCreate(savedInstanceState: Bundle?) {
        StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .penaltyDeathOnNetwork()
                        .build())
        StrictMode.setVmPolicy(
                VmPolicy.Builder()
                        .detectLeakedClosableObjects()
                        .detectLeakedSqlLiteObjects() // TODO: Not including detectActivityLeaks() since I'm getting leaks of
                        // Browse*Activity
                        // objects that I
                        // don't understand.
                        .penaltyLog()
                        .penaltyDeath()
                        .build())
        super.onCreate(savedInstanceState)
        Log.d(TAG, "activity created")
        setContentView(R.layout.main)
        pauseButton = findViewById<View>(R.id.pause_button) as Button
        prevButton = findViewById<View>(R.id.prev_button) as Button
        nextButton = findViewById<View>(R.id.next_button) as Button
        albumImageView = findViewById<View>(R.id.album_image) as ImageView
        artistLabel = findViewById<View>(R.id.artist_label) as TextView
        titleLabel = findViewById<View>(R.id.title_label) as TextView
        albumLabel = findViewById<View>(R.id.album_label) as TextView
        timeLabel = findViewById<View>(R.id.time_label) as TextView
        downloadStatusLabel = findViewById<View>(R.id.download_status_label) as TextView
        playlistView = findViewById<View>(R.id.playlist) as ListView
        registerForContextMenu(playlistView)
        playlistView!!.adapter = songListAdapter
        val serviceIntent = Intent(this, NupService::class.java)
        startService(serviceIntent)
        bindService(Intent(this, NupService::class.java), connection, 0)
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onDestroy() {
        Log.d(TAG, "activity destroyed")
        super.onDestroy()
        var stopService = false
        if (service != null) {
            service!!.unregisterListener(this)
            // Shut down the service as well if the playlist is empty.
            if (service!!.getSongs().size == 0) stopService = true
        }
        unbindService(connection)
        if (stopService) stopService(Intent(this, NupService::class.java))
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.d(TAG, "connected to service")
            service = (binder as LocalBinder).service
            service!!.setSongListener(this@NupActivity)

            // Get current state from service.
            onPlaylistChange(service!!.getSongs())
            onPauseStateChange(service!!.paused)
            if (currentSong != null) {
                onSongPositionChange(
                        service!!.currentSong,
                        service!!.currentSongLastPositionMs,
                        0)
                playlistView!!.smoothScrollToPosition(currentSongIndex)
            }

            // TODO: Go to prefs page if server and account are unset.
            if (songs!!.isEmpty()) {
                startActivity(Intent(this@NupActivity, BrowseTopActivity::class.java))
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.d(TAG, "disconnected from service")
            service = null
        }
    }

    fun onPauseButtonClicked(view: View?) {
        service!!.togglePause()
    }

    fun onPrevButtonClicked(view: View?) {
        if (currentSongIndex <= 0) return
        service!!.stopPlaying()
        updateCurrentSongIndex(currentSongIndex - 1)
        schedulePlaySongTask(SONG_CHANGE_DELAY_MS)
    }

    fun onNextButtonClicked(view: View?) {
        if (currentSongIndex >= songs!!.size - 1) return
        service!!.stopPlaying()
        updateCurrentSongIndex(currentSongIndex + 1)
        schedulePlaySongTask(SONG_CHANGE_DELAY_MS)
    }

    // Implements NupService.SongListener.
    override fun onSongChange(song: Song?, index: Int) {
        updateCurrentSongIndex(index)
    }

    // Implements NupService.SongListener.
    override fun onSongPositionChange(song: Song?, positionMs: Int, durationMs: Int) {
        runOnUiThread(
                Runnable {
                    if (song != currentSong) return@Runnable
                    val positionSec = positionMs / 1000
                    if (positionSec == lastSongPositionSec) return@Runnable
                    // MediaPlayer appears to get confused sometimes and report things like
                    // 0:01.
                    val durationSec = Math.max(durationMs / 1000, currentSong!!.lengthSec)
                    timeLabel!!.text = formatDurationProgressString(positionSec, durationSec)
                    lastSongPositionSec = positionSec
                })
    }

    // Implements NupService.SongListener.
    override fun onPauseStateChange(isPaused: Boolean) {
        runOnUiThread { pauseButton!!.text = getString(if (isPaused) R.string.play else R.string.pause) }
    }

    // Implements NupService.SongListener.
    override fun onSongCoverLoad(song: Song?) {
        if (song == currentSong) {
            albumImageView!!.visibility = View.VISIBLE
            albumImageView!!.setImageBitmap(song!!.coverBitmap)
        }
    }

    // Implements NupService.SongListener.
    override fun onPlaylistChange(newSongs: List<Song>?) {
        runOnUiThread {
            songs = newSongs
            findViewById<View>(R.id.playlist_heading).visibility = if (songs!!.isEmpty()) View.INVISIBLE else View.VISIBLE
            updateCurrentSongIndex(service!!.currentSongIndex)
        }
    }

    // Implements NupService.SongListener.
    override fun onSongFileSizeChange(song: Song?) {
        val availableBytes = song!!.availableBytes
        val totalBytes = song.totalBytes
        if (song == currentSong) {
            if (availableBytes == totalBytes) {
                downloadStatusLabel!!.text = ""
            } else {
                downloadStatusLabel!!.text = String.format(
                        "%,d of %,d KB",
                        Math.round(availableBytes / 1024.0),
                        Math.round(totalBytes / 1024.0))
            }
        }
        songListAdapter.notifyDataSetChanged()
    }

    // Update the onscreen information about the current song.
    private fun updateSongDisplay(song: Song?) {
        if (song == null) {
            artistLabel!!.text = ""
            titleLabel!!.text = ""
            albumLabel!!.text = ""
            timeLabel!!.text = ""
            downloadStatusLabel!!.text = ""
            albumImageView!!.visibility = View.INVISIBLE
        } else {
            artistLabel!!.text = song.artist
            titleLabel!!.text = song.title
            albumLabel!!.text = song.album
            timeLabel!!.text = formatDurationProgressString(
                    if (song == service!!.currentSong) service!!.currentSongLastPositionMs / 1000 else 0,
                    song.lengthSec)
            downloadStatusLabel!!.text = ""
            if (song.coverBitmap != null) {
                albumImageView!!.visibility = View.VISIBLE
                albumImageView!!.setImageBitmap(song.coverBitmap)
            } else {
                albumImageView!!.visibility = View.INVISIBLE
                service!!.fetchCoverForSongIfMissing(song)
            }
        }

        // Update the displayed time in response to the next position change we get.
        lastSongPositionSec = -1
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.download_all_menu_item)
        // TODO: This sometimes runs before the service is bound, resulting in a crash.
        // Find a better way to handle it.
        val downloadAll = if (service != null) service!!.getShouldDownloadAll() else false
        item.setTitle(if (downloadAll) R.string.dont_download_all else R.string.download_all)
        item.setIcon(
                if (downloadAll) R.drawable.ic_cloud_off_white_24dp else R.drawable.ic_cloud_download_white_24dp)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.browse_menu_item -> {
                if (service != null) startActivity(Intent(this, BrowseTopActivity::class.java))
                true
            }
            R.id.search_menu_item -> {
                if (service != null) startActivity(Intent(this, SearchFormActivity::class.java))
                true
            }
            R.id.pause_menu_item -> {
                if (service != null) service!!.pause()
                true
            }
            R.id.download_all_menu_item -> {
                if (service != null) service!!.setShouldDownloadAll(!service!!.getShouldDownloadAll())
                true
            }
            R.id.settings_menu_item -> {
                if (service != null) startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.exit_menu_item -> {
                if (service != null) stopService(Intent(this, NupService::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Adapts our information about the current playlist and song for the song list view.
    private inner class SongListAdapter : BaseAdapter() {
        override fun getCount(): Int {
            return songs!!.size
        }

        override fun getItem(position: Int): Any {
            return position
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View, parent: ViewGroup): View {
            val view: View
            view = if (convertView != null) {
                convertView
            } else {
                val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
                inflater.inflate(R.layout.playlist_row, null)
            }
            val artistView = view.findViewById<View>(R.id.artist) as TextView
            val titleView = view.findViewById<View>(R.id.title) as TextView
            val percentView = view.findViewById<View>(R.id.percent) as TextView
            val song = songs!![position]
            artistView.text = song.artist
            titleView.text = song.title
            if (song.totalBytes > 0) {
                if (song.availableBytes == song.totalBytes) percentView.text = "\u2713" // CHECK MARK from Dingbats
                else percentView.setText((Math.round(100.0
                        * song.availableBytes
                        / song.totalBytes) as Int)
                .toString() + "%")
                percentView.visibility = View.VISIBLE
            } else {
                percentView.visibility = View.GONE
            }
            val currentlyPlaying = position == currentSongIndex
            view.setBackgroundColor(
                    resources
                            .getColor(
                                    if (currentlyPlaying) R.color.primary else android.R.color.transparent))
            artistView.setTextColor(
                    resources
                            .getColor(if (currentlyPlaying) R.color.icons else R.color.primary_text))
            titleView.setTextColor(
                    resources
                            .getColor(if (currentlyPlaying) R.color.icons else R.color.primary_text))
            percentView.setTextColor(
                    resources
                            .getColor(if (currentlyPlaying) R.color.icons else R.color.primary_text))
            return view
        }
    }

    override fun onCreateContextMenu(
            menu: ContextMenu, view: View, menuInfo: ContextMenuInfo) {
        if (view.id == R.id.playlist) {
            val info = menuInfo as AdapterContextMenuInfo
            val song = songs!![info.position]
            menu.setHeaderTitle(song.title)
            menu.add(0, MENU_ITEM_PLAY, 0, R.string.play)
            menu.add(0, MENU_ITEM_REMOVE_FROM_LIST, 0, R.string.remove_from_list)
            menu.add(0, MENU_ITEM_TRUNCATE_LIST, 0, R.string.truncate_list)
            menu.add(0, MENU_ITEM_SONG_DETAILS, 0, R.string.song_details_ellipsis)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val song = songs!![info.position]
        return when (item.itemId) {
            MENU_ITEM_PLAY -> {
                updateCurrentSongIndex(info.position)
                schedulePlaySongTask(0)
                true
            }
            MENU_ITEM_REMOVE_FROM_LIST -> {
                service!!.removeFromPlaylist(info.position)
                true
            }
            MENU_ITEM_TRUNCATE_LIST -> {
                service!!.removeRangeFromPlaylist(info.position, songs!!.size - 1)
                true
            }
            MENU_ITEM_SONG_DETAILS -> {
                if (song != null) showDialog(DIALOG_SONG_DETAILS, createBundle(song))
                true
            }
            else -> false
        }
    }

    override fun onCreateDialog(id: Int, args: Bundle): Dialog? {
        return if (id == DIALOG_SONG_DETAILS) createDialog(this) else null
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog, args: Bundle) {
        super.onPrepareDialog(id, dialog, args)
        if (id == DIALOG_SONG_DETAILS) prepareDialog(dialog, args)
    }

    private val currentSong: Song?
        private get() = if (currentSongIndex >= 0 && currentSongIndex < songs!!.size) {
            songs!![currentSongIndex]
        } else null

    private fun updateCurrentSongIndex(index: Int) {
        currentSongIndex = index
        updateSongDisplay(currentSong)
        songListAdapter.notifyDataSetChanged()
        prevButton!!.isEnabled = currentSongIndex > 0
        nextButton!!.isEnabled = !songs!!.isEmpty() && currentSongIndex < songs!!.size - 1
        pauseButton!!.isEnabled = !songs!!.isEmpty()
    }

    private fun schedulePlaySongTask(delayMs: Int) {
        handler.removeCallbacks(playSongTask)
        handler.postDelayed(playSongTask, delayMs.toLong())
    }

    companion object {
        private const val TAG = "NupActivity"

        // Wait this many milliseconds before switching tracks in response to the Prev and Next buttons.
        // This avoids requesting a bunch of tracks that we don't want when the user is repeatedly
        // pressing the button to skip through tracks.
        private const val SONG_CHANGE_DELAY_MS = 500

        // IDs for items in our context menus.
        private const val MENU_ITEM_PLAY = 1
        private const val MENU_ITEM_REMOVE_FROM_LIST = 2
        private const val MENU_ITEM_TRUNCATE_LIST = 3
        private const val MENU_ITEM_SONG_DETAILS = 4
        private const val DIALOG_SONG_DETAILS = 1

        // Persistent service to which we connect.
        @JvmStatic
        public var service: NupService? = null
            private set
    }
}