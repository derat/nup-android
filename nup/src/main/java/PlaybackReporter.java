package org.erat.nup;

import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class PlaybackReporter {
    private static final String TAG = "PlaybackReporter";

    private final SongDatabase songDb;
    private final Downloader downloader;
    private final TaskRunner taskRunner;
    private final NetworkHelper networkHelper;

    private final ReentrantLock lock = new ReentrantLock();

    public PlaybackReporter(
            SongDatabase songDb,
            Downloader downloader,
            TaskRunner taskRunner,
            NetworkHelper networkHelper) {
        this.songDb = songDb;
        this.downloader = downloader;
        this.taskRunner = taskRunner;
        this.networkHelper = networkHelper;

        // TODO: Listen for the network coming up and send pending reports then?

        // Retry all of the pending reports in the background.
        if (networkHelper.isNetworkAvailable()) {
            this.taskRunner.runInBackground(
                    new Runnable() {
                        @Override
                        public void run() {
                            lock.lock();
                            try {
                                reportPendingSongs();
                            } finally {
                                lock.unlock();
                            }
                        }
                    });
        }
    }

    /**
     * Asynchronously reports the playback of a song.
     *
     * @param songId ID of played song
     * @param startDate time at which playback started
     */
    public void report(final long songId, final Date startDate) {
        taskRunner.runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        lock.lock();
                        try {
                            songDb.addPendingPlaybackReport(songId, startDate);
                            if (networkHelper.isNetworkAvailable()) {
                                reportPendingSongs();
                            }
                        } finally {
                            lock.unlock();
                        }
                    }
                });
    }

    /** Reports all unreported songs. */
    public void reportPendingSongs() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Lock not held");
        }

        List<SongDatabase.PendingPlaybackReport> reports = songDb.getAllPendingPlaybackReports();
        for (SongDatabase.PendingPlaybackReport report : reports) {
            if (reportInternal(report.songId, report.startDate)) {
                songDb.removePendingPlaybackReport(report.songId, report.startDate);
            }
        }
    }

    /**
     * Synchronously reports the playback of a song.
     *
     * @param songId ID of played song
     * @param startDate time at which playback started
     * @return <code>true</code> if the report was successfully received by the server
     */
    private boolean reportInternal(long songId, Date startDate) {
        if (!networkHelper.isNetworkAvailable()) {
            return false;
        }

        Log.d(TAG, "reporting song " + songId + " started at " + startDate);
        HttpURLConnection conn = null;
        try {
            String path =
                    String.format(
                            "/report_played?songId=%d&startTime=%f",
                            songId, startDate.getTime() / 1000.0);
            conn =
                    downloader.download(
                            downloader.getServerUrl(path),
                            "POST",
                            Downloader.AuthType.SERVER,
                            null);
            if (conn.getResponseCode() == 200) {
                return true;
            }
            Log.e(
                    TAG,
                    "got " + conn.getResponseCode() + " from server: " + conn.getResponseMessage());
        } catch (Downloader.PrefException e) {
            Log.e(TAG, "got preferences error: " + e);
        } catch (IOException e) {
            Log.e(TAG, "got IO error: " + e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return false;
    }
}
