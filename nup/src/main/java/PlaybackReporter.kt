package org.erat.nup

import android.util.Log
import org.erat.nup.Downloader
import org.erat.nup.Downloader.PrefException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.*
import java.util.concurrent.locks.ReentrantLock

class PlaybackReporter(
        private val songDb: SongDatabase,
        private val downloader: Downloader,
        private val taskRunner: TaskRunner,
        private val networkHelper: NetworkHelper) {
    private val lock = ReentrantLock()

    /**
     * Asynchronously reports the playback of a song.
     *
     * @param songId ID of played song
     * @param startDate time at which playback started
     */
    fun report(songId: Long, startDate: Date?) {
        taskRunner.runInBackground {
            lock.lock()
            try {
                songDb.addPendingPlaybackReport(songId, startDate!!)
                if (networkHelper.isNetworkAvailable) {
                    reportPendingSongs()
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /** Reports all unreported songs.  */
    fun reportPendingSongs() {
        check(lock.isHeldByCurrentThread) { "Lock not held" }
        val reports = songDb.allPendingPlaybackReports
        for (report in reports) {
            if (reportInternal(report.songId, report.startDate)) {
                songDb.removePendingPlaybackReport(report.songId, report.startDate)
            }
        }
    }

    /**
     * Synchronously reports the playback of a song.
     *
     * @param songId ID of played song
     * @param startDate time at which playback started
     * @return `true` if the report was successfully received by the server
     */
    private fun reportInternal(songId: Long, startDate: Date): Boolean {
        if (!networkHelper.isNetworkAvailable) {
            return false
        }
        Log.d(TAG, "reporting song $songId started at $startDate")
        var conn: HttpURLConnection? = null
        try {
            val path = String.format(
                    "/report_played?songId=%d&startTime=%f",
                    songId, startDate.time / 1000.0)
            conn = downloader.download(
                    downloader.getServerUrl(path),
                    "POST",
                    Downloader.AuthType.SERVER,
                    null)
            if (conn.responseCode == 200) {
                return true
            }
            Log.e(
                    TAG,
                    "got " + conn.responseCode + " from server: " + conn.responseMessage)
        } catch (e: PrefException) {
            Log.e(TAG, "got preferences error: $e")
        } catch (e: IOException) {
            Log.e(TAG, "got IO error: $e")
        } finally {
            conn?.disconnect()
        }
        return false
    }

    companion object {
        private const val TAG = "PlaybackReporter"
    }

    init {

        // TODO: Listen for the network coming up and send pending reports then?

        // Retry all of the pending reports in the background.
        if (networkHelper.isNetworkAvailable) {
            taskRunner.runInBackground {
                lock.lock()
                try {
                    reportPendingSongs()
                } finally {
                    lock.unlock()
                }
            }
        }
    }
}
