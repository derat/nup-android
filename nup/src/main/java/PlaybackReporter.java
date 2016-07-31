package org.erat.nup;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Date;
import java.util.List;

public class PlaybackReporter {
    private static final String TAG = "PlaybackReporter";

    private final Context mContext;
    private final SongDatabase mSongDb;

    public PlaybackReporter(Context context, SongDatabase songDb) {
        mContext = context;
        mSongDb = songDb;

        // FIXME: Listen for the network coming up and send pending reports then, or maybe just try
        // to go through the pending reports whenever report() is successful?

        // Retry all of the pending reports in the background.
        if (Util.isNetworkAvailable(mContext)) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... args) {
                    List<SongDatabase.PendingPlaybackReport> reports =
                        mSongDb.getAllPendingPlaybackReports();
                    for (SongDatabase.PendingPlaybackReport report : reports) {
                        if (reportInternal(report.songId, report.startDate)) {
                            mSongDb.removePendingPlaybackReport(report.songId, report.startDate);
                        }
                    }
                    return (Void) null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    // Asynchronously report the playback of a song.
    public void report(final long songId, final Date startDate) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                // FIXME: Add the pending report first and then remove it on success.
                if (!reportInternal(songId, startDate)) {
                    mSongDb.addPendingPlaybackReport(songId, startDate);
                }
                return (Void) null;
            }
        }.execute();
    }

    // Synchronously report the playback of a song.
    private boolean reportInternal(long songId, Date startDate) {
        if (!Util.isNetworkAvailable(mContext)) {
            return false;
        }

        Log.d(TAG, "reporting song " + songId + " started at " + startDate);
        HttpURLConnection conn = null;
        try {
            String params = String.format("songId=%d&startTime=%f", songId, startDate.getTime() / 1000.0);
            conn = Download.download(mContext, Download.getServerUrl(mContext, "/report_played?" + params),
                                     "POST", Download.AuthType.SERVER, null);
            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "got " + conn.getResponseCode() + " from server: " + conn.getResponseMessage());
                return false;
            }
        } catch (Download.PrefException e) {
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
