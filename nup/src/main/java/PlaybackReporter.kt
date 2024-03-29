/*
 * Copyright 2011 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Date
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.erat.nup.Downloader.PrefException

class PlaybackReporter(
    private val scope: CoroutineScope,
    private val songDb: SongDatabase,
    private val downloader: Downloader,
    private val networkHelper: NetworkHelper,
    private val pendingDispatcher: CoroutineDispatcher = Dispatchers.IO, // for tests
) : NetworkHelper.Listener {
    private val lock = ReentrantLock()
    private var reporting = false

    /** Start listening for network changes and send pending reports if possible. */
    fun start() {
        networkHelper.addListener(this)
        if (networkHelper.isNetworkAvailable) scope.launch(pendingDispatcher) { reportPending() }
    }

    override fun onNetworkAvailabilityChange(available: Boolean) {
        if (available) scope.launch(pendingDispatcher) { reportPending() }
    }

    /** Report the playback of [songId] starting at [startDate]. */
    suspend fun report(songId: Long, startDate: Date) {
        songDb.addPendingPlaybackReport(songId, startDate)
        if (networkHelper.isNetworkAvailable) reportPending()
    }

    /** Synchronously report all unreported songs. */
    suspend fun reportPending() {
        lock.withLock() {
            if (reporting) return
            reporting = true
        }

        val reports = songDb.allPendingPlaybackReports()
        if (!reports.isEmpty()) Log.d(TAG, "Sending ${reports.size} pending report(s)")
        for (report in reports) {
            if (send(report.songId, report.startDate)) {
                songDb.removePendingPlaybackReport(report.songId, report.startDate)
            }
        }

        // report() could've added more songs while we were sending the pending reports to the
        // server. This probably isn't a big deal, since we'll send those songs next time. Kotlin
        // won't let us call suspend functions in critical sections.
        lock.withLock() { reporting = false }
    }

    /** Send a report of [songId] starting at [startDate]. */
    private suspend fun send(songId: Long, startDate: Date): Boolean {
        if (!networkHelper.isNetworkAvailable) return false

        Log.d(TAG, "Reporting song $songId started at $startDate")
        var conn: HttpURLConnection? = null
        try {
            val start = String.format("%.3f", startDate.time / 1000.0)
            val path = "/played?songId=$songId&startTime=$start"
            conn = downloader.download(
                downloader.getServerUrl(path),
                "POST",
                Downloader.AuthType.SERVER,
                null
            )
            if (conn.responseCode == 200) return true
            Log.e(TAG, "Got ${conn.responseCode} from server: ${conn.responseMessage}")
        } catch (e: PrefException) {
            Log.e(TAG, "Preferences error: $e")
        } catch (e: IOException) {
            Log.e(TAG, "IO error: $e")
        } finally {
            conn?.disconnect()
        }
        return false
    }

    companion object {
        private const val TAG = "PlaybackReporter"
    }
}
