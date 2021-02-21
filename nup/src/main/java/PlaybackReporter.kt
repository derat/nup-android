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
import org.erat.nup.Downloader.PrefException

class PlaybackReporter(
    private val songDb: SongDatabase,
    private val downloader: Downloader,
    private val networkHelper: NetworkHelper,
) {
    private val lock = ReentrantLock()

    /** Synchronously report the playback of [songId] starting at [startDate]. */
    suspend fun report(songId: Long, startDate: Date) {
        lock.withLock() { songDb.addPendingPlaybackReport(songId, startDate) }
        if (networkHelper.isNetworkAvailable) reportPending()
    }

    /** Synchronously report all unreported songs. */
    suspend fun reportPending() {
        lock.withLock() {
            for (report in songDb.allPendingPlaybackReports()) {
                if (send(report.songId, report.startDate)) {
                    songDb.removePendingPlaybackReport(report.songId, report.startDate)
                }
            }
        }
    }

    /** Sends a report of [songId] starting at [startDate]. */
    private fun send(songId: Long, startDate: Date): Boolean {
        if (!networkHelper.isNetworkAvailable) return false

        Log.d(TAG, "Reporting song $songId started at $startDate")
        var conn: HttpURLConnection? = null
        try {
            val start = String.format("%.3f", startDate.time / 1000.0)
            val path = "/report_played?songId=$songId&startTime=$start"
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
