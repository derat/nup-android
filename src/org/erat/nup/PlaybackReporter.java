package org.erat.nup;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

class PlaybackReporter {
    private static final String TAG = "PlaybackReporter";

    private final Context mContext;
    private final SongDatabase mSongDb;

    PlaybackReporter(Context context, SongDatabase songDb) {
        mContext = context;
        mSongDb = songDb;

        // Retry all of the pending reports in the background.
        if (Util.isNetworkAvailable(mContext)) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... args) {
                    List<SongDatabase.PendingPlaybackReport> reports =
                        mSongDb.getAllPendingPlaybackReports();
                    for (SongDatabase.PendingPlaybackReport report : reports) {
                        if (reportInternal(report.songId, report.startDate))
                            mSongDb.removePendingPlaybackReport(report.songId, report.startDate);
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
                if (!reportInternal(songId, startDate))
                    mSongDb.addPendingPlaybackReport(songId, startDate);
                return (Void) null;
            }
        }.execute();
    }

    // Synchronously report the playback of a song.
    private boolean reportInternal(long songId, Date startDate) {
        if (!Util.isNetworkAvailable(mContext))
            return false;

        Log.d(TAG, "reporting song " + songId + " started at " + startDate);
        DownloadResult result = null;

        String errorMessage;
        try {
            String params = "songId=" + songId + "&startTime=" + String.format("%f", (startDate.getTime() / 1000.0));
            DownloadRequest request = new DownloadRequest(
                mContext, DownloadRequest.getServerUri(mContext, "/report_played", params),
                DownloadRequest.Method.POST, DownloadRequest.AuthType.SERVER);
            result = Download.startDownload(request);
            if (result.getStatusCode() == 200)
                return true;
            Log.e(TAG, "got " + result.getStatusCode() + " from server: " + result.getReason());
        } catch (DownloadRequest.PrefException e) {
            Log.e(TAG, "got preferences error: " + e);
        } catch (org.apache.http.HttpException e) {
            Log.e(TAG, "got HTTP error: " + e);
        } catch (IOException e) {
            Log.e(TAG, "got IO error: " + e);
        } finally {
            if (result != null)
                result.close();
        }
        return false;
    }
}
