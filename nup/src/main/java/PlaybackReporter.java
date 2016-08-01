package org.erat.nup;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Date;
import java.util.List;

public class PlaybackReporter {
    private static final String TAG = "PlaybackReporter";

    private final SongDatabase mSongDb;
    private final Downloader mDownloader;
    private final TaskRunner mTaskRunner;
    private final NetworkHelper mNetworkHelper;

    private final ReentrantLock mLock = new ReentrantLock();

    public PlaybackReporter(SongDatabase songDb, Downloader downloader, TaskRunner taskRunner,
                            NetworkHelper networkHelper) {
        mSongDb = songDb;
        mDownloader = downloader;
        mTaskRunner = taskRunner;
        mNetworkHelper = networkHelper;

        // TODO: Listen for the network coming up and send pending reports then?

        // Retry all of the pending reports in the background.
        if (mNetworkHelper.isNetworkAvailable()) {
            mTaskRunner.runInBackground(new Runnable() {
                @Override public void run() {
                    mLock.lock();
                    try {
                        reportPendingSongs();
                    } finally {
                        mLock.unlock();
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
        mTaskRunner.runInBackground(new Runnable() {
            @Override public void run() {
                mLock.lock();
                try {
                    mSongDb.addPendingPlaybackReport(songId, startDate);
                    if (mNetworkHelper.isNetworkAvailable()) {
                        reportPendingSongs();
                    }
                } finally {
                    mLock.unlock();
                }
            }
        });
    }

    /** Reports all unreported songs. */
    public void reportPendingSongs() {
        if (!mLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Lock not held");
        }

        List<SongDatabase.PendingPlaybackReport> reports =
            mSongDb.getAllPendingPlaybackReports();
        for (SongDatabase.PendingPlaybackReport report : reports) {
            if (reportInternal(report.songId, report.startDate)) {
                mSongDb.removePendingPlaybackReport(report.songId, report.startDate);
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
        if (!mNetworkHelper.isNetworkAvailable()) {
            return false;
        }

        Log.d(TAG, "reporting song " + songId + " started at " + startDate);
        HttpURLConnection conn = null;
        try {
            String path = String.format("/report_played?songId=%d&startTime=%f", songId, startDate.getTime() / 1000.0);
            conn = mDownloader.download(mDownloader.getServerUrl(path), "POST", Downloader.AuthType.SERVER, null);
            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "got " + conn.getResponseCode() + " from server: " + conn.getResponseMessage());
                return false;
            }
        } catch (Downloader.PrefException e) {
            Log.e(TAG, "got preferences error: " + e);
        } catch (IOException e) {
            Log.e(TAG, "got IO error: " + e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return true;
    }
}
