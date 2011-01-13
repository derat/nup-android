// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Runnable;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;

class FileCache implements Runnable {
    private static final String TAG = "FileCache";

    private static final int BUFFER_SIZE = 8 * 1024;

    // We download this many initial bytes as quickly as we can.
    private static final int INITIAL_BYTES = 128 * 1024;

    private static final int MAX_BYTES_PER_SECOND = 0;

    private static final int PROGRESS_REPORT_BYTES = 64 * 1024;

    interface DownloadListener {
        void onDownloadFail(FileCacheEntry entry, String reason);
        void onDownloadProgress(FileCacheEntry entry, long receivedBytes, long elapsedMs);
        void onDownloadComplete(FileCacheEntry entry);
    }

    // Application context.
    private final Context mContext;

    // Directory where we write music files.
    private final File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    // IDs of entries that are currently being downloaded.
    private HashSet mInProgressIds = new HashSet();

    private FileCacheDatabase mDb;

    FileCache(Context context) {
        mContext = context;

        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mMusicDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        mDb = new FileCacheDatabase(mContext);
    }

    public void run() {
        Looper.prepare();
        mHandler = new Handler();
        Looper.loop();
    }

    public void quit() {
        synchronized(mInProgressIds) {
            mInProgressIds.clear();
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quit();
            }
        });
    }

    // Get the entry corresponding to a particular cached URL.
    // Returns null if the URL isn't cached.
    public FileCacheEntry getEntry(String urlPath) {
        return mDb.getEntryForRemotePath(urlPath);
    }

    // Abort a previously-started download.
    public void abortDownload(int id) {
        synchronized(mInProgressIds) {
            if (!mInProgressIds.contains(id)) {
                Log.e(TAG, "tried to abort nonexistent download " + id);
                return;
            }
            mInProgressIds.remove(id);
            Log.d(TAG, "canceled download " + id);
        }
    }

    // Get the total size of all cached data.
    public long getDataBytes() {
        long size = 0;
        for (File file : mMusicDir.listFiles())
            size += file.length();
        return size;
    }

    // Clear all cached data.
    public void clear() {
        mDb.clear();
        for (File file : mMusicDir.listFiles())
            file.delete();
    }

    // Download a URL to the cache.  Returns the cache entry, or null if the URL is
    // already being downloaded.
    public FileCacheEntry downloadFile(final String urlPath, final DownloadListener listener) {
        final FileCacheEntry entry;

        FileCacheEntry existingEntry = mDb.getEntryForRemotePath(urlPath);
        if (existingEntry != null) {
            entry = existingEntry;
        } else {
            String localFilename = new File(mMusicDir, urlPath.replace("/", "_")).getAbsolutePath();
            int id = mDb.addEntry(urlPath, localFilename);
            entry = mDb.getEntryForRemotePath(urlPath);
        }

        final int id = entry.getId();
        synchronized(mInProgressIds) {
            if (mInProgressIds.contains(id))
                return null;
            mInProgressIds.add(id);
        }

        Log.d(TAG, "posting download " + id + " of " + urlPath);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isDownloadActive(id))
                    return;

                // If the file was already downloaded, report success.
                File file = new File(entry.getLocalFilename());
                if (entry.getContentLength() > 0 && file.exists() && file.length() == entry.getContentLength()) {
                    listener.onDownloadComplete(entry);
                    return;
                }

                // TODO: Resume partial downloads.
                DownloadRequest request;
                try {
                    request = new DownloadRequest(mContext, DownloadRequest.Method.GET, urlPath, null);
                } catch (DownloadRequest.PrefException e) {
                    listener.onDownloadFail(entry, "Invalid server info settings");
                    return;
                }

                DownloadResult result;
                try {
                    result = Download.startDownload(request);
                } catch (org.apache.http.HttpException e) {
                    listener.onDownloadFail(entry, "Got HTTP error while connecting");
                    return;
                } catch (IOException e) {
                    listener.onDownloadFail(entry, "Got IO error while connecting");
                    return;
                }

                if (!isDownloadActive(id)) {
                    result.close();
                    return;
                }

                // Update the cache entry with the total content size.
                mDb.setContentLength(id, result.getContentLength());
                final FileCacheEntry updatedEntry = mDb.getEntryForRemotePath(urlPath);

                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        result.close();
                        listener.onDownloadFail(updatedEntry, "Unable to create local file");
                        return;
                    }
                }

                FileOutputStream outputStream;
                try {
                    // TODO: Once partial downloads are supported, append instead of overwriting.
                    outputStream = new FileOutputStream(file, false);
                } catch (java.io.FileNotFoundException e) {
                    result.close();
                    listener.onDownloadFail(updatedEntry, "Unable to create output stream to local file");
                    return;
                }

                try {
                    Date startDate = new Date();
                    int bytesRead = 0, bytesWritten = 0;
                    int lastReportBytes = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while ((bytesRead = result.getStream().read(buffer)) != -1) {
                        if (!isDownloadActive(id)) {
                            result.close();
                            return;
                        }

                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;

                        Date now = new Date();
                        long elapsedMs = now.getTime() - startDate.getTime();

                        if (bytesWritten >= lastReportBytes + PROGRESS_REPORT_BYTES) {
                            listener.onDownloadProgress(updatedEntry, bytesWritten, elapsedMs);
                            lastReportBytes = bytesWritten;
                        }

                        if (MAX_BYTES_PER_SECOND > 0) {
                            long expectedMs = (long) (bytesWritten / (float) MAX_BYTES_PER_SECOND * 1000);
                            if (elapsedMs < expectedMs)
                                SystemClock.sleep(expectedMs - elapsedMs);
                        }
                    }
                    Date endDate = new Date();
                    Log.d(TAG, "finished download " + id + " (" + bytesWritten + " bytes to " +
                          file.getAbsolutePath() + " in " + (endDate.getTime() - startDate.getTime()) + " ms)");
                } catch (IOException e) {
                    result.close();
                    listener.onDownloadFail(updatedEntry, "Got IO error while downloading");
                    return;
                }

                result.close();
                listener.onDownloadComplete(updatedEntry);
            }
        });

        return entry;
    }

    private boolean isDownloadActive(int id) {
        synchronized(mInProgressIds) {
            return mInProgressIds.contains(id);
        }
    }
}
