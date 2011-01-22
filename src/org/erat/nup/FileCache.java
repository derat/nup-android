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
import java.net.URI;
import java.util.Date;
import java.util.HashSet;

class FileCache implements Runnable {
    private static final String TAG = "FileCache";

    interface DownloadListener {
        void onDownloadError(FileCacheEntry entry, String reason);
        void onDownloadFail(FileCacheEntry entry, String reason);
        void onDownloadProgress(FileCacheEntry entry, long diskBytes, long receivedBytes, long elapsedMs);
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

    @Override
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
        FileCacheEntry entry = mDb.getEntryForRemotePath(urlPath);
        if (entry == null) {
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
        mHandler.post(new DownloadTask(entry, listener));
        return entry;
    }

    private class DownloadTask implements Runnable {
        private static final String TAG = "FileCache.DownloadTask";

        private static final int BUFFER_SIZE = 8 * 1024;

        // We download this many initial bytes as quickly as we can.
        private static final int INITIAL_BYTES = 128 * 1024;

        // Maximum number of bytes that we'll download per second, or 0 to disable throttling.
        private static final int MAX_BYTES_PER_SECOND = 0;

        private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

        private FileCacheEntry mEntry;
        private final int mId;
        private final DownloadListener mListener;

        public DownloadTask(FileCacheEntry entry, DownloadListener listener) {
            mEntry = entry;
            mId = entry.getId();
            mListener = listener;
        }

        // TODO: This needs to be broken up into smaller pieces.
        @Override
        public void run() {
            if (!isDownloadActive(mId))
                return;

            int numAttempts = 0;
            while (numAttempts < MAX_DOWNLOAD_ATTEMPTS) {
                numAttempts++;
                DownloadResult result = null;

                // If the file was already downloaded, report success.
                File file = new File(mEntry.getLocalFilename());
                long existingLength = file.exists() ? file.length() : 0;
                if (mEntry.getContentLength() > 0 && existingLength == mEntry.getContentLength()) {
                    handleSuccess(mEntry, result, mListener);
                    return;
                }

                DownloadRequest request;
                try {
                    request = new DownloadRequest(mContext, DownloadRequest.Method.GET, mEntry.getRemotePath(), null);
                } catch (DownloadRequest.PrefException e) {
                    handleFailure(mEntry, result, mListener, "Invalid server info settings");
                    return;
                }
                if (existingLength > 0 && existingLength < mEntry.getContentLength()) {
                    Log.d(TAG, "attempting to resume download at byte " + existingLength);
                    request.setHeader("Range", "bytes=" + new Long(existingLength).toString() + "-");
                }

                try {
                    result = Download.startDownload(request);
                } catch (org.apache.http.HttpException e) {
                    handleError(mEntry, result, mListener, "HTTP error while connecting");
                    continue;
                } catch (IOException e) {
                    handleError(mEntry, result, mListener, "IO error while connecting");
                    continue;
                }

                Log.d(TAG, "got " + result.getStatusCode() + " from server");
                if (result.getStatusCode() != 200 && result.getStatusCode() != 206) {
                    handleFailure(mEntry, result, mListener, "Got status code " + result.getStatusCode());
                    return;
                }

                if (!isDownloadActive(mId)) {
                    result.close();
                    return;
                }

                // Update the cache entry with the total content size.
                if (existingLength == 0) {
                    mDb.setContentLength(mId, result.getEntity().getContentLength());
                    mEntry = mDb.getEntryForRemotePath(mEntry.getRemotePath());
                }

                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        handleFailure(mEntry, result, mListener, "Unable to create local file");
                        return;
                    }
                }

                FileOutputStream outputStream;
                try {
                    // TODO: Also check the Content-Range header.
                    boolean append = (result.getStatusCode() == 206);
                    outputStream = new FileOutputStream(file, append);
                } catch (java.io.FileNotFoundException e) {
                    handleFailure(mEntry, result, mListener, "Unable to create output stream to local file");
                    return;
                }

                ProgressReporter reporter = new ProgressReporter(mEntry, mListener);
                Thread reporterThread = new Thread(reporter, "FileCache.ProgressReporter" + mId);
                reporterThread.start();

                try {
                    Date startDate = new Date();

                    int bytesRead = 0, bytesWritten = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    InputStream inputStream = result.getEntity().getContent();
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        if (!isDownloadActive(mId)) {
                            result.close();
                            return;
                        }

                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;

                        // Well, we made some progress, at least.  Reset the attempt counter.
                        numAttempts = 0;

                        Date now = new Date();
                        long elapsedMs = now.getTime() - startDate.getTime();
                        reporter.update(existingLength + bytesWritten, bytesWritten, elapsedMs);

                        if (MAX_BYTES_PER_SECOND > 0) {
                            long expectedMs = (long) (bytesWritten / (float) MAX_BYTES_PER_SECOND * 1000);
                            if (elapsedMs < expectedMs)
                                SystemClock.sleep(expectedMs - elapsedMs);
                        }
                    }
                    Date endDate = new Date();
                    Log.d(TAG, "finished download " + mId + " (" + bytesWritten + " bytes to " +
                          file.getAbsolutePath() + " in " + (endDate.getTime() - startDate.getTime()) + " ms)");
                } catch (IOException e) {
                    handleError(mEntry, result, mListener, "IO error while reading body");
                    continue;
                } finally {
                    reporter.quit();
                    try {
                        reporterThread.join();
                    } catch (InterruptedException e) {
                    }
                }

                handleSuccess(mEntry, result, mListener);
                return;
            }

            String reason = "Giving up after " + MAX_DOWNLOAD_ATTEMPTS + " attempt" +
                            (MAX_DOWNLOAD_ATTEMPTS == 1 ? "" : "s") + " without progress";
            handleFailure(mEntry, null, mListener, reason);
        }

        private void handleError(FileCacheEntry entry, DownloadResult result, DownloadListener listener, String reason) {
            if (result != null)
                result.close();
            listener.onDownloadError(entry, reason);
        }

        private void handleFailure(FileCacheEntry entry, DownloadResult result, DownloadListener listener, String reason) {
            if (result != null)
                result.close();
            synchronized(mInProgressIds) {
                mInProgressIds.remove(entry.getId());
            }
            listener.onDownloadFail(entry, reason);
        }

        private void handleSuccess(FileCacheEntry entry, DownloadResult result, DownloadListener listener) {
            if (result != null)
                result.close();
            synchronized(mInProgressIds) {
                mInProgressIds.remove(entry.getId());
            }
            listener.onDownloadComplete(entry);
        }

        private class ProgressReporter implements Runnable {
            private static final String TAG = "FileCache.ProgressReporter";
            private static final long PROGRESS_REPORT_MS = 250;

            private final FileCacheEntry mEntry;
            private final DownloadListener mListener;
            private long mDiskBytes = 0;
            private long mDownloadedBytes = 0;
            private long mElapsedMs = 0;
            private Handler mHandler;
            private Runnable mTask;
            private Date mLastReportDate;
            private boolean mShouldQuit = false;

            ProgressReporter(final FileCacheEntry entry, final DownloadListener listener) {
                mEntry = entry;
                mListener = listener;
            }

            @Override
            public void run() {
                Looper.prepare();
                synchronized (this) {
                    if (mShouldQuit)
                        return;
                    mHandler = new Handler();
                }
                Looper.loop();
            }

            public void quit() {
                synchronized (this) {
                    // The thread hasn't started looping yet; tell it to exit before starting.
                    if (mHandler == null) {
                        mShouldQuit = true;
                        return;
                    }
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Looper.myLooper().quit();
                    }
                });
            }

            public void update(final long diskBytes, final long downloadedBytes, final long elapsedMs) {
                synchronized (this) {
                    mDiskBytes = diskBytes;
                    mDownloadedBytes = downloadedBytes;
                    mElapsedMs = elapsedMs;

                    if (mHandler != null && mTask == null) {
                        mTask = new Runnable() {
                            @Override
                            public void run() {
                                synchronized (ProgressReporter.this) {
                                    mListener.onDownloadProgress(mEntry, mDiskBytes, mDownloadedBytes, mElapsedMs);
                                    mLastReportDate = new Date();
                                    mTask = null;
                                }
                            }
                        };

                        long delayMs = 0;
                        if (mLastReportDate != null) {
                            long timeSinceLastReportMs = new Date().getTime() - mLastReportDate.getTime();
                            delayMs = Math.max(PROGRESS_REPORT_MS - timeSinceLastReportMs, 0);
                        }
                        mHandler.postDelayed(mTask, delayMs);
                    }
                }
            }
        }
    }

    private boolean isDownloadActive(int id) {
        synchronized(mInProgressIds) {
            return mInProgressIds.contains(id);
        }
    }
}
