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
        }.execute();
    }

    // Asynchronously report the playback of a song.
    public void report(final int songId, final Date startDate) {
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
    private boolean reportInternal(int songId, Date startDate) {
        Log.d(TAG, "reporting song " + songId + " started at " + startDate);
        DownloadResult result = null;

        try {
            DownloadRequest request = new DownloadRequest(mContext, DownloadRequest.Method.POST, "/report_played", null);
            String body = "songId=" + songId + "&startTime=" + (startDate.getTime() / 1000);
            request.setBody(new ByteArrayInputStream(body.getBytes()), body.length());
            request.setHeader("Content-Type", "application/x-www-form-urlencoded");
            request.setHeader("Content-Length", Long.toString(body.length()));
            result = Download.startDownload(request);
            return true;
        } catch (DownloadRequest.PrefException e) {
            Log.w(TAG, "got preferences error while reporting played song: " + e);
        } catch (org.apache.http.HttpException e) {
            Log.w(TAG, "got HTTP error while reporting played song: " + e);
        } catch (IOException e) {
            Log.w(TAG, "got IO error while reporting played song: " + e);
        } finally {
            if (result != null)
                result.close();
        }
        return false;
    }
}
