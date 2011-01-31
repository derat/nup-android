// Copyright 2011 Daniel Erat <dan@erat.org>
// All rights reserved.

package org.erat.nup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

class FileCache implements Runnable {
    private static final String TAG = "FileCache";

    interface Listener {
        void onCacheDownloadError(FileCacheEntry entry, String reason);
        void onCacheDownloadFail(FileCacheEntry entry, String reason);
        void onCacheDownloadProgress(FileCacheEntry entry, long downloadedBytes, long elapsedMs);
        void onCacheDownloadComplete(FileCacheEntry entry);
        void onCacheEviction(FileCacheEntry entry);
    }

    // Status returned by DownloadTask's startDownload() and writeFile() methods.
    // Up here because only static internal classes can define enums.
    private enum DownloadStatus {
        SUCCESS,
        ABORTED,
        RETRYABLE_ERROR,
        FATAL_ERROR
    }

    // Application context.
    private final Context mContext;

    // Directory where we write music files.
    private final File mMusicDir;

    // Used to run tasks on our own thread.
    private Handler mHandler;

    // IDs of entries that are currently being downloaded.
    private HashSet mInProgressIds = new HashSet();

    // IDs of entries that shouldn't be purged from the cache.
    private HashSet mPinnedIds = new HashSet();

    // Persistent information about cached items.
    private FileCacheDatabase mDb = null;

    private SharedPreferences mPrefs;

    private final Listener mListener;

    FileCache(Context context, Listener listener) {
        mContext = context;
        mListener = listener;

        // FIXME: display a message here
        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED))
            Log.e(TAG, "media has state " + state + "; we need " + Environment.MEDIA_MOUNTED);

        mMusicDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Override
    public void run() {
        mDb = new FileCacheDatabase(mContext);
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
    public FileCacheEntry getEntry(String remotePath) {
        return mDb.getEntryForRemotePath(remotePath);
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
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized(mInProgressIds) {
                    mInProgressIds.clear();
                }
                clearPinnedIds();

                List<Integer> ids = mDb.getIdsByAge();
                for (int id : ids) {
                    FileCacheEntry entry = mDb.getEntryById(id);
                    mDb.removeEntry(id);
                    File file = new File(entry.getLocalPath());
                    file.delete();
                    mListener.onCacheEviction(entry);
                }

                // Shouldn't be anything there, but whatever.
                for (File file : mMusicDir.listFiles())
                    file.delete();
            }
        });
    }

    // Download a URL to the cache.  Returns the cache entry, or null if the URL is
    // already being downloaded.
    public FileCacheEntry downloadFile(String remotePath, String filenameSuggestion) {
        FileCacheEntry entry = mDb.getEntryForRemotePath(remotePath);
        if (entry == null) {
            String localPath = new File(mMusicDir, filenameSuggestion).getAbsolutePath();
            entry = mDb.addEntry(remotePath, localPath);
        } else {
            mDb.updateLastAccessTime(entry.getId());
        }

        final int id = entry.getId();
        synchronized(mInProgressIds) {
            if (mInProgressIds.contains(id))
                return null;
            mInProgressIds.add(id);
        }

        Log.d(TAG, "posting download " + id + " of " + remotePath + " to " + entry.getLocalPath());
        mHandler.post(new DownloadTask(entry));
        return entry;
    }

    public void pinId(int id) {
        synchronized (mPinnedIds) {
            mPinnedIds.add(id);
        }
    }

    public void clearPinnedIds() {
        synchronized (mPinnedIds) {
            mPinnedIds.clear();
        }
    }

    private class DownloadTask implements Runnable {
        private static final String TAG = "FileCache.DownloadTask";

        // Maximum number of seconds we'll wait before retrying after failure.  Never give up!
        private static final int MAX_BACKOFF_SEC = 60;

        // Size of buffer used to write data to disk, in bytes.
        private static final int BUFFER_SIZE = 8 * 1024;

        // Cache entry that we're downloading.
        private final FileCacheEntry mEntry;

        // How long we should wait before retrying after an error, in milliseconds.
        // We start at 0 but back off exponentially after errors where we haven't made any progress.
        private int mBackoffTimeMs = 0;

        // Reason for the failure.
        private String mReason;

        private DownloadRequest mRequest = null;
        private DownloadResult mResult = null;
        private FileOutputStream mOutputStream = null;

        public DownloadTask(FileCacheEntry entry) {
            mEntry = entry;
        }

        @Override
        public void run() {
            if (!isActive())
                return;

            while (true) {
                try {
                    if (mBackoffTimeMs > 0) {
                        Log.d(TAG, "sleeping for " + mBackoffTimeMs + " ms before retrying download " + mEntry.getId());
                        SystemClock.sleep(mBackoffTimeMs);
                    }

                    // If the file is fully downloaded already, report success.
                    if (mEntry.isFullyCached()) {
                        handleSuccess();
                        return;
                    }

                    // Start the download.
                    switch (startDownload()) {
                        case SUCCESS:
                            break;
                        case ABORTED:
                            return;
                        case RETRYABLE_ERROR:
                            mListener.onCacheDownloadError(mEntry, mReason);
                            updateBackoffTime(false);
                            continue;
                        case FATAL_ERROR:
                            handleFailure();
                            return;
                    }

                    if (!isActive())
                        return;

                    switch (writeFile()) {
                        case SUCCESS:
                            break;
                        case ABORTED:
                            return;
                        case RETRYABLE_ERROR:
                            mListener.onCacheDownloadError(mEntry, mReason);
                            updateBackoffTime(true);
                            continue;
                        case FATAL_ERROR:
                            handleFailure();
                            return;
                    }

                    handleSuccess();
                    return;
                } finally {
                    if (mResult != null) {
                        mResult.close();
                        mResult = null;
                    }
                    if (mOutputStream != null) {
                        try {
                            mOutputStream.close();
                        } catch (IOException e) {
                            Log.e(TAG, "got IO exception while closing output stream: " + e);
                        }
                        mOutputStream = null;
                    }
                }
            }
        }

        private DownloadStatus startDownload() {
            try {
                mRequest = new DownloadRequest(mContext, DownloadRequest.Method.GET, mEntry.getRemotePath(), null);
            } catch (DownloadRequest.PrefException e) {
                mReason = "Invalid server info settings";
                return DownloadStatus.FATAL_ERROR;
            }

            if (mEntry.getCachedBytes() > 0 && mEntry.getCachedBytes() < mEntry.getTotalBytes()) {
                Log.d(TAG, "attempting to resume download at byte " + mEntry.getCachedBytes());
                mRequest.setHeader("Range", "bytes=" + new Long(mEntry.getCachedBytes()).toString() + "-");
            }

            try {
                mResult = Download.startDownload(mRequest);
            } catch (org.apache.http.HttpException e) {
                mReason = "HTTP error while connecting";
                return DownloadStatus.RETRYABLE_ERROR;
            } catch (IOException e) {
                mReason = "IO error while connecting";
                return DownloadStatus.RETRYABLE_ERROR;
            }

            Log.d(TAG, "got " + mResult.getStatusCode() + " from server");
            if (mResult.getStatusCode() != 200 && mResult.getStatusCode() != 206) {
                mReason = "Got status code " + mResult.getStatusCode();
                return DownloadStatus.FATAL_ERROR;
            }

            // Update the cache entry with the total file size.
            if (mResult.getStatusCode() == 200)
                mDb.setTotalBytes(mEntry.getId(), mResult.getEntity().getContentLength());

            return DownloadStatus.SUCCESS;
        }

        private DownloadStatus writeFile() {
            // Make space for whatever we're planning to download.
            if (!makeSpace(mResult.getEntity().getContentLength())) {
                mReason = "Unable to make space for " + mResult.getEntity().getContentLength() + "-byte download";
                return DownloadStatus.FATAL_ERROR;
            }

            File file = new File(mEntry.getLocalPath());
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    mReason = "Unable to create local file " + file.getPath();
                    return DownloadStatus.FATAL_ERROR;
                }
            }

            try {
                // TODO: Also check the Content-Range header.
                boolean append = (mResult.getStatusCode() == 206);
                mOutputStream = new FileOutputStream(file, append);
                if (!append)
                    mEntry.setCachedBytes(0);
            } catch (java.io.FileNotFoundException e) {
                mReason = "Unable to create output stream to local file";
                return DownloadStatus.FATAL_ERROR;
            }

            final long maxBytesPerSecond = Long.valueOf(
                mPrefs.getString(NupPreferences.DOWNLOAD_RATE,
                                 NupPreferences.DOWNLOAD_RATE_DEFAULT)) * 1024;

            ProgressReporter reporter = new ProgressReporter(mEntry);
            Thread reporterThread = new Thread(reporter, "FileCache.ProgressReporter" + mEntry.getId());
            reporterThread.start();

            try {
                Date startDate = new Date();

                int bytesRead = 0, bytesWritten = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                InputStream inputStream = mResult.getEntity().getContent();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (!isActive())
                        return DownloadStatus.ABORTED;

                    mOutputStream.write(buffer, 0, bytesRead);
                    bytesWritten += bytesRead;

                    Date now = new Date();
                    long elapsedMs = now.getTime() - startDate.getTime();
                    mEntry.incrementCachedBytes(bytesRead);
                    reporter.update(bytesWritten, elapsedMs);

                    if (maxBytesPerSecond > 0) {
                        long expectedMs = (long) (bytesWritten / (float) maxBytesPerSecond * 1000);
                        if (elapsedMs < expectedMs)
                            SystemClock.sleep(expectedMs - elapsedMs);
                    }
                }
                Date endDate = new Date();
                Log.d(TAG, "finished download " + mEntry.getId() + " (" + bytesWritten + " bytes to " +
                      file.getAbsolutePath() + " in " + (endDate.getTime() - startDate.getTime()) + " ms)");

                // I see this happen when I kill the server midway through the download.
                if (bytesWritten != mResult.getEntity().getContentLength()) {
                    mReason = "Expected " + mResult.getEntity().getContentLength() + " bytes but got " + bytesWritten;
                    return DownloadStatus.RETRYABLE_ERROR;
                }
            } catch (IOException e) {
                mReason = "IO error while reading body";
                return DownloadStatus.RETRYABLE_ERROR;
            } finally {
                reporter.quit();
                try { reporterThread.join(); } catch (InterruptedException e) { /* !~$#%$#!$#!~$#! */ }
            }
            return DownloadStatus.SUCCESS;
        }

        private void handleFailure() {
            synchronized(mInProgressIds) {
                mInProgressIds.remove(mEntry.getId());
            }
            mListener.onCacheDownloadFail(mEntry, mReason);
        }

        private void handleSuccess() {
            synchronized(mInProgressIds) {
                mInProgressIds.remove(mEntry.getId());
            }
            mListener.onCacheDownloadComplete(mEntry);
        }

        private void updateBackoffTime(boolean madeProgress) {
            if (madeProgress) {
                mBackoffTimeMs = 0;
            } else if (mBackoffTimeMs == 0) {
                mBackoffTimeMs = 1000;
            } else {
                mBackoffTimeMs = Math.min(mBackoffTimeMs * 2, MAX_BACKOFF_SEC * 1000);
            }
        }

        // Is this download currently active, or has it been cancelled?
        private boolean isActive() {
            return isDownloadActive(mEntry.getId());
        }

        private class ProgressReporter implements Runnable {
            private static final String TAG = "FileCache.ProgressReporter";
            private static final long PROGRESS_REPORT_MS = 500;

            private final FileCacheEntry mEntry;
            private long mDownloadedBytes = 0;
            private long mElapsedMs = 0;
            private Handler mHandler;
            private Runnable mTask;
            private Date mLastReportDate;
            private boolean mShouldQuit = false;

            ProgressReporter(final FileCacheEntry entry) {
                mEntry = entry;
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

            public void update(final long downloadedBytes, final long elapsedMs) {
                synchronized (this) {
                    mDownloadedBytes = downloadedBytes;
                    mElapsedMs = elapsedMs;

                    if (mHandler != null && mTask == null) {
                        mTask = new Runnable() {
                            @Override
                            public void run() {
                                synchronized (ProgressReporter.this) {
                                    mListener.onCacheDownloadProgress(mEntry, mDownloadedBytes, mElapsedMs);
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

    // Try to make room for |neededBytes| in the cache.
    // We delete the least-recently-accessed files first, ignoring ones
    // that are currently being downloaded or are pinned.
    synchronized private boolean makeSpace(long neededBytes) {
        long maxBytes = Long.valueOf(
            mPrefs.getString(NupPreferences.CACHE_SIZE,
                             NupPreferences.CACHE_SIZE_DEFAULT)) * 1024 * 1024;
        long availableBytes = maxBytes - getDataBytes();

        if (neededBytes <= availableBytes)
            return true;

        Log.d(TAG, "need to make space for " + neededBytes + " bytes (" + availableBytes + " available)");
        List<Integer> ids = mDb.getIdsByAge();
        for (int id : ids) {
            if (neededBytes <= availableBytes)
                break;

            if (isDownloadActive(id))
                continue;

            synchronized(mPinnedIds) {
                if (mPinnedIds.contains(id))
                    continue;
            }

            FileCacheEntry entry = mDb.getEntryById(id);
            File file = new File(entry.getLocalPath());
            Log.d(TAG, "deleting " + id + " (" + entry.getLocalPath() + ", " + file.length() + " bytes)");
            availableBytes += file.length();
            file.delete();
            mDb.removeEntry(id);
            mListener.onCacheEviction(entry);
        }

        return neededBytes <= availableBytes;
    }
}
