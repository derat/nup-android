package org.erat.nup;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Date;
import java.util.List;

public class PlaybackReporter {
    private static final String TAG = "PlaybackReporter";

    private final SongDatabase mSongDb;
    private final Downloader mDownloader;
    private final TaskRunner mTaskRunner;
    private final NetworkHelper mNetworkHelper;

    public PlaybackReporter(SongDatabase songDb, Downloader downloader, TaskRunner taskRunner,
                            NetworkHelper networkHelper) {
        mSongDb = songDb;
        mDownloader = downloader;
        mTaskRunner = taskRunner;
        mNetworkHelper = networkHelper;

        // FIXME: Listen for the network coming up and send pending reports then, or maybe just try
        // to go through the pending reports whenever report() is successful?

        // Retry all of the pending reports in the background.
        if (mNetworkHelper.isNetworkAvailable()) {
            mTaskRunner.runInBackground(new Runnable() {
                @Override public void run() {
                    List<SongDatabase.PendingPlaybackReport> reports =
                        mSongDb.getAllPendingPlaybackReports();
                    for (SongDatabase.PendingPlaybackReport report : reports) {
                        if (reportInternal(report.songId, report.startDate)) {
                            mSongDb.removePendingPlaybackReport(report.songId, report.startDate);
                        }
                    }
                }
            });
        }
    }

    // Asynchronously report the playback of a song.
    public void report(final long songId, final Date startDate) {
        mTaskRunner.runInBackground(new Runnable() {
            @Override public void run() {
                // FIXME: Add the pending report first and then remove it on success.
                if (!reportInternal(songId, startDate)) {
                    mSongDb.addPendingPlaybackReport(songId, startDate);
                }
            }
        });
    }

    // Synchronously report the playback of a song.
    private boolean reportInternal(long songId, Date startDate) {
        if (!mNetworkHelper.isNetworkAvailable()) {
            return false;
        }

        Log.d(TAG, "reporting song " + songId + " started at " + startDate);
        HttpURLConnection conn = null;
        try {
            String params = String.format("songId=%d&startTime=%f", songId, startDate.getTime() / 1000.0);
            conn = mDownloader.download(mDownloader.getServerUrl("/report_played?" + params),
                                        "POST", Downloader.AuthType.SERVER, null);
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
